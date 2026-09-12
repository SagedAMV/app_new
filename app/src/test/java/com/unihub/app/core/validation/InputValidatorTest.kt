package com.unihub.app.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidatorTest {

    @Test
    fun emptyNameIsRejected() {
        val result = InputValidator.validateName("   ")
        assertTrue(result.isFailure)
        assertEquals(InputValidator.InputError.Empty.message, result.exceptionOrNull()?.message)
    }

    @Test
    fun arabicNameIsAcceptedAndTrimmed() {
        val result = InputValidator.validateName("  رياضيات ٢  ")
        assertTrue(result.isSuccess)
        assertEquals("رياضيات ٢", result.getOrThrow())
    }

    @Test
    fun pathSymbolsAreRejected() {
        assertTrue(InputValidator.validateName("ملف/مهم").isFailure)
        assertTrue(InputValidator.validateName("ملف*هام").isFailure)
        assertTrue(InputValidator.validateName("a:b").isFailure)
        assertTrue(InputValidator.validateName("x\\y").isFailure)
    }

    @Test
    fun tooLongNameIsRejected() {
        val long = "أ".repeat(InputValidator.MAX_NAME_LENGTH + 1)
        assertTrue(InputValidator.validateName(long).isFailure)
    }

    @Test
    fun extensionsValidation() {
        assertTrue(InputValidator.validateExtension("PDF").isSuccess)
        assertTrue(InputValidator.validateExtension(".docx").isSuccess)
        assertEquals("pdf", InputValidator.validateExtension("PDF").getOrThrow())
        assertTrue(InputValidator.validateExtension("exe2").isFailure)
        assertTrue(InputValidator.validateExtension("").isFailure)
    }

    @Test
    fun isoDateValidation() {
        assertTrue(InputValidator.validateDate("2026-09-14").isSuccess)
        assertTrue(InputValidator.validateDate("2026-13-01").isFailure)
        assertTrue(InputValidator.validateDate("").isFailure)
        assertTrue(InputValidator.validateDate("14-09-2026").isFailure)
    }

    @Test
    fun timeRangeValidation() {
        assertTrue(InputValidator.validateTimeRange("08:00", "10:00").isSuccess)
        assertTrue(InputValidator.validateTimeRange("10:00", "08:00").isFailure)
        assertTrue(InputValidator.validateTimeRange("10:00", "10:00").isFailure)
        // طرف واحد اختياري — لا فحص
        assertTrue(InputValidator.validateTimeRange("10:00", null).isSuccess)
        assertTrue(InputValidator.validateTimeRange(null, null).isSuccess)
    }

    @Test
    fun sanitizeNameRemovesControlCharsAndCollapsesSpaces() {
        val dirty = "محاضرة" + '\u0007' + "   في   " + '\u001F' + "الفيزياء"
        val clean = InputValidator.sanitizeName(dirty)
        assertEquals("محاضرة في الفيزياء", clean)
    }

    @Test
    fun sanitizeTextKeepsNewlines() {
        val text = "سطر أول" + "\n" + "سطر ثانٍ"
        val clean = InputValidator.sanitizeText(text)
        assertTrue(clean.contains("\n"))
        assertEquals("سطر أول" + "\n" + "سطر ثانٍ", clean)
    }

    @Test
    fun titleValidation() {
        assertTrue(InputValidator.validateTitle("").isFailure)
        assertTrue(InputValidator.validateTitle("عنوان صالح").isSuccess)
        val long = "م".repeat(InputValidator.MAX_TITLE_LENGTH + 1)
        assertTrue(InputValidator.validateTitle(long).isFailure)
    }
}
