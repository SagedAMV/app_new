package com.unihub.app.feature.planner.notes

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات تدفق الكتابة في الملاحظات — تغطي طلب هذه الجلسة:
 * الكتابة من اليمين، بلا حقل عنوان فرعي يظهر أثناء الكتابة، وبلا زر «إضافة فقرة».
 *
 * الربط بالسيناريوهات (منهجية تفكير.md / الحلقة 4.5):
 * S-RTL   → T1        | S-BLANK → T2
 * S-KEEP  → T3        | S-DEL   → T4
 */
class NotesWritingFlowTest {

    // ========================================================================
    // T1 (S-RTL): نمط الكتابة الموحّد يبدأ من اليمين ويحترم المحتوى اللاتيني
    // Oracle: textAlign = Start مع ContentOrRtl، ويستمران بعد .copy() كما في الواجهة
    // ========================================================================
    @Test
    fun noteWritingStyle_startsFromRightAndSurvivesTypographyCopy() {
        // Arrange: نمط أساس بحجم خط مختلف (كما يحدث مع محرر الفقرة)
        val base = TextStyle(fontSize = 18.sp, lineHeight = 26.sp)

        // Act: النمط المشترك كما تُطبّقه الواجهة، ثم .copy() فوقه كما يفعل عنوان الفقرة
        val styled = base.noteWritingStyle()
        val styledThenCopied = base.noteWritingStyle().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

        // Assert
        assertEquals(TextAlign.Start, styled.textAlign)
        assertEquals(TextDirection.ContentOrRtl, styled.textDirection)
        assertEquals(18.sp, styled.fontSize)
        // copy() فوق النمط لا يجوز أن يفقد اتجاه الكتابة — وإلا رجعت الملاحظات لليسار
        assertEquals(TextDirection.ContentOrRtl, styledThenCopied.textDirection)
        assertEquals(TextAlign.Start, styledThenCopied.textAlign)
        assertTrue(styledThenCopied.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold)
    }

    // ========================================================================
    // T2 (S-BLANK): الملاحظة الفارغة = مساحة كتابة واحدة فارغة تمامًا
    // Oracle: كتلة واحدة، بلا عنوان، بلا نص — لا «عنوان فرعي» ولا «اكتب هنا بحرية»
    // ========================================================================
    @Test
    fun blankTemplate_isASingleEmptyWritingArea_withoutHeading() {
        // Act
        val doc = NoteTemplates.blank()

        // Assert: مستخدم الملاحظات يريد ورقة فارغة يكتب فيها وينزل بحرية
        assertEquals(1, doc.blocks.size)
        val section = doc.blocks.single() as NoteBlock.TextSection
        assertEquals("", section.heading)
        assertEquals("", section.body)
        assertEquals(TextSectionStyle.PARAGRAPH, section.style)
        assertFalse("ملاحظة فارغة لا تُعتبر محتوى", doc.hasMeaningfulContent())
    }

    // ========================================================================
    // T3 (S-KEEP): العنوان المحفوظ يبقى بعد تحرير النص فقط
    // يثبت أن إزالة «حقل العنوان» من الواجهة لا تُفقد عنوانًا موجودًا (قالب/ملاحظة قديمة)
    // ========================================================================
    @Test
    fun editingOnlyTheBody_preservesALegacyHeadingAndOtherBlocks() {
        // Arrange: ملاحظة قديمة فيها فقرة بعنوان + جدول
        val legacyHeading = "النقاط الأساسية"
        val legacyBody = "سطر أول"
        val original = NoteWorkspaceDocument(
            colorTag = NoteColorTag.STUDY,
            summary = "ملخص",
            blocks = listOf(
                NoteBlock.TextSection(id = "t1", heading = legacyHeading, body = legacyBody),
                NoteBlock.TableBlock(
                    id = "tbl",
                    caption = "جدول المحاضرة",
                    headers = listOf("العنصر", "الخصائص"),
                    rows = listOf(listOf("أ", "ب"))
                )
            )
        )

        // Act: الواجهة بعد التعديل لا تملك حقل عنوان، فتُحدّث النص فقط عبر copy(body = …)
        val editedSection = original.blocks.first() as NoteBlock.TextSection
        val bodyOnlyEdit = editedSection.copy(body = "سطر أول + سطر ثانٍ")
        val edited = NoteWorkspaceOperations.assembleEditedDocument(
            original = original,
            colorTag = original.colorTag,
            summary = original.summary,
            textSections = listOf(bodyOnlyEdit),
            ideaCards = emptyList(),
            drawingStrokes = emptyList()
        )
        val decoded = NoteWorkspaceCodec.decode(NoteWorkspaceCodec.encode(edited))
        val textBlock = decoded.blocks.filterIsInstance<NoteBlock.TextSection>().single()

        // Assert: العنوان محفوظ (يُعرض كنص ثابت) والنص معدّل والجدول لم يضيع
        assertEquals(legacyHeading, textBlock.heading)
        assertEquals("سطر أول + سطر ثانٍ", textBlock.body)
        assertEquals(2, decoded.blocks.size)
        assertTrue(decoded.blocks[1] is NoteBlock.TableBlock)
        // مع خلاصة فارغة، العنوان المحفوظ ما زال يغذّي اقتراح العنوان في قائمة الملاحظات
        assertEquals(legacyHeading, edited.copy(summary = "").suggestedFallbackTitle())
    }

    // ========================================================================
    // T4 (S-DEL): حذف فقرة من ملاحظة متعددة الفقرات (زر الحذف ما زال وحيدًا للكيان المتعدد)
    // Oracle: لا تكرار، لا فقد، والترتيب محفوظ — ثم الترميز يعيد نفس الحالة
    // ========================================================================
    @Test
    fun deletingMiddleParagraph_keepsOtherParagraphsInOrder() {
        // Arrange: ملاحظة فيها ثلاث فقرات
        val original = NoteWorkspaceDocument(
            blocks = listOf(
                NoteBlock.TextSection(id = "p1", body = "مقدمة"),
                NoteBlock.TextSection(id = "p2", body = "جهاز الحذف"),
                NoteBlock.TextSection(id = "p3", body = "خاتمة")
            )
        )

        // Act: المستخدم حذف الفقرة الوسطى من الواجهة
        val remaining = (original.blocks.filterIsInstance<NoteBlock.TextSection>())
            .filter { it.id != "p2" }
        val saved = NoteWorkspaceOperations.assembleEditedDocument(
            original = original,
            colorTag = original.colorTag,
            summary = original.summary,
            textSections = remaining,
            ideaCards = emptyList(),
            drawingStrokes = emptyList()
        )
        val decoded = NoteWorkspaceCodec.decode(NoteWorkspaceCodec.encode(saved))
        val bodies = decoded.blocks.filterIsInstance<NoteBlock.TextSection>().map { it.body }

        // Assert
        assertEquals(listOf("مقدمة", "خاتمة"), bodies)
        assertFalse("الفقرة المحذوفة يجب ألا تعود", bodies.contains("جهاز الحذف"))
        assertEquals(2, decoded.blocks.size)
    }
}
