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
}
