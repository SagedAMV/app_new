package com.unihub.app.feature.planner.notes

import com.unihub.app.core.validation.InputValidator
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * نماذج ومنطق مساحة عمل الملاحظات والملخصات المتكاملة (Data -> Logic/State -> UI).
 *
 * تدعم الملاحظة الواحدة:
 * 1) ملخصاً سريعاً (Executive Summary) للمراجعة السريعة.
 * 2) كتل كتابة منسقة (فقرة، عنوان فرعي، إضاءة/تنبيه، نقاط مرتبة).
 * 3) لوحة بطاقات ترتيب الأفكار (Idea Cards Board) مع وسوم وألوان وإعادة ترتيب.
 * 4) جداول مرتبة (Structured Tables) متعددة الصفوف والأعمدة مع ثبات الأبعاد.
 * 5) لوحة رسم حر (Freehand Drawing Canvas) مع قلم حبر، قلم تظليل، ممحاة، تراجع، وخلفية ورقية.
 * 6) قوائم مهام/مراجعة (Checklists) مع حساب نسبة الإنجاز.
 *
 * جميع البيانات تُرمّز داخل حقل `NoteEntity.content` ببادئة `__UNIHUB_NOTE_V2__:` مع
 * توافق عكسي كامل مع الملاحظات النصية القديمة ومع النسخ الاحتياطي.
 */

/** مرشحات عرض الملاحظات في تبويب المخطط */
enum class NoteFilter(val label: String) {
    ALL("الكل"),
    PINNED("مثبتة"),
    IDEA_CARDS("بطاقات أفكار"),
    TABLES("جداول"),
    DRAWINGS("رسومات"),
    CHECKLISTS("مهام")
}

/** ألوان ووسوم الملاحظة المتناغمة مع لوحة ألوان التطبيق في Color.kt */
enum class NoteColorTag(val label: String, val colorHex: String) {
    DEFAULT("عام", "#4E7D6E"),
    SUMMARY("ملخص", "#5B7FA6"),
    IDEA("أفكار", "#C79A4B"),
    IMPORTANT("مهم", "#C25E52"),
    STUDY("مذاكرة", "#4C8B6E"),
    REVIEW("مراجعة", "#7D5A7A");

