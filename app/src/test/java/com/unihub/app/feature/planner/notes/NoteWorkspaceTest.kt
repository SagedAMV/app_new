package com.unihub.app.feature.planner.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الوحدة المحلية لنظام الملاحظات والملخصات المتكامل (الحلقة 4.5).
 * تغطي السيناريوهات الواقعية S1..S10، الثوابت Inv-1..Inv-5، والعلاقات الميتامورفية MR-1..MR-3.
 */
class NoteWorkspaceTest {

    // ========================================================================
    // S1: إنشاء ملخص محاضرة متكامل (نص + جدول مقارنة + قائمة مراجعة)
    // ========================================================================
    @Test
    fun s1_lectureSummaryRoundTrip_preservesTextTableAndChecklist() {
        // Arrange
        val doc = NoteWorkspaceDocument(
            colorTag = NoteColorTag.STUDY,
            summary = "مقارنة شاملة بين المصفوفات والقوائم المترابطة",
            blocks = listOf(
                NoteBlock.TextSection(
                    id = "txt1",
                    heading = "النقاط الأساسية",
                    body = "المصفوفة ثابتة الحجم بينما القائمة ديناميكية",
                    style = TextSectionStyle.BULLETS
                ),
                NoteBlock.TableBlock(
                    id = "tbl1",
                    caption = "جدول التعقيد الزمني",
                    headers = listOf("العملية", "المصفوفة", "القائمة"),
                    rows = listOf(
                        listOf("الوصول العشوائي", "O(1)", "O(n)"),
                        listOf("الإدراج في البداية", "O(n)", "O(1)")
                    )
                ),
                NoteBlock.ChecklistBlock(
                    id = "chk1",
                    title = "مهام المراجعة",
                    items = listOf(
                        ChecklistItem(id = "i1", text = "مراجعة الكود", isChecked = true),
                        ChecklistItem(id = "i2", text = "حل التمارين", isChecked = false)
                    )
                )
            )
        )

        // Act
        val encoded = NoteWorkspaceCodec.encode(doc)
        val decoded = NoteWorkspaceCodec.decode(encoded)
        val stats = NoteWorkspaceCodec.summarize(encoded)

        // Assert (Inv-1 + S1 Oracle)
        assertTrue(encoded.startsWith(NoteWorkspaceCodec.PREFIX))
        assertEquals(NoteColorTag.STUDY, decoded.colorTag)
        assertEquals("مقارنة شاملة بين المصفوفات والقوائم المترابطة", decoded.summary)
        assertEquals(3, decoded.blocks.size)
        assertEquals(1, stats.textSectionCount)
        assertEquals(1, stats.tableCount)
        assertEquals(2, stats.totalChecklistItems)
        assertEquals(1, stats.checkedChecklistItems)

        val decodedTable = decoded.blocks[1] as NoteBlock.TableBlock
        assertEquals(listOf("العملية", "المصفوفة", "القائمة"), decodedTable.headers)
        assertEquals("O(1)", decodedTable.rows[0][1])
    }

    // ========================================================================
    // S2: لوحة بطاقات ترتيب الأفكار وإعادة ترتيبها والفلترة
    // ========================================================================
    @Test
    fun s2_ideaBoardCardsReorderingAndFiltering_preservesOrderAndMatchesFilter() {
        // Arrange
        val cards = listOf(
            IdeaCardItem(id = "c1", title = "فكرة أولى", details = "تفصيل 1", tag = "فكرة", colorHex = "#4E7D6E"),
            IdeaCardItem(id = "c2", title = "فكرة ثانية", details = "تفصيل 2", tag = "خطة", colorHex = "#5B7FA6"),
            IdeaCardItem(id = "c3", title = "فكرة ثالثة", details = "تفصيل 3", tag = "خلاصة", colorHex = "#C79A4B")
        )

        // Act: تحريك البطاقة الثالثة (index 2) خطوتين للأعلى لتصبح في المركز الأول
        val movedOnce = NoteWorkspaceOperations.moveIdeaCard(cards, 2, -1)
        val movedTwice = NoteWorkspaceOperations.moveIdeaCard(movedOnce, 1, -1)

        val doc = NoteWorkspaceDocument(
            colorTag = NoteColorTag.IDEA,
            blocks = listOf(NoteBlock.IdeaBoard(boardTitle = "مشروع التخرج", cards = movedTwice))
        )
        val encoded = NoteWorkspaceCodec.encode(doc)
        val decoded = NoteWorkspaceCodec.decode(encoded)
        val board = decoded.blocks.first() as NoteBlock.IdeaBoard

        // Assert
        assertEquals(listOf("c3", "c1", "c2"), board.cards.map { it.id })
        assertTrue(
            NoteWorkspaceCodec.matchesFilterAndQuery(
                title = "خطة المشروع",
                rawContent = encoded,
                isPinned = true,
                filter = NoteFilter.IDEA_CARDS,
                query = "فكرة ثالثة"
            )
        )
        assertTrue(
            NoteWorkspaceCodec.matchesFilterAndQuery(
                title = "خطة المشروع",
                rawContent = encoded,
                isPinned = true,
                filter = NoteFilter.PINNED,
                query = ""
            )
        )
    }

