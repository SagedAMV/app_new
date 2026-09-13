package com.unihub.app.core.common

import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * اختبارات منطق "المحاضرة التالية" وكشف التعارض — منطق نقي بلا أندرويد.
 * 2026-09-14 يوم الاثنين.
 */
class NextLectureResolverTest {

    private fun lecture(
        day: Weekday,
        from: String,
        to: String = "",
        subject: String = "مادة"
    ) = LectureEntity(subject = subject, day = day, timeFrom = from, timeTo = to)

    @Test
    fun `جدول فارغ يعيد null`() {
        assertNull(
            NextLectureResolver.resolve(emptyList(), LocalDateTime.of(2026, 9, 14, 8, 0))
        )
    }

    @Test
    fun `أقرب محاضرة اليوم تفوز على الأبعد`() {
        val lectures = listOf(
            lecture(Weekday.MONDAY, "12:00", "13:00", "بعيدة"),
            lecture(Weekday.MONDAY, "09:30", "10:30", "قريبة")
        )
        val next = NextLectureResolver.resolve(lectures, LocalDateTime.of(2026, 9, 14, 8, 0))!!
        assertEquals("قريبة", next.lecture.subject)
        assertEquals(0, next.dayOffset)
        assertEquals(90L, next.minutesUntilStart)
    }

    @Test
    fun `محاضرة جارية الآن تُكتشف حتى لو بدأت قبل ساعات`() {
        val lectures = listOf(lecture(Weekday.MONDAY, "08:00", "09:30", "جارية"))
        val next = NextLectureResolver.resolve(lectures, LocalDateTime.of(2026, 9, 14, 8, 30))!!
        assertEquals(NextLecture.Status.ONGOING, next.status)
        assertEquals("جارية", next.lecture.subject)
    }

    @Test
    fun `غياب وقت النهاية يفترض مدة ساعة للجارية`() {
        val lectures = listOf(lecture(Weekday.MONDAY, "08:00", "", "جارية"))
        val inside = NextLectureResolver.resolve(lectures, LocalDateTime.of(2026, 9, 14, 8, 45))!!
        assertEquals(NextLecture.Status.ONGOING, inside.status)
        // بعد انتهاء الساعة الافتراضية تصبح "فائتة" ولا تُحسب
        val after = NextLectureResolver.resolve(lectures, LocalDateTime.of(2026, 9, 14, 9, 15))
        assertNull(after)
    }

    @Test
    fun `بعد انتهاء محاضرات اليوم ينتقل ليوم الغد`() {
        val lectures = listOf(
            lecture(Weekday.MONDAY, "08:00", "09:00", "انتهت"),
            lecture(Weekday.TUESDAY, "10:00", "", "غد")
        )
        val next = NextLectureResolver.resolve(lectures, LocalDateTime.of(2026, 9, 14, 12, 0))!!
        assertEquals("غد", next.lecture.subject)
        assertEquals(1, next.dayOffset)
    }

    @Test
    fun `يتخطى الأيام الفارغة حتى يجد محاضرة`() {
        val lectures = listOf(lecture(Weekday.SATURDAY, "09:00", "", "سبت"))
        // الأحد 2026-09-20 — أقرب محاضرة السبت بعد 6 أيام
        val next = NextLectureResolver.resolve(lectures, LocalDateTime.of(2026, 9, 20, 12, 0))!!
        assertEquals(6, next.dayOffset)
        assertEquals("سبت", next.lecture.subject)
    }

    @Test
    fun `توقيت غير صالح في محاضرة لا يكسر البحث`() {
        val lectures = listOf(
            LectureEntity(subject = "معطلة", day = Weekday.MONDAY, timeFrom = "xx:yy"),
            lecture(Weekday.MONDAY, "10:00", "", "سليمة")
        )
        val next = NextLectureResolver.resolve(lectures, LocalDateTime.of(2026, 9, 14, 8, 0))!!
        assertEquals("سليمة", next.lecture.subject)
    }

    @Test
    fun `تنسيق العد التنازلي`() {
        val ongoing = NextLecture(lecture(Weekday.MONDAY, "08:00"), 0, NextLecture.Status.ONGOING, 0)
        assertEquals("جارية الآن", NextLectureResolver.formatCountdown(ongoing))

        val soon = NextLecture(lecture(Weekday.MONDAY, "08:00"), 0, NextLecture.Status.UPCOMING, 25)
        assertEquals("بعد 25 دقيقة", NextLectureResolver.formatCountdown(soon))

        val hours = NextLecture(lecture(Weekday.MONDAY, "08:00"), 0, NextLecture.Status.UPCOMING, 150)
        assertEquals("بعد 2 س و 30 د", NextLectureResolver.formatCountdown(hours))

        val tomorrow = NextLecture(lecture(Weekday.TUESDAY, "08:00"), 1, NextLecture.Status.UPCOMING, 1000)
        assertEquals("غداً", NextLectureResolver.formatCountdown(tomorrow))
    }

    @Test
    fun `كشف التعارض الزمني بين محاضرتين`() {
        assertTrue(NextLectureResolver.timeRangesOverlap("08:00", "09:00", "08:30", "09:30"))
        assertTrue(NextLectureResolver.timeRangesOverlap("08:00", "", "08:30", ""))
        assertFalse(NextLectureResolver.timeRangesOverlap("08:00", "09:00", "09:00", "10:00"))
        assertFalse(NextLectureResolver.timeRangesOverlap("10:00", "11:00", "08:00", "09:00"))
    }
}