    companion object {
        fun fromNameOrDefault(name: String?): NoteColorTag =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** أنماط الفقرات النصية داخل الملاحظة */
enum class TextSectionStyle(val label: String) {
    PARAGRAPH("فقرة"),
    HEADING("عنوان فرعي"),
    CALLOUT("إضاءة مهمة"),
    BULLETS("نقاط ملخصة");

    companion object {
        fun fromNameOrDefault(name: String?): TextSectionStyle =
            entries.firstOrNull { it.name == name } ?: PARAGRAPH
    }
}

/** أنماط خلفية لوحة الرسم الحر */
enum class CanvasBackground(val label: String) {
    PLAIN("سادة"),
    RULED("مسطّر"),
    GRID("شبكة");

    companion object {
        fun fromNameOrDefault(name: String?): CanvasBackground =
            entries.firstOrNull { it.name == name } ?: GRID
    }
}

/** بطاقة فكرة واحدة داخل لوحة ترتيب الأفكار */
data class IdeaCardItem(
    val id: String = newItemId(),
    val title: String = "",
    val details: String = "",
    val tag: String = "فكرة",
    val colorHex: String = "#4E7D6E"
)

/** عنصر واحد داخل قائمة مراجعة/مهام الملاحظة */
data class ChecklistItem(
    val id: String = newItemId(),
    val text: String = "",
    val isChecked: Boolean = false
)

/** نقطة رسم مطبّعة ضمن المجال [0f, 1f] لتعمل بثبات على مختلف أحجام الشاشات */
data class NormalizedPoint(
    val x: Float,
    val y: Float
) {
    fun clamped(): NormalizedPoint {
        val safeX = if (x.isFinite()) x.coerceIn(0f, 1f) else 0f
        val safeY = if (y.isFinite()) y.coerceIn(0f, 1f) else 0f
        return NormalizedPoint(
            x = (safeX * 1000f).roundToInt() / 1000f,
            y = (safeY * 1000f).roundToInt() / 1000f
        )
    }
}

/** مسار رسم واحد (خط قلم أو تظليل) */
data class DrawingStroke(
    val id: String = newItemId(),
    val colorHex: String = "#3E6B5E",
    val widthDp: Float = 4f,
    val isHighlighter: Boolean = false,
    val points: List<NormalizedPoint> = emptyList()
)

/** الكتل المعيارية التي تتألف منها الملاحظة */
sealed interface NoteBlock {
    val id: String

    /** كتلة كتابة نصية منسقة */
    data class TextSection(
        override val id: String = newItemId(),
        val heading: String = "",
        val body: String = "",
        val style: TextSectionStyle = TextSectionStyle.PARAGRAPH
    ) : NoteBlock

    /** لوحة بطاقات لترتيب الأفكار والعصف الذهني */
    data class IdeaBoard(
        override val id: String = newItemId(),
        val boardTitle: String = "ترتيب الأفكار",
        val cards: List<IdeaCardItem> = emptyList()
    ) : NoteBlock

    /** جدول بيانات/مقارنات مرتب */
    data class TableBlock(
        override val id: String = newItemId(),
        val caption: String = "",
        val headers: List<String> = listOf("البند", "التفاصيل"),
        val rows: List<List<String>> = listOf(
            listOf("", ""),
            listOf("", "")
        )
    ) : NoteBlock

    /** لوحة رسم حر وتخطيط يدوي على الملاحظة */
    data class DrawingBoard(
        override val id: String = newItemId(),
        val title: String = "رسم توضيحي",
        val background: CanvasBackground = CanvasBackground.GRID,
        val strokes: List<DrawingStroke> = emptyList()
    ) : NoteBlock

    /** قائمة مهام أو نقاط فحص للمراجعة */
    data class ChecklistBlock(
        override val id: String = newItemId(),
        val title: String = "نقاط المراجعة",
        val items: List<ChecklistItem> = emptyList()
    ) : NoteBlock
}

/** الوثيقة الكاملة للملاحظة */
data class NoteWorkspaceDocument(
    val version: Int = NoteWorkspaceCodec.CURRENT_VERSION,
    val colorTag: NoteColorTag = NoteColorTag.DEFAULT,
    val summary: String = "",
    val blocks: List<NoteBlock> = listOf(NoteBlock.TextSection())
) {
    /** التحقق مما إذا كانت الوثيقة تحتوي على أي محتوى فعلي (نص أو بطاقة أو جدول أو رسم أو مهمة) */
    fun hasMeaningfulContent(): Boolean {
        if (summary.isNotBlank()) return true
        return blocks.any { block ->
            when (block) {
                is NoteBlock.TextSection -> block.heading.isNotBlank() || block.body.isNotBlank()
                is NoteBlock.IdeaBoard -> block.cards.any { it.title.isNotBlank() || it.details.isNotBlank() }
                is NoteBlock.TableBlock -> {
                    val defaultHeaders = listOf("البند", "التفاصيل")
                    val hasCustomHeaders = block.headers != defaultHeaders &&
                        block.headers.any { it.isNotBlank() }
                    block.caption.isNotBlank() ||
                        hasCustomHeaders ||
                        block.rows.any { row -> row.any { it.isNotBlank() } }
                }
                is NoteBlock.DrawingBoard -> block.strokes.any { it.points.isNotEmpty() }
                is NoteBlock.ChecklistBlock -> block.items.any { it.text.isNotBlank() }
            }
        }
    }

    /** اقتراح عنوان افتراضي ذكي عند ترك العنوان فارغاً مع وجود محتوى */
    fun suggestedFallbackTitle(): String {
        if (summary.isNotBlank()) return summary.lineSequence().first().trim().take(60)
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.TextSection -> {
                    if (block.heading.isNotBlank()) return block.heading.trim().take(60)
                    if (block.body.isNotBlank()) return block.body.lineSequence().first().trim().take(60)
                }
                is NoteBlock.IdeaBoard -> {
                    val firstCard = block.cards.firstOrNull { it.title.isNotBlank() }
                    if (firstCard != null) return firstCard.title.trim().take(60)
                    if (block.boardTitle.isNotBlank()) return block.boardTitle.trim().take(60)
                }
                is NoteBlock.TableBlock -> {
                    if (block.caption.isNotBlank()) return block.caption.trim().take(60)
                    return "جدول ملاحظات"
                }
                is NoteBlock.DrawingBoard -> {
                    if (block.strokes.isNotEmpty()) {
                        return block.title.ifBlank { "ملاحظة مرسومة" }.trim().take(60)
                    }
                }
                is NoteBlock.ChecklistBlock -> {
                    if (block.title.isNotBlank()) return block.title.trim().take(60)
                }
            }
        }
        return "ملاحظة بلا عنوان"
    }
}

/** ملخص إحصائي ومعاينة سريعة لبطاقة الملاحظة في القائمة الرئيسية */
data class NotePreviewStats(
    val colorTag: NoteColorTag,
    val plainPreview: String,
    val textSectionCount: Int,
    val ideaCardCount: Int,
    val tableCount: Int,
    val drawingStrokeCount: Int,
    val totalChecklistItems: Int,
    val checkedChecklistItems: Int,
    val previewDrawing: NoteBlock.DrawingBoard?,
    val previewIdeaTitles: List<String>,
    val previewTableHeaders: List<String>
)

/** عمليات نقية (Pure Functions) لتحرير الكتل والبطاقات والجداول والرسومات */
object NoteWorkspaceOperations {