    // ========================================================================
    // S3: الرسم الحر، التظليل، الممحاة، والتراجع (Undo & Eraser)
    // ========================================================================
    @Test
    fun s3_drawingCanvas_supportsHighlighterUndoAndEraser() {
        // Arrange
        val stroke1 = DrawingStroke(
            id = "s1",
            colorHex = "#3E6B5E",
            widthDp = 4f,
            isHighlighter = false,
            points = listOf(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.2f, 0.2f))
        )
        val stroke2ToErase = DrawingStroke(
            id = "s2",
            colorHex = "#C25E52",
            widthDp = 5f,
            isHighlighter = false,
            points = listOf(NormalizedPoint(0.5f, 0.5f), NormalizedPoint(0.52f, 0.52f))
        )
        val highlighterStroke = DrawingStroke(
            id = "s3",
            colorHex = "#C79A4B",
            widthDp = 14f,
            isHighlighter = true,
            points = listOf(NormalizedPoint(0.8f, 0.8f), NormalizedPoint(0.9f, 0.8f))
        )
        val accidentalStroke = DrawingStroke(
            id = "s4",
            colorHex = "#232B27",
            widthDp = 3f,
            isHighlighter = false,
            points = listOf(NormalizedPoint(0.3f, 0.7f), NormalizedPoint(0.4f, 0.7f))
        )

        // Act: تراجع عن المسار الرابع، ثم مسح المسار الثاني بالممحاة قرب (0.5f, 0.5f)
        val afterUndo = NoteWorkspaceOperations.undoLastStroke(
            listOf(stroke1, stroke2ToErase, highlighterStroke, accidentalStroke)
        )
        val afterErase = NoteWorkspaceOperations.eraseStrokesNear(afterUndo, x = 0.505f, y = 0.505f)

        val doc = NoteWorkspaceDocument(
            blocks = listOf(
                NoteBlock.DrawingBoard(
                    title = "مخطط الدارة",
                    background = CanvasBackground.GRID,
                    strokes = afterErase
                )
            )
        )
        val encoded = NoteWorkspaceCodec.encode(doc)
        val decoded = NoteWorkspaceCodec.decode(encoded)
        val stats = NoteWorkspaceCodec.summarize(encoded)

