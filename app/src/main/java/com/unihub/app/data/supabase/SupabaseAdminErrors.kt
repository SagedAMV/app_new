package com.unihub.app.data.supabase

import java.io.IOException

/**
 * ترجمة أخطاء الخادم إلى كلام المستخدم.
 *
 * المبدأ نفسه الذي كان يحمله جسر Appwrite: الخادم يرفع رمزًا دلاليًا
 * (`invalid_permissions`، `protected_admin`…) والعميل يترجمه إلى جملة عربية
 * مفهومة. الأسباق محفوظة حرفيًا حتى لا يتغيّر ما يراه المستخدم.
 */
object SupabaseAdminErrors {

    fun messageFor(error: Throwable): String = when (error) {
        is SupabaseApiException -> forApiError(error)
        else -> error.message.orEmpty()
    }

    private fun forApiError(error: SupabaseApiException): String = when (val signal = error.detail.trim()) {
        "invalid_permissions" ->
            "الرفع يحتاج إذن القراءة، والتعديل يحتاج إذنَي القراءة والرفع"
        "invalid_email" ->
            "أدخل بريدًا إلكترونيًا صحيحًا لحسابك"
        "invalid_password" ->
            "كلمة المرور يجب أن تكون من 10 إلى 256 حرفًا"
        "protected_admin" ->
            "لا يمكن تعديل حساب إداري من هذه الواجهة؛ تواصل مع المشرف"
        "already_initialized" ->
            "تم إعداد حساب مشرف لهذا التطبيق من قبل؛ سجّل الدخول بحسابك الحالي أو تواصل مع المشرف لإنشاء حساب لك"
        "conflict" ->
            "الحساب موجود مسبقًا أو حدث تعارض؛ حدّث القائمة وأعد المحاولة"
        "not_found" ->
            "الحساب المطلوب غير موجود"
        "forbidden" ->
            "لا يملك الحساب صلاحية تنفيذ هذا الإجراء"
        "unauthorized" ->
            "انتهت الجلسة؛ سجّل الدخول مجددًا"
        else -> when {
            error.isUniqueViolation -> "الحساب موجود مسبقًا أو حدث تعارض؛ حدّث القائمة وأعد المحاولة"
            else -> error.message.orEmpty()
        }
    }
}

/** خطأ إداري يحمل رسالة عربية جاهزة (لا رمزًا تقنيًا). */
class SupabaseAdminException(message: String) : IOException(message)