    const val MIN_TABLE_COLUMNS = 1
    const val MAX_TABLE_COLUMNS = 6
    const val MIN_TABLE_ROWS = 1
    const val MAX_TABLE_ROWS = 30
    const val MAX_POINTS_PER_STROKE = 320

    /** تحريك كتلة للأعلى أو الأسفل داخل الوثيقة */
    fun moveBlock(blocks: List<NoteBlock>, index: Int, direction: Int): List<NoteBlock> {
        val target = index + direction
        if (index !in blocks.indices || target !in blocks.indices) return blocks
        val mutable = blocks.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        return mutable
    }

    /** تحريك بطاقة فكرة داخل لوحة ترتيب الأفكار */
    fun moveIdeaCard(cards: List<IdeaCardItem>, index: Int, direction: Int): List<IdeaCardItem> {
        val target = index + direction
        if (index !in cards.indices || target !in cards.indices) return cards
        val mutable = cards.toMutableList()
        val card = mutable.removeAt(index)
        mutable.add(target, card)
        return mutable
    }

    /** تطبيع الجدول لضمان أن عدد خلايا كل صف يساوي عدد الأعمدة دائماً (Invariant Inv-3) */
    fun normalizeTable(table: NoteBlock.TableBlock): NoteBlock.TableBlock {
        val colCount = table.headers.size.coerceIn(MIN_TABLE_COLUMNS, MAX_TABLE_COLUMNS)
        val normalizedHeaders = (0 until colCount).map { col ->
            val rawHeader = table.headers.getOrNull(col)
            if (rawHeader != null) {
                InputValidator.sanitizeName(rawHeader)
            } else {
                "عمود ${col + 1}"
            }
        }
        val rowCount = table.rows.size.coerceIn(MIN_TABLE_ROWS, MAX_TABLE_ROWS)
        val normalizedRows = (0 until rowCount).map { rowIdx ->
            val existingRow = table.rows.getOrNull(rowIdx).orEmpty()
            (0 until colCount).map { colIdx ->
                InputValidator.sanitizeText(existingRow.getOrNull(colIdx) ?: "")
            }
        }
        return table.copy(
            caption = InputValidator.sanitizeName(table.caption),
            headers = normalizedHeaders,
            rows = normalizedRows
        )
    }

    fun addTableRow(table: NoteBlock.TableBlock): NoteBlock.TableBlock {
        val normalized = normalizeTable(table)
        if (normalized.rows.size >= MAX_TABLE_ROWS) return normalized
        val emptyRow = List(normalized.headers.size) { "" }
        return normalized.copy(rows = normalized.rows + listOf(emptyRow))
    }

    fun removeLastTableRow(table: NoteBlock.TableBlock): NoteBlock.TableBlock {
        val normalized = normalizeTable(table)
        if (normalized.rows.size <= MIN_TABLE_ROWS) return normalized
        return normalized.copy(rows = normalized.rows.dropLast(1))
    }

    fun addTableColumn(table: NoteBlock.TableBlock): NoteBlock.TableBlock {
        val normalized = normalizeTable(table)
        if (normalized.headers.size >= MAX_TABLE_COLUMNS) return normalized
        val newColIndex = normalized.headers.size + 1
        return normalized.copy(
            headers = normalized.headers + "عمود $newColIndex",
            rows = normalized.rows.map { it + "" }
        )
    }

    fun removeLastTableColumn(table: NoteBlock.TableBlock): NoteBlock.TableBlock {
        val normalized = normalizeTable(table)
        if (normalized.headers.size <= MIN_TABLE_COLUMNS) return normalized
        return normalized.copy(
            headers = normalized.headers.dropLast(1),
            rows = normalized.rows.map { it.dropLast(1) }
        )
    }

    fun updateTableHeader(table: NoteBlock.TableBlock, colIndex: Int, value: String): NoteBlock.TableBlock {
        val normalized = normalizeTable(table)
        if (colIndex !in normalized.headers.indices) return normalized
        val updated = normalized.headers.toMutableList()
        updated[colIndex] = value
        return normalized.copy(headers = updated)
    }

    fun updateTableCell(
        table: NoteBlock.TableBlock,
        rowIndex: Int,
        colIndex: Int,
        value: String
    ): NoteBlock.TableBlock {
        val normalized = normalizeTable(table)
        if (rowIndex !in normalized.rows.indices || colIndex !in normalized.headers.indices) {
            return normalized
        }
        val updatedRows = normalized.rows.mapIndexed { rIdx, row ->
            if (rIdx != rowIndex) row
            else row.mapIndexed { cIdx, cell -> if (cIdx == colIndex) value else cell }
        }
        return normalized.copy(rows = updatedRows)
    }

