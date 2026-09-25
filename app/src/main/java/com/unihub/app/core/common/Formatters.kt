package com.unihub.app.core.common

/** أدوات تنسيق للعرض فقط — بلا أي منطق أعمال */
object Formatters {

    /** تنسيق حجم ملف بايت إلى صيغة مقروءة */
    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes بايت"
        bytes < 1024 * 1024 -> "%.1f ك.ب".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f م.ب".format(bytes / (1024.0 * 1024))
        else -> "%.2f ج.ب".format(bytes / (1024.0 * 1024 * 1024))
    }

    /** تحية حسب وقت اليوم */
    fun greeting(): String {
        val hour = java.time.LocalTime.now().hour
        return when {
            hour in 5..11 -> "صباح الخير"
            hour in 12..16 -> "طاب يومك"
            else -> "مساء الخير"
        }
    }

    /**
     * عبارة عدد الملفات بصيغة عربية سليمة حسب قواعد العدد:
     * 1 = «ملف واحد»، 2 = «ملفان»، 3..10 = «4 ملفات»، 11+ = «15 ملفاً».
     * تُبنى عليها رسائل الحذف والنقل الجماعي بدل «ملفات» الثابتة التي كانت
     * تُركّب خاطئة نحوياً مع الأعداد الكبيرة.
     */
    fun fileCountLabel(count: Int): String = when {
        count <= 0 -> "لا ملفات"
        count == 1 -> "ملف واحد"
        count == 2 -> "ملفان"
        count in 3..10 -> "$count ملفات"
        else -> "$count ملفاً"
    }
}
