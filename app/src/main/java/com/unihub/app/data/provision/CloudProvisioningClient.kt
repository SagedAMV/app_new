package com.unihub.app.data.provision

import com.unihub.app.BuildConfig
import com.unihub.app.data.cloud.withCancellableConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/** Delivers permanent R2 credentials in a device-bound encrypted envelope, not temporary S3 keys. */
@Singleton
class CloudProvisioningClient @Inject constructor() {
    val isAvailable: Boolean get() = endpointOrNull(BuildConfig.PROVISIONING_URL) != null

    suspend fun receive(invitation: String, request: ProvisioningProtocol.ReceiveRequest): ProvisioningProtocol.Envelope =
        withContext(Dispatchers.IO) {
            val endpoint = endpointOrNull(BuildConfig.PROVISIONING_URL)
                ?: throw IOException("خدمة توزيع الاتصال غير مفعّلة لهذه النسخة؛ استخدم الباركود المشفر أو راجع المالك")
            val token = invitation.trim()
            if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) throw IOException("أدخل رمز التفعيل الذي أرسله المالك")
            ProvisioningProtocol.validate(request)
            val body = JSONObject().apply {
                put("invitation", token)
                put("request", JSONObject(request.toJson()))
            }.toString().toByteArray(Charsets.UTF_8)
            val conn = endpoint.toURL().openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Cache-Control", "no-store")
            try {
                withTimeout(35_000L) {
                    withCancellableConnection(conn) {
                        conn.outputStream.use { it.write(body) }
                        when (conn.responseCode) {
                            200 -> Unit
                            400, 403, 409 -> throw IOException("رمز التفعيل غير صالح أو منتهٍ أو مستخدم لجهاز آخر؛ راجع المالك")
                            429 -> throw IOException("طلبات كثيرة؛ انتظر قليلًا ثم أعد المحاولة")
                            else -> throw IOException("خدمة توزيع الاتصال غير متاحة الآن؛ أعد المحاولة لاحقًا")
                        }
                        val out = ByteArrayOutputStream()
                        conn.inputStream.use { input ->
                            val buffer = ByteArray(1_024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (out.size() + count > 4_096) throw IOException("حزمة الخدمة أكبر من المسموح")
                                out.write(buffer, 0, count)
                            }
                        }
                        ProvisioningProtocol.readEnvelope(out.toString("UTF-8"))
                    }
                }
            } finally {
                body.fill(0)
                conn.disconnect()
            }
        }

    companion object {
        /** Only the public compile-time endpoint is accepted; no user-supplied credential receiver URL. */
        internal fun endpointOrNull(value: String): URI? = runCatching {
            val uri = URI(value.trim())
            val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return@runCatching null
            if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in listOf(-1, 443) || uri.rawPath != "/v1/connection" || !host.contains('.') ||
                host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") ||
                host.matches(Regex("[0-9.]+")) || ':' in host || host.any(Char::isISOControl)) null else uri
        }.getOrNull()
    }
}