    /**
     * تبسيط نقاط مسار الرسم لإزالة النقاط المتكررة والمتقاربة جداً وتقييد الإحداثيات في [0f, 1f]
     * (Invariant Inv-4 + حماية حجم التخزين في S10).
     */
    fun simplifyPoints(
        rawPoints: List<NormalizedPoint>,
        minDistance: Float = 0.005f
    ): List<NormalizedPoint> {
        val finitePoints = rawPoints
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map { it.clamped() }
        if (finitePoints.size <= 2) return finitePoints

        val filtered = ArrayList<NormalizedPoint>(finitePoints.size)
        var lastKept = finitePoints.first()
        filtered.add(lastKept)

        for (i in 1 until finitePoints.lastIndex) {
            val current = finitePoints[i]
            val dist = hypot((current.x - lastKept.x).toDouble(), (current.y - lastKept.y).toDouble())
            if (dist >= minDistance) {
                filtered.add(current)
                lastKept = current
            }
        }
        val lastPoint = finitePoints.last()
        if (filtered.last() != lastPoint) {
            filtered.add(lastPoint)
        }

        if (filtered.size <= MAX_POINTS_PER_STROKE) return filtered
        val step = (filtered.size - 1).toFloat() / (MAX_POINTS_PER_STROKE - 1).toFloat()
        return (0 until MAX_POINTS_PER_STROKE).map { idx ->
            val sourceIdx = (idx * step).roundToInt().coerceIn(0, filtered.lastIndex)
            filtered[sourceIdx]
        }
    }

    /** التراجع عن آخر مسار رسم */
    fun undoLastStroke(strokes: List<DrawingStroke>): List<DrawingStroke> =
        if (strokes.isEmpty()) emptyList() else strokes.dropLast(1)

    /** مسح المسارات القريبة من موضع الممحاة (يفحص المسافة إلى القطع المستقيمة وليس الرؤوس فقط) */
    fun eraseStrokesNear(
        strokes: List<DrawingStroke>,
        x: Float,
        y: Float,
        radius: Float = 0.045f
    ): List<DrawingStroke> {
        val target = NormalizedPoint(x, y).clamped()
        return strokes.filterNot { stroke ->
            when {
                stroke.points.isEmpty() -> true
                stroke.points.size == 1 -> {
                    val pt = stroke.points.first()
                    hypot((pt.x - target.x).toDouble(), (pt.y - target.y).toDouble()) <= radius
                }
                else -> {
                    stroke.points.zipWithNext().any { (a, b) ->
                        distanceToSegment(target, a, b) <= radius
                    }
                }
            }
        }
    }

    private fun distanceToSegment(
        p: NormalizedPoint,
        a: NormalizedPoint,
        b: NormalizedPoint
    ): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-8f) {
            return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble())
        }
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot((p.x - projX).toDouble(), (p.y - projY).toDouble())
    }
}

/** قوالب جاهزة لإنشاء ملاحظات احترافية بضغطة واحدة */
object NoteTemplates {

    fun blank(): NoteWorkspaceDocument = NoteWorkspaceDocument(
        colorTag = NoteColorTag.DEFAULT,
        summary = "",
        blocks = listOf(
            NoteBlock.TextSection(
                heading = "",
                body = "",
                style = TextSectionStyle.PARAGRAPH
            )
        )
    )

    fun lectureSummary(): NoteWorkspaceDocument = NoteWorkspaceDocument(
        colorTag = NoteColorTag.SUMMARY,
        summary = "",
        blocks = listOf(
            NoteBlock.TextSection(
                heading = "النقاط الأساسية",
                body = "",
                style = TextSectionStyle.BULLETS
            ),
            NoteBlock.TableBlock(
                caption = "جدول المقارنة أو المصطلحات",
                headers = listOf("المفهوم", "الشرح المختصر"),
                rows = listOf(
                    listOf("", ""),
                    listOf("", "")
                )
            ),
            NoteBlock.ChecklistBlock(
                title = "أسئلة ومراجعة المحاضرة",
                items = listOf(
                    ChecklistItem(text = "مراجعة التعريفات الرئيسية"),
                    ChecklistItem(text = "حل أمثلة التطبيق")
                )
            )
        )
    )

    fun ideaBoard(): NoteWorkspaceDocument = NoteWorkspaceDocument(
        colorTag = NoteColorTag.IDEA,
        summary = "",
        blocks = listOf(
            NoteBlock.IdeaBoard(
                boardTitle = "لوحة ترتيب الأفكار",
                cards = listOf(
                    IdeaCardItem(
                        title = "الفكرة المحورية",
                        details = "",
                        tag = "فكرة",
                        colorHex = "#4E7D6E"
                    ),
                    IdeaCardItem(
                        title = "الخطوة التطبيقية",
                        details = "",
                        tag = "خطة",
                        colorHex = "#5B7FA6"
                    )
                )
            ),
            NoteBlock.TextSection(
                heading = "الخلاصة والاستنتاج",
                body = "",
                style = TextSectionStyle.CALLOUT
            )
        )
    )