        // Assert
        val drawing = decoded.blocks.first() as NoteBlock.DrawingBoard
        assertEquals(2, drawing.strokes.size)
        assertEquals(listOf("s1", "s3"), drawing.strokes.map { it.id })
        assertTrue(drawing.strokes[1].isHighlighter)
        assertNotNull(stats.previewDrawing)
        assertEquals(2, stats.drawingStrokeCount)
    }

    // ========================================================================
    // S4: التوافق العكسي مع الملاحظات النصية القديمة (Legacy Plain Text Migration)
    // ========================================================================
    @Test
    fun s4_legacyPlainTextNote_decodesCleanlyAndUpgradesWithoutDataLoss() {
        // Arrange (Inv-2)
        val legacyContent = "محاضرة الفيزياء النووية\nالقانون الأول والانشطار\nمراجعة ص 45"

        // Act
        val decoded = NoteWorkspaceCodec.decode(legacyContent)
        val stats = NoteWorkspaceCodec.summarize(legacyContent)

        // Assert
        assertTrue(decoded.hasMeaningfulContent())
        assertEquals(1, decoded.blocks.size)
        val textBlock = decoded.blocks.first() as NoteBlock.TextSection
        assertEquals(legacyContent, textBlock.body)
        assertTrue(stats.plainPreview.contains("القانون الأول"))
        assertTrue(
            NoteWorkspaceCodec.matchesFilterAndQuery(
                title = "فيزياء",
                rawContent = legacyContent,
                isPinned = false,
                filter = NoteFilter.ALL,
                query = "الانشطار"
            )
        )
    }

    // ========================================================================
    // S5: ملاحظة تحتوي على رسم فقط بدون عنوان وبدون نص
    // ========================================================================
    @Test
    fun s5_drawingOnlyNoteWithoutTitle_isRecognizedAsMeaningfulAndGeneratesFallbackTitle() {
        // Arrange
        val doc = NoteWorkspaceDocument(
            summary = "",
            blocks = listOf(
                NoteBlock.DrawingBoard(
                    title = "خريطة ذهنية للخوارزميات",
                    strokes = listOf(
                        DrawingStroke(
                            points = listOf(NormalizedPoint(0.2f, 0.3f), NormalizedPoint(0.6f, 0.7f))
                        )
                    )
                )
            )
        )

        // Act & Assert
        assertTrue(doc.hasMeaningfulContent())
        assertEquals("خريطة ذهنية للخوارزميات", doc.suggestedFallbackTitle())
    }

    // ========================================================================
    // S6: عمليات الحدود القصوى والدنيا على الجداول وثبات الأبعاد (Inv-3)
    // ========================================================================
    @Test
    fun s6_tableBoundaryOperations_enforcesRectangularBoundsAndMinMaxLimits() {
        // Arrange: جدول غير منتظم (3 أعمدة لكن الصفوف بأطوال 1 و5)
        val raggedTable = NoteBlock.TableBlock(
            caption = "جدول اختباري",
            headers = listOf("أ", "ب", "ج"),
            rows = listOf(
                listOf("خلية وحيدة"),
                listOf("1", "2", "3", "4", "5")
            )
        )

        // Act
        var table = NoteWorkspaceOperations.normalizeTable(raggedTable)
        // Assert rectangular invariant (Inv-3)
        assertEquals(3, table.headers.size)
        assertTrue(table.rows.all { it.size == 3 })

        // إضافة أعمدة عشر مرات (الحد الأقصى 6)
        repeat(10) { table = NoteWorkspaceOperations.addTableColumn(table) }
        assertEquals(NoteWorkspaceOperations.MAX_TABLE_COLUMNS, table.headers.size)
        assertTrue(table.rows.all { it.size == NoteWorkspaceOperations.MAX_TABLE_COLUMNS })

        // حذف أعمدة عشر مرات (الحد الأدنى 1)
        repeat(10) { table = NoteWorkspaceOperations.removeLastTableColumn(table) }
        assertEquals(NoteWorkspaceOperations.MIN_TABLE_COLUMNS, table.headers.size)
        assertTrue(table.rows.all { it.size == NoteWorkspaceOperations.MIN_TABLE_COLUMNS })

        // حذف صفوف عشر مرات (الحد الأدنى 1)
        repeat(10) { table = NoteWorkspaceOperations.removeLastTableRow(table) }
        assertEquals(NoteWorkspaceOperations.MIN_TABLE_ROWS, table.rows.size)
    }

    // ========================================================================
    // S7: رفض الملاحظة الفارغة تماماً (نصوص بيضاء ولوحة رسم فارغة)
    // ========================================================================
    @Test
    fun s7_completelyBlankDocument_hasMeaningfulContentReturnsFalse() {
        // Arrange
        val blankDoc = NoteWorkspaceDocument(
            summary = "   ",
            blocks = listOf(
                NoteBlock.TextSection(heading = "  ", body = "\n\t "),
                NoteBlock.DrawingBoard(title = "رسم توضيحي", strokes = emptyList()),
                NoteBlock.IdeaBoard(
                    boardTitle = "ترتيب الأفكار",
                    cards = listOf(IdeaCardItem(title = " ", details = " "))
                ),
                NoteBlock.TableBlock(
                    caption = " ",
                    headers = listOf("البند", "التفاصيل"),
                    rows = listOf(listOf(" ", ""), listOf("", " "))
                ),
                NoteBlock.ChecklistBlock(
                    title = "نقاط المراجعة",
                    items = listOf(ChecklistItem(text = "   ", isChecked = true))
                )
            )
        )

        // Act & Assert
        assertFalse(blankDoc.hasMeaningfulContent())
    }

    // ========================================================================
    // S8: عزل البحث عن مفاتيح JSON الداخلية (Inv-5)
    // ========================================================================
    @Test
    fun s8_searchFilter_ignoresInternalJsonKeysAndMatchesHumanTextOnly() {
        // Arrange
        val doc = NoteWorkspaceDocument(
            colorTag = NoteColorTag.SUMMARY,
            summary = "ملخص شبكات الحاسوب",
            blocks = listOf(
                NoteBlock.TableBlock(
                    caption = "طبقات الشبكة",
                    headers = listOf("الطبقة", "البروتوكول"),
                    rows = listOf(listOf("النقل", "TCP / UDP"))
                ),
                NoteBlock.DrawingBoard(
                    title = "مخطط التوجيه",
                    strokes = listOf(
                        DrawingStroke(points = listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.3f, 0.4f)))
                    )
                )
            )
        )
        val encoded = NoteWorkspaceCodec.encode(doc)

        // Act & Assert: المفاتيح التقنية لا يجوز أن تطابق البحث أبداً (Inv-5)
        assertFalse(
            NoteWorkspaceCodec.matchesFilterAndQuery(
                title = "ملاحظة الشبكات",
                rawContent = encoded,
                isPinned = false,
                filter = NoteFilter.ALL,
                query = "__UNIHUB_NOTE_V2__"
            )
        )
        assertFalse(
            NoteWorkspaceCodec.matchesFilterAndQuery(
                title = "ملاحظة الشبكات",
                rawContent = encoded,
                isPinned = false,
                filter = NoteFilter.ALL,
                query = "strokes"
            )
        )
        assertFalse(
            NoteWorkspaceCodec.matchesFilterAndQuery(
                title = "ملاحظة الشبكات",
                rawContent = encoded,
                isPinned = false,
                filter = NoteFilter.ALL,
                query = "isHighlighter"
            )
        )

        // بينما النص الفعلي داخل الجدول يطابق البحث بنجاح
        assertTrue(
            NoteWorkspaceCodec.matchesFilterAndQuery(
                title = "ملاحظة الشبكات",
                rawContent = encoded,
                isPinned = false,
                filter = NoteFilter.TABLES,
                query = "TCP"
            )
        )
    }

    // ========================================================================
    // S9: الصمود أمام JSON التالف وإحداثيات الرسم الشاذة NaN/Infinity/Out-of-bounds
    // ========================================================================
    @Test
    fun s9_corruptedJsonPayload_recoversGracefullyWithoutException() {
        // Arrange
        val corrupted = NoteWorkspaceCodec.PREFIX + "{\"version\":2,\"blocks\":[{\"type\":\"TABLE\",\"rows\":[[1,2"

        // Act
        val recovered = NoteWorkspaceCodec.decode(corrupted)

        // Assert
        assertNotNull(recovered)
        assertTrue(recovered.blocks.isNotEmpty())
    }

    @Test
    fun s9_extremeAndNaNCoordinates_areClampedToUnitSquare() {
        // Arrange (Inv-4)
        val rawPoints = listOf(
            NormalizedPoint(-50f, 120f),
            NormalizedPoint(Float.NaN, 0.5f),
            NormalizedPoint(0.25f, Float.POSITIVE_INFINITY),
            NormalizedPoint(0.75f, -0.3f)
        )

        // Act
        val simplified = NoteWorkspaceOperations.simplifyPoints(rawPoints)
        val clampedDirect = NormalizedPoint(Float.NaN, -99f).clamped()

        // Assert
        assertEquals(0f, clampedDirect.x, 0.0001f)
        assertEquals(0f, clampedDirect.y, 0.0001f)
        assertEquals(2, simplified.size)
        assertTrue(simplified.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    // ========================================================================
    // S10: ضغط آلاف النقاط المتكررة وتنظيف محارف التحكم غير المرئية
    // ========================================================================
    @Test
    fun s10_massiveDuplicatePointsAndControlChars_areSimplifiedAndSanitized() {
        // Arrange: 2000 نقطة في نفس الموضع + نصوص بمحارف تحكم
        val duplicatePoints = List(2_000) { NormalizedPoint(0.5f, 0.5f) }
        val doc = NoteWorkspaceDocument(
            summary = "ملخص\u0000 نظيف\u0007",
            blocks = listOf(
                NoteBlock.DrawingBoard(
                    title = "رسم\u001F",
                    strokes = listOf(DrawingStroke(points = duplicatePoints))
                )
            )
        )

        // Act
        val encoded = NoteWorkspaceCodec.encode(doc)
        val decoded = NoteWorkspaceCodec.decode(encoded)
        val drawing = decoded.blocks.first() as NoteBlock.DrawingBoard

        // Assert
        assertFalse(encoded.contains("\u0000"))
        assertFalse(encoded.contains("\u0007"))
        assertEquals("ملخص نظيف", decoded.summary)
        assertTrue(drawing.strokes.first().points.size <= 2)
    }

    // ========================================================================
    // اختبارات العلاقات الميتامورفية (Metamorphic Relations MR-1..MR-3)
    // ========================================================================
    @Test
    fun mr1_addingIdeaCard_incrementsIdeaCountWhileKeepingOtherStatsConstant() {
        val baseDoc = NoteTemplates.lectureSummary().copy(
            blocks = NoteTemplates.lectureSummary().blocks + NoteBlock.IdeaBoard(
                cards = listOf(IdeaCardItem(title = "فكرة 1", details = "تفصيل"))
            )
        )
        val expandedDoc = baseDoc.copy(
            blocks = baseDoc.blocks.map { block ->
                if (block is NoteBlock.IdeaBoard) {
                    block.copy(cards = block.cards + IdeaCardItem(title = "فكرة 2", details = "تفصيل"))
                } else block
            }
        )

        val stats1 = NoteWorkspaceCodec.summarize(NoteWorkspaceCodec.encode(baseDoc))
        val stats2 = NoteWorkspaceCodec.summarize(NoteWorkspaceCodec.encode(expandedDoc))

        assertEquals(stats1.ideaCardCount + 1, stats2.ideaCardCount)
        assertEquals(stats1.tableCount, stats2.tableCount)
        assertEquals(stats1.totalChecklistItems, stats2.totalChecklistItems)
    }

    @Test
    fun mr2_movingBlockUpThenDown_restoresExactOriginalBlockOrder() {
        val initialBlocks = listOf(
            NoteBlock.TextSection(id = "b1", body = "1"),
            NoteBlock.TableBlock(id = "b2"),
            NoteBlock.DrawingBoard(id = "b3")
        )

        val movedUp = NoteWorkspaceOperations.moveBlock(initialBlocks, index = 1, direction = -1)
        val movedBack = NoteWorkspaceOperations.moveBlock(movedUp, index = 0, direction = 1)

        assertEquals(listOf("b1", "b2", "b3"), movedBack.map { it.id })
    }

    // ========================================================================
    // اختبارات علاج ثقوب التغطية المكتشفة في المحاكمة (Coverage Holes 1..3)
    // ========================================================================
    @Test
    fun hole1_clearingTableHeaderWhileTyping_preservesEmptyString() {
        val table = NoteBlock.TableBlock(headers = listOf("البند", "التفاصيل"))
        val updated = NoteWorkspaceOperations.updateTableHeader(table, colIndex = 0, value = "")
        val normalized = NoteWorkspaceOperations.normalizeTable(updated)

        assertEquals("", normalized.headers[0])
        assertEquals("التفاصيل", normalized.headers[1])
    }

    @Test
    fun hole2_eraserRemovesLongSegmentWhenTouchedAtMidpoint() {
        val longLine = DrawingStroke(
            id = "long1",
            points = listOf(
                NormalizedPoint(0.1f, 0.5f),
                NormalizedPoint(0.9f, 0.5f)
            )
        )
        // الممحاة تلمس المنتصف (0.5, 0.5) البعيد عن الرأسين (0.1, 0.5) و(0.9, 0.5)
        val remaining = NoteWorkspaceOperations.eraseStrokesNear(
            strokes = listOf(longLine),
            x = 0.5f,
            y = 0.5f,
            radius = 0.045f
        )

        assertTrue(remaining.isEmpty())
    }

    @Test
    fun hole3_customTableHeadersOnly_isRecognizedAsMeaningfulContent() {
        val doc = NoteWorkspaceDocument(
            summary = "",
            blocks = listOf(
                NoteBlock.TableBlock(
                    caption = "",
                    headers = listOf("المادة", "الدرجة"),
                    rows = listOf(listOf("", ""), listOf("", ""))
                )
            )
        )

        assertTrue(doc.hasMeaningfulContent())
    }
}
