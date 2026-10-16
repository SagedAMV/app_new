package com.unihub.app.data.appwrite

import com.unihub.app.data.cloud.CloudConflictException
import com.unihub.app.data.cloud.CloudStorageConfig
import com.unihub.app.data.cloud.CloudStorageConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppwriteStorageTransportTest {
    @Test fun listsEveryPageAndSendsOnlyStorageProjectAuthorization() = runBlocking {
        val network = Network { request ->
            val second = URLDecoder.decode(request.url.query.orEmpty(), "UTF-8").contains("cursorAfter")
            val files = if (second) listOf(metadata("file100")) else (0..99).map { metadata("file${it.toString().padStart(3, '0')}") }
            json(JSONObject().put("files", JSONArray(files)))
        }
        val files = network.transport.listBucketObjects(TARGET).getOrThrow()
        assertEquals(101, files.size)
        assertEquals(2, network.requests.size)
        network.requests.forEach {
            assertEquals(AppwriteConfig.STORAGE_PROJECT_ID, it.getRequestProperty("X-Appwrite-Project"))
            assertEquals("storage.jwt.signature", it.getRequestProperty("X-Appwrite-JWT"))
            assertNull(it.getRequestProperty("X-Appwrite-Key"))
            assertNull(it.getRequestProperty("Authorization"))
            assertFalse(it.instanceFollowRedirects)
            // الاتصال الناجح لا يُغلق فورًا: يبقى صالحًا لإعادة الاستخدام (keep-alive) في
            // الصفحة التالية بدل إعادة تفاوض TCP/TLS من الصفر لكل طلب (إصلاح بطء الرفع).
            assertFalse(it.disconnected)
        }
    }
    @Test fun repeatedCursorFailsInsteadOfLoopingForever() = runBlocking {
        val network = Network { json(JSONObject().put("files", JSONArray((0..99).map { metadata("file$it") }))) }
        assertTrue(network.transport.listBucketObjects(TARGET).isFailure)
        assertEquals(2, network.requests.size)
    }
    @Test fun incompleteUploadsAreNotExposedAsDownloadableFiles() = runBlocking {
        val partial = metadata("file1").put("chunksTotal", 2).put("chunksUploaded", 1)
        val network = Network { json(JSONObject().put("files", JSONArray().put(partial))) }
        assertTrue(network.transport.listBucketObjects(TARGET).getOrThrow().isEmpty())
    }
    @Test fun largeUploadStartsAfter404AndFinalChunkIsNotPadded() = runBlocking {
        val bytes = ByteArray(AppwriteFileProtocol.CHUNK_BYTES + 17) { (it % 251).toByte() }
        val file = temp().apply { writeBytes(bytes) }
        val posts = arrayListOf<FakeConnection>()
        val key = "files/content.bin"
        val id = AppwriteFileProtocol.fileId(key)
        val name = AppwriteFileProtocol.uploadName(key)
        val network = Network { request ->
            if (request.requestMethod == "GET") missing() else {
                posts += request
                json(metadata(id, name, bytes.size.toLong()).put("chunksTotal", 2).put("chunksUploaded", posts.size), 201)
            }
        }
        try {
            val result = network.transport.uploadFile(TARGET, key, file).getOrThrow()
            assertEquals(bytes.size.toLong(), result.size)
            assertEquals(2, posts.size)
            assertEquals("bytes 0-${AppwriteFileProtocol.CHUNK_BYTES - 1}/${bytes.size}", posts[0].getRequestProperty("Content-Range"))
            assertEquals("bytes ${AppwriteFileProtocol.CHUNK_BYTES}-${bytes.lastIndex}/${bytes.size}", posts[1].getRequestProperty("Content-Range"))
            assertArrayEquals(bytes.copyOfRange(0, AppwriteFileProtocol.CHUNK_BYTES), posts[0].fileBytes())
            assertArrayEquals(bytes.copyOfRange(AppwriteFileProtocol.CHUNK_BYTES, bytes.size), posts[1].fileBytes())
            assertEquals(id, posts[1].getRequestProperty("X-Appwrite-ID"))
            // إصلاح عطل «يتوقف الرفع بعد نصف دقيقة»: مهلة قراءة قطعة رفع كاملة (5 ميجابايت)
            // يجب أن تكون أكبر بوضوح من المهلة الثابتة القديمة (30 ثانية) التي كانت تقطع
            // الرفع فوق شبكة بطيئة قبل وصول رد الخادم.
            assertTrue("قطعة الرفع يجب ألا تُقيَّد بمهلة 30 ثانية القديمة", posts[0].readTimeout > 30_000)
        } finally { file.delete() }
    }
    @Test fun smallRequestsKeepAShorterReadTimeoutThanLargeChunkUploads() = runBlocking {
        val big = ByteArray(AppwriteFileProtocol.CHUNK_BYTES) { 1 }
        val file = temp().apply { writeBytes(big) }
        var listTimeout = -1
        var uploadTimeout = -1
        val network = Network { request ->
            when {
                // uploadFile يتحقق أولًا من وجود الملف عبر GET بلا معاملات استعلام (مسار
                // "/files/{id}" مباشرة)؛ هذا طلب خفيف منفصل عن استعراض الصفحات، ويجب أن
                // يُحاكى بردّ «غير موجود» (404) لا بقائمة ملفات فارغة لها بنية مختلفة.
                request.requestMethod == "GET" && request.url.query.isNullOrEmpty() -> missing()
                request.requestMethod == "GET" -> { listTimeout = request.readTimeout; json(JSONObject().put("files", JSONArray())) }
                else -> { uploadTimeout = request.readTimeout
                    json(metadata(AppwriteFileProtocol.fileId("files/big.bin"), AppwriteFileProtocol.uploadName("files/big.bin"), big.size.toLong()), 201) }
            }
        }
        try {
            network.transport.listBucketObjects(TARGET).getOrThrow()
            network.transport.uploadFile(TARGET, "files/big.bin", file).getOrThrow()
            assertTrue(listTimeout in 1..uploadTimeout)
            assertTrue(uploadTimeout > 30_000)
        } finally { file.delete() }
    }
    @Test fun emptyFileIsUploadedOnceWithoutAnInvalidRange() = runBlocking {
        val file = temp()
        val key = "files/empty.bin"
        val network = Network { request ->
            if (request.requestMethod == "GET") missing() else json(metadata(AppwriteFileProtocol.fileId(key), AppwriteFileProtocol.uploadName(key), 0), 201)
        }
        try {
            network.transport.uploadFile(TARGET, key, file).getOrThrow()
            val post = network.requests.single { it.requestMethod == "POST" }
            assertEquals(0, post.fileBytes().size)
            assertNull(post.getRequestProperty("Content-Range"))
        } finally { file.delete() }
    }
    @Test fun resumeChecksContentRangeAndComputesDigestOfWholeFile() = runBlocking {
        downloadCase(rangeSupported = true)
    }
    @Test fun ignoredRangeRestartsInsteadOfAppendingAWholeFileToAPart() = runBlocking {
        downloadCase(rangeSupported = false)
    }
    private suspend fun downloadCase(rangeSupported: Boolean) {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val part = temp().apply { writeBytes(bytes.copyOfRange(0, 123)) }
        val file = metadata("download-file", size = bytes.size.toLong())
        val version = AppwriteStoredFile.fromJson(file).version
        var digest = ""
        val network = Network { request ->
            if (request.url.path.endsWith("/download")) {
                assertEquals("bytes=123-", request.getRequestProperty("Range"))
                if (rangeSupported) Reply(206, bytes.copyOfRange(123, bytes.size), mapOf("Content-Range" to "bytes 123-${bytes.lastIndex}/${bytes.size}"))
                else Reply(200, bytes)
            } else json(file)
        }
        try {
            assertTrue(network.transport.downloadFile(TARGET, "external:download-file", part, version, bytes.size.toLong(),
                onDigest = { digest = it }, resume = true).getOrThrow())
            assertArrayEquals(bytes, part.readBytes())
            assertEquals(sha256(bytes), digest)
        } finally { part.delete() }
    }
    @Test fun malformedRangeDeletesTheUnsafePart() = runBlocking {
        val part = temp().apply { writeText("ab") }
        val file = metadata("file1", size = 6)
        val network = Network { if (it.url.path.endsWith("/download")) Reply(206, "cdef".toByteArray(), mapOf("Content-Range" to "bytes 1-4/6")) else json(file) }
        try {
            assertTrue(network.transport.downloadFile(TARGET, "external:file1", part,
                AppwriteStoredFile.fromJson(file).version, 6L, resume = true).exceptionOrNull() is CloudConflictException)
            assertFalse(part.exists())
        } finally { part.delete() }
    }
    @Test fun truncatedDownloadIsNotSaved() = runBlocking {
        val part = temp()
        val file = metadata("file1", size = 6)
        val network = Network { if (it.url.path.endsWith("/download")) Reply(200, "short".toByteArray(), mapOf("Content-Length" to "6")) else json(file) }
        try {
            assertTrue(network.transport.downloadFile(TARGET, "external:file1", part).isFailure)
            assertFalse(part.exists())
        } finally { part.delete() }
    }
    @Test fun changedVersionAfterDownloadIsNotCommitted() = runBlocking {
        val part = temp()
        var gets = 0
        val network = Network {
            if (it.url.path.endsWith("/download")) Reply(200, "abc".toByteArray())
            else json(metadata("file1", size = 3).put("signature", if (++gets == 1) "first" else "replacement"))
        }
        try {
            assertTrue(network.transport.downloadFile(TARGET, "external:file1", part).exceptionOrNull() is CloudConflictException)
            assertFalse(part.exists())
        } finally { part.delete() }
    }
    @Test fun cancellationDisconnectsAndPreservesOnlyAResumablePart() = runBlocking {
        val part = temp()
        val file = metadata("file1", size = 100_000)
        val network = Network { if (it.url.path.endsWith("/download")) Reply(200, ByteArray(100_000)) else json(file) }
        try {
            val error = runCatching {
                network.transport.downloadFile(TARGET, "external:file1", part, resume = true,
                    onProgress = { _, _ -> throw CancellationException("test cancellation") })
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertTrue(part.length() in 1..100_000)
            // طلب بحث الملف الأول اكتمل بنجاح كامل قبل الإلغاء (مجرد استعلام بيانات وصفية)،
            // فيبقى مفتوحًا لإعادة الاستخدام (keep-alive) كبقية الطلبات الناجحة في هذا
            // الملف. الطلب المُلغى فعليًا هو طلب التنزيل نفسه (الأخير) — ووحده يجب أن
            // يُغلق قسرًا لأن قراءته توقفت في المنتصف بحالة غير معروفة للمقبس.
            assertFalse(network.requests.dropLast(1).any { it.disconnected })
            assertTrue(network.requests.last().disconnected)
        } finally { part.delete() }
    }
    @Test fun bucket404IsNotSilentlyTreatedAsAMissingFile() = runBlocking {
        val part = temp()
        val network = Network { Reply(404, """{"type":"storage_bucket_not_found"}""".toByteArray()) }
        try { assertTrue(network.transport.downloadFile(TARGET, "external:file1", part).isFailure) }
        finally { part.delete() }
    }
    @Test fun signedOutSessionCannotIssueAFileRequest() = runBlocking {
        val network = Network { error("network must not be reached") }
        network.authorizer.active = false
        assertTrue(network.transport.listBucketObjects(TARGET).isFailure)
        assertTrue(network.requests.isEmpty())
    }
    @Test fun metadataCannotBeDeletedThroughAnExternalAlias() = runBlocking {
        val id = AppwriteFileProtocol.nextManifestId(null)
        val network = Network { json(metadata(id, CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY)) }
        assertTrue(network.transport.deleteObject(TARGET, "external:$id").isFailure)
        assertTrue(network.requests.none { it.requestMethod == "DELETE" })
    }
    @Test fun concurrentManifestPublishHasOneWinnerAndNeverDeletesThePreviousRevision() = runBlocking {
        val root = AppwriteFileProtocol.nextManifestId(null)
        val files = ConcurrentHashMap<String, JSONObject>().apply { put(root, metadata(root, CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY, 2)) }
        val barrier = CyclicBarrier(2)
        val reads = AtomicInteger()
        val network = Network { request ->
            if (request.requestMethod == "GET") {
                val snapshot = JSONArray(files.values.toList())
                if (reads.incrementAndGet() <= 2) barrier.await(5, TimeUnit.SECONDS)
                json(JSONObject().put("files", snapshot))
            } else {
                val id = request.fileId()
                val file = metadata(id, CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY, request.fileBytes().size.toLong())
                if (files.putIfAbsent(id, file) == null) json(file, 201) else Reply(409, "{}".toByteArray())
            }
        }
        val results = (1..2).map { writer -> async(Dispatchers.IO) {
            network.transport.uploadText(TARGET, CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY, "{\"writer\":$writer}", ifMatch = root)
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is CloudConflictException })
        assertEquals(2, files.size)
        assertTrue(files.containsKey(root))
        assertTrue(network.requests.none { it.requestMethod == "DELETE" })
    }

    private class Authorizer : StorageAuthorizer {
        var active = true
        private val token = StorageGrant("storage.jwt.signature", "test-user", 1, Long.MAX_VALUE)
        override suspend fun grant(): StorageGrant { requireCurrent(token); return token }
        override fun requireCurrent(grant: StorageGrant) { if (!active) throw IOException("signed out") }
        override fun clear() = Unit
    }
    private class Network(handler: (FakeConnection) -> Reply) {
        val authorizer = Authorizer()
        val requests = Collections.synchronizedList(arrayListOf<FakeConnection>())
        // الباني آخر وسائطه platformHeaders: Map، فالـlambda الخلفية غير قانونية هنا؛
        // المصنع يُمرَّر وساطًا مُسمّى (خطأ ترجمة كان يُسقط حزمة الاختبارات كلها).
        val transport = AppwriteStorageTransport(
            TARGET, authorizer,
            openConnection = { url -> FakeConnection(url, handler).also { requests += it } }
        )
    }
    private data class Reply(val status: Int, val bytes: ByteArray, val headers: Map<String, String> = emptyMap())
    private class FakeConnection(url: URL, handler: (FakeConnection) -> Reply) : HttpURLConnection(url) {
        private val output = ByteArrayOutputStream()
        private val reply by lazy { handler(this) }
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = output
        override fun getInputStream() = ByteArrayInputStream(reply.bytes)
        override fun getErrorStream() = ByteArrayInputStream(reply.bytes)
        override fun getResponseCode() = reply.status
        override fun getContentLengthLong() = reply.headers["Content-Length"]?.toLong() ?: reply.bytes.size.toLong()
        override fun getHeaderField(name: String) = reply.headers[name]
        fun fileId(): String = output.toString("UTF-8").substringAfter("name=\"fileId\"\r\n\r\n").substringBefore("\r\n")
        fun fileBytes(): ByteArray {
            val raw = output.toByteArray()
            val prefix = raw.copyOfRange(0, minOf(raw.size, 1024)).toString(Charsets.UTF_8)
            val start = prefix.indexOf("\r\n\r\n", prefix.indexOf("filename=\"")) + 4
            check(start >= 4)
            val boundary = getRequestProperty("Content-Type").substringAfter("boundary=")
            val suffix = "\r\n--$boundary--\r\n".toByteArray()
            return raw.copyOfRange(start, raw.size - suffix.size)
        }
    }
    companion object {
        private val TARGET = CloudStorageConnection()
        private fun temp() = File.createTempFile("unihub-appwrite-test", ".part")
        private fun json(value: JSONObject, status: Int = 200) = Reply(status, value.toString().toByteArray(Charsets.UTF_8))
        private fun missing() = Reply(404, """{"type":"storage_file_not_found"}""".toByteArray())
        private fun metadata(id: String, name: String = "$id.pdf", size: Long = 1L): JSONObject = JSONObject()
            .put("\$id", id).put("name", name).put("sizeOriginal", size).put("signature", "content-signature")
            .put("\$createdAt", "2026-10-12T00:00:00Z").put("\$updatedAt", "2026-10-12T00:00:00Z")
            .put("chunksTotal", 1).put("chunksUploaded", 1)
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