    fun structuredTable(): NoteWorkspaceDocument = NoteWorkspaceDocument(
        colorTag = NoteColorTag.STUDY,
        summary = "",
        blocks = listOf(
            NoteBlock.TableBlock(
                caption = "جدول منظم",
                headers = listOf("العنصر", "الخصائص", "ملاحظات"),
                rows = listOf(
                    listOf("", "", ""),
                    listOf("", "", ""),
                    listOf("", "", "")
                )
            ),
            NoteBlock.TextSection(
                heading = "تعليقات إضافية",
                body = "",
                style = TextSectionStyle.PARAGRAPH
            )
        )
    )

    fun sketchNote(): NoteWorkspaceDocument = NoteWorkspaceDocument(
        colorTag = NoteColorTag.REVIEW,
        summary = "",
        blocks = listOf(
            NoteBlock.DrawingBoard(
                title = "مخطط توضيحي",
                background = CanvasBackground.GRID,
                strokes = emptyList()
            ),
            NoteBlock.TextSection(
                heading = "شرح المخطط",
                body = "",
                style = TextSectionStyle.PARAGRAPH
            )
        )
    )
}

/**
 * مرمّز ومحلل وثيقة الملاحظة المهيكلة (NoteWorkspaceCodec).
 * يحافظ على التوافق العكسي مع النصوص العادية القديمة، ويحصّن التطبيق ضد البيانات التالفة.
 */
object NoteWorkspaceCodec {

    const val PREFIX = "__UNIHUB_NOTE_V2__:"
    const val CURRENT_VERSION = 2

    private const val BLOCK_TEXT = "TEXT"
    private const val BLOCK_IDEA_BOARD = "IDEA_BOARD"
    private const val BLOCK_TABLE = "TABLE"
    private const val BLOCK_DRAWING = "DRAWING"
    private const val BLOCK_CHECKLIST = "CHECKLIST"

    /** تحويل الوثيقة إلى نص مهيكل جاهز للتخزين في NoteEntity.content */
    fun encode(document: NoteWorkspaceDocument): String {
        val normalizedBlocks = if (document.blocks.isEmpty()) {
            listOf(NoteBlock.TextSection())
        } else {
            document.blocks
        }

        val root = JSONObject()
        root.put("version", CURRENT_VERSION)
        root.put("colorTag", document.colorTag.name)
        root.put("summary", InputValidator.sanitizeText(document.summary))

        val blocksArray = JSONArray()
        normalizedBlocks.forEach { block ->
            blocksArray.put(encodeBlock(block))
        }
        root.put("blocks", blocksArray)
        return PREFIX + root.toString()
    }

    /** فك ترميز محتوى NoteEntity.content مع توافق عكسي تام مع الملاحظات النصية القديمة */
    fun decode(rawContent: String): NoteWorkspaceDocument {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) {
            return NoteTemplates.blank()
        }
        if (!trimmed.startsWith(PREFIX)) {
            return NoteWorkspaceDocument(
                version = CURRENT_VERSION,
                colorTag = NoteColorTag.DEFAULT,
                summary = "",
                blocks = listOf(
                    NoteBlock.TextSection(
                        heading = "",
                        body = InputValidator.sanitizeText(rawContent),
                        style = TextSectionStyle.PARAGRAPH
                    )
                )
            )
        }

        val jsonPart = trimmed.removePrefix(PREFIX)
        return runCatching {
            val root = JSONObject(jsonPart)
            val colorTag = NoteColorTag.fromNameOrDefault(root.optString("colorTag"))
            val summary = InputValidator.sanitizeText(root.optString("summary", ""))
            val blocksArray = root.optJSONArray("blocks") ?: JSONArray()
            val blocks = buildList {
                for (i in 0 until blocksArray.length()) {
                    val obj = blocksArray.optJSONObject(i) ?: continue
                    decodeBlock(obj)?.let { add(it) }
                }
            }.ifEmpty { listOf(NoteBlock.TextSection()) }

            NoteWorkspaceDocument(
                version = root.optInt("version", CURRENT_VERSION),
                colorTag = colorTag,
                summary = summary,
                blocks = blocks
            )
        }.getOrElse {
            // في حال تلف JSON، ننقذ النص القابل للقراءة بدل رمي استثناء أو فقدان البيانات
            val rescued = jsonPart
                .replace(Regex("[{}\\[\\]\"]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            NoteWorkspaceDocument(
                version = CURRENT_VERSION,
                colorTag = NoteColorTag.DEFAULT,
                summary = "",
                blocks = listOf(
                    NoteBlock.TextSection(
                        heading = "محتوى مستعاد",
                        body = InputValidator.sanitizeText(rescued),
                        style = TextSectionStyle.PARAGRAPH
                    )
                )
            )
        }
    }

