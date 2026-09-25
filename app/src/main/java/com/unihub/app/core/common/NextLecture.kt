package com.unihub.app.core.common

import com.unihub.app.data.local.entity.LectureEntity
import java.time.LocalDateTime

/**
 * نتيجة حساب "المحاضرة التالية" من الجدول الأسبوعي.
 *
 * [dayOffset] عدد الأيام من اليوم (0 = اليوم، 1 = غداً … 6).
 * [minutesUntilStart] الدقائق المتبقية حتى البداية (0 عندما تكون جارية الآن).
 */
data class NextLecture(
    val lecture: LectureEntity,
    val dayOffset: Int,
    val status: Status,
    val minutesUntilStart: Long
) {
    enum class Status { ONGOING, UPCOMING }
}

/**
 * منطق نقي قابل للاختبار: يقرأ الجدول الأسبوعي ويجد أقرب محاضرة قادمة
 * (أو الجارية الآن) بالنسبة للحظة [now].
 *
 * القواعد:
 * 1. محاضرة جارية الآن اليوم (بين وقت البداية والنهاية) تفوز فوراً.
 * 2. وإلا أقرب محاضرة اليوم التي لم تبدأ بعد.
 * 3. وإلا earliest محاضرة في أقرب يوم تالٍ فيه محاضرات (حتى 6 أيام للأمام).
 *
 * عند غياب وقت النهاية تُفترض مدة افتراضية 60 دقيقة (نفس افتراض التطبيق في بقية المواضع).
 */
object NextLectureResolver {

    private const val DEFAULT_DURATION_MINUTES = 60

    fun resolve(lectures: List<LectureEntity>, now: LocalDateTime): NextLecture? {
        if (lectures.isEmpty()) return null

        val today = Weekdays.fromJava(now.dayOfWeek.value)
        val nowMinutes = now.hour * 60 + now.minute

        // 1) جارية الآن؟
        val ongoing = lectures
            .filter { it.day == today }
            .firstOrNull { lecture ->
                val from = DateFormats.parseTimeOrNull(lecture.timeFrom) ?: return@firstOrNull false
                val fromMin = from.hour * 60 + from.minute
                val toMin = DateFormats.parseTimeOrNull(lecture.timeTo)
                    ?.let { it.hour * 60 + it.minute }
                    ?: (fromMin + DEFAULT_DURATION_MINUTES)
                nowMinutes in fromMin until toMin
            }
        if (ongoing != null) return NextLecture(ongoing, 0, NextLecture.Status.ONGOING, 0)

        // 2) أقرب محاضرة اليوم لم تبدأ بعد
        val upcomingToday = lectures
            .filter { it.day == today }
            .mapNotNull { lecture ->
                DateFormats.parseTimeOrNull(lecture.timeFrom)?.let { time ->
                    lecture to (time.hour * 60 + time.minute)
                }
            }
            .filter { (_, fromMin) -> fromMin > nowMinutes }
            .minByOrNull { (_, fromMin) -> fromMin }
        if (upcomingToday != null) {
            val (lecture, fromMin) = upcomingToday
            return NextLecture(
                lecture = lecture,
                dayOffset = 0,
                status = NextLecture.Status.UPCOMING,
                minutesUntilStart = (fromMin - nowMinutes).toLong()
            )
        }

        // 3) أقرب يوم تالٍ فيه محاضرات
        for (offset in 1..6) {
            val javaDay = ((now.dayOfWeek.value - 1 + offset) % 7) + 1
            val day = Weekdays.fromJava(javaDay)
            val earliest = lectures
                .filter { it.day == day }
                .mapNotNull { lecture ->
                    DateFormats.parseTimeOrNull(lecture.timeFrom)?.let { time ->
                        lecture to (time.hour * 60 + time.minute)
                    }
                }
                .minByOrNull { (_, fromMin) -> fromMin }
            if (earliest != null) {
                val (lecture, fromMin) = earliest
                return NextLecture(
                    lecture = lecture,
                    dayOffset = offset,
                    status = NextLecture.Status.UPCOMING,
                    minutesUntilStart = offset * 24L * 60 - nowMinutes + fromMin
                )
            }
        }
        return null
    }

    /** هل يوجد تعارض زمني بين نطاقين في نفس اليوم؟ (نهاية فارغة = بداية + 60 د) */
    fun timeRangesOverlap(
        fromA: String, toA: String,
        fromB: String, toB: String
    ): Boolean {
        val a1 = DateFormats.parseTimeOrNull(fromA) ?: return false
        val b1 = DateFormats.parseTimeOrNull(fromB) ?: return false
        val a1m = a1.hour * 60 + a1.minute
        val b1m = b1.hour * 60 + b1.minute
        val a2m = DateFormats.parseTimeOrNull(toA)?.let { it.hour * 60 + it.minute }
            ?: (a1m + DEFAULT_DURATION_MINUTES)
        val b2m = DateFormats.parseTimeOrNull(toB)?.let { it.hour * 60 + it.minute }
            ?: (b1m + DEFAULT_DURATION_MINUTES)
        return a1m < b2m && b1m < a2m
    }

    /** تنسيق العد التنازلي بالعربية */
    fun formatCountdown(next: NextLecture): String = when {
        next.status == NextLecture.Status.ONGOING -> "جارية الآن"
        next.dayOffset > 0 -> dayLabel(next.dayOffset)
        next.minutesUntilStart <= 0 -> "تبدأ الآن"
        next.minutesUntilStart < 60 -> "بعد ${next.minutesUntilStart} دقيقة"
        else -> {
            val h = next.minutesUntilStart / 60
            val m = next.minutesUntilStart % 60
            if (m == 0L) "بعد $h ساعة" else "بعد $h س و $m د"
        }
    }

    /** اليوم بالعربية النسبية: غداً / بعد يومين / اسم اليوم */
    fun dayLabel(dayOffset: Int): String = when (dayOffset) {
        0 -> "اليوم"
        1 -> "غداً"
        2 -> "بعد يومين"
        else -> "بعد $dayOffset أيام"
    }
}
