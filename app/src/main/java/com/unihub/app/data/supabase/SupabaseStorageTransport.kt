package com.unihub.app.data.supabase

import com.unihub.app.data.cloud.CloudConflictException
import com.unihub.app.data.cloud.CloudDeleteRules
import com.unihub.app.data.cloud.CloudFileProtocol
import com.unihub.app.data.cloud.CloudObjectSummary
import com.unihub.app.data.cloud.CloudStorageConfig
import com.unihub.app.data.cloud.CloudStorageConnection
import com.unihub.app.data.cloud.CloudTextObject
import com.unihub.app.data.cloud.CloudUploadedObject
import com.unihub.app.data.cloud.StorageAuthorizer
import com.unihub.app.data.cloud.cloudAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * نقل الملفات عبر Supabase.
 *
 * **بديل AppwriteStorageTransport** بسطح الدوال نفسه حرفيًا، فلم يتغيّر
 * `CloudSyncManager` (1366 سطرًا) ولا `CloudTransferQueue` ولا شاشات الملفات.
 *
 * الفروق الجوهريّة وطريقة الحفاظ على الدلالة:
 *
 * |原来 Appwrite | الآن Supabase |
 * |---|---|
 * | كائن في الحاوية + «معرّف» فريد | بايتات في `storage.objects` + صف `cloud_objects` يحمل الهوية |
 * | البيان كائن «unihub_manifest.json» بإصدارات | جدول `cloud_manifests` (المفتاح = معرّف الجيل) |
 * | تعارض الكتابة = 409 من قيد التفرّد | تعارض الكتابة = 23505 من قيد التفرّد ⇒ نفس الدلالة |
 * | رفع مجزّأ 5MiB قابل للاستئناف | رفع بطلب واحد مع إعادة محاولة (TUS غير متاح في هذا المشروع) |
 * | تنزيل بـ Range | تنزيل بـ Range (مُتحقَّق: 206) ⇒ الاستئناف محفوظ |
 *
 * **نتائج البايتات متطابقة**: معرّف الملف يبقى `f<sha256>` ومعرّف الجيل يبقى
 * `m<gen>_<sha>`، وبذلك تبقى روابط المكتبة المحلية صالحة بلا تحويل.
 */
