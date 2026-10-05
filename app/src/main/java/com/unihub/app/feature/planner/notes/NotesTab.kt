package com.unihub.app.feature.planner.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.local.entity.NoteEntity
import com.unihub.app.ui.components.ChoiceChips
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.TintChip
import com.unihub.app.ui.components.UiMessagesHost
import com.unihub.app.ui.theme.PaperSurface
import com.unihub.app.ui.theme.SemanticInfo
import com.unihub.app.ui.theme.SemanticSuccess
import com.unihub.app.ui.theme.SemanticWarning
import com.unihub.app.ui.theme.toComposeColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تبويب الملاحظات والملخصات المتكامل:
 * - يدعم إنشاء وتحرير الملاحظات بأدوات احترافية متكاملة:
 *   1) كتابة منسقة وملخصات (فقرات، عناوين فرعية، إضاءات مهمة، نقاط ملخصة)
 *   2) لوحة بطاقات ترتيب الأفكار (Idea Cards) مع تلوين ووسوم وإعادة ترتيب
 *   3) جداول مرتبة (Structured Tables) قابلة لإضافة وحذف الصفوف والأعمدة
 *   4) لوحة رسم حر على الملاحظة (Drawing Canvas) مع قلم حبر، تظليل، ممحاة، تراجع، وخلفية شبكية/مسطرة
 *   5) قوائم مراجعة ومهام داخل الملاحظة
 * - مصمم وفق `طبيعة تطبيق.md`: إفصاح تدريجي، تسميات قصيرة واضحة، انتقالات ناعمة، وعدم ازدحام.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesTab(viewModel: NotesViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val currentFilter by viewModel.currentFilter.collectAsStateWithLifecycle()

    var activeEditorSession by remember { mutableStateOf<NoteEditorSession?>(null) }
    var deleteTarget by remember { mutableStateOf<NoteEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    activeEditorSession = NoteEditorSession(
                        editing = null,
                        initialTitle = "",
                        initialPinned = false,
                        initialDocument = NoteTemplates.blank()
                    )
                }
            ) {
                Icon(Icons.Filled.EditNote, contentDescription = "ملاحظة جديدة")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    placeholder = { Text("ابحث في الملاحظات، البطاقات، الجداول…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "مسح البحث")
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
            }

            // شريط إنشاء سريع بالقوالب الجاهزة — يمكّن المستخدم من بدء الأداة المطلوبة بضغطة واحدة
            item {
                QuickTemplateStrip(
                    onCreateBlank = {
                        activeEditorSession = NoteEditorSession(
                            editing = null,
                            initialTitle = "",
                            initialPinned = false,
                            initialDocument = NoteTemplates.blank()
                        )
                    },
                    onCreateSummary = {
                        activeEditorSession = NoteEditorSession(
                            editing = null,
                            initialTitle = "",
                            initialPinned = false,
                            initialDocument = NoteTemplates.lectureSummary()
                        )
                    },
                    onCreateIdeaBoard = {
                        activeEditorSession = NoteEditorSession(
                            editing = null,
                            initialTitle = "",
                            initialPinned = false,
                            initialDocument = NoteTemplates.ideaBoard()
                        )
                    },
                    onCreateTable = {
                        activeEditorSession = NoteEditorSession(
                            editing = null,
                            initialTitle = "",
                            initialPinned = false,
                            initialDocument = NoteTemplates.structuredTable()
                        )
                    },
                    onCreateSketch = {
                        activeEditorSession = NoteEditorSession(
                            editing = null,
                            initialTitle = "",
                            initialPinned = false,
                            initialDocument = NoteTemplates.sketchNote()
                        )
                    }
                )
            }

            // مرشحات سريعة حسب نوع محتوى الملاحظة
            item {
                ChoiceChips(
                    labels = NoteFilter.entries.map { it.label },
                    selectedIndex = NoteFilter.entries.indexOf(currentFilter),
                    onSelect = { index -> viewModel.setFilter(NoteFilter.entries[index]) }
                )
            }

            if (notes.isEmpty()) {
                item {
                    val hasSearch = searchQuery.trim().isNotBlank()
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.StickyNote2,
                        title = when {
                            hasSearch -> "لا نتائج للبحث"
                            currentFilter != NoteFilter.ALL -> "لا ملاحظات في هذا التصنيف"
                            else -> "لا ملاحظات بعد"
                        },
                        subtitle = when {
                            hasSearch -> "جرّب كلمات بحث أخرى أو غيّر المرشح"
                            currentFilter != NoteFilter.ALL -> "اختر «الكل» أو أنشئ ملاحظة جديدة من الشريط العلوي"
                            else -> "أنشئ ملاحظة نصية أو لوحة ترتيب أفكار أو جدولاً منظماً أو رسماً توضيحياً"
                        }
                    )
                }
            }

            items(notes, key = { it.id }) { note ->
                RichNoteCard(
                    note = note,
                    onClick = {
                        activeEditorSession = NoteEditorSession(
                            editing = note,
                            initialTitle = note.title,
                            initialPinned = note.isPinned,
                            initialDocument = NoteWorkspaceCodec.decode(note.content)
                        )
                    },
                    onTogglePin = { viewModel.togglePin(note) },
                    onDuplicate = { viewModel.duplicate(note) },
                    onDeleteRequest = { deleteTarget = note }
                )
            }

            item { Spacer(Modifier.height(84.dp)) }
        }

        activeEditorSession?.let { session ->
            NoteWorkspaceDialog(
                session = session,
                onDismiss = { activeEditorSession = null },
                onSave = { title, isPinned, doc ->
                    val accepted = viewModel.saveDocument(
                        editing = session.editing,
                        title = title,
                        isPinned = isPinned,
                        document = doc
                    )
                    if (accepted) {
                        activeEditorSession = null
                    }
                },
                onDelete = session.editing?.let { existing ->
                    {
                        activeEditorSession = null
                        deleteTarget = existing
                    }
                }
            )
        }

        deleteTarget?.let { note ->
            ConfirmDialog(
                title = "حذف الملاحظة؟",
                message = "ستُحذف \"${note.title}\" نهائياً بجميع بطاقاتها وجداولها ورسوماتها.",
                onConfirm = {
                    viewModel.delete(note)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }
}

private data class NoteEditorSession(
    val editing: NoteEntity?,
    val initialTitle: String,
    val initialPinned: Boolean,
    val initialDocument: NoteWorkspaceDocument
)

@Composable
private fun QuickTemplateStrip(
    onCreateBlank: () -> Unit,
    onCreateSummary: () -> Unit,
    onCreateIdeaBoard: () -> Unit,
    onCreateTable: () -> Unit,
    onCreateSketch: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        item {
            AssistChip(
                onClick = onCreateBlank,
                label = { Text("ملاحظة حرة") },
                leadingIcon = {
                    Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        item {
            AssistChip(
                onClick = onCreateIdeaBoard,
                label = { Text("بطاقات أفكار") },
                leadingIcon = {
                    Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        item {
            AssistChip(
                onClick = onCreateTable,
                label = { Text("جدول مرتب") },
                leadingIcon = {
                    Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        item {
            AssistChip(
                onClick = onCreateSketch,
                label = { Text("رسم على ملاحظة") },
                leadingIcon = {
                    Icon(Icons.Filled.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
        item {
            AssistChip(
                onClick = onCreateSummary,
                label = { Text("ملخص محاضرة") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.AutoAwesomeMotion,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RichNoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDuplicate: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val stats = remember(note.content) { NoteWorkspaceCodec.summarize(note.content) }
    val tagColor = remember(stats.colorTag) { stats.colorTag.colorHex.toComposeColor() }
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }

    Card(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(onClick = onClick, onLongClick = onDeleteRequest)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // السطر العلوي: وسم اللون + العنوان + أيقونة التثبيت
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(tagColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if ( stats.colorTag != NoteColorTag.DEFAULT ) {
                    Spacer(Modifier.width(6.dp))
                    TintChip(
                        text = stats.colorTag.label,
                        containerColor = tagColor.copy(alpha = 0.15f),
                        contentColor = tagColor
                    )
                }
                if (note.isPinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "مثبتة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // المعاينة النصية الهادئة
            if (stats.plainPreview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stats.plainPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // معاينة مصغّرة لبطاقات الأفكار إن وجدت
            if (stats.previewIdeaTitles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    stats.previewIdeaTitles.forEach { ideaTitle ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SemanticWarning.copy(alpha = 0.14f),
                            border = BorderStroke(0.5.dp, SemanticWarning.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lightbulb,
                                    contentDescription = null,
                                    tint = SemanticWarning,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = ideaTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // معاينة مصغّرة لأعمدة الجدول إن وجد
            if (stats.previewTableHeaders.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SemanticInfo.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, SemanticInfo.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TableChart,
                            contentDescription = null,
                            tint = SemanticInfo,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stats.previewTableHeaders.joinToString("  |  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // معاينة مصغّرة للرسم التوضيحي إن وجد
            stats.previewDrawing?.let { drawing ->
                Spacer(Modifier.height(8.dp))
                MiniDrawingPreview(
                    drawing = drawing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // شارات المحتوى (فقرات، بطاقات، جداول، رسومات، مهام)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (stats.textSectionCount > 1) {
                    TintChip(
                        text = "${stats.textSectionCount} فقرات",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (stats.ideaCardCount > 0) {
                    TintChip(
                        text = "${stats.ideaCardCount} بطاقة",
                        containerColor = SemanticWarning.copy(alpha = 0.15f),
                        contentColor = SemanticWarning
                    )
                }
                if (stats.tableCount > 0) {
                    TintChip(
                        text = "${stats.tableCount} جدول",
                        containerColor = SemanticInfo.copy(alpha = 0.15f),
                        contentColor = SemanticInfo
                    )
                }
                if (stats.drawingStrokeCount > 0) {
                    TintChip(
                        text = "رسم (${stats.drawingStrokeCount})",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (stats.totalChecklistItems > 0) {
                    TintChip(
                        text = "مهام ${stats.checkedChecklistItems}/${stats.totalChecklistItems}",
                        containerColor = SemanticSuccess.copy(alpha = 0.15f),
                        contentColor = SemanticSuccess
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // شريط التاريخ والإجراءات السريعة
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(note.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDuplicate,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "نسخ الملاحظة",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDeleteRequest,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "حذف الملاحظة",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onTogglePin) {
                    Text(if (note.isPinned) "إلغاء التثبيت" else "تثبيت")
                }
            }
        }
    }
}

/** معاينة مصغّرة غير تفاعلية للرسم داخل بطاقة الملاحظة */
@Composable
private fun MiniDrawingPreview(
    drawing: NoteBlock.DrawingBoard,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PaperSurface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
    ) {
        drawCanvasPaperBackground(drawing.background)
        drawing.strokes.forEach { stroke ->
            drawNormalizedStroke(stroke)
        }
    }
}



// ============================================================================
// الدوال المساعدة للرسم وأشكال البطاقات المخصصة في مساحة الورقة الموحدة
// ============================================================================

/** رسم خلفية الورقة (سادة / مسطر / شبكة) داخل مساحة الملاحظة */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCanvasPaperBackground(
    background: CanvasBackground
) {
    val lineColor = Color(0xFFCBD5E1).copy(alpha = 0.45f)
    when (background) {
        CanvasBackground.PLAIN -> Unit
        CanvasBackground.RULED -> {
            val step = 28.dp.toPx().coerceAtLeast(12f)
            var y = step
            while (y < size.height) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += step
            }
        }
        CanvasBackground.GRID -> {
            val step = 24.dp.toPx().coerceAtLeast(12f)
            var x = step
            while (x < size.width) {
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += step
            }
        }
    }
}

/** رسم مسار رسم واحد مطبّع على لوحة Canvas بانسيابية Bézier تامة */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNormalizedStroke(
    stroke: DrawingStroke
) {
    if (stroke.points.isEmpty()) return
    val baseColor = stroke.colorHex.toComposeColor()
    val strokeColor = if (stroke.isHighlighter) baseColor.copy(alpha = 0.38f) else baseColor
    val widthPx = stroke.widthDp.dp.toPx().coerceAtLeast(2f)

    if (stroke.points.size == 1) {
        val pt = stroke.points.first()
        drawCircle(
            color = strokeColor,
            radius = widthPx / 2f,
            center = Offset(pt.x * size.width, pt.y * size.height)
        )
        return
    }

    val path = Path().apply {
        val first = stroke.points.first()
        moveTo(first.x * size.width, first.y * size.height)
        for (i in 1 until stroke.points.size) {
            val prev = stroke.points[i - 1]
            val curr = stroke.points[i]
            val midX = ((prev.x + curr.x) / 2f) * size.width
            val midY = ((prev.y + curr.y) / 2f) * size.height
            quadraticTo(
                prev.x * size.width,
                prev.y * size.height,
                midX,
                midY
            )
        }
        val last = stroke.points.last()
        lineTo(last.x * size.width, last.y * size.height)
    }

    drawPath(
        path = path,
        color = strokeColor,
        style = Stroke(
            width = widthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

/** شكل ورقة ملاحظات لاصقة مع زاوية مطوية ثلاثية الأبعاد كالستيكي نوت الشهيرة */
class FoldedStickyShape(private val foldSizePx: Float = 36f) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val fold = foldSizePx.coerceAtMost(size.width * 0.3f).coerceAtMost(size.height * 0.3f)
        val path = Path().apply {
            moveTo(14f, 0f)
            lineTo(size.width - fold, 0f)
            lineTo(size.width, fold)
            lineTo(size.width, size.height - 14f)
            quadraticTo(size.width, size.height, size.width - 14f, size.height)
            lineTo(14f, size.height)
            quadraticTo(0f, size.height, 0f, size.height - 14f)
            lineTo(0f, 14f)
            quadraticTo(0f, 0f, 14f, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/** تحويل نمط شكل البطاقة إلى Shape مخصص في Jetpack Compose */
private fun resolveCardShape(shape: IdeaCardShape, foldPx: Float): Shape = when (shape) {
    IdeaCardShape.ROUNDED -> RoundedCornerShape(16.dp)
    IdeaCardShape.FOLDED_STICKY -> FoldedStickyShape(foldPx)
    IdeaCardShape.CAPSULE -> RoundedCornerShape(percent = 40)
    IdeaCardShape.CUT_CORNER -> CutCornerShape(16.dp)
    IdeaCardShape.BADGE -> RoundedCornerShape(topStart = 20.dp, bottomEnd = 20.dp, topEnd = 4.dp, bottomStart = 4.dp)
}

/** خلفية هادئة ثابتة حول ورقة الملاحظة — بلا حركة دائمة تستهلك البطارية وتشتت النظر */
@Composable
private fun AmbientLivingEdgeBackground(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // تدرجان هادئان على الحواف
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF4E7D6E).copy(alpha = 0.14f),
                    Color.Transparent,
                    Color.Transparent,
                    Color(0xFF5B7FA6).copy(alpha = 0.12f)
                )
            ),
            size = size
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFC79A4B).copy(alpha = 0.09f),
                    Color.Transparent,
                    Color.Transparent,
                    Color(0xFF4E7D6E).copy(alpha = 0.10f)
                )
            ),
            size = size
        )

        // إشراقة ثابتة في الزاويتين المتقابلتين
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF5B7FA6).copy(alpha = 0.16f),
                    Color.Transparent
                ),
                center = Offset(w * 0.10f, h * 0.06f),
                radius = w * 0.45f
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFC79A4B).copy(alpha = 0.14f),
                    Color.Transparent
                ),
                center = Offset(w * 0.90f, h * 0.94f),
                radius = w * 0.45f
            )
        )
    }
}

/** أنماط الأدوات المتاحة في شريط الملاحظات السفلي */
private enum class NoteToolMode(val label: String) {
    NONE("تصفح"),
    TEXT("كتابة"),
    DRAW("رسم حر"),
    CARDS("بطاقات"),
    FORMAT("تنسيق الخط")
}

private enum class DrawingToolMode(val label: String) {
    PEN("قلم"),
    HIGHLIGHTER("تظليل"),
    ERASER("ممحاة")
}

// ============================================================================
// محرر ورقة الملاحظات الموحد (Unified Note Studio)
// يجمع الكتابة، والرسم المباشر فوق النص، والبطاقات الحرة المتحركة في ورقة بيضاء واحدة
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteWorkspaceDialog(
    session: NoteEditorSession,
    onDismiss: () -> Unit,
    onSave: (title: String, isPinned: Boolean, document: NoteWorkspaceDocument) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember(session) { mutableStateOf(session.initialTitle) }
    var isPinned by remember(session) { mutableStateOf(session.initialPinned) }
    var colorTag by remember(session) { mutableStateOf(session.initialDocument.colorTag) }
    var summary by remember(session) { mutableStateOf(session.initialDocument.summary) }
    var showSummaryField by remember(session) {
        mutableStateOf(session.initialDocument.summary.isNotBlank())
    }

    // استخراج واستعادة الكتل الحالية: نصوص، بطاقات، رسومات
    val initialTexts = remember(session) {
        session.initialDocument.blocks
            .filterIsInstance<NoteBlock.TextSection>()
            .ifEmpty { listOf(NoteBlock.TextSection()) }
    }
    var textSections by remember(session) { mutableStateOf(initialTexts) }
    var activeSectionIndex by remember(session) { mutableStateOf(0) }

    val initialCards = remember(session) {
        session.initialDocument.blocks
            .filterIsInstance<NoteBlock.IdeaBoard>()
            .flatMap { it.cards }
    }
    var cards by remember(session) { mutableStateOf(initialCards) }
    var selectedCardId by remember(session) { mutableStateOf<String?>(null) }

    val initialStrokes = remember(session) {
        session.initialDocument.blocks
            .filterIsInstance<NoteBlock.DrawingBoard>()
            .flatMap { it.strokes }
    }
    var drawingStrokes by remember(session) { mutableStateOf(initialStrokes) }
    var inProgressStroke by remember { mutableStateOf<DrawingStroke?>(null) }
    var liveTouchPoint by remember { mutableStateOf<Offset?>(null) }

    // حالات الأدوات وشريط التحكم
    var activeToolMode by remember { mutableStateOf(NoteToolMode.NONE) }
    var drawingMode by remember { mutableStateOf(DrawingToolMode.PEN) }
    var strokeWidthDp by remember { mutableFloatStateOf(4f) }
    var strokeColorHex by remember { mutableStateOf(NotePalette.defaultStrokeColor) }

    // خيارات تنسيق الخط للنصوص
    var currentFontSizeSp by remember { mutableFloatStateOf(16f) }
    var currentIsBold by remember { mutableStateOf(false) }
    var currentTextColorHex by remember { mutableStateOf(NotePalette.defaultTextColor) }

    // خيارات إضافة البطاقات الحرة
    var cardShapeChoice by remember { mutableStateOf(IdeaCardShape.selectable.first()) }
    var cardColorChoice by remember { mutableStateOf(NotePalette.defaultCardColor) }

    var showDiscardDialog by remember { mutableStateOf(false) }

    // تجميع الوثيقة الكاملة للحفظ: يُعاد بناء الكتل المحرّرة في مواضعها،
    // وتبقى الجداول والمهام كما هي حتى لا تضيع عند حفظ ملاحظة قديمة
    val currentDocument = remember(colorTag, summary, textSections, cards, drawingStrokes, session) {
        NoteWorkspaceOperations.assembleEditedDocument(
            original = session.initialDocument,
            colorTag = colorTag,
            summary = summary,
            textSections = textSections,
            ideaCards = cards,
            drawingStrokes = drawingStrokes
        )
    }

    val hasUnsavedChanges = remember(title, isPinned, currentDocument, session) {
        title != session.initialTitle ||
            isPinned != session.initialPinned ||
            NoteWorkspaceCodec.encode(currentDocument) != NoteWorkspaceCodec.encode(session.initialDocument)
    }

    val handleCloseRequest = {
        if (hasUnsavedChanges && (title.isNotBlank() || currentDocument.hasMeaningfulContent())) {
            showDiscardDialog = true
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = handleCloseRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        BackHandler(onBack = handleCloseRequest)

        Scaffold(
            containerColor = Color(0xFFF1F5F9),
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = handleCloseRequest) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }

                        // حقل العنوان المباشر النظيف في الشريط العلوي
                        Box(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            BasicTextField(
                                value = title,
                                onValueChange = { title = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    if (title.isEmpty()) {
                                        Text(
                                            text = "عنوان الملاحظة…",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }

                        // وسم/لون الملاحظة
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(colorTag.colorHex.toComposeColor())
                                .clickable {
                                    val nextIdx = (colorTag.ordinal + 1) % NoteColorTag.entries.size
                                    colorTag = NoteColorTag.entries[nextIdx]
                                }
                        )

                        IconButton(onClick = { isPinned = !isPinned }) {
                            Icon(
                                imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (isPinned) "إلغاء التثبيت" else "تثبيت",
                                tint = if (isPinned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (onDelete != null) {
                            IconButton(onClick = onDelete) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "حذف الملاحظة",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Spacer(Modifier.width(4.dp))
                        FilledTonalButton(
                            onClick = { onSave(title, isPinned, currentDocument) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("حفظ", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            bottomBar = {
                // شريط الأدوات السفلي المدمج والعصري
                ModernNoteBottomToolbar(
                    activeToolMode = activeToolMode,
                    onToggleTool = { mode ->
                        activeToolMode = if (activeToolMode == mode) NoteToolMode.NONE else mode
                    },
                    drawingMode = drawingMode,
                    onSelectDrawingMode = { drawingMode = it },
                    strokeWidthDp = strokeWidthDp,
                    onSelectStrokeWidth = { strokeWidthDp = it },
                    strokeColorHex = strokeColorHex,
                    onSelectStrokeColor = { strokeColorHex = it },
                    onClearDrawings = {
                        drawingStrokes = emptyList()
                        inProgressStroke = null
                    },
                    currentFontSizeSp = currentFontSizeSp,
                    onChangeFontSize = { delta ->
                        val newSize = (currentFontSizeSp + delta).coerceIn(12f, 36f)
                        currentFontSizeSp = newSize
                        if (activeSectionIndex in textSections.indices) {
                            textSections = textSections.mapIndexed { idx, sec ->
                                if (idx == activeSectionIndex) sec.copy(fontSizeSp = newSize) else sec
                            }
                        }
                    },
                    currentIsBold = currentIsBold,
                    onToggleBold = {
                        val newBold = !currentIsBold
                        currentIsBold = newBold
                        if (activeSectionIndex in textSections.indices) {
                            textSections = textSections.mapIndexed { idx, sec ->
                                if (idx == activeSectionIndex) sec.copy(isBold = newBold) else sec
                            }
                        }
                    },
                    currentTextColorHex = currentTextColorHex,
                    onSelectTextColor = { hex ->
                        currentTextColorHex = hex
                        if (activeSectionIndex in textSections.indices) {
                            textSections = textSections.mapIndexed { idx, sec ->
                                if (idx == activeSectionIndex) sec.copy(colorHex = hex) else sec
                            }
                        }
                    },
                    currentStyle = textSections.getOrNull(activeSectionIndex)?.style ?: TextSectionStyle.PARAGRAPH,
                    onSelectStyle = { style ->
                        if (activeSectionIndex in textSections.indices) {
                            textSections = textSections.mapIndexed { idx, sec ->
                                if (idx == activeSectionIndex) sec.copy(style = style) else sec
                            }
                        }
                    },
                    cardShapeChoice = cardShapeChoice,
                    onSelectCardShape = { cardShapeChoice = it },
                    cardColorChoice = cardColorChoice,
                    onSelectCardColor = { cardColorChoice = it },
                    onAddCard = {
                        val count = cards.size
                        val x = 0.2f + (count % 3) * 0.25f
                        val y = 0.25f + (count % 4) * 0.15f
                        val newCard = IdeaCardItem(
                            title = "",
                            details = "",
                            tag = "فكرة",
                            colorHex = cardColorChoice,
                            xPercent = x.coerceIn(0.1f, 0.8f),
                            yPercent = y.coerceIn(0.1f, 0.8f),
                            shape = cardShapeChoice
                        )
                        cards = cards + newCard
                        selectedCardId = newCard.id
                    },
                    canUndo = drawingStrokes.isNotEmpty(),
                    onUndo = {
                        drawingStrokes = NoteWorkspaceOperations.undoLastStroke(drawingStrokes)
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 1) الخلفية الحركية الناعمة من الجوانب
                AmbientLivingEdgeBackground(modifier = Modifier.fillMaxSize())

                // 2) ورقة الملاحظة البيضاء الصافية الموحدة في المنتصف
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    val paperWidth = maxWidth
                    val paperHeight = maxHeight

                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(16.dp),
                                spotColor = Color(0x1F000000)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFCFDFD),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // طبقة خطوط ورقة الملاحظة الهادئة
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCanvasPaperBackground(CanvasBackground.RULED)
                            }

                            // طبقة النصوص القابلة للتمرير والتحرير على الورقة
                            val textScrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(
                                        textScrollState,
                                        enabled = activeToolMode != NoteToolMode.DRAW
                                    )
                                    .padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // الملخص السريع (اختياري بنقر زر الإفصاح)
                                if (showSummaryField || summary.isNotBlank()) {
                                    OutlinedCard(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.outlinedCardColors(
                                            containerColor = Color(0xFFF8FAFC)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = "الملخص السريع",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            BasicTextField(
                                                value = summary,
                                                onValueChange = { summary = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                    color = Color(0xFF334155)
                                                ),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                decorationBox = { inner ->
                                                    if (summary.isEmpty()) {
                                                        Text(
                                                            "خلاصة الملاحظة في جملتين…",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = Color(0xFF94A3B8)
                                                        )
                                                    }
                                                    inner()
                                                }
                                            )
                                        }
                                    }
                                }

                                // فقرات وكتل الكتابة على الورقة
                                textSections.forEachIndexed { index, section ->
                                    val isFocused = index == activeSectionIndex
                                    PaperTextSectionEditor(
                                        section = section,
                                        isFocused = isFocused,
                                        onFocus = {
                                            activeSectionIndex = index
                                            currentFontSizeSp = section.fontSizeSp
                                            currentIsBold = section.isBold
                                            currentTextColorHex = section.colorHex
                                        },
                                        onUpdate = { updated ->
                                            textSections = textSections.mapIndexed { idx, s ->
                                                if (idx == index) updated else s
                                            }
                                        },
                                        onDelete = if (textSections.size > 1) {
                                            {
                                                textSections = textSections.filterIndexed { idx, _ -> idx != index }
                                                activeSectionIndex = activeSectionIndex.coerceAtMost(textSections.lastIndex)
                                            }
                                        } else null
                                    )
                                }

                                // زر إضافة فقرة جديدة بسلاسة على الورقة
                                TextButton(
                                    onClick = {
                                        val newSec = NoteBlock.TextSection(
                                            fontSizeSp = currentFontSizeSp,
                                            isBold = currentIsBold,
                                            colorHex = currentTextColorHex
                                        )
                                        textSections = textSections + newSec
                                        activeSectionIndex = textSections.lastIndex
                                    },
                                    modifier = Modifier.align(Alignment.Start)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("فقرة جديدة")
                                }

                                Spacer(Modifier.height(140.dp))
                            }

                            // 3) طبقة البطاقات الحرة الملونة القابلة للسحب والتحريك على الورقة
                            cards.forEach { card ->
                                val isSelected = card.id == selectedCardId
                                FloatingDraggableCard(
                                    card = card,
                                    containerWidthDp = paperWidth,
                                    containerHeightDp = paperHeight,
                                    isSelected = isSelected,
                                    onSelect = { selectedCardId = card.id },
                                    onUpdatePosition = { newX, newY ->
                                        cards = cards.map {
                                            if (it.id == card.id) it.copy(xPercent = newX, yPercent = newY) else it
                                        }
                                    },
                                    onUpdateCard = { updated ->
                                        cards = cards.map { if (it.id == card.id) updated else it }
                                    },
                                    onDeleteCard = {
                                        cards = cards.filter { it.id != card.id }
                                        if (selectedCardId == card.id) selectedCardId = null
                                    }
                                )
                            }

                            // 4) طبقة الرسم الحر المباشر على الورقة وفوق النص
                            PaperDrawingCanvasOverlay(
                                strokes = drawingStrokes,
                                inProgressStroke = inProgressStroke,
                                liveTouchPoint = liveTouchPoint,
                                isDrawingActive = activeToolMode == NoteToolMode.DRAW,
                                drawingMode = drawingMode,
                                strokeWidthDp = strokeWidthDp,
                                strokeColorHex = strokeColorHex,
                                onDragStart = { offset, normPt ->
                                    liveTouchPoint = offset
                                    if (drawingMode == DrawingToolMode.ERASER) {
                                        drawingStrokes = NoteWorkspaceOperations.eraseStrokesNear(
                                            strokes = drawingStrokes,
                                            x = normPt.x,
                                            y = normPt.y
                                        )
                                    } else {
                                        inProgressStroke = DrawingStroke(
                                            colorHex = strokeColorHex,
                                            widthDp = if (drawingMode == DrawingToolMode.HIGHLIGHTER) strokeWidthDp * 2.2f else strokeWidthDp,
                                            isHighlighter = drawingMode == DrawingToolMode.HIGHLIGHTER,
                                            points = listOf(normPt)
                                        )
                                    }
                                },
                                onDrag = { offset, normPt ->
                                    liveTouchPoint = offset
                                    if (drawingMode == DrawingToolMode.ERASER) {
                                        drawingStrokes = NoteWorkspaceOperations.eraseStrokesNear(
                                            strokes = drawingStrokes,
                                            x = normPt.x,
                                            y = normPt.y
                                        )
                                    } else {
                                        inProgressStroke?.let { curr ->
                                            inProgressStroke = curr.copy(points = curr.points + normPt)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    liveTouchPoint = null
                                    inProgressStroke?.let { finished ->
                                        if (finished.points.isNotEmpty()) {
                                            // تبسيط النقاط يحفظ شكل الخط ويمنع تضخّم حجم الملاحظة في التخزين
                                            drawingStrokes = drawingStrokes + finished.copy(
                                                points = NoteWorkspaceOperations.simplifyPoints(finished.points)
                                            )
                                        }
                                    }
                                    inProgressStroke = null
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showDiscardDialog) {
            ConfirmDialog(
                title = "تجاهل التعديلات؟",
                message = "هناك محتوى غير محفوظ في الورقة، هل تريد إغلاق الملاحظة دون حفظ؟",
                confirmLabel = "تجاهل",
                onConfirm = {
                    showDiscardDialog = false
                    onDismiss()
                },
                onDismiss = { showDiscardDialog = false }
            )
        }
    }
}

// ============================================================================
// محرر فقرة نصية على الورقة مع تأثير حركي ناعم وتنسيق الخطوط
// ============================================================================
@Composable
private fun PaperTextSectionEditor(
    section: NoteBlock.TextSection,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onUpdate: (NoteBlock.TextSection) -> Unit,
    onDelete: (() -> Unit)?
) {
    // نبض المؤشر للفقرة المركّزة فقط — بلا حركة مستمرة في بقية الفقرات
    val cursorAlpha by if (isFocused) {
        val transition = rememberInfiniteTransition(label = "cursorBlink")
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursorAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    val textColor = remember(section.colorHex) { section.colorHex.toComposeColor() }
    val fontWeight = if (section.isBold) FontWeight.Bold else FontWeight.Normal

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onFocus() }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // شريط عنوان الفقرة (إن وجد أو كان في وضع التركيز)
        if (section.heading.isNotBlank() || isFocused) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = section.heading,
                    onValueChange = { onUpdate(section.copy(heading = it)) },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (section.heading.isEmpty()) {
                            Text(
                                "عنوان فرعي (اختياري)…",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            )
                        }
                        inner()
                    }
                )
                if (onDelete != null && isFocused) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "حذف الفقرة",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // نص الفقرة الفعلي مع التنسيقات المخصصة
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // مؤشر نمط النقاط إن كان النمط BULLETS
            if (section.style == TextSectionStyle.BULLETS) {
                Text(
                    text = "• ",
                    fontSize = section.fontSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (section.style == TextSectionStyle.CALLOUT) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(8.dp))
            }

            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = section.body,
                    onValueChange = { onUpdate(section.copy(body = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = section.fontSizeSp.sp,
                        fontWeight = fontWeight,
                        color = textColor,
                        lineHeight = (section.fontSizeSp * 1.45f).sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha)),
                    decorationBox = { inner ->
                        if (section.body.isEmpty()) {
                            Text(
                                "اكتب هنا بحرية… استخدم شريط الأدوات بالأسفل للرسم أو إضافة البطاقات وتغيير الخط",
                                fontSize = section.fontSizeSp.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        inner()
                    }
                )
            }
        }
    }
}

// ============================================================================
// بطاقة حرة ملونة وعائمة وقابلة للسحب والتحريك على الورقة
// بأشكال متعددة (مستطيل، ورقة مطوية، كبسولة، مشطوفة، شارة)
// ============================================================================
@Composable
private fun FloatingDraggableCard(
    card: IdeaCardItem,
    containerWidthDp: androidx.compose.ui.unit.Dp,
    containerHeightDp: androidx.compose.ui.unit.Dp,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUpdatePosition: (Float, Float) -> Unit,
    onUpdateCard: (IdeaCardItem) -> Unit,
    onDeleteCard: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cardWidthDp = 160.dp

    val cardWidthPx = with(density) { cardWidthDp.toPx() }
    val containerWidthPx = with(density) { containerWidthDp.toPx() }
    val containerHeightPx = with(density) { containerHeightDp.toPx() }

    val currentX = (card.xPercent * (containerWidthPx - cardWidthPx)).coerceIn(0f, (containerWidthPx - cardWidthPx).coerceAtLeast(0f))
    val currentY = (card.yPercent * (containerHeightPx - 200f)).coerceIn(0f, (containerHeightPx - 200f).coerceAtLeast(0f))

    val cardBg = remember(card.colorHex) { card.colorHex.toComposeColor() }
    val cardShape = remember(card.shape) { resolveCardShape(card.shape, 32f) }

    val elevation by animateDpAsState(
        targetValue = if (isSelected) 10.dp else 4.dp,
        label = "cardElevation"
    )

    Box(
        modifier = Modifier
            .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
            .width(cardWidthDp)
            .shadow(elevation, cardShape, spotColor = Color(0x28000000))
            .clip(cardShape)
            .background(cardBg)
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.12f),
                shape = cardShape
            )
            .pointerInput(card.id) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val newX = (currentX + dragAmount.x) / (containerWidthPx - cardWidthPx).coerceAtLeast(1f)
                    val newY = (currentY + dragAmount.y) / (containerHeightPx - 200f).coerceAtLeast(1f)
                    onUpdatePosition(newX.coerceIn(0.02f, 0.98f), newY.coerceIn(0.02f, 0.98f))
                }
            }
            .clickable { onSelect() }
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // شريط البطاقة العلوي
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // وسم نوع الشكل
                Text(
                    text = card.shape.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black.copy(alpha = 0.45f),
                    fontSize = 10.sp
                )
                if (isSelected) {
                    IconButton(onClick = onDeleteCard, modifier = Modifier.size(18.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "حذف البطاقة",
                            tint = Color.Black.copy(alpha = 0.55f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // عنوان البطاقة
            BasicTextField(
                value = card.title,
                onValueChange = { onUpdateCard(card.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B)
                ),
                cursorBrush = SolidColor(Color(0xFF1E293B)),
                decorationBox = { inner ->
                    if (card.title.isEmpty()) {
                        Text(
                            "عنوان الفكرة…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black.copy(alpha = 0.35f)
                        )
                    }
                    inner()
                }
            )

            // تفاصيل البطاقة
            BasicTextField(
                value = card.details,
                onValueChange = { onUpdateCard(card.copy(details = it)) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF334155)
                ),
                cursorBrush = SolidColor(Color(0xFF1E293B)),
                decorationBox = { inner ->
                    if (card.details.isEmpty()) {
                        Text(
                            "اكتب تفاصيل…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black.copy(alpha = 0.35f)
                        )
                    }
                    inner()
                }
            )

            // عند تحديد البطاقة: إظهار خيارات سريعة لتبديل الشكل واللون
            if (isSelected) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // زر تبديل الشكل
                    Text(
                        text = "تبديل الشكل",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                onUpdateCard(card.copy(shape = IdeaCardShape.nextSelectable(card.shape)))
                            }
                            .padding(2.dp)
                    )
                    // زر تبديل اللون
                    Text(
                        text = "لون آخر",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                val colors = NotePalette.cardColors
                                val currIdx = colors.indexOf(card.colorHex)
                                val nextColor = colors[(currIdx + 1).coerceAtLeast(0) % colors.size]
                                onUpdateCard(card.copy(colorHex = nextColor))
                            }
                            .padding(2.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// طبقة الرسم الحر على الورقة وفوق النصوص والبطاقات مباشرة
// ============================================================================
@Composable
private fun PaperDrawingCanvasOverlay(
    strokes: List<DrawingStroke>,
    inProgressStroke: DrawingStroke?,
    liveTouchPoint: Offset?,
    isDrawingActive: Boolean,
    drawingMode: DrawingToolMode,
    strokeWidthDp: Float,
    strokeColorHex: String,
    onDragStart: (Offset, NormalizedPoint) -> Unit,
    onDrag: (Offset, NormalizedPoint) -> Unit,
    onDragEnd: () -> Unit
) {
    // نبض خفيف لمؤشر القلم أثناء الرسم فقط — لا حركة دائمة أثناء القراءة أو الكتابة
    val glowRadius by if (isDrawingActive) {
        val transition = rememberInfiniteTransition(label = "brushGlow")
        transition.animateFloat(
            initialValue = 4f,
            targetValue = 9f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowPulse"
        )
    } else {
        remember { mutableFloatStateOf(4f) }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isDrawingActive) {
                    Modifier.pointerInput(drawingMode, strokeWidthDp, strokeColorHex) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val normPt = NormalizedPoint(
                                    x = (offset.x / size.width).coerceIn(0f, 1f),
                                    y = (offset.y / size.height).coerceIn(0f, 1f)
                                )
                                onDragStart(offset, normPt)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val normPt = NormalizedPoint(
                                    x = (change.position.x / size.width).coerceIn(0f, 1f),
                                    y = (change.position.y / size.height).coerceIn(0f, 1f)
                                )
                                onDrag(change.position, normPt)
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd
                        )
                    }
                } else Modifier
            )
    ) {
        // رسم الخطوط المحفوظة
        strokes.forEach { stroke ->
            drawNormalizedStroke(stroke)
        }

        // رسم الخط قيد الرسم حالياً
        inProgressStroke?.let { stroke ->
            drawNormalizedStroke(stroke)
        }

        // رسم مؤشر تفاعلي ناعم يتبع إصبع المستخدم أثناء الرسم
        if (isDrawingActive && liveTouchPoint != null) {
            val tipColor = if (drawingMode == DrawingToolMode.ERASER) Color.Red else strokeColorHex.toComposeColor()
            drawCircle(
                color = tipColor.copy(alpha = 0.25f),
                radius = strokeWidthDp.dp.toPx() + glowRadius,
                center = liveTouchPoint
            )
            drawCircle(
                color = tipColor,
                radius = (strokeWidthDp.dp.toPx() / 2f).coerceAtLeast(3f),
                center = liveTouchPoint
            )
        }
    }
}

// ============================================================================
// شريط الأدوات السفلي الحديث والعائم للملاحظات
// ============================================================================
@Composable
private fun ModernNoteBottomToolbar(
    activeToolMode: NoteToolMode,
    onToggleTool: (NoteToolMode) -> Unit,
    drawingMode: DrawingToolMode,
    onSelectDrawingMode: (DrawingToolMode) -> Unit,
    strokeWidthDp: Float,
    onSelectStrokeWidth: (Float) -> Unit,
    strokeColorHex: String,
    onSelectStrokeColor: (String) -> Unit,
    onClearDrawings: () -> Unit,
    currentFontSizeSp: Float,
    onChangeFontSize: (Float) -> Unit,
    currentIsBold: Boolean,
    onToggleBold: () -> Unit,
    currentTextColorHex: String,
    onSelectTextColor: (String) -> Unit,
    currentStyle: TextSectionStyle,
    onSelectStyle: (TextSectionStyle) -> Unit,
    cardShapeChoice: IdeaCardShape,
    onSelectCardShape: (IdeaCardShape) -> Unit,
    cardColorChoice: String,
    onSelectCardColor: (String) -> Unit,
    onAddCard: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // الشريط الثانوي السياقي المتكيف مع الأداة المختارة
            AnimatedVisibility(
                visible = activeToolMode != NoteToolMode.NONE,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                when (activeToolMode) {
                    NoteToolMode.DRAW -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // اختيار القلم / التظليل / الممحاة
                                DrawingToolMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = drawingMode == mode,
                                        onClick = { onSelectDrawingMode(mode) },
                                        label = { Text(mode.label) },
                                        leadingIcon = {
                                            when (mode) {
                                                DrawingToolMode.PEN -> Icon(Icons.Filled.Brush, null, modifier = Modifier.size(16.dp))
                                                DrawingToolMode.HIGHLIGHTER -> Icon(Icons.Filled.Draw, null, modifier = Modifier.size(16.dp))
                                                DrawingToolMode.ERASER -> Icon(Icons.Filled.Clear, null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.height(20.dp).width(1.dp))

                                // أحجام الخط
                                listOf(2f to "رفيع", 5f to "متوسط", 10f to "عريض", 18f to "تظليل").forEach { (width, label) ->
                                    FilterChip(
                                        selected = strokeWidthDp == width,
                                        onClick = { onSelectStrokeWidth(width) },
                                        label = { Text(label, fontSize = 11.sp) }
                                    )
                                }

                                TextButton(onClick = onClearDrawings) {
                                    Text("مسح الرسم", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                }
                            }

                            // باليتة ألوان الرسم
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val drawColors = NotePalette.strokeColors
                                drawColors.forEach { hex ->
                                    val isSelected = strokeColorHex.equals(hex, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .size(if (isSelected) 26.dp else 22.dp)
                                            .clip(CircleShape)
                                            .background(hex.toComposeColor())
                                            .border(
                                                width = if (isSelected) 2.dp else 0.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            )
                                            .clickable { onSelectStrokeColor(hex) }
                                    )
                                }
                            }
                        }
                    }

                    NoteToolMode.FORMAT -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // أزرار تكبير وتصغير الخط
                                IconButton(
                                    onClick = { onChangeFontSize(-2f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("A-", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                }
                                Text(
                                    text = "${currentFontSizeSp.toInt()}sp",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { onChangeFontSize(2f) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text("A+", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                }

                                HorizontalDivider(modifier = Modifier.height(20.dp).width(1.dp))

                                // زر الخط العريض
                                FilterChip(
                                    selected = currentIsBold,
                                    onClick = onToggleBold,
                                    label = { Text("عريض B", fontWeight = FontWeight.ExtraBold) }
                                )

                                HorizontalDivider(modifier = Modifier.height(20.dp).width(1.dp))

                                // أنماط الفقرة
                                TextSectionStyle.entries.forEach { style ->
                                    FilterChip(
                                        selected = currentStyle == style,
                                        onClick = { onSelectStyle(style) },
                                        label = { Text(style.label, fontSize = 11.sp) }
                                    )
                                }
                            }

                            // باليتة ألوان النص
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val textColors = NotePalette.textColors
                                textColors.forEach { hex ->
                                    val isSelected = currentTextColorHex.equals(hex, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .size(if (isSelected) 26.dp else 22.dp)
                                            .clip(CircleShape)
                                            .background(hex.toComposeColor())
                                            .border(
                                                width = if (isSelected) 2.dp else 0.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            )
                                            .clickable { onSelectTextColor(hex) }
                                    )
                                }
                            }
                        }
                    }

                    NoteToolMode.CARDS -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // اختيار شكل البطاقة
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IdeaCardShape.selectable.forEach { shape ->
                                    FilterChip(
                                        selected = cardShapeChoice == shape,
                                        onClick = { onSelectCardShape(shape) },
                                        label = { Text(shape.label, fontSize = 11.sp) }
                                    )
                                }
                            }

                            // ألوان البطاقة الباستيل + زر الإضافة
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val cardColors = NotePalette.cardColors
                                    cardColors.forEach { hex ->
                                        val isSelected = cardColorChoice.equals(hex, ignoreCase = true)
                                        Box(
                                            modifier = Modifier
                                                .size(if (isSelected) 24.dp else 20.dp)
                                                .clip(CircleShape)
                                                .background(hex.toComposeColor())
                                                .border(
                                                    width = if (isSelected) 2.dp else 0.5.dp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                                    shape = CircleShape
                                                )
                                                .clickable { onSelectCardColor(hex) }
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = onAddCard,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("إضافة بطاقة للورقة", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    else -> Unit
                }
            }

            // الشريط الرئيسي للأدوات (الأيقونات الأساسية)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // زر أداة النص
                IconButton(
                    onClick = { onToggleTool(NoteToolMode.TEXT) }
                ) {
                    Icon(
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = "كتابة",
                        tint = if (activeToolMode == NoteToolMode.TEXT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // زر أداة الرسم الحر
                IconButton(
                    onClick = { onToggleTool(NoteToolMode.DRAW) }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Draw,
                        contentDescription = "رسم حر",
                        tint = if (activeToolMode == NoteToolMode.DRAW) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // زر أداة البطاقات
                IconButton(
                    onClick = { onToggleTool(NoteToolMode.CARDS) }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesomeMotion,
                        contentDescription = "بطاقات أفكار",
                        tint = if (activeToolMode == NoteToolMode.CARDS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // زر تنسيق الخط
                IconButton(
                    onClick = { onToggleTool(NoteToolMode.FORMAT) }
                ) {
                    Text(
                        text = "Aa",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = if (activeToolMode == NoteToolMode.FORMAT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // زر التراجع
                IconButton(
                    onClick = onUndo,
                    enabled = canUndo
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "تراجع",
                        tint = if (canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}
