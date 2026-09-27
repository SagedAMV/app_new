package com.unihub.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DateFormatsTest {

    @Test
    fun parsesValidIsoDate() {
        assertNotNull(DateFormats.parseDateOrNull("2026-09-14"))
        assertEquals(LocalDate.of(2026, 9, 14), DateFormats.parseDateOrNull("2026-09-14"))
    }

    @Test
    fun invalidDatesReturnNullInsteadOfThrowing() {
        assertNull(DateFormats.parseDateOrNull("2026-13-40"))
        assertNull(DateFormats.parseDateOrNull(""))
        assertNull(DateFormats.parseDateOrNull(null))
        assertNull(DateFormats.parseDateOrNull("غداً"))
    }

    @Test
    fun timeParsing() {
        assertNotNull(DateFormats.parseTimeOrNull("08:30"))
        assertNull(DateFormats.parseTimeOrNull("25:00"))
        assertNull(DateFormats.parseTimeOrNull("8:5"))
    }

    @Test
    fun daysUntilComputation() {
        val today = DateFormats.todayIso()
        assertEquals(0L, DateFormats.daysUntil(today))
        val tomorrow = LocalDate.now().plusDays(1).format(DateFormats.DATE)
        assertEquals(1L, DateFormats.daysUntil(tomorrow))
        val yesterday = LocalDate.now().minusDays(1).format(DateFormats.DATE)
        assertEquals(-1L, DateFormats.daysUntil(yesterday))
        assertNull(DateFormats.daysUntil(null))
    }

    @Test
    fun friendlyDueLabels() {
        val today = DateFormats.todayIso()
        assertEquals("اليوم", DateFormats.friendlyDueLabel(today))
        val tomorrow = LocalDate.now().plusDays(1).format(DateFormats.DATE)
        assertEquals("غداً", DateFormats.friendlyDueLabel(tomorrow))
        val late = LocalDate.now().minusDays(3).format(DateFormats.DATE)
        assertTrue(DateFormats.friendlyDueLabel(late)!!.startsWith("متأخر"))
    }

    @Test
    fun dateTimeComposition() {
        val dt = DateFormats.dateTimeOf("2026-09-14", "10:30")
        assertNotNull(dt)
        assertEquals(10, dt!!.hour)
        assertEquals(30, dt.minute)
        // بدون وقت يُفترض التاسعة صباحاً
        val morning = DateFormats.dateTimeOf("2026-09-14", null)
        assertEquals(9, morning!!.hour)
    }

    @Test
    fun time12FormattingCoversAllEdgeCases() {
        // منتصف الليل والدقائق المبكرة → 12 صباحاً
        assertEquals("12:05 ص", DateFormats.formatTime12("00:05"))
        // صباح عادي
        assertEquals("9:30 ص", DateFormats.formatTime12("09:30"))
        // آخر دقيقة قبل الظهر
        assertEquals("11:59 ص", DateFormats.formatTime12("11:59"))
        // الظهر تماماً يبقى 12 ولا يصبح صفراً
        assertEquals("12:00 م", DateFormats.formatTime12("12:00"))
        // بعد الظهر
        assertEquals("1:45 م", DateFormats.formatTime12("13:45"))
        // آخر دقيقة في اليوم
        assertEquals("11:59 م", DateFormats.formatTime12("23:59"))
    }

    @Test
    fun time12FormattingHandlesBlankAndInvalidInput() {
        assertEquals("—", DateFormats.formatTime12(null))
        assertEquals("—", DateFormats.formatTime12(""))
        assertEquals("—", DateFormats.formatTime12("25:00"))
        // تسمية بديلة للمواضع التي تستخدم رمز الحذف (خلية الشبكة مثلاً)
        assertEquals("…", DateFormats.formatTime12("", blankLabel = "…"))
    }

    @Test
    fun minutesOfDayAxisLabelsOmitZeroMinutes() {
        assertEquals("12 ص", DateFormats.formatMinutesOfDay12(0))
        assertEquals("7 ص", DateFormats.formatMinutesOfDay12(7 * 60))
        assertEquals("12 م", DateFormats.formatMinutesOfDay12(12 * 60))
        assertEquals("1:30 م", DateFormats.formatMinutesOfDay12(13 * 60 + 30))
        assertEquals("11:59 م", DateFormats.formatMinutesOfDay12(23 * 60 + 59))
    }
}
