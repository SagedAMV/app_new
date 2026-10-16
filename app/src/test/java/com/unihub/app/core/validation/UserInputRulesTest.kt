package com.unihub.app.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UserInputRulesTest {

    private val today = LocalDate.of(2026, 10, 11)

    @Test
    fun pastDateWarnsOnlyWhenNewlyPicked() {
        assertTrue(UserInputRules.isNewPastDate("2026-10-10", null, today))
        assertFalse(UserInputRules.isNewPastDate("2026-10-11", null, today))
        assertFalse(UserInputRules.isNewPastDate("2026-12-01", null, today))
        // تعديل سجل قديم دون تغيير تاريخه لا يزعج المستخدم
        assertFalse(UserInputRules.isNewPastDate("2025-01-01", "2025-01-01", today))
        assertFalse(UserInputRules.isNewPastDate(null, null, today))
    }

    @Test
    fun stripsExtensionUserTypedByHand() {
        assertEquals("تقرير", UserInputRules.stripOwnExtension("تقرير.pdf", "pdf"))
        assertEquals("تقرير", UserInputRules.stripOwnExtension("تقرير.PDF.pdf", "pdf"))
        assertEquals("تقرير.docx", UserInputRules.stripOwnExtension("تقرير.docx", "pdf"))
        assertEquals("ملف", UserInputRules.stripOwnExtension("ملف", ""))
        // اسم هو الامتداد فقط لا يُفرَّغ
        assertEquals(".pdf", UserInputRules.stripOwnExtension(".pdf", "pdf"))
    }

    @Test
    fun cloudRenameKeepsExtensionWhenDotIsPartOfName() {
        assertEquals("ملخص د. أحمد" to "pdf", UserInputRules.splitRenamedFileName("ملخص د. أحمد", "pdf"))
        assertEquals("محاضرة 1.5 مراجعة" to "pdf", UserInputRules.splitRenamedFileName("محاضرة 1.5 مراجعة", "pdf"))
        assertEquals("محاضرة" to "pdf", UserInputRules.splitRenamedFileName("محاضرة", "pdf"))
        assertEquals("محاضرة" to "pdf", UserInputRules.splitRenamedFileName("محاضرة.PDF", "pdf"))
        // تغيير مقصود لامتداد معروف ما زال مسموحاً
        assertEquals("صورة" to "png", UserInputRules.splitRenamedFileName("صورة.png", "jpg"))
    }

    @Test
    fun duplicateNamesIgnoreCaseSpacesAndSelf() {
        data class F(val id: Long, val name: String)
        val siblings = listOf(F(1, "Math"), F(2, "فيزياء"))
        assertTrue(UserInputRules.hasDuplicateName("  math ", siblings, { it.name }, { it.id }))
        assertTrue(UserInputRules.hasDuplicateName("فيزياء", siblings, { it.name }, { it.id }))
        assertFalse(UserInputRules.hasDuplicateName("Math", siblings, { it.name }, { it.id }, ignoreId = 1))
        assertFalse(UserInputRules.hasDuplicateName("كيمياء", siblings, { it.name }, { it.id }))
    }

    @Test
    fun duplicateExamDetection() {
        assertTrue(UserInputRules.isDuplicateExam("Math", "FINAL", "2026-12-01", "", "math ", "FINAL", "2026-12-01", "09:00"))
        assertFalse(UserInputRules.isDuplicateExam("Math", "FINAL", "2026-12-01", "09:00", "Math", "FINAL", "2026-12-01", "13:00"))
        assertFalse(UserInputRules.isDuplicateExam("Math", "MIDTERM", "2026-12-01", "", "Math", "FINAL", "2026-12-01", ""))
    }

    @Test
    fun lectureFormCatchesMissingStartAndReversedRange() {
        assertEquals("اسم المادة مطلوب", UserInputRules.lectureFormError(" ", "08:00", null))
        assertEquals("حدد وقت بداية المحاضرة", UserInputRules.lectureFormError("Math", null, "10:00"))
        assertEquals(
            "وقت غير صالح",
            UserInputRules.lectureFormError("Math", "10:00", "08:00")
        )
        assertEquals(
            "وقت غير صالح",
            UserInputRules.lectureFormError("Math", "10:00", "10:00")
        )
        assertNull(UserInputRules.lectureFormError("Math", "08:00", "10:00"))
        assertNull(UserInputRules.lectureFormError("Math", "08:00", null))
    }

    @Test
    fun importSummaryStatesFailureCount() {
        assertEquals("تم استيراد 3 ملف بنجاح", UserInputRules.importSummary(3, 0, null))
        assertEquals("استُورد 2 ملف وتعذّر 1: السبب", UserInputRules.importSummary(2, 1, "السبب"))
        assertEquals("السبب", UserInputRules.importSummary(0, 2, "السبب"))
    }
}
