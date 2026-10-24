package com.unihub.app.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * اخ طبقة HTTP على Supabase — بلا شبكة (اتصال مزيف).
 *
 * الهدف: تثبيت الدروس التي كلّفت وقتًا في الفحص الحي:
 *  - `apikey` = مفتاح النشر، و`Authorization` = رمز المستخدم. الخلط بينهما
 *    يُنتج 401 «Invalid API key».
 *  - تعارض التفرّد (23505) هو ما يقابل 409 في التعارضWriting المتزامن.
 */
class SupabaseHttpTest {

    private class FakeConnection(
        private val code: Int,
        private val body: String,
        private val headers: Map<String, String> = emptyMap()
    ) : HttpURLConnection(URL("https://example.test/rest/v1/thing")) {
        val sentHeaders = mutableMapOf<String, String>()
        var payload: ByteArray = ByteArray(0)
        private val buffer = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream? =
            if (code in 200..299) null else ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getHeaderField(name: String): String? = headers[name]
        override fun setRequestProperty(key: String, value: String?) {
            sentHeaders[key] = value ?: ""
        }
        override fun getOutputStream(): OutputStream = buffer
    }

    @Test
    fun apiKeyAndUserTokenTravelInSeparateHeaders() {
        val connection = FakeConnection(200, """[{"id":"x"}]""")
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }

        val body = http.requestText("GET", "/rest/v1/thing", token = "USER_JWT")

        assertEquals("""[{"id":"x"}]""", body)
        assertEquals("PUBLISHABLE_KEY", connection.sentHeaders["apikey"])
        assertEquals("Bearer USER_JWT", connection.sentHeaders["Authorization"])
    }

    @Test
    fun anonymousRequestFallsBackToTheApiKey() {
        val connection = FakeConnection(200, "[]")
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }
        http.requestText("GET", "/rest/v1/thing")
        assertEquals("Bearer PUBLISHABLE_KEY", connection.sentHeaders["Authorization"])
    }

    @Test
    fun uniqueViolationIsRecognisedAsWriteConflict() {
        val connection = FakeConnection(
            409,
            """{"code":"23505","message":"duplicate key value violates unique constraint \"cloud_manifests_pkey\""}"""
        )
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }

        val error = runCatching { http.requestText("POST", "/rest/v1/cloud_manifests", body = "{}") }
            .exceptionOrNull()

        assertTrue(error is SupabaseApiException)
        val api = error as SupabaseApiException
        assertEquals(409, api.status)
        assertEquals("23505", api.code)
        assertTrue("23505 = تعارض تفرّد = تعارض كتابة", api.isUniqueViolation)
    }

    @Test
    fun forbiddenAndMissingAreDistinguished() {
        val forbidden = FakeConnection(403, """{"code":"42501","message":"forbidden"}""")
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { forbidden }
        val error = runCatching { http.requestText("GET", "/rest/v1/profiles") }.exceptionOrNull()
        assertTrue(error is SupabaseApiException)
        assertTrue((error as SupabaseApiException).isForbidden)
        assertFalse(error.isUniqueViolation)

        val missing = FakeConnection(404, """{"code":"PGRST116","message":"not found"}""")
        val http2 = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { missing }
        val error2 = runCatching { http2.requestText("GET", "/rest/v1/profiles") }.exceptionOrNull()
        assertTrue((error2 as SupabaseApiException).isNotFound)
    }

    @Test
    fun streamResponseKeepsRangeInformation() {
        val connection = FakeConnection(
            206,
            "tail",
            mapOf("Content-Range" to "bytes 6-18/19")
        )
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }
        val response = http.requestStream("GET", "/storage/v1/object/unihub-files/a/b.pdf", token = "USER_JWT")
        assertEquals(206, response.code)
        assertEquals("bytes 6-18/19", response.contentRange)
        assertEquals("tail", response.stream.readBytes().toString(Charsets.UTF_8))
    }
}
