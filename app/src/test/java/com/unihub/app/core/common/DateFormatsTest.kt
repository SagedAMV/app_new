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
}
