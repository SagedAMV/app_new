package com.unihub.app.core.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * أدوات التاريخ والوقت الموحّدة للتطبيق كله.
 * كل التواريخ تُخزَّن بصيغة ISO (yyyy-MM-dd) وكل الأوقات بصيغة (HH:mm)
 * حتى لا تتوزع منطق التحليل في عدة أماكن كما في التطبيق المرجعي.
 */
object DateFormats {

    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** تاريخ اليوم بصيغة ISO */
    fun todayIso(): String = LocalDate.now().format(DATE)

    /** تحليل تاريخ ISO بأمان — يعيد null عند فشل التحليل بدل رمي استثناء */
    fun parseDateOrNull(value: String?): LocalDate? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it, DATE) }.getOrNull()
        }

    /** تحليل وقت ISO بأمان */
    fun parseTimeOrNull(value: String?): LocalTime? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalTime.parse(it, TIME) }.getOrNull()
        }

    /** تركيب تاريخ+وقت لامتحان أو مهمة (الوقت الافتراضي 9 صباحاً) */
    fun dateTimeOf(dateIso: String, timeIso: String?): LocalDateTime? {
        val date = parseDateOrNull(dateIso) ?: return null
        val time = parseTimeOrNull(timeIso) ?: LocalTime.of(9, 0)
        return date.atTime(time)
    }

    /** عدد الأيام من اليوم حتى التاريخ المعطى (سالب إن كان في الماضي) */
    fun daysUntil(dateIso: String?): Long? =
        parseDateOrNull(dateIso)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }

    /** تنسيق عربي ودود للتاريخ: اليوم / غداً / بعد 3 أيام / التاريخ نفسه */
    fun friendlyDueLabel(dateIso: String?): String? {
        val days = daysUntil(dateIso) ?: return null
        return when {
            days < -1 -> "متأخر ${-days} يوم"
            days == -1L -> "متأخر يوماً"
            days == 0L -> "اليوم"
            days == 1L -> "غداً"
            days == 2L -> "بعد يومين"
            days <= 10L -> "بعد $days أيام"
            else -> format(dateIso)
        }
    }

    /** تنسيق التاريخ للعرض (مثال: 2026-09-14) */
    fun format(dateIso: String?): String {
        val date = parseDateOrNull(dateIso) ?: return "—"
        return date.format(DATE)
    }
}

/** تحويلات أيام الأسبوع بين java.time وأيام التطبيق */
object Weekdays {
    /**
     * java.time: Monday=1 .. Sunday=7 — تحويل آمن لا يرمي استثناء أبداً:
     * أي قيمة خارج النطاق (مستحيلة عملياً لكن دفاعياً) تعود للأحد بدل
     * `NoSuchElementException` من `first{}`.
     */
    fun fromJava(javaDay: Int): com.unihub.app.data.local.entity.Weekday =
        com.unihub.app.data.local.entity.Weekday.entries
            .firstOrNull { it.javaDayValue == javaDay }
            ?: com.unihub.app.data.local.entity.Weekday.SUNDAY

    fun today(): com.unihub.app.data.local.entity.Weekday =
        fromJava(LocalDate.now().dayOfWeek.value)
}
