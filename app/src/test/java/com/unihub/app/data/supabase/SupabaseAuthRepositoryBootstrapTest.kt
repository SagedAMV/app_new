package com.unihub.app.data.supabase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * `bootstrap_first_admin` — السبب الجذري الذي عالجته هذه الجلسة لعطل
 * «لا أستطيع تسجيل الدخول إطلاقًا» على مشروع Supabase جديد بلا أي مشرف.
 *
 * الدليل الحي (موثَّق في مراجعة.md): مشروع Supabase بعد ترحيل Appwrite بدأ
 * فارغًا تمامًا (0 مستخدم)، ولا توجد شاشة تسجيل ذاتي في التطبيق، ودالة
 * إنشاء المستخدمين العادية (`admin_create_user`) ترفض كل من ليس مشرفًا
 * بالفعل — فلا طريقة لإنشاء أول حساب يدخل به أي شخص. هذه الاختبارات تثبت
 * أن طبقة العميل (Kotlin) تستدعي المسار الجديد بشكل صحيح: بلا رمز مستخدم
 * (anon فقط)، وتترجم رفض الخادم بعد وجود مشرف إلى رسالة عربية واضحة بدل
 * فشل صامت أو رسالة عامة غير مفيدة.
 */
class SupabaseAuthRepositoryBootstrapTest {

    private class FakeConnection(
        private val code: Int,
        private val body: String
    ) : HttpURLConnection(URL("https://proj.supabase.co/rest/v1/rpc/bootstrap_first_admin")) {
        val sentHeaders = mutableMapOf<String, String>()
        private val sentBodyBuffer = ByteArrayOutputStream()
        val sentBody: String get() = sentBodyBuffer.toString(Charsets.UTF_8.name())

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream? =
            if (code in 200..299) null else ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun setRequestProperty(key: String, value: String?) {
            sentHeaders[key] = value ?: ""
        }
        override fun getOutputStream() = sentBodyBuffer
    }

    @Test
    fun bootstrapCallIsAnonymous_noUserTokenEvenThoughNoneIsLoggedInYet() = runBlocking {
        val connection = FakeConnection(200, "\"11111111-1111-1111-1111-111111111111\"")
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }
        val repository = SupabaseAuthRepository(http)

        repository.bootstrapFirstAdmin("Admin@Example.com", "SuperSecret123")

        // apikey والمفتاح نفسه في Authorization (بلا Bearer جلسة) — هذه
        // هي اللحظة الوحيدة التي لا يوجد فيها أي مستخدم ليُسجَّل دخوله.
        assertEquals("PUBLISHABLE_KEY", connection.sentHeaders["apikey"])
        assertEquals("Bearer PUBLISHABLE_KEY", connection.sentHeaders["Authorization"])
        assertTrue("البريد يُطبَّع إلى أحرف صغيرة قبل الإرسال", connection.sentBody.contains("admin@example.com"))
        assertFalse("لا يُرسل أبدًا مفتاح سرّي من العميل", connection.sentBody.contains("sb_secret_"))
    }

    @Test
    fun bootstrapRejectsLocallyBeforeAnyNetworkCall_whenEmailIsInvalid() {
        val connection = FakeConnection(200, "{}")
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }
        val repository = SupabaseAuthRepository(http)

        val error = runCatching { runBlocking { repository.bootstrapFirstAdmin("ليس-بريدًا", "SuperSecret123") } }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(connection.sentHeaders.isEmpty())
    }

    @Test
    fun bootstrapRejectsLocallyBeforeAnyNetworkCall_whenPasswordTooShort() {
        val connection = FakeConnection(200, "{}")
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }
        val repository = SupabaseAuthRepository(http)

        val error = runCatching { runBlocking { repository.bootstrapFirstAdmin("admin@example.com", "short") } }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(connection.sentHeaders.isEmpty())
    }

    @Test
    fun bootstrapSurfacesAGuidingArabicMessage_onceAnAdminAlreadyExists() {
        // هذا هو الحارس الذي يمنع تحوّل المسار إلى ثغرة تسجيل ذاتي دائمة:
        // بمجرد وجود مشرف واحد، يرفض الخادم أي محاولة لاحقة بـ already_initialized.
        val connection = FakeConnection(403, """{"code":"42501","message":"already_initialized"}""")
        val http = SupabaseHttp("https://proj.supabase.co", "PUBLISHABLE_KEY") { connection }
        val repository = SupabaseAuthRepository(http)

        val error = runCatching { runBlocking { repository.bootstrapFirstAdmin("admin@example.com", "SuperSecret123") } }
            .exceptionOrNull()

        assertTrue(error is SupabaseAdminException)
        assertEquals(
            "تم إعداد حساب مشرف لهذا التطبيق من قبل؛ سجّل الدخول بحسابك الحالي أو تواصل مع المشرف لإنشاء حساب لك",
            error?.message
        )
    }
}
