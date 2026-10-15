package com.unihub.app.data.appwrite

import com.unihub.app.data.cloud.CloudConflictException
import com.unihub.app.data.cloud.CloudDeleteRules
import com.unihub.app.data.cloud.CloudDownloadPart
import com.unihub.app.data.cloud.CloudFailureMessages
import com.unihub.app.data.cloud.CloudObjectSummary
import com.unihub.app.data.cloud.CloudStorageConfig
import com.unihub.app.data.cloud.CloudStorageConnection
import com.unihub.app.data.cloud.CloudTextObject
import com.unihub.app.data.cloud.CloudUploadedObject
import com.unihub.app.data.cloud.cloudAttempt
import com.unihub.app.data.cloud.withCancellableConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * Appwrite Storage REST transport. Unlike getFileDownload(), it does not load a large file into
 * a ByteArray. Authentication is per request, not mutable global headers shared by both clients.
 * The connection factory is injectable solely for network-free protocol tests.
 */
open class AppwriteStorageTransport(
    private val target: CloudStorageConnection,
    private val authorization: StorageAuthorizer,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    /** Base headers copied from the official Appwrite Android SDK Client (origin/response-format/user-agent...). */
    private val platformHeaders: Map<String, String> = emptyMap()
) {
    init { require(target.isConfigured) { "مشروع تخزين Appwrite غير مطابق لإعدادات التطبيق" } }
    private val filesPath = "/storage/buckets/${target.bucketId}/files"

    suspend fun listBucketObjects(connection: CloudStorageConnection): Result<List<CloudObjectSummary>> = withContext(Dispatchers.IO) {
        cloudAttempt {
            requireTarget(connection)
            val lease = authorization.grant()
            val files = listFiles(lease)
            val latest = AppwriteFileProtocol.latestManifest(files)
            files.filter { it.complete && !AppwriteFileProtocol.isManifest(it.id, it.name) }
                .map { it.summary() } + listOfNotNull(latest?.summary())
        }
    }

    suspend fun uploadFile(
        connection: CloudStorageConnection,
        objectKey: String,
        sourceFile: File,
        contentType: String = "application/octet-stream",
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<CloudUploadedObject> = withContext(Dispatchers.IO) {
        cloudAttempt {
            requireTarget(connection)
            require(CloudDeleteRules.isDeletableObjectKey(objectKey) && !objectKey.startsWith("external:"))
            if (!sourceFile.isFile) throw IOException("الملف المحلي غير موجود")
            val lease = authorization.grant()
            val id = AppwriteFileProtocol.fileId(objectKey)
            val name = AppwriteFileProtocol.uploadName(objectKey)
            val size = sourceFile.length()
            val modifiedAt = sourceFile.lastModified()
            var remote = getFileOrNull(lease, id)
            if (remote != null && (remote.name != name || remote.size != size)) throw CloudConflictException()
            if (remote?.complete == true) {
                onProgress(size, size)
                return@cloudAttempt CloudUploadedObject(remote.version, size)
            }
            var offset = (remote?.chunksUploaded ?: 0L) * AppwriteFileProtocol.CHUNK_BYTES
            if (offset > size || (offset == size && size > 0L)) throw CloudConflictException()
            RandomAccessFile(sourceFile, "r").use { input ->
                do {
                    currentCoroutineContext().ensureActive()
                    authorization.requireCurrent(lease)
                    if (sourceFile.length() != size || sourceFile.lastModified() != modifiedAt) {
                        throw IOException("تغيّر الملف المحلي أثناء الرفع؛ أعد المحاولة")
                    }
                    val length = AppwriteFileProtocol.chunkLength(size, offset)
                    input.seek(offset)
                    remote = sendChunk(lease, id, name, contentType, size, offset, length) { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var remaining = length
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            authorization.requireCurrent(lease)
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) throw IOException("تغيّر الملف المحلي أثناء قراءته")
                            output.write(buffer, 0, count)
                            remaining -= count
                            onProgress(offset + length - remaining, size)
                        }
                    }
                    if (remote?.id != id || remote?.name != name || remote?.size != size) throw CloudConflictException()
                    offset += length
                } while (offset < size)
            }
            val completed = remote ?: throw IOException("لم يُكمل الخادم الرفع")
            if (!completed.complete || sourceFile.length() != size || sourceFile.lastModified() != modifiedAt) {
                throw IOException("لم يكتمل رفع نسخة ثابتة من الملف")
            }
            onProgress(size, size)
            CloudUploadedObject(completed.version, size)
        }
    }

    suspend fun uploadText(
        connection: CloudStorageConnection,
        objectKey: String,
        text: String,
        contentType: String = "application/json; charset=utf-8",
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false
    ): Result<CloudUploadedObject> = withContext(Dispatchers.IO) {
        cloudAttempt {
            requireTarget(connection)
            require(objectKey == CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY)
            require(ifNoneMatch.xor(!ifMatch.isNullOrBlank())) { "تحديث الفهرس يجب أن يكون مشروطًا بإصداره" }
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > AppwriteFileProtocol.MAX_MANIFEST_BYTES) throw IOException("فهرس المكتبة تجاوز حد التحديث الذري (أقل من 5 MiB)")
            val lease = authorization.grant()
            val previous = latestManifest(lease)
            if ((ifNoneMatch && previous != null) || (!ifNoneMatch && previous?.id != ifMatch)) throw CloudConflictException()
            val id = AppwriteFileProtocol.nextManifestId(previous?.id)
            // A 409 is a real optimistic-write conflict. Never delete and recreate a manifest.
            val created = sendChunk(lease, id, objectKey, contentType, bytes.size.toLong(), 0, bytes.size) { it.write(bytes) }
            if (!created.complete || created.id != id || created.size != bytes.size.toLong()) throw IOException("لم يكتمل نشر إصدار الفهرس")
            CloudUploadedObject(created.id, created.size)
        }
    }

    suspend fun downloadTextObject(
        connection: CloudStorageConnection,
        objectKey: String,
        expectedEtag: String? = null,
        ifNoneMatchEtag: String? = null,
        maxResponseBytes: Long = AppwriteFileProtocol.MAX_MANIFEST_BYTES.toLong()
    ): Result<CloudTextObject?> = withContext(Dispatchers.IO) {
        cloudAttempt {
            requireTarget(connection)
            require(objectKey == CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY)
            require(maxResponseBytes in 1..AppwriteFileProtocol.MAX_MANIFEST_BYTES.toLong())
            require(expectedEtag.isNullOrBlank() || ifNoneMatchEtag.isNullOrBlank())
            val lease = authorization.grant()
            val file = latestManifest(lease) ?: return@cloudAttempt null
            if (!expectedEtag.isNullOrBlank() && expectedEtag != file.id) throw CloudConflictException()
            if (file.id == ifNoneMatchEtag) return@cloudAttempt CloudTextObject("", file.id, notModified = true)
            if (file.size > maxResponseBytes) throw IOException("الفهرس أكبر من حد الأمان المسموح")
            val text = request(lease, "GET", "$filesPath/${file.id}/download") { conn ->
                conn.inputStream.use { readBoundedText(it, maxResponseBytes) }
            }
            if (text.toByteArray(Charsets.UTF_8).size.toLong() != file.size) throw IOException("تنزيل الفهرس غير مكتمل")
            CloudTextObject(text, file.id)
        }
    }

    suspend fun downloadFile(
        connection: CloudStorageConnection,
        objectKey: String,
        targetFile: File,
        expectedEtag: String? = null,
        expectedSize: Long? = null,
        onDigest: ((String) -> Unit)? = null,
        resume: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        cloudAttempt {
            requireTarget(connection)
            val lease = authorization.grant()
            val id = AppwriteFileProtocol.fileId(objectKey)
            val file = getFileOrNull(lease, id) ?: run { targetFile.delete(); return@cloudAttempt false }
            if (!file.complete || (!expectedEtag.isNullOrBlank() && expectedEtag != file.version) ||
                (expectedSize != null && expectedSize != file.size)) throw CloudConflictException()
            val partSize = if (resume && targetFile.isFile) targetFile.length() else 0L
            val plan = CloudDownloadPart.plan(partSize, file.size)
            val from = if (plan is CloudDownloadPart.Resume.Continue) plan.from else 0L
            var complete = false
            var keepPartial = false
            try {
                request(lease, "GET", "$filesPath/$id/download", headers =
                    if (from > 0L) mapOf("Range" to "bytes=$from-") else emptyMap()) { conn ->
                    val partial = conn.responseCode == 206
                    if (partial) {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(conn.getHeaderField("Content-Range").orEmpty())
                            ?: throw CloudConflictException()
                        if (from <= 0 || range.groupValues[1].toLong() != from ||
                            range.groupValues[2].toLong() != file.size - 1 || range.groupValues[3].toLong() != file.size) {
                            throw CloudConflictException()
                        }
                    }
                    val resumeFrom = if (partial) from else 0L
                    if (conn.contentLengthLong >= 0 && conn.contentLengthLong != file.size - resumeFrom) throw CloudConflictException()
                    targetFile.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    if (resumeFrom > 0) targetFile.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    var received = resumeFrom
                    var progressAt = 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(targetFile, resumeFrom > 0).use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                authorization.requireCurrent(lease)
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > file.size) throw IOException("حجم التنزيل تجاوز الحجم المعلن")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val now = System.nanoTime()
                                if (now - progressAt >= 150_000_000L) { onProgress(received, file.size); progressAt = now }
                            }
                            output.fd.sync()
                        }
                    }
                    if (received != file.size) throw IOException("تنزيل غير مكتمل؛ لم يُحفظ الملف في المكتبة")
                    if (getFileOrNull(lease, id)?.version != file.version) throw CloudConflictException()
                    authorization.requireCurrent(lease)
                    onDigest?.invoke(hex(digest.digest()))
                    onProgress(received, file.size)
                    complete = true
                }
                true
            } catch (cancelled: CancellationException) {
                keepPartial = resume
                throw cancelled
            } finally {
                if (!complete && !keepPartial) targetFile.delete()
            }
        }
    }

    suspend fun deleteObject(connection: CloudStorageConnection, objectKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        cloudAttempt {
            requireTarget(connection)
            if (!CloudDeleteRules.isDeletableObjectKey(objectKey)) throw IOException("هذا الكائن محمي من الحذف")
            val lease = authorization.grant()
            val id = AppwriteFileProtocol.fileId(objectKey)
            val file = getFileOrNull(lease, id) ?: return@cloudAttempt Unit
            if (AppwriteFileProtocol.isManifest(file.id, file.name) || !CloudDeleteRules.isDeletableObjectKey(file.summary().key)) {
                throw IOException("لا يمكن حذف إصدارات الفهرس من واجهة الملفات")
            }
            try { request(lease, "DELETE", "$filesPath/$id") { Unit } }
            catch (error: StorageHttpException) { if (!error.fileMissing) throw error }
        }
    }

    fun sha256Hex(file: File, checkCancellation: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private suspend fun sendChunk(lease: StorageGrant, id: String, name: String, mime: String, size: Long,
        offset: Long, length: Int, write: suspend (OutputStream) -> Unit): AppwriteStoredFile {
        require(AppwriteFileProtocol.validId(id) && name.none { it == '"' || it.isISOControl() })
        require(mime.isNotBlank() && mime.none(Char::isISOControl))
        val boundary = "unihub-${UUID.randomUUID()}"
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"fileId\"\r\n\r\n$id\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
            "Content-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val headers = mutableMapOf("Content-Type" to "multipart/form-data; boundary=$boundary")
        if (size > AppwriteFileProtocol.CHUNK_BYTES) headers["Content-Range"] = "bytes $offset-${offset + length - 1}/$size"
        if (offset > 0) headers["X-Appwrite-ID"] = id
        return request(lease, "POST", filesPath, headers, prepare = { conn ->
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(prefix.size.toLong() + length + suffix.size)
            conn.outputStream.use { output -> output.write(prefix); write(output); output.write(suffix) }
        }) { conn -> AppwriteStoredFile.fromJson(JSONObject(conn.inputStream.use { readBoundedText(it, MAX_JSON_BYTES) })) }
    }

    private suspend fun latestManifest(lease: StorageGrant): AppwriteStoredFile? = AppwriteFileProtocol.latestManifest(
        listFiles(lease, onlyManifest = true)
    )

    private suspend fun listFiles(lease: StorageGrant, onlyManifest: Boolean = false): List<AppwriteStoredFile> {
        val result = arrayListOf<AppwriteStoredFile>()
        val cursors = hashSetOf<String>()
        var cursor: String? = null
        do {
            val queries = mutableListOf(query("limit", values = listOf(AppwriteFileProtocol.PAGE_SIZE)), query("orderAsc", "\$id"))
            if (onlyManifest) queries += query("equal", "name", listOf(CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY))
            cursor?.let { queries += query("cursorAfter", values = listOf(it)) }
            val path = filesPath + "?" + queries.joinToString("&") { "queries%5B%5D=" + URLEncoder.encode(it, "UTF-8") }
            val page = request(lease, "GET", path) { conn ->
                JSONObject(conn.inputStream.use { readBoundedText(it, MAX_JSON_BYTES) }).getJSONArray("files")
            }
            for (i in 0 until page.length()) result += AppwriteStoredFile.fromJson(page.getJSONObject(i))
            if (result.size > 100_000) throw IOException("فهرس التخزين تجاوز حد الأمان؛ تواصل مع المشرف لتوسعة الكتالوج دون حذف إصداراته")
            cursor = if (page.length() >= AppwriteFileProtocol.PAGE_SIZE) result.lastOrNull()?.id else null
            if (cursor != null && !cursors.add(cursor)) throw IOException("تكرر مؤشر صفحة ملفات Appwrite")
        } while (cursor != null)
        return result
    }

    private suspend fun getFileOrNull(lease: StorageGrant, id: String): AppwriteStoredFile? = try {
        require(AppwriteFileProtocol.validId(id))
        request(lease, "GET", "$filesPath/$id") { conn ->
            AppwriteStoredFile.fromJson(JSONObject(conn.inputStream.use { readBoundedText(it, MAX_JSON_BYTES) }))
        }
    } catch (error: StorageHttpException) {
        if (error.fileMissing) null else throw error
    }

    private suspend fun <T> request(lease: StorageGrant, method: String, path: String,
        headers: Map<String, String> = emptyMap(), prepare: (suspend (HttpURLConnection) -> Unit)? = null,
        read: suspend (HttpURLConnection) -> T): T {
        authorization.requireCurrent(lease)
        val current = authorization.grant()
        authorization.requireCurrent(lease)
        val connection = openConnection(URL(target.endpointUrl + path)).apply {
            requestMethod = method
            connectTimeout = 20_000; readTimeout = 30_000
            instanceFollowRedirects = false; useCaches = false

            // Match the official SDK's header names/values (SDK v14.1.0): origin + response-format.
            // We deliberately exclude content-type and auth headers here because individual operations
            // override them (multipart boundaries) and we set the routing/JWT explicitly below.
            setRequestProperty("Accept-Encoding", "identity")
            val platformKeys = platformHeaders.keys.map { it.lowercase() }.toSet()
            platformHeaders.forEach { (name, value) ->
                val key = name.lowercase()
                if (value.isBlank()) return@forEach
                if (key in setOf("content-type", "x-appwrite-project", "x-appwrite-jwt", "accept-encoding")) return@forEach
                setRequestProperty(name, value)
            }
            if ("x-appwrite-response-format" !in platformKeys) {
                setRequestProperty("x-appwrite-response-format", DEFAULT_RESPONSE_FORMAT)
            }
            if ("origin" !in platformKeys) {
                setRequestProperty("origin", DEFAULT_ORIGIN)
            }

            // Authentication and routing headers.
            setRequestProperty("x-appwrite-project", target.projectId)
            setRequestProperty("x-appwrite-jwt", current.jwt)

            // Operation-specific headers.
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return withCancellableConnection(connection) {
            currentCoroutineContext().ensureActive()
            authorization.requireCurrent(lease)
            prepare?.invoke(connection)
            val status = connection.responseCode
            if (status !in 200..299) {
                if (status == 401) authorization.clear()
                if (status == 409 || status == 412) throw CloudConflictException()
                val errorJson = connection.errorStream?.use { input ->
                    val text = readBoundedText(input, 32 * 1024L)
                    runCatching { JSONObject(text) }.getOrNull()
                }
                val type = errorJson?.optString("type").orEmpty()
                val upstreamMessage = errorJson?.optString("message").orEmpty()
                throw StorageHttpException(status, type, upstreamMessage)
            }
            val result = read(connection)
            authorization.requireCurrent(lease)
            result
        }
    }

    private fun requireTarget(connection: CloudStorageConnection) { require(connection == target && connection.isConfigured) }
    private fun query(method: String, attribute: String? = null, values: List<Any> = emptyList()): String =
        JSONObject().put("method", method).put("values", JSONArray(values)).apply { attribute?.let { put("attribute", it) } }.toString()

    private suspend fun readBoundedText(input: InputStream, max: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > max) throw IOException("رد Appwrite تجاوز حد الأمان")
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private class StorageHttpException(
        val status: Int,
        val type: String,
        val upstreamMessage: String
    ) : IOException(storageFailureMessage(status, type)) {
        val fileMissing = status == 404 && type == "storage_file_not_found"
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_JSON_BYTES = 1024L * 1024

        // SDK v14.1.0 default. Sending it matches the official Client and avoids server-side format mismatches.
        const val DEFAULT_RESPONSE_FORMAT = "1.9.0"

        // Fallback only. The preferred value is platformHeaders["origin"] from the SDK Client.
        const val DEFAULT_ORIGIN = "appwrite-android://com.unihub.app"

        private fun storageFailureMessage(status: Int, type: String): String = when (type) {
            // Appwrite validates client origins per project. This is the most common misconfiguration
            // when a project uses multiple Appwrite projects (AUTH vs STORAGE) and only one of them
            // has the Android platform registered.
            "general_unknown_origin" ->
                "السحابة غير معتمدة لهذا التطبيق. أضف منصة Android في مشروع الملفات على Appwrite ثم أعد المحاولة"

            else -> CloudFailureMessages.messageFor(status)
        }
    }
}
