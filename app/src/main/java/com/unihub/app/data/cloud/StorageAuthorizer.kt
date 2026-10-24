package com.unihub.app.data.cloud

import java.io.IOException

/**
 * تفويض الوصول إلى ملفات السحابة.
 *
 * في Appwrite كان هنا **جسر كامل**: هوية «ظل» لكل مستخدم في مشروع الملفات
 * ورمز تخزين مدته 300 ثانية. في Supabase لا حاجة لأيٍّ منهما: **رمز المستخدم
 * نفسه** هو التفويض، وسياسات RLS على `storage.objects` هي ما يحدّد ما يراه
 * المستخدم وما لا يراه. ⇒ حُذفت الهوية الظل ورمز التخزين القصير العمر.
 *
 *بقى الحدّ نفسه لسببين:
 *  1) تتيح لكل اختبار بروتوكول استخدام تفويض وهمي بلا شبكة.
 *  2) التمييز بين «انتهت الجلسة» و«تعذّر الاتصال» في رسالة المستخدم.
 */
interface StorageAuthorizer {
    /** يُنسي التخويل المؤقت (عند تسجيل الخروج أو تبديل الحساب). */
    fun clear()

    /** يتحقق أن التفويض ما زال صالحًا لجلسة سارية. */
    fun requireCurrent(grant: StorageGrant)

    /** يفوز بتفويض صالح (يحدّث الرمز عند الحاجة). */
    suspend fun grant(): StorageGrant
}

/** تفويض في الذاكرة فقط: لا يُحفظ في التفضيلات ولا في السجلّات. */
class StorageGrant(
    val jwt: String,
    val authUserId: String,
    val sessionVersion: Long,
    val refreshAtNanos: Long
) {
    override fun toString(): String = "StorageGrant(jwt=<redacted>)"
}

/**
 * انتهت الجلسة أثناء نقل الملفات.
 * منفصل عن خطأ «جلسة غير صالحة» الذي يمسح الجلسة المحلية: هنا نُبقي
 * الجلسة على الجهاز ونكتفي بإيقاف النقل.
 */
class CloudSessionExpiredException(message: String) : IOException(message)
