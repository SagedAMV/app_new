package com.unihub.app.core.validation

import com.unihub.app.core.common.DateFormats
import java.util.Locale

/**
 * قواعد نقية (بلا Android ولا Room) تكتشف أخطاء المستخدم الشائعة قبل الحفظ.
 * كلها قابلة للاختبار مباشرة على JVM — راجع UserInputRulesTest.
 *
 * الهدف: أن يُكتشف الخطأ **والنموذج ما زال مفتوحاً** فلا يخسر المستخدم ما كتبه،
 * بدل أن يُغلق اللوح ثم تظهر رسالة خطأ خلفه.
 */
object UserInputRules {

    /**
     * هل التاريخ المختار في الماضي ويستحق تنبيه المستخدم؟
     * لا نزعج المستخدم عند تعديل سجل قديم لم يُغيّر تاريخه أصلاً.
     */
    fun isNewPastDate(pickedIso: String?, originalIso: String?, today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
        val picked = DateFormats.parseDateOrNull(pickedIso) ?: return false
        if (pickedIso == originalIso) return false
        return picked.isBefore(today)
    }

    /**
     * المستخدم كثيراً ما يكتب الامتداد عند إعادة تسمية ملف («تقرير.pdf») بينما
     * التطبيق يخزّن الاسم بدون امتداد ويضيفه بنفسه — فيصبح «تقرير.pdf.pdf».
     * نحذف الامتداد من نهاية الاسم إن طابق امتداد الملف نفسه (بدون حساسية حالة).
     */
    fun stripOwnExtension(name: String, extension: String): String {
        val ext = extension.trim().trimStart('.')
        if (ext.isBlank()) return name
        val suffix = ".$ext"
        var result = name.trim()
        // تكرار للحالات مثل «ملف.pdf.pdf» التي يلصقها المستخدم من مكان آخر
        while (result.length > suffix.length && result.endsWith(suffix, ignoreCase = true)) {
            result = result.dropLast(suffix.length).trimEnd()
        }
        return result
    }

    /**
     * تقسيم اسم ملف أعاد المستخدم كتابته (مع الامتداد) إلى (اسم، امتداد).
     * المشكلة: «محاضرة 1.5 مراجعة» أو «ملخص د. أحمد» كانت تُقرأ كامتداد «5 مراجعة»
     * أو «أحمد» فيتغير نوع الملف ولا يعود يُفتح. الآن لا يتغير الامتداد إلا إذا
     * كتب المستخدم امتداداً معروفاً فعلاً؛ وإلا يُعدّ النص كله اسماً ويبقى الامتداد.
     */
    fun splitRenamedFileName(input: String, currentExtension: String): Pair<String, String> {
        val cleaned = input.trim().trim('.')
        val current = currentExtension.trim().trimStart('.').lowercase(Locale.US)
        val dot = cleaned.lastIndexOf('.')
        if (dot <= 0) return cleaned to current
        val typedExt = cleaned.substring(dot + 1).trim().lowercase(Locale.US)
        val base = cleaned.substring(0, dot).trimEnd()
        return when {
            typedExt == current -> base to current
            typedExt in InputValidator.ALLOWED_EXTENSIONS -> base to typedExt
            else -> cleaned to current
        }
    }

    /** مفتاح مقارنة للأسماء: بلا حالة أحرف وبلا مسافات زائدة */
    fun nameKey(name: String): String =
        InputValidator.sanitizeName(name).lowercase(Locale.ROOT)

    /**
     * هل يوجد اسم مطابق بين الإخوة (نفس المجلد الأب)؟ [ignoreId] يستبعد العنصر
     * نفسه عند إعادة التسمية. مجلدان بنفس الاسم في نفس المكان يربكان المستخدم
     * ويتصادمان في مسارات المزامنة السحابية.
     */
    fun <T> hasDuplicateName(
        candidate: String,
        siblings: List<T>,
        nameOf: (T) -> String,
        idOf: (T) -> Long,
        ignoreId: Long? = null
    ): Boolean {
        val key = nameKey(candidate)
        return siblings.any { idOf(it) != ignoreId && nameKey(nameOf(it)) == key }
    }

    /** امتحان مكرر: نفس المادة ونفس النوع ونفس التاريخ (والوقت إن حُدد) */
    fun isDuplicateExam(
        subject: String, typeName: String, date: String, time: String,
        otherSubject: String, otherTypeName: String, otherDate: String, otherTime: String
    ): Boolean =
        nameKey(subject) == nameKey(otherSubject) &&
            typeName == otherTypeName &&
            date == otherDate &&
            (time.isBlank() || otherTime.isBlank() || time == otherTime)

    /**
     * فحص نموذج المحاضرة محلياً قبل إرساله: يعيد رسالة الخطأ الأولى أو null.
     * يمنع إغلاق اللوح عند نسيان وقت البداية أو اختيار نهاية قبل البداية.
     */
    fun lectureFormError(subject: String, timeFrom: String?, timeTo: String?): String? {
        if (subject.isBlank()) return "اسم المادة مطلوب"
        if (timeFrom.isNullOrBlank()) return "حدد وقت بداية المحاضرة"
        val from = DateFormats.parseTimeOrNull(timeFrom) ?: return "وقت البداية غير صالح"
        val to = DateFormats.parseTimeOrNull(timeTo)
        if (to != null && !to.isAfter(from)) return "وقت النهاية يجب أن يكون بعد وقت البداية"
        return null
    }

    /** رسالة عربية صحيحة لنتيجة استيراد جزئي: «استُورد 3 ملفات وتعذّر 2: السبب» */
    fun importSummary(ok: Int, failed: Int, lastReason: String?): String = when {
        failed == 0 -> "تم استيراد $ok ملف بنجاح"
        ok == 0 -> lastReason ?: "فشل الاستيراد"
        else -> "استُورد $ok ملف وتعذّر $failed" + (lastReason?.let { ": $it" } ?: "")
    }
}
