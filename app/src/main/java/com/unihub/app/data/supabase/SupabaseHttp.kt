package com.unihub.app.data.supabase

import com.unihub.app.data.cloud.CloudFailureMessages
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * عطل من خادم Supabase.
 *
 * الرسالة التي يراها المستخدم عربية جاهزة (من `CloudFailureMessages` — مصدر
 * واحد لنصوص المستخدم)، والتفاصيل التقنية تبقى في الحقل `detail` ولا
 * تُعرض أبدًا (طبيعة تطبيق.md §16: «رسالة الخطأ يجب أن تساعد المستخدم،
 * لا أن تعرض له المشكلة البرمجية الداخلية»).
 */
class SupabaseApiException(
    val status: Int,
    val code: String = "",
    val detail: String = ""
) : IOException(CloudFailureMessages.messageFor(status)) {

    /** عطل عابر في النقل/الخادم (مهلة، انقطاع، 5xx...) — يستحق إعادة محاولة، لا مسح جلسة. */
    val isTransient: Boolean get() = status == 0 || status == 408 || status in 500..599
    /** تعارض تفرّد: هو ما يقابل 409 في Appwrite (كتابة البيان المتزامنة). */
    val isUniqueViolation: Boolean get() = code == "23505"

    /** رموز أخطاء Postgres التي تعني «غير مصرّح» أو «ممنوع». */
    val isForbidden: Boolean get() = code == "42501" || status == 403

    val isNotFound: Boolean get() = status == 404 || code == "P0002"
}

/**
 * فشل نقل/شبكة عابر أثناء نداء مصادقة (لا استجابة من الخادم، انقطاع أثناء
 * الإرسال، 5xx...). لا يعني هذا أن الجلسة أو البيانات غير صالحة — يجب أن
 * يُعرض للمستخدم «أعد المحاولة» دون مسح أي جلسة محلية.
 *
 * إصلاح 2026-10-27: سابقًا كانت أي استجابة خاطئة من نداء تسجيل الدخول/تجديد
 * الرمز تُغلَّف SupabaseSessionInvalidException، فيمسح busyOperation الجلسة
 * المحلية ويعرض رسالة توثيق مضللة على مجرد انقطاع شبكة لحظي.
 */
class SupabaseNetworkException(message: String) : IOException(message)

/**
 * عميل REST صغير لـ Supabase — بلا أي مكتبة خارجية.
 *
 * قرار مقصود (طبيعة تطبيق.md §14: «لا تضف Dependency بلا حاجة»):
 * المشروع كان يملك نمط REST خامًا بـ HttpURLConnection في طبقة Appwrite،
 * والنقل إلى Supabase لا يحتاج أكثر من ذلك. مكتبة `supabase-kt` كانت ستضيف
 * اعتمادية كبيرة مقابل تغطية احتوت عليها الطبقة القائمة سلفًا.
 *
 * ترويسة `apikey` تحمل دائمًا مفتاح الـ API، و`Authorization` تحمل رمز
 * المستخدم. (خلطهما — إرسال رمز المستخدم في `apikey` — يُرفض بـ
 * «Invalid API key»؛ وهذا درسٌ محفوظ في سكربت فحص RLS.)
 */
