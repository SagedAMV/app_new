package com.unihub.app.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

internal suspend fun <T> cloudAttempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

class CloudConflictException : IOException("تغيّرت النسخة على الخادم أثناء العملية؛ أعد الفحص والمحاولة")
data class R2UploadedObject(val etag: String, val size: Long)
data class R2TextObject(val text: String, val etag: String)

/** نقل تدفقي ثابت الذاكرة، توقيع SigV4، وقوائم R2 متعددة الصفحات. */
@Singleton
class CloudflareR2Client @Inject constructor() {
    suspend fun uploadFile(
        credentials: R2Credentials,
        objectKey: String,
        sourceFile: File,
        contentType: String = "application/octet-stream",
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<R2UploadedObject> = withContext(Dispatchers.IO) {
        cloudAttempt {
            if (!sourceFile.isFile) throw IOException("الملف المحلي غير موجود")
            if (sourceFile.length() > 5L * 1024 * 1024 * 1024) {
                throw IOException("رفع ملف أكبر من 5 ج.ب يتطلب الرفع متعدد الأجزاء؛ لم تُفعّل هذه الميزة بعد")
            }
            val size = sourceFile.length()
            val jobContext = currentCoroutineContext()
            val conn = connection(credentials, objectKey, "PUT", sha256Hex(sourceFile) { jobContext.ensureActive() }).apply {
                doOutput = true
                setFixedLengthStreamingMode(size)
                setRequestProperty("Content-Type", contentType)
            }
            withCancellableConnection(conn) {
                var sent = 0L
                var lastProgressAt = 0L
                sourceFile.inputStream().use { input ->
                    conn.outputStream.use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            sent += n
                            val now = System.nanoTime()
                            if (now - lastProgressAt >= 150_000_000L) { onProgress(sent, size); lastProgressAt = now }
                        }
                    }
                }
                requireSuccess(conn)
                onProgress(sent, size)
                R2UploadedObject(conn.getHeaderField("ETag").orEmpty().trim('"'), size)
            }
        }
    }

    suspend fun uploadText(
        credentials: R2Credentials,
        objectKey: String,
        text: String,
        contentType: String = "application/json; charset=utf-8",
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false
    ): Result<R2UploadedObject> = withContext(Dispatchers.IO) {
        cloudAttempt {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val conn = connection(credentials, objectKey, "PUT", sha256Hex(bytes)).apply {
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", contentType)
                if (!ifMatch.isNullOrBlank()) setRequestProperty("If-Match", quoteEtag(ifMatch))
                if (ifNoneMatch) setRequestProperty("If-None-Match", "*")
            }
            withCancellableConnection(conn) {
                conn.outputStream.use { it.write(bytes) }
                requireSuccess(conn)
                R2UploadedObject(conn.getHeaderField("ETag").orEmpty().trim('"'), bytes.size.toLong())
            }
        }
    }

    suspend fun downloadTextObject(
        credentials: R2Credentials,
        objectKey: String,
        expectedEtag: String? = null
    ): Result<R2TextObject?> = withContext(Dispatchers.IO) {
        cloudAttempt {
            val conn = connection(credentials, objectKey, "GET").apply {
                if (!expectedEtag.isNullOrBlank()) setRequestProperty("If-Match", quoteEtag(expectedEtag))
            }
            withCancellableConnection(conn) {
                if (conn.responseCode == 404) null else {
                    requireSuccess(conn)
                    R2TextObject(
                        conn.inputStream.use { readBoundedText(it) },
                        conn.getHeaderField("ETag").orEmpty().trim('"')
                    )
                }
            }
        }
    }

    suspend fun downloadText(credentials: R2Credentials, objectKey: String): Result<String?> =
        downloadTextObject(credentials, objectKey).map { it?.text }

    /**
     * تنزيل تدفقي مع إمكانية الاستكمال. عند [resume] وبعد انقطاع/إيقاف، يبقى الملف الجزئي
     * ويُستأنف بـ `Range: bytes=N-` (الجواب 206) بدل إعادته من أوله؛ وإذا تجاهل الخادم النطاق
     * (جاء 200) نُصفّر ونبدأ من جديد — لا نخلط جزئيًا بكامله. والبصمة تُحسب على الكل: نُمرّر
     * الجزء السابق عبر الـdigest قبل اللصق، فلا تتفكك المقارنة مع الفهرس.
     */
    suspend fun downloadFile(
        credentials: R2Credentials,
        objectKey: String,
        targetFile: File,
        expectedEtag: String? = null,
        expectedSize: Long? = null,
        onDigest: ((String) -> Unit)? = null,
        resume: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        cloudAttempt {
            val partLength = if (resume && targetFile.isFile) targetFile.length() else 0L
            val plan = CloudDownloadPart.plan(partLength, expectedSize)
            val from = if (plan is CloudDownloadPart.Resume.Continue) plan.from else 0L
            val conn = connection(credentials, objectKey, "GET").apply {
                if (!expectedEtag.isNullOrBlank()) setRequestProperty("If-Match", quoteEtag(expectedEtag))
                if (from > 0L) setRequestProperty("Range", "bytes=$from-")
            }
            var completed = false
            var keepPartial = false
            withCancellableConnection(conn) {
            try {
                if (conn.responseCode == 404) {
                    if (from > 0L) targetFile.delete() // جزء من نسخة لم تعد موجودة: لا قيمة لها
                    return@withCancellableConnection false
                }
                requireSuccess(conn)
                val partialAccepted = conn.responseCode == 206 && from > 0L
                val resumeFrom = if (partialAccepted) from else 0L
                val total = when {
                    expectedSize != null && expectedSize > 0L -> expectedSize
                    partialAccepted && conn.contentLengthLong >= 0 -> resumeFrom + conn.contentLengthLong
                    conn.contentLengthLong >= 0 -> conn.contentLengthLong
                    else -> -1L
                }
                if (expectedSize != null && expectedSize > 0L && conn.contentLengthLong >= 0) {
                    val announced = if (partialAccepted) resumeFrom + conn.contentLengthLong else conn.contentLengthLong
                    if (announced != expectedSize) throw CloudConflictException()
                }
                targetFile.parentFile?.mkdirs()
                var bytes = resumeFrom
                val digest = MessageDigest.getInstance("SHA-256")
                if (resumeFrom > 0L) digest.updateFromFile(targetFile, resumeFrom)
                var lastProgressAt = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile, resumeFrom > 0L).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            if (total >= 0 && bytes > total) throw IOException("حجم التنزيل تجاوز الحجم المعلن")
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            val now = System.nanoTime()
                            if (now - lastProgressAt >= 150_000_000L) {
                                onProgress(bytes, total)
                                lastProgressAt = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (total >= 0 && bytes != total) throw IOException("التنزيل غير مكتمل؛ لم يُحفظ الملف في المكتبة")
                currentCoroutineContext().ensureActive()
                onDigest?.invoke(hex(digest.digest()))
                onProgress(bytes, total)
                completed = true
                true
            } catch (cancelled: CancellationException) {
                // مقاطعة المستخدم أو النظام: الجزء كنزٌ لا نفايات — يُستأنف منه
                keepPartial = true
                throw cancelled
            } finally {
                if (!completed && !keepPartial) targetFile.delete()
            }
            }
        }
    }

    private fun MessageDigest.updateFromFile(file: File, length: Long) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            var left = length
            while (left > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                update(buffer, 0, read)
                left -= read
            }
        }
    }

    suspend fun listBucketObjects(credentials: R2Credentials): Result<List<R2ObjectSummary>> =
        withContext(Dispatchers.IO) {
            cloudAttempt {
                if (!credentials.useS3Protocol) throw IOException("استعراض الحاوية يتطلب مفاتيح R2 S3")
                val result = ArrayList<R2ObjectSummary>()
                var token: String? = null
                val tokensSeen = HashSet<String>()
                do {
                    currentCoroutineContext().ensureActive()
                    val params = mutableMapOf("encoding-type" to "url", "list-type" to "2", "max-keys" to "1000")
                    token?.let { params["continuation-token"] = it }
                    val conn = connection(credentials, null, "GET", query = params)
                    val page = withCancellableConnection(conn) {
                        requireSuccess(conn)
                        R2ListParser.parse(conn.inputStream.use { readBoundedText(it) })
                    }
                    result += page.objects
                    token = page.nextToken
                    if (token != null && !tokensSeen.add(token)) throw IOException("مؤشر صفحة R2 متكرر")
                } while (token != null)
                result
            }
        }

    /** تنظيف مسموح فقط للكائنات المؤقتة التي تنشئها اختبارات هذا العميل. */
    internal suspend fun deleteTestObject(credentials: R2Credentials, objectKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            cloudAttempt {
                require(objectKey.startsWith("unihub-tests/"))
                val conn = connection(credentials, objectKey, "DELETE")
                withCancellableConnection(conn) { requireSuccess(conn) }
            }
        }

    /**
     * حذف كائن محتوى من الحاوية (تحكم كامل داخل شاشة السحابة).
     * S3 يجعل DELETE متكرراً آمناً: 404 تعني «محذوف مسبقاً» لا فشلاً — فتكون إعادة المحاولة
     * بعد انقطاع آمنة. كائنات النظام (الفهرس/الوصف/النسخة الاحتياطية) محميّة بنيوياً هنا
     * وفي قواعد الحذف، فلا يمكن لهذا المسار أن يمحو فهرس الخادم أو نسخة احتياطية.
     */
    suspend fun deleteObject(credentials: R2Credentials, objectKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            cloudAttempt {
                if (!CloudDeleteRules.isDeletableObjectKey(objectKey)) {
                    throw IOException("هذا الكائن محمي من الحذف")
                }
                val conn = connection(credentials, objectKey, "DELETE")
                withCancellableConnection(conn) {
                    if (conn.responseCode != 404) requireSuccess(conn)
                }
            }
        }

    private fun connection(
        credentials: R2Credentials,
        objectKey: String?,
        method: String,
        payloadHash: String = EMPTY_HASH,
        query: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        if (!credentials.isConfigured) throw IOException("بيانات خادم R2 غير مضبوطة")
        val base = credentials.resolvedEndpoint.trimEnd('/')
        val bucket = credentials.bucketName.trim().trim('/')
        val endpoint = if (bucket.isNotBlank() && !base.endsWith("/$bucket")) "$base/${S3Encoding.component(bucket)}" else base
        val path = objectKey?.let { "/${S3Encoding.path(it)}" }.orEmpty()
        val queryString = S3Encoding.query(query)
        val url = URL(endpoint + path + if (queryString.isEmpty()) "" else "?$queryString")
        if (url.protocol != "https") throw IOException("اتصال R2 يجب أن يستخدم HTTPS لحماية مفاتيحك")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            authenticate(this, url, method, payloadHash, credentials)
        }
    }

    private fun authenticate(conn: HttpURLConnection, url: URL, method: String, hash: String, creds: R2Credentials) {
        if (!creds.useS3Protocol) {
            if (creds.secretAccessKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${creds.secretAccessKey}")
            return
        }
        val now = Instant.now().atZone(ZoneOffset.UTC)
        val amzDate = AMZ_DATE.format(now)
        val date = DATE.format(now)
        val host = if (url.port != -1 && url.port != url.defaultPort) "${url.host}:${url.port}" else url.host
        val headers = "host:$host\nx-amz-content-sha256:$hash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val request = "$method\n${url.path.ifBlank { "/" }}\n${url.query.orEmpty()}\n$headers\n$signedHeaders\n$hash"
        val scope = "$date/${creds.region}/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(request.toByteArray(Charsets.UTF_8))}"
        var key = ("AWS4${creds.secretAccessKey.trim()}").toByteArray(Charsets.UTF_8)
        for (part in listOf(date, creds.region, "s3", "aws4_request")) key = hmac(key, part)
        val signature = hex(hmac(key, toSign))
        conn.setRequestProperty("x-amz-content-sha256", hash)
        conn.setRequestProperty("x-amz-date", amzDate)
        conn.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=${creds.accessKeyId.trim()}/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
        // HttpURLConnection يبني Host من URL نفسه؛ لا نحاول تجاوز ترويسة محظورة على JVM.
    }

    private fun requireSuccess(conn: HttpURLConnection) {
        val status = conn.responseCode
        if (status == 412 || status == 409) throw CloudConflictException()
        if (status !in 200..299) throw IOException("فشل الاتصال بخادم R2 (HTTP $status)")
    }

    private suspend fun readBoundedText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size().toLong() + n > MAX_INDEX_BYTES) throw IOException("الفهرس أكبر من حد الأمان (16 م.ب)")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    fun sha256Hex(file: File, checkCancellation: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkCancellation()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256Hex(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(text.toByteArray(Charsets.UTF_8))
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    private fun quoteEtag(etag: String) = "\"${etag.trim('"')}\""

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_INDEX_BYTES = 16L * 1024 * 1024
        private const val EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    }
}
