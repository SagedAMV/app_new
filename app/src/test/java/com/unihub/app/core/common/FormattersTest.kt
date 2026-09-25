package com.unihub.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun fileSizeFormatting() {
        assertEquals("512 بايت", Formatters.fileSize(512))
        assertEquals("1.0 ك.ب", Formatters.fileSize(1024))
        assertEquals("1.5 م.ب", Formatters.fileSize(1024L * 1024 + 512 * 1024))
    }

    @Test
    fun fileCountLabelFollowsArabicPluralRules() {
        assertEquals("لا ملفات", Formatters.fileCountLabel(0))
        assertEquals("ملف واحد", Formatters.fileCountLabel(1))
        assertEquals("ملفان", Formatters.fileCountLabel(2))
        assertEquals("3 ملفات", Formatters.fileCountLabel(3))
        assertEquals("10 ملفات", Formatters.fileCountLabel(10))
        assertEquals("11 ملفاً", Formatters.fileCountLabel(11))
        assertEquals("150 ملفاً", Formatters.fileCountLabel(150))
    }
}