open class SupabaseStorageTransport(
    private val target: CloudStorageConnection,
    private val authorization: StorageAuthorizer,
    private val http: SupabaseHttp = SupabaseHttp(SupabaseConfig.PROJECT_URL, SupabaseConfig.PUBLISHABLE_KEY)
) {

    suspend fun listBucketObjects(connection: CloudStorageConnection): Result<List<CloudObjectSummary>> =
        withContext(Dispatchers.IO) {
            cloudAttempt {
                requireTarget(connection)
                val token = authorization.grant().jwt
                val objects = mutableListOf<CloudObjectSummary>()
                var offset = 0
                while (true) {
                    val page = http.requestText(
                        "GET",
                        "${SupabaseConfig.REST_PATH}/${SupabaseConfig.TABLE_CLOUD_OBJECTS}" +
                            "?select=id,object_key,name,size,storage_path,version,updated_at" +
                            "&order=object_key.asc&limit=$PAGE_SIZE&offset=$offset",
                        token = token
                    )
                    val rows = JSONArray(page)
                    if (rows.length() == 0) break
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        objects += CloudObjectSummary(
                            key = row.optString("object_key"),
                            size = row.optLong("size"),
                            etag = row.optString("version"),
                            lastModifiedAt = parseTimestamp(row.optString("updated_at")),
                            displayName = row.optString("name")
                        )
                    }
                    if (rows.length() < PAGE_SIZE) break
                    offset += rows.length()
                    if (offset > MAX_LISTED_OBJECTS) throw IOException("تجاوز عدد ملفات السحابة حد الأمان")
                }
                latestManifest(token)?.let { manifest ->
                    objects += CloudObjectSummary(
                        key = CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY,
                        size = manifest.optLong("size_bytes"),
                        etag = manifest.optString("id"),
                        lastModifiedAt = parseTimestamp(manifest.optString("created_at")),
                        displayName = CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY
                    )
                }
                objects
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
            val token = authorization.grant().jwt
            val id = CloudFileProtocol.fileId(objectKey)
            val name = CloudFileProtocol.uploadName(objectKey)
            val size = sourceFile.length()
            val modifiedAt = sourceFile.lastModified()

            val existing = findObject(token, id)
            if (existing != null) {
                if (existing.optString("name") != name || existing.optLong("size") != size) {
                    throw CloudConflictException()
                }
                // الملف موجود بنفس الهوية ⇒ محتواه مطابق للمفتاح (المفتاح يشمل بصمة المحتوى).
                onProgress(size, size)
                return@cloudAttempt CloudUploadedObject(existing.optString("version"), size)
            }

            http.uploadFile(
                path = storagePath(objectKey),
                token = token,
                source = sourceFile,
                contentType = contentType,
                onProgress = onProgress
            )
            if (sourceFile.length() != size || sourceFile.lastModified() != modifiedAt) {
                throw IOException("تغيّر الملف المحلي أثناء الرفع؛ أعد المحاولة")
            }

            val version = CloudFileProtocol.sha256("$id:${System.currentTimeMillis()}:$size")
            val row = JSONObject()
                .put("id", id)
                .put("object_key", objectKey)
                .put("name", name)
                .put("size", size)
                .put("mime_type", contentType)
                .put("storage_path", id) // معرّف الملف = مسار الحاوية (ASCII آمن)
                .put("version", version)
            upsertObject(token, row)
            onProgress(size, size)
            CloudUploadedObject(version, size)
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
            if (bytes.size > CloudFileProtocol.MAX_MANIFEST_BYTES) {
                throw IOException("فهرس المكتبة تجاوز حد التحديث الذري (أقل من 5 MiB)")
            }
            val token = authorization.grant().jwt
            val previous = latestManifest(token)
            val previousId = previous?.optString("id")
            if (ifNoneMatch && previousId != null) throw CloudConflictException()
            if (!ifNoneMatch && previousId != ifMatch) throw CloudConflictException()

            val nextId = CloudFileProtocol.nextManifestId(previousId)
            val generation = previousId?.let { CloudFileProtocol.manifestGeneration(it) + 1 } ?: 0L
            val row = JSONObject()
                .put("id", nextId)
                .put("generation", generation)
                .put("payload", JSONObject(text))
                .put("size_bytes", bytes.size.toLong())
            try {
                http.requestText(
                    "POST",
                    "${SupabaseConfig.REST_PATH}/${SupabaseConfig.TABLE_CLOUD_MANIFESTS}",
                    token = token,
                    body = row.toString()
                )
            } catch (error: SupabaseApiException) {
                // قيد التفرّد على المعرّف = تعارض كتابة متزامنة (ما كان 409 في Appwrite).
                if (error.isUniqueViolation) throw CloudConflictException()
                throw error
            }
            CloudUploadedObject(nextId, bytes.size.toLong())
        }
    }

    suspend fun downloadTextObject(
        connection: CloudStorageConnection,
        objectKey: String,
        expectedEtag: String? = null,
        ifNoneMatchEtag: String? = null,
        maxResponseBytes: Long = CloudFileProtocol.MAX_MANIFEST_BYTES.toLong()
    ): Result<CloudTextObject?> = withContext(Dispatchers.IO) {
        cloudAttempt {
            requireTarget(connection)
            require(objectKey == CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY)
            require(maxResponseBytes in 1..CloudFileProtocol.MAX_MANIFEST_BYTES.toLong())
            require(expectedEtag.isNullOrBlank() || ifNoneMatchEtag.isNullOrBlank())
            val token = authorization.grant().jwt
            val manifest = latestManifest(token) ?: return@cloudAttempt null
            val id = manifest.optString("id")
            if (!expectedEtag.isNullOrBlank() && expectedEtag != id) throw CloudConflictException()
            if (id == ifNoneMatchEtag) return@cloudAttempt CloudTextObject("", id, notModified = true)
            if (manifest.optLong("size_bytes") > maxResponseBytes) throw IOException("الفهرس أكبر من حد الأمان المسموح")
            val payload = manifest.optJSONObject("payload") ?: throw IOException("فهرس الخادم تالف")
            CloudTextObject(payload.toString(), id)
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
            val token = lease.jwt
            val id = CloudFileProtocol.fileId(objectKey)
            val row = findObject(token, id) ?: run { targetFile.delete(); return@cloudAttempt false }
            val size = row.optLong("size")
            if (!expectedEtag.isNullOrBlank() && expectedEtag != row.optString("version")) throw CloudConflictException()
            if (expectedSize != null && expectedSize != size) throw CloudConflictException()
            val storagePath = storagePathOf(row, objectKey)

            val resumeFrom = if (resume && targetFile.isFile) targetFile.length() else 0L
            var complete = false
            var keepPartial = false
            try {
                val headers = if (resumeFrom > 0L) mapOf("Range" to "bytes=$resumeFrom-") else emptyMap()
                val response = http.requestStream(
                    "GET",
                    "${SupabaseConfig.STORAGE_PATH}/object/${SupabaseConfig.STORAGE_BUCKET_ID}/$storagePath",
                    token = token,
                    headers = headers
                )
                val from = if (response.code == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.contentRange.orEmpty())
                        ?: throw CloudConflictException()
                    if (resumeFrom <= 0L || range.groupValues[1].toLong() != resumeFrom ||
                        range.groupValues[2].toLong() != size - 1 || range.groupValues[3].toLong() != size) {
                        throw CloudConflictException()
                    }
                    resumeFrom
                } else 0L
                if (response.contentLength >= 0 && response.contentLength != size - from) throw CloudConflictException()

                targetFile.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                if (from > 0L) targetFile.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                var received = from
                var progressAt = 0L
                response.stream.use { input ->
                    FileOutputStream(targetFile, from > 0L).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            authorization.requireCurrent(lease)
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > size) throw IOException("حجم التنزيل تجاوز الحجم المعلن")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - progressAt >= 150_000_000L) {
                                onProgress(received, size)
                                progressAt = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (received != size) throw IOException("تنزيل غير مكتمل؛ لم يُحفظ الملف في المكتبة")
                val latest = findObject(token, id)
                if (latest == null || latest.optString("version") != row.optString("version")) throw CloudConflictException()
                onDigest?.invoke(hex(digest.digest()))
                onProgress(received, size)
                complete = true
                true
            } catch (cancelled: CancellationException) {
                keepPartial = resume
                throw cancelled
            } finally {
                if (!complete && !keepPartial) targetFile.delete()
            }
        }
    }

    suspend fun deleteObject(connection: CloudStorageConnection, objectKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            cloudAttempt {
                requireTarget(connection)
                if (!CloudDeleteRules.isDeletableObjectKey(objectKey)) throw IOException("هذا الكائن محمي من الحذف")
                val token = authorization.grant().jwt
                val id = CloudFileProtocol.fileId(objectKey)
                val row = findObject(token, id) ?: return@cloudAttempt Unit
                val storagePath = storagePathOf(row, objectKey)
                try {
                    http.requestText(
                        "DELETE",
                        "${SupabaseConfig.STORAGE_PATH}/object/${SupabaseConfig.STORAGE_BUCKET_ID}/$storagePath",
                        token = token
                    )
                } catch (error: SupabaseApiException) {
                    if (!error.isNotFound) throw error
                }
                http.requestText(
                    "DELETE",
                    "${SupabaseConfig.REST_PATH}/${SupabaseConfig.TABLE_CLOUD_OBJECTS}?id=eq.$id",
                    token = token
                )
                Unit
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

    // ── مساعدات داخلية ────────────────────────────────────────────────────

    private fun findObject(token: String, id: String): JSONObject? {
        val body = http.requestText(
            "GET",
            "${SupabaseConfig.REST_PATH}/${SupabaseConfig.TABLE_CLOUD_OBJECTS}" +
                "?select=id,object_key,name,size,storage_path,version,updated_at&id=eq.$id",
            token = token
        )
        val rows = JSONArray(body)
        return if (rows.length() == 0) null else rows.optJSONObject(0)
    }

    private fun upsertObject(token: String, row: JSONObject) {
        http.requestText(
            "POST",
            "${SupabaseConfig.REST_PATH}/${SupabaseConfig.TABLE_CLOUD_OBJECTS}?on_conflict=id",
            token = token,
            body = row.toString(),
            prefer = "resolution=merge-duplicates,return=minimal"
        )
    }

    private fun latestManifest(token: String): JSONObject? {
        val body = http.requestText(
            "GET",
            "${SupabaseConfig.REST_PATH}/${SupabaseConfig.TABLE_CLOUD_MANIFESTS}" +
                "?select=id,generation,payload,size_bytes,created_at&order=generation.desc&limit=1",
            token = token
        )
        val rows = JSONArray(body)
        return if (rows.length() == 0) null else rows.optJSONObject(0)
    }

    /**
     * المسار داخل الحاوية.
     *
     * **ليس المفتاح المنطقي**: Supabase Storage يرفض المفاتيح غير ASCII
     * (ومُتحقَّق حيًّا: «Invalid key: مادة/ملف.pdf» ⇒ 400)، ويفرض أيضًا
     * قيودًا على بعض الرموز. ومفاتيح UniHub قد تحتوي العربية والمسافات
     * وأسماء مجلدات المستخدم.
     *
     * ⇒ المسار = **معرّف الملف** (`f` + 35 محرفًا ست عشريًا): أحرف لاتينية
     * صغيرة دائمًا، فريدة لكل مفتاح، ومشتقّ بقيمة (بلا عشوائية) فيسهل
     * استنساخه وإعادة بنائه. الرابط المنطقي يبقى محفوظًا في
     * `cloud_objects.object_key` و`name` — فلا يضيع شيء.
     */
    private fun storagePath(objectKey: String): String = CloudFileProtocol.fileId(objectKey)

    /** مسار آمن للاستخدام من صف قديم؛ وإلا يُشتقّ من المفتاح. */
    private fun storagePathOf(row: JSONObject, objectKey: String): String {
        val stored = row.optString("storage_path")
        return if (stored.isNotBlank() && isStorageSafe(stored)) stored else storagePath(objectKey)
    }

    /** Storage لا يقبل إلا ASCII في المسار (ومُتحقَّق: حرف ~ مرفوض أيضًا). */
    private fun isStorageSafe(path: String): Boolean =
        path.isNotBlank() && path.length <= 200 && path.all { it.code in 32..126 && it != '~' }

    private fun requireTarget(connection: CloudStorageConnection) {
        require(connection == target && connection.isConfigured) { "إعدادات مشروع الملفات غير مطابقة" }
    }

    private fun parseTimestamp(raw: String): Long = try {
        java.time.Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PAGE_SIZE = 500
        const val MAX_LISTED_OBJECTS = 20_000
    }
}
