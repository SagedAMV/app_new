package com.unihub.app.feature.files

/**
 * سياسة زر الرجوع (النظام) في شاشات الملفات — جولة تعليمات.md:
 * زر رجوع الخاص بنظام التشغيل كان يُخرج من الشاشة كاملة بدل التراجع التدريجي.
 *
 * السلوك المعتمد (سلّم أولويات موحّد):
 * 1) يوجد تحديد نشط → إلغاء التحديد أولاً (الحالة الأحدث والأكثر عرضية).
 * 2) داخل مجلد → الصعود للمجلد الأب.
 * 3) غير ذلك → الخروج من الشاشة (السلوك الافتراضي السابق).
 *
 * نافذة/ورقة مفتوحة تُعالج رجوعها بنفسها (ModalBottomSheet/AlertDialog) لأن
 * مستمعها يُسجَّل بعد هذا المستمع في OnBackPressedDispatcher — لذا لا تدخل هنا.
 *
 * القرار نقي (بلا Compose) حتى يُختبر محلياً — طريقة التفحص المطلوبة في تعليمات.md.
 */
enum class BackStep { CLEARED_SELECTION, WENT_UP, EXITED }

object CloudBackPolicy {
    fun step(hasSelection: Boolean, isInsideFolder: Boolean): BackStep = when {
        hasSelection -> BackStep.CLEARED_SELECTION
        isInsideFolder -> BackStep.WENT_UP
        else -> BackStep.EXITED
    }
}