    /** استخلاص جميع النصوص البشرية القابلة للبحث فقط (دون مفاتيح JSON أو إحداثيات الرسم) */
    fun extractSearchableText(rawContent: String): String {
        val doc = decode(rawContent)
        val parts = ArrayList<String>()
        if (doc.summary.isNotBlank()) parts.add(doc.summary)
        parts.add(doc.colorTag.label)

        doc.blocks.forEach { block ->
            when (block) {
                is NoteBlock.TextSection -> {
                    if (block.heading.isNotBlank()) parts.add(block.heading)
                    if (block.body.isNotBlank()) parts.add(block.body)
                }
                is NoteBlock.IdeaBoard -> {
                    if (block.boardTitle.isNotBlank()) parts.add(block.boardTitle)
                    block.cards.forEach { card ->
                        if (card.title.isNotBlank()) parts.add(card.title)
                        if (card.details.isNotBlank()) parts.add(card.details)
                        if (card.tag.isNotBlank()) parts.add(card.tag)
                    }
                }
                is NoteBlock.TableBlock -> {
                    if (block.caption.isNotBlank()) parts.add(block.caption)
                    block.headers.forEach { if (it.isNotBlank()) parts.add(it) }
                    block.rows.forEach { row ->
                        row.forEach { cell -> if (cell.isNotBlank()) parts.add(cell) }
                    }
                }
                is NoteBlock.DrawingBoard -> {
                    if (block.strokes.isNotEmpty() && block.title.isNotBlank()) {
                        parts.add(block.title)
                    }
                }
                is NoteBlock.ChecklistBlock -> {
                    if (block.title.isNotBlank()) parts.add(block.title)
                    block.items.forEach { item ->
                        if (item.text.isNotBlank()) parts.add(item.text)
                    }
                }
            }
        }
        return parts.joinToString("\n")
    }

    /** التحقق مما إذا كانت الملاحظة تطابق نص البحث ومرشح النوع */
    fun matchesFilterAndQuery(
        title: String,
        rawContent: String,
        isPinned: Boolean,
        filter: NoteFilter,
        query: String
    ): Boolean {
        val doc = decode(rawContent)
        val filterMatches = when (filter) {
            NoteFilter.ALL -> true
            NoteFilter.PINNED -> isPinned
            NoteFilter.IDEA_CARDS -> doc.blocks.any {
                it is NoteBlock.IdeaBoard && it.cards.any { c -> c.title.isNotBlank() || c.details.isNotBlank() }
            }
            NoteFilter.TABLES -> doc.blocks.any { it is NoteBlock.TableBlock }
            NoteFilter.DRAWINGS -> doc.blocks.any {
                it is NoteBlock.DrawingBoard && it.strokes.isNotEmpty()
            }
            NoteFilter.CHECKLISTS -> doc.blocks.any {
                it is NoteBlock.ChecklistBlock && it.items.any { item -> item.text.isNotBlank() }
            }
        }
        if (!filterMatches) return false

        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return true

        if (title.contains(trimmedQuery, ignoreCase = true)) return true
        val searchable = extractSearchableText(rawContent)
        return searchable.contains(trimmedQuery, ignoreCase = true)
    }

    /** استخلاص إحصائيات وملخص البطاقة للعرض الهادئ في القائمة الرئيسية */
    fun summarize(rawContent: String): NotePreviewStats {
        val doc = decode(rawContent)
        var textCount = 0
        var ideaCount = 0
        var tableCount = 0
        var strokeCount = 0
        var totalTasks = 0
        var checkedTasks = 0
        var firstDrawing: NoteBlock.DrawingBoard? = null
        val ideaTitles = ArrayList<String>()
        val tableHeaders = ArrayList<String>()
        val previewLines = ArrayList<String>()

        if (doc.summary.isNotBlank()) {
            previewLines.add(doc.summary.trim())
        }

        doc.blocks.forEach { block ->
            when (block) {
                is NoteBlock.TextSection -> {
                    if (block.heading.isNotBlank() || block.body.isNotBlank()) {
                        textCount++
                        val textLine = buildString {
                            if (block.heading.isNotBlank()) append(block.heading.trim())
                            if (block.heading.isNotBlank() && block.body.isNotBlank()) append(": ")
                            if (block.body.isNotBlank()) append(block.body.trim())
                        }
                        if (textLine.isNotBlank()) previewLines.add(textLine)
                    }
                }
                is NoteBlock.IdeaBoard -> {
                    val activeCards = block.cards.filter { it.title.isNotBlank() || it.details.isNotBlank() }
                    ideaCount += activeCards.size
                    activeCards.forEach { card ->
                        val titleOrDetail = card.title.ifBlank { card.details }.trim()
                        if (titleOrDetail.isNotBlank() && ideaTitles.size < 3) {
                            ideaTitles.add(titleOrDetail)
                        }
                    }
                    if (activeCards.isNotEmpty() && previewLines.size < 3) {
                        previewLines.add(
                            activeCards.joinToString(" • ") { it.title.ifBlank { it.details }.trim() }
                        )
                    }
                }
                is NoteBlock.TableBlock -> {
                    tableCount++
                    if (tableHeaders.isEmpty()) {
                        tableHeaders.addAll(block.headers.filter { it.isNotBlank() }.take(4))
                    }
                    if (block.caption.isNotBlank() && previewLines.size < 3) {
                        previewLines.add("جدول: ${block.caption.trim()}")
                    }
                }
                is NoteBlock.DrawingBoard -> {
                    if (block.strokes.isNotEmpty()) {
                        strokeCount += block.strokes.size
                        if (firstDrawing == null) firstDrawing = block
                    }
                }
                is NoteBlock.ChecklistBlock -> {
                    val validItems = block.items.filter { it.text.isNotBlank() }
                    totalTasks += validItems.size
                    checkedTasks += validItems.count { it.isChecked }
                    if (validItems.isNotEmpty() && previewLines.size < 3) {
                        previewLines.add(validItems.first().text.trim())
                    }
                }
            }
        }

        return NotePreviewStats(
            colorTag = doc.colorTag,
            plainPreview = previewLines.joinToString("\n").take(240),
            textSectionCount = textCount,
            ideaCardCount = ideaCount,
            tableCount = tableCount,
            drawingStrokeCount = strokeCount,
            totalChecklistItems = totalTasks,
            checkedChecklistItems = checkedTasks,
            previewDrawing = firstDrawing,
            previewIdeaTitles = ideaTitles,
            previewTableHeaders = tableHeaders
        )
    }

