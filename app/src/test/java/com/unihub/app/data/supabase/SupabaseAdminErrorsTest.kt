package com.unihub.app.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ترجمة رموز أخطاء القاعدة إلى نص عربي — خصوصًا `already_initialized`،
 * الرمز الذي يحمي `bootstrap_first_admin` من إعادة الاستخدام بعد وجود
 * مشرف (راجع مراجعة.md: تحليل سبب تعذّر تسجيل الدخول على مشروع جديد).
 */
class SupabaseAdminErrorsTest {

    @Test
    fun alreadyInitializedMapsToAGuidingArabicMessage() {
        val error = SupabaseApiException(status = 403, code = "42501", detail = "already_initialized")
        val message = SupabaseAdminErrors.messageFor(error)
        assertEquals(
            "تم إعداد حساب مشرف لهذا التطبيق من قبل؛ سجّل الدخول بحسابك الحالي أو تواصل مع المشرف لإنشاء حساب لك",
            message
        )
    }

    @Test
    fun unrelatedForbiddenSignalsStillMapToTheirOwnMessage() {
        val error = SupabaseApiException(status = 403, code = "42501", detail = "forbidden")
        assertEquals("لا يملك الحساب صلاحية تنفيذ هذا الإجراء", SupabaseAdminErrors.messageFor(error))
    }
}