class SupabaseHttp(
    private val baseUrl: String,
    private val apiKey: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {

    /** استجابة تدفّقية (تنزيل ملف) مع ما يلزم للتحقق من السلامة. */
    class StreamResponse(
        val code: Int,
        val contentLength: Long,
        val contentRange: String?,
        val stream: InputStream
    )

    fun requestText(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        prefer: String? = null
    ): String {
        val connection = openConnection(url(path, query))
        try {
            connection.requestMethod = method
            applyHeaders(connection, token, headers, prefer)
            if (body != null) {
                // إصلاح 2026-10-26 (سبب جذري لعطل «لا يمكن تسجيل الدخول إطلاقًا»):
                // لم تكن تُضبط ترويسة Content-Type، فيرسل HttpURLConnection الجسم
                // بـ application/x-www-form-urlencoded افتراضيًا. خادم GoTrue لا
                // يقرأ JSON إلا مع application/json، فيرد 400 bad_json
                // («Could not parse request body as JSON») على تسجيل الدخول وكل
                // نداءات RPC — رغم صحة البريد وكلمة المرور والخادم.
                // الدليل: اختبار حي LiveLoginVerificationTest فشل قبل الإصلاح
                // بنفس المسار الذي ينجح عبر curl مع الترويسة الصريحة.
                if (connection.getRequestProperty("Content-Type") == null) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    /** تنزيل ملف مع دعم استئناف (Range) — يقرأ البايتات من التدفق مباشرة. */
    fun requestStream(
        method: String,
        path: String,
        token: String? = null,
        headers: Map<String, String> = emptyMap()
    ): StreamResponse {
        val connection = openConnection(url(path))
        try {
            connection.requestMethod = method
            applyHeaders(connection, token, headers, null)
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = readErrorBody(connection)
                connection.disconnect()
                throw failure(code, body)
            }
            val length = connection.contentLengthLong
            val range = connection.getHeaderField("Content-Range")
            val stream = connection.inputStream
            return StreamResponse(code, length, range, stream)
        } catch (error: IOException) {
            connection.disconnect()
            throw error
        }
    }

    /**
     * رفع بايتات خام. Storage يقبلها مع `x-upsert` (مُتحقَّق منه حيًّا).
     * الاستجابة 200 مع JSON صغير فيه `Key` و`Id`.
     */
    fun uploadBytes(
        path: String,
        token: String,
        bytes: ByteArray,
        contentType: String,
        upsert: Boolean = true
    ): String {
        val connection = openConnection(url(path))
        try {
            connection.requestMethod = "POST"
            applyHeaders(connection, token, mapOf("Content-Type" to contentType), null)
            if (upsert) connection.setRequestProperty("x-upsert", "true")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * رفع ملف من القرص مع بثّ التقدّم.
     *
     * يُستخدم وضع التدرج (chunked) لا الطول الثابت: هكذا يُبلَّغ عن نسبة
     * الرفع الحقيقية أثناء الإرسال، بدل قفزة من 0 إلى 100% في النهاية.
     */
    fun uploadFile(
        path: String,
        token: String,
        source: File,
        contentType: String,
        upsert: Boolean = true,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): String {
        val connection = openConnection(url(path))
        try {
            connection.requestMethod = "POST"
            applyHeaders(connection, token, mapOf("Content-Type" to contentType), null)
            if (upsert) connection.setRequestProperty("x-upsert", "true")
            connection.doOutput = true
            connection.setChunkedStreamingMode(UPLOAD_CHUNK_BYTES)
            val total = source.length()
            var sent = 0L
            connection.outputStream.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        onProgress(sent, total)
                    }
                }
            }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun url(path: String, query: Map<String, String> = emptyMap()): URL {
        val base = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        if (query.isEmpty()) return URL(base)
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return URL("$base?$encoded")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun applyHeaders(
        connection: HttpURLConnection,
        token: String?,
        headers: Map<String, String>,
        prefer: String?
    ) {
        connection.setRequestProperty("apikey", apiKey)
        connection.setRequestProperty("Authorization", "Bearer ${token ?: apiKey}")
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (!prefer.isNullOrBlank()) connection.setRequestProperty("Prefer", prefer)
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val body = if (code in 200..299) {
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            readErrorBody(connection)
        }
        if (code !in 200..299) throw failure(code, body)
        return body
    }

    private fun readErrorBody(connection: HttpURLConnection): String = try {
        connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    } catch (_: IOException) {
        ""
    }

    /**
     * نصّ من JSON دون تحويل قيمة JSON null إلى سلسلة "null".
     * (org.json قد يعيد "null" نصًّا، فيفشل التطابق مع رموز الأخطاء.)
     */
    private fun jsonText(json: org.json.JSONObject, key: String): String =
        if (!json.has(key) || json.isNull(key)) "" else json.optString(key)

    /** يستخرج رمز الخطأ من أي من صيغ Supabase (PostgREST / GoTrue / Storage). */
    private fun failure(status: Int, body: String): SupabaseApiException {
        var code = ""
        var detail = ""
        if (body.isNotBlank()) {
            val json = runCatching { org.json.JSONObject(body) }.getOrNull()
            if (json != null) {
                code = jsonText(json, "code").ifBlank { jsonText(json, "error") }
                // GoTrue يضع نص الخطأ في «msg» (و«error_code» رمزه النصي)؛ بدونهما
                // يبقى detail فارغًا فلا تتعرّف authMessage على «invalid login
                // credentials» وتظهر رسالة عامة لا تساعد المستخدم.
                if (code.isBlank() || code.all { it.isDigit() }) {
                    code = jsonText(json, "error_code").ifBlank { code }
                }
                detail = jsonText(json, "details").ifBlank { jsonText(json, "message") }
                    .ifBlank { jsonText(json, "msg") }
                    .ifBlank { jsonText(json, "error_description") }
            } else {
                detail = body.take(200)
            }
        }
        return SupabaseApiException(status, code, detail)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val UPLOAD_CHUNK_BYTES = 64 * 1024
    }
}