    private fun encodeBlock(block: NoteBlock): JSONObject = when (block) {
        is NoteBlock.TextSection -> JSONObject()
            .put("id", block.id)
            .put("type", BLOCK_TEXT)
            .put("heading", InputValidator.sanitizeName(block.heading))
            .put("body", InputValidator.sanitizeText(block.body))
            .put("style", block.style.name)

        is NoteBlock.IdeaBoard -> {
            val cardsArray = JSONArray()
            block.cards.forEach { card ->
                cardsArray.put(
                    JSONObject()
                        .put("id", card.id)
                        .put("title", InputValidator.sanitizeName(card.title))
                        .put("details", InputValidator.sanitizeText(card.details))
                        .put("tag", InputValidator.sanitizeName(card.tag).ifBlank { "فكرة" })
                        .put("colorHex", sanitizeColorHex(card.colorHex))
                )
            }
            JSONObject()
                .put("id", block.id)
                .put("type", BLOCK_IDEA_BOARD)
                .put("boardTitle", InputValidator.sanitizeName(block.boardTitle))
                .put("cards", cardsArray)
        }

        is NoteBlock.TableBlock -> {
            val normalized = NoteWorkspaceOperations.normalizeTable(block)
            val headersArray = JSONArray()
            normalized.headers.forEach { headersArray.put(it) }
            val rowsArray = JSONArray()
            normalized.rows.forEach { row ->
                val rowArray = JSONArray()
                row.forEach { cell -> rowArray.put(cell) }
                rowsArray.put(rowArray)
            }
            JSONObject()
                .put("id", normalized.id)
                .put("type", BLOCK_TABLE)
                .put("caption", normalized.caption)
                .put("headers", headersArray)
                .put("rows", rowsArray)
        }

        is NoteBlock.DrawingBoard -> {
            val strokesArray = JSONArray()
            block.strokes.forEach { stroke ->
                val simplified = NoteWorkspaceOperations.simplifyPoints(stroke.points)
                if (simplified.isNotEmpty()) {
                    val pointsArray = JSONArray()
                    simplified.forEach { pt ->
                        pointsArray.put(
                            JSONArray()
                                .put((pt.x * 1000f).roundToInt())
                                .put((pt.y * 1000f).roundToInt())
                        )
                    }
                    strokesArray.put(
                        JSONObject()
                            .put("id", stroke.id)
                            .put("colorHex", sanitizeColorHex(stroke.colorHex))
                            .put("widthDp", stroke.widthDp.coerceIn(1f, 28f).toDouble())
                            .put("isHighlighter", stroke.isHighlighter)
                            .put("points", pointsArray)
                    )
                }
            }
            JSONObject()
                .put("id", block.id)
                .put("type", BLOCK_DRAWING)
                .put("title", InputValidator.sanitizeName(block.title))
                .put("background", block.background.name)
                .put("strokes", strokesArray)
        }

        is NoteBlock.ChecklistBlock -> {
            val itemsArray = JSONArray()
            block.items.forEach { item ->
                itemsArray.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("text", InputValidator.sanitizeText(item.text))
                        .put("isChecked", item.isChecked)
                )
            }
            JSONObject()
                .put("id", block.id)
                .put("type", BLOCK_CHECKLIST)
                .put("title", InputValidator.sanitizeName(block.title))
                .put("items", itemsArray)
        }
    }

    private fun decodeBlock(obj: JSONObject): NoteBlock? {
        val id = obj.optString("id").ifBlank { newItemId() }
        return when (obj.optString("type")) {
            BLOCK_TEXT -> NoteBlock.TextSection(
                id = id,
                heading = InputValidator.sanitizeName(obj.optString("heading", "")),
                body = InputValidator.sanitizeText(obj.optString("body", "")),
                style = TextSectionStyle.fromNameOrDefault(obj.optString("style"))
            )

            BLOCK_IDEA_BOARD -> {
                val cardsArr = obj.optJSONArray("cards") ?: JSONArray()
                val cards = buildList {
                    for (i in 0 until cardsArr.length()) {
                        val c = cardsArr.optJSONObject(i) ?: continue
                        add(
                            IdeaCardItem(
                                id = c.optString("id").ifBlank { newItemId() },
                                title = InputValidator.sanitizeName(c.optString("title", "")),
                                details = InputValidator.sanitizeText(c.optString("details", "")),
                                tag = InputValidator.sanitizeName(c.optString("tag", "فكرة")).ifBlank { "فكرة" },
                                colorHex = sanitizeColorHex(c.optString("colorHex", "#4E7D6E"))
                            )
                        )
                    }
                }
                NoteBlock.IdeaBoard(
                    id = id,
                    boardTitle = InputValidator.sanitizeName(obj.optString("boardTitle", "ترتيب الأفكار")),
                    cards = cards
                )
            }

            BLOCK_TABLE -> {
                val headersArr = obj.optJSONArray("headers") ?: JSONArray()
                val headers = buildList {
                    for (i in 0 until headersArr.length()) {
                        add(headersArr.optString(i, "عمود ${i + 1}"))
                    }
                }.ifEmpty { listOf("البند", "التفاصيل") }

                val rowsArr = obj.optJSONArray("rows") ?: JSONArray()
                val rows = buildList {
                    for (r in 0 until rowsArr.length()) {
                        val rArr = rowsArr.optJSONObject(r)?.let { null } ?: rowsArr.optJSONArray(r)
                        if (rArr != null) {
                            add(
                                buildList {
                                    for (c in 0 until rArr.length()) {
                                        add(rArr.optString(c, ""))
                                    }
                                }
                            )
                        }
                    }
                }.ifEmpty { listOf(List(headers.size) { "" }) }

                NoteWorkspaceOperations.normalizeTable(
                    NoteBlock.TableBlock(
                        id = id,
                        caption = obj.optString("caption", ""),
                        headers = headers,
                        rows = rows
                    )
                )
            }

            BLOCK_DRAWING -> {
                val strokesArr = obj.optJSONArray("strokes") ?: JSONArray()
                val strokes = buildList {
                    for (s in 0 until strokesArr.length()) {
                        val sObj = strokesArr.optJSONObject(s) ?: continue
                        val ptsArr = sObj.optJSONArray("points") ?: JSONArray()
                        val pts = buildList {
                            for (p in 0 until ptsArr.length()) {
                                val pair = ptsArr.optJSONArray(p)
                                if (pair != null && pair.length() >= 2) {
                                    val rawX = pair.optDouble(0, 0.0).toFloat() / 1000f
                                    val rawY = pair.optDouble(1, 0.0).toFloat() / 1000f
                                    add(NormalizedPoint(rawX, rawY).clamped())
                                }
                            }
                        }
                        if (pts.isNotEmpty()) {
                            val rawWidth = sObj.optDouble("widthDp", 4.0).toFloat()
                            add(
                                DrawingStroke(
                                    id = sObj.optString("id").ifBlank { newItemId() },
                                    colorHex = sanitizeColorHex(sObj.optString("colorHex", "#3E6B5E")),
                                    widthDp = (if (rawWidth.isFinite()) rawWidth else 4f).coerceIn(1f, 28f),
                                    isHighlighter = sObj.optBoolean("isHighlighter", false),
                                    points = pts
                                )
                            )
                        }
                    }
                }
                NoteBlock.DrawingBoard(
                    id = id,
                    title = InputValidator.sanitizeName(obj.optString("title", "رسم توضيحي")),
                    background = CanvasBackground.fromNameOrDefault(obj.optString("background")),
                    strokes = strokes
                )
            }

            BLOCK_CHECKLIST -> {
                val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (i in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.optJSONObject(i) ?: continue
                        add(
                            ChecklistItem(
                                id = itemObj.optString("id").ifBlank { newItemId() },
                                text = InputValidator.sanitizeText(itemObj.optString("text", "")),
                                isChecked = itemObj.optBoolean("isChecked", false)
                            )
                        )
                    }
                }
                NoteBlock.ChecklistBlock(
                    id = id,
                    title = InputValidator.sanitizeName(obj.optString("title", "نقاط المراجعة")),
                    items = items
                )
            }

            else -> null
        }
    }

    private fun sanitizeColorHex(raw: String): String {
        val cleaned = raw.trim().uppercase()
        return if (Regex("^#[0-9A-F]{6}$").matches(cleaned)) cleaned else "#4E7D6E"
    }
}

private fun newItemId(): String = UUID.randomUUID().toString().take(8)
