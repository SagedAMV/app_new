package com.unihub.app.feature.planner.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.text.style.TextDecoration
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
import com.unihub.app.ui.theme.FolderPalette
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

/**
 * محرر مساحة عمل الملاحظة (Note Studio) في نافذة ملء الشاشة.
 * يعزل حركات اللمس والجدول عن `HorizontalPager` في `PlannerScreen`،
 * ويطبق الإفصاح التدريجي للأدوات.
 */
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
    var blocks by remember(session) { mutableStateOf(session.initialDocument.blocks) }
    var activeDrawingBlockId by remember(session) {
        mutableStateOf(
            session.initialDocument.blocks
                .filterIsInstance<NoteBlock.DrawingBoard>()
                .firstOrNull { it.strokes.isEmpty() }
                ?.id
        )
    }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val currentDocument = remember(colorTag, summary, blocks) {
        NoteWorkspaceDocument(
            colorTag = colorTag,
            summary = summary,
            blocks = blocks
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

        val scrollState = rememberScrollState()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = handleCloseRequest) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                        Text(
                            text = if (session.editing == null) "ملاحظة جديدة" else "تحرير الملاحظة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
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
                            onClick = { onSave(title, isPinned, currentDocument) }
                        ) {
                            Text("حفظ")
                        }
                    }
                }
            },
            bottomBar = {
                EditorAddToolBar(
                    onAddText = {
                        blocks = blocks + NoteBlock.TextSection()
                    },
                    onAddIdeaBoard = {
                        blocks = blocks + NoteBlock.IdeaBoard(
                            boardTitle = "ترتيب الأفكار",
                            cards = listOf(
                                IdeaCardItem(title = "", details = "", tag = "فكرة", colorHex = "#4E7D6E")
                            )
                        )
                    },
                    onAddTable = {
                        blocks = blocks + NoteBlock.TableBlock()
                    },
                    onAddDrawing = {
                        val newBoard = NoteBlock.DrawingBoard()
                        blocks = blocks + newBoard
                        activeDrawingBlockId = newBoard.id
                    },
                    onAddChecklist = {
                        blocks = blocks + NoteBlock.ChecklistBlock(
                            title = "نقاط المراجعة",
                            items = listOf(ChecklistItem(text = ""))
                        )
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // تعطيل التمرير العمودي مؤقتاً أثناء تفعيل القلم على لوحة الرسم لمنع تعارض السحب
                    .verticalScroll(scrollState, enabled = activeDrawingBlockId == null)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // العنوان الرئيسي
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("عنوان الملاحظة") },
                    placeholder = { Text("مثال: ملخص الفصل الثالث - الفيزياء…") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )

                // شريط وسم/لون الملاحظة + زر إظهار الملخص السريع (إفصاح تدريجي)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(NoteColorTag.entries) { tag ->
                            val selected = tag == colorTag
                            val chipColor = tag.colorHex.toComposeColor()
                            FilterChip(
                                selected = selected,
                                onClick = { colorTag = tag },
                                label = { Text(tag.label) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(chipColor)
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showSummaryField = !showSummaryField }) {
                        Text(if (showSummaryField) "إخفاء الملخص" else "+ ملخص سريع")
                    }
                }

                AnimatedVisibility(
                    visible = showSummaryField,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    OutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الملخص السريع (الخلاصة الأساسية للملاحظة)") },
                        maxLines = 4,
                        shape = MaterialTheme.shapes.small
                    )
                }

                // عرض وتحرير الكتل التفاعلية بالترتيب
                blocks.forEachIndexed { index, block ->
                    val canMoveUp = index > 0
                    val canMoveDown = index < blocks.lastIndex
                    val canDeleteBlock = blocks.size > 1

                    when (block) {
                        is NoteBlock.TextSection -> TextSectionEditorCard(
                            block = block,
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            canDelete = canDeleteBlock,
                            onMoveUp = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, -1) },
                            onMoveDown = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, 1) },
                            onDelete = { blocks = blocks.filterIndexed { i, _ -> i != index } },
                            onUpdate = { updated ->
                                blocks = blocks.mapIndexed { i, b -> if (i == index) updated else b }
                            }
                        )

                        is NoteBlock.IdeaBoard -> IdeaBoardEditorCard(
                            block = block,
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            canDelete = canDeleteBlock,
                            onMoveUp = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, -1) },
                            onMoveDown = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, 1) },
                            onDelete = { blocks = blocks.filterIndexed { i, _ -> i != index } },
                            onUpdate = { updated ->
                                blocks = blocks.mapIndexed { i, b -> if (i == index) updated else b }
                            }
                        )

                        is NoteBlock.TableBlock -> TableBlockEditorCard(
                            block = block,
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            canDelete = canDeleteBlock,
                            onMoveUp = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, -1) },
                            onMoveDown = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, 1) },
                            onDelete = { blocks = blocks.filterIndexed { i, _ -> i != index } },
                            onUpdate = { updated ->
                                blocks = blocks.mapIndexed { i, b -> if (i == index) updated else b }
                            }
                        )

                        is NoteBlock.DrawingBoard -> DrawingBoardEditorCard(
                            block = block,
                            isDrawingActive = activeDrawingBlockId == block.id,
                            onToggleDrawingActive = {
                                activeDrawingBlockId =
                                    if (activeDrawingBlockId == block.id) null else block.id
                            },
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            canDelete = canDeleteBlock,
                            onMoveUp = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, -1) },
                            onMoveDown = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, 1) },
                            onDelete = {
                                if (activeDrawingBlockId == block.id) activeDrawingBlockId = null
                                blocks = blocks.filterIndexed { i, _ -> i != index }
                            },
                            onUpdate = { updated ->
                                blocks = blocks.mapIndexed { i, b -> if (i == index) updated else b }
                            }
                        )

                        is NoteBlock.ChecklistBlock -> ChecklistBlockEditorCard(
                            block = block,
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            canDelete = canDeleteBlock,
                            onMoveUp = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, -1) },
                            onMoveDown = { blocks = NoteWorkspaceOperations.moveBlock(blocks, index, 1) },
                            onDelete = { blocks = blocks.filterIndexed { i, _ -> i != index } },
                            onUpdate = { updated ->
                                blocks = blocks.mapIndexed { i, b -> if (i == index) updated else b }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }

        if (showDiscardDialog) {
            ConfirmDialog(
                title = "تجاهل التعديلات؟",
                message = "لديك تعديلات غير محفوظة في هذه الملاحظة. هل تريد الخروج دون حفظ؟",
                confirmLabel = "خروج دون حفظ",
                onConfirm = {
                    showDiscardDialog = false
                    onDismiss()
                },
                onDismiss = { showDiscardDialog = false }
            )
        }
    }
}

/** شريط الأدوات السفلي لإضافة الكتل والأقسام الجديدة إلى الملاحظة */
@Composable
private fun EditorAddToolBar(
    onAddText: () -> Unit,
    onAddIdeaBoard: () -> Unit,
    onAddTable: () -> Unit,
    onAddDrawing: () -> Unit,
    onAddChecklist: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    AssistChip(
                        onClick = onAddText,
                        label = { Text("+ نص") },
                        leadingIcon = {
                            Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddIdeaBoard,
                        label = { Text("+ بطاقات أفكار") },
                        leadingIcon = {
                            Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddTable,
                        label = { Text("+ جدول") },
                        leadingIcon = {
                            Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddDrawing,
                        label = { Text("+ رسم حر") },
                        leadingIcon = {
                            Icon(Icons.Filled.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
                item {
                    AssistChip(
                        onClick = onAddChecklist,
                        label = { Text("+ مهام") },
                        leadingIcon = {
                            Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
    }
}

/** ترويسة موحدة لكل كتلة في المحرر مع أزرار الترتيب والحذف */
@Composable
private fun BlockHeaderBar(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        trailingContent?.invoke()
        if (canMoveUp) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "تحريك للأعلى", modifier = Modifier.size(16.dp))
            }
        }
        if (canMoveDown) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "تحريك للأسفل", modifier = Modifier.size(16.dp))
            }
        }
        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "حذف القسم",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ============================================================================
// 1) كتلة الكتابة المنسقة والملخصات النصية
// ============================================================================
@Composable
private fun TextSectionEditorCard(
    block: NoteBlock.TextSection,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (NoteBlock.TextSection) -> Unit
) {
    val containerColor = when (block.style) {
        TextSectionStyle.CALLOUT -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surface
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BlockHeaderBar(
                icon = Icons.Filled.TextFields,
                label = "كتابة وتلخيص",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                canDelete = canDelete,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete
            )

            ChoiceChips(
                labels = TextSectionStyle.entries.map { it.label },
                selectedIndex = TextSectionStyle.entries.indexOf(block.style),
                onSelect = { idx -> onUpdate(block.copy(style = TextSectionStyle.entries[idx])) }
            )

            OutlinedTextField(
                value = block.heading,
                onValueChange = { onUpdate(block.copy(heading = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("عنوان الفقرة (اختياري)") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )

            OutlinedTextField(
                value = block.body,
                onValueChange = { onUpdate(block.copy(body = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when (block.style) {
                            TextSectionStyle.PARAGRAPH -> "النص أو الشرح"
                            TextSectionStyle.HEADING -> "النص الرئيسي تحت العنوان"
                            TextSectionStyle.CALLOUT -> "نص الإضاءة أو التنبيه المهم"
                            TextSectionStyle.BULLETS -> "النقاط الملخصة (كل نقطة في سطر)"
                        }
                    )
                },
                minLines = 3,
                maxLines = 12,
                shape = MaterialTheme.shapes.small
            )
        }
    }
}

// ============================================================================
// 2) كتلة لوحة بطاقات ترتيب الأفكار (Idea Cards Board)
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdeaBoardEditorCard(
    block: NoteBlock.IdeaBoard,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (NoteBlock.IdeaBoard) -> Unit
) {
    val ideaTags = remember { listOf("فكرة", "سؤال", "خطة", "مفهوم", "خلاصة") }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BlockHeaderBar(
                icon = Icons.Filled.Lightbulb,
                label = "بطاقات ترتيب الأفكار (${block.cards.size})",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                canDelete = canDelete,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                trailingContent = {
                    TextButton(
                        onClick = {
                            val nextColor = FolderPalette[block.cards.size % FolderPalette.size]
                            onUpdate(
                                block.copy(
                                    cards = block.cards + IdeaCardItem(
                                        title = "",
                                        details = "",
                                        tag = "فكرة",
                                        colorHex = nextColor
                                    )
                                )
                            )
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("بطاقة")
                    }
                }
            )

            OutlinedTextField(
                value = block.boardTitle,
                onValueChange = { onUpdate(block.copy(boardTitle = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("عنوان لوحة الأفكار") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )

            block.cards.forEachIndexed { cardIndex, card ->
                val cardColor = card.colorHex.toComposeColor()
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = cardColor.copy(alpha = 0.10f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = cardColor.copy(alpha = 0.45f),
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TintChip(
                                text = "#${cardIndex + 1} ${card.tag}",
                                containerColor = cardColor.copy(alpha = 0.22f),
                                contentColor = cardColor
                            )
                            Spacer(Modifier.weight(1f))
                            // تبديل وسم البطاقة بضغطة سريعة
                            TextButton(
                                onClick = {
                                    val nextTag = ideaTags[(ideaTags.indexOf(card.tag) + 1) % ideaTags.size]
                                    val updatedCards = block.cards.mapIndexed { idx, c ->
                                        if (idx == cardIndex) c.copy(tag = nextTag) else c
                                    }
                                    onUpdate(block.copy(cards = updatedCards))
                                }
                            ) {
                                Text("نوع: ${card.tag}", style = MaterialTheme.typography.labelSmall)
                            }
                            if (cardIndex > 0) {
                                IconButton(
                                    onClick = {
                                        onUpdate(
                                            block.copy(
                                                cards = NoteWorkspaceOperations.moveIdeaCard(
                                                    block.cards,
                                                    cardIndex,
                                                    -1
                                                )
                                            )
                                        )
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = "تقديم الفكرة",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            if (cardIndex < block.cards.lastIndex) {
                                IconButton(
                                    onClick = {
                                        onUpdate(
                                            block.copy(
                                                cards = NoteWorkspaceOperations.moveIdeaCard(
                                                    block.cards,
                                                    cardIndex,
                                                    1
                                                )
                                            )
                                        )
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowDownward,
                                        contentDescription = "تأخير الفكرة",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            if (block.cards.size > 1) {
                                IconButton(
                                    onClick = {
                                        onUpdate(
                                            block.copy(
                                                cards = block.cards.filterIndexed { idx, _ -> idx != cardIndex }
                                            )
                                        )
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "حذف البطاقة",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        // دوائر اختيار لون البطاقة
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FolderPalette.take(6).forEach { hex ->
                                val swatch = hex.toComposeColor()
                                val isSelected = hex.equals(card.colorHex, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 22.dp else 18.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .border(
                                            width = if (isSelected) 2.dp else 0.5.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                            else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            val updatedCards = block.cards.mapIndexed { idx, c ->
                                                if (idx == cardIndex) c.copy(colorHex = hex) else c
                                            }
                                            onUpdate(block.copy(cards = updatedCards))
                                        }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = card.title,
                            onValueChange = { newTitle ->
                                val updatedCards = block.cards.mapIndexed { idx, c ->
                                    if (idx == cardIndex) c.copy(title = newTitle) else c
                                }
                                onUpdate(block.copy(cards = updatedCards))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("عنوان الفكرة") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small
                        )

                        OutlinedTextField(
                            value = card.details,
                            onValueChange = { newDetails ->
                                val updatedCards = block.cards.mapIndexed { idx, c ->
                                    if (idx == cardIndex) c.copy(details = newDetails) else c
                                }
                                onUpdate(block.copy(cards = updatedCards))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("تفاصيل الفكرة أو الروابط المرتبطة") },
                            minLines = 2,
                            maxLines = 5,
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 3) كتلة الجداول المرتبة (Structured Table Block)
// ============================================================================
@Composable
private fun TableBlockEditorCard(
    block: NoteBlock.TableBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (NoteBlock.TableBlock) -> Unit
) {
    val normalized = remember(block) { NoteWorkspaceOperations.normalizeTable(block) }
    val horizontalScrollState = rememberScrollState()

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BlockHeaderBar(
                icon = Icons.Filled.TableChart,
                label = "جدول مرتب (${normalized.rows.size}×${normalized.headers.size})",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                canDelete = canDelete,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete
            )

            OutlinedTextField(
                value = normalized.caption,
                onValueChange = { onUpdate(normalized.copy(caption = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("عنوان الجدول (اختياري)") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )

            // أزرار التحكم بأبعاد الجدول (صفوف وأعمدة)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = { onUpdate(NoteWorkspaceOperations.addTableRow(normalized)) },
                    label = { Text("+ صف") }
                )
                AssistChip(
                    onClick = { onUpdate(NoteWorkspaceOperations.removeLastTableRow(normalized)) },
                    enabled = normalized.rows.size > NoteWorkspaceOperations.MIN_TABLE_ROWS,
                    label = { Text("- صف") }
                )
                AssistChip(
                    onClick = { onUpdate(NoteWorkspaceOperations.addTableColumn(normalized)) },
                    enabled = normalized.headers.size < NoteWorkspaceOperations.MAX_TABLE_COLUMNS,
                    label = { Text("+ عمود") }
                )
                AssistChip(
                    onClick = { onUpdate(NoteWorkspaceOperations.removeLastTableColumn(normalized)) },
                    enabled = normalized.headers.size > NoteWorkspaceOperations.MIN_TABLE_COLUMNS,
                    label = { Text("- عمود") }
                )
            }

            // شبكة الجدول القابلة للتمرير الأفقي
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clip(RoundedCornerShape(10.dp))
            ) {
                // صف ترويسة الأعمدة
                Row(
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                ) {
                    normalized.headers.forEachIndexed { colIndex, headerText ->
                        OutlinedTextField(
                            value = headerText,
                            onValueChange = { newHeader ->
                                onUpdate(
                                    NoteWorkspaceOperations.updateTableHeader(
                                        normalized,
                                        colIndex,
                                        newHeader
                                    )
                                )
                            },
                            modifier = Modifier
                                .width(150.dp)
                                .padding(4.dp),
                            label = { Text("عمود ${colIndex + 1}") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraSmall
                        )
                    }
                }

                // صفوف البيانات
                normalized.rows.forEachIndexed { rowIndex, rowCells ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row {
                        rowCells.forEachIndexed { colIndex, cellText ->
                            OutlinedTextField(
                                value = cellText,
                                onValueChange = { newValue ->
                                    onUpdate(
                                        NoteWorkspaceOperations.updateTableCell(
                                            normalized,
                                            rowIndex,
                                            colIndex,
                                            newValue
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .width(150.dp)
                                    .padding(4.dp),
                                placeholder = { Text("صف ${rowIndex + 1}") },
                                singleLine = false,
                                maxLines = 3,
                                shape = MaterialTheme.shapes.extraSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 4) كتلة لوحة الرسم الحر على الملاحظة (Freehand Drawing Canvas)
// ============================================================================
private enum class DrawingToolMode(val label: String) {
    PEN("قلم"),
    HIGHLIGHTER("تظليل"),
    ERASER("ممحاة")
}

@Composable
private fun DrawingBoardEditorCard(
    block: NoteBlock.DrawingBoard,
    isDrawingActive: Boolean,
    onToggleDrawingActive: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (NoteBlock.DrawingBoard) -> Unit
) {
    val penColors = remember {
        listOf(
            "#232B27", // حبر داكن
            "#3E6B5E", // أخضر بحيري
            "#5B7FA6", // أزرق هادئ
            "#C25E52", // أحمر طوبي
            "#C79A4B", // كهرماني للتظليل
            "#7D5A7A"  // بنفسجي هادئ
        )
    }
    var toolMode by remember { mutableStateOf(DrawingToolMode.PEN) }
    var selectedColorHex by remember { mutableStateOf(penColors[1]) }
    var strokeWidthDp by remember { mutableFloatStateOf(4f) }
    val currentPoints = remember { mutableStateListOf<NormalizedPoint>() }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BlockHeaderBar(
                icon = Icons.Filled.Draw,
                label = "لوحة الرسم والتخطيط",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                canDelete = canDelete,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                trailingContent = {
                    FilterChip(
                        selected = isDrawingActive,
                        onClick = onToggleDrawingActive,
                        label = { Text(if (isDrawingActive) "وضع الرسم مفعل" else "تفعيل الرسم") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Brush,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            )

            OutlinedTextField(
                value = block.title,
                onValueChange = { onUpdate(block.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("عنوان الرسم التوضيحي") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )

            // إفصاح تدريجي: لا تظهر أدوات القلم والألوان والممحاة إلا عند تفعيل الرسم
            AnimatedVisibility(
                visible = isDrawingActive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // السطر الأول: أداة الرسم (قلم / تظليل / ممحاة) + تراجع + مسح الكل
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DrawingToolMode.entries.forEach { mode ->
                            FilterChip(
                                selected = toolMode == mode,
                                onClick = {
                                    toolMode = mode
                                    if (mode == DrawingToolMode.HIGHLIGHTER) {
                                        strokeWidthDp = 14f
                                    } else if (strokeWidthDp > 10f) {
                                        strokeWidthDp = 4f
                                    }
                                },
                                label = { Text(mode.label) }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                onUpdate(
                                    block.copy(
                                        strokes = NoteWorkspaceOperations.undoLastStroke(block.strokes)
                                    )
                                )
                            },
                            enabled = block.strokes.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "تراجع")
                        }
                        IconButton(
                            onClick = { onUpdate(block.copy(strokes = emptyList())) },
                            enabled = block.strokes.isNotEmpty()
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "مسح اللوحة")
                        }
                    }

                    // السطر الثاني: الألوان + السماكة + نمط الورقة
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        penColors.forEach { hex ->
                            val color = hex.toComposeColor()
                            val selected = selectedColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(if (selected) 26.dp else 20.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selected) 2.dp else 0.5.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface
                                        else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        selectedColorHex = hex
                                        if (toolMode == DrawingToolMode.ERASER) {
                                            toolMode = DrawingToolMode.PEN
                                        }
                                    }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        AssistChip(
                            onClick = {
                                val nextBg = CanvasBackground.entries[
                                    (CanvasBackground.entries.indexOf(block.background) + 1) %
                                        CanvasBackground.entries.size
                                ]
                                onUpdate(block.copy(background = nextBg))
                            },
                            label = { Text("ورق: ${block.background.label}") },
                            leadingIcon = {
                                Icon(Icons.Filled.GridOn, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        )
                    }
                }
            }

            // مساحة الرسم الفعلية
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PaperSurface)
                    .border(
                        width = if (isDrawingActive) 1.5.dp else 1.dp,
                        color = if (isDrawingActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isDrawingActive) {
                                Modifier
                                    .pointerInput(block.id, toolMode, selectedColorHex, strokeWidthDp, block.strokes) {
                                        detectTapGestures { offset ->
                                            if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                                            val nx = (offset.x / size.width).coerceIn(0f, 1f)
                                            val ny = (offset.y / size.height).coerceIn(0f, 1f)
                                            if (toolMode == DrawingToolMode.ERASER) {
                                                onUpdate(
                                                    block.copy(
                                                        strokes = NoteWorkspaceOperations.eraseStrokesNear(
                                                            block.strokes,
                                                            nx,
                                                            ny
                                                        )
                                                    )
                                                )
                                            } else {
                                                val dotPoint = NormalizedPoint(nx, ny).clamped()
                                                val dotStroke = DrawingStroke(
                                                    colorHex = selectedColorHex,
                                                    widthDp = strokeWidthDp,
                                                    isHighlighter = toolMode == DrawingToolMode.HIGHLIGHTER,
                                                    points = listOf(dotPoint, dotPoint)
                                                )
                                                onUpdate(block.copy(strokes = block.strokes + dotStroke))
                                            }
                                        }
                                    }
                                    .pointerInput(block.id, toolMode, selectedColorHex, strokeWidthDp, block.strokes) {
                                        detectDragGestures(
                                            onDragStart = { startOffset ->
                                                currentPoints.clear()
                                                if (size.width > 0 && size.height > 0) {
                                                    val nx = (startOffset.x / size.width).coerceIn(0f, 1f)
                                                    val ny = (startOffset.y / size.height).coerceIn(0f, 1f)
                                                    if (toolMode == DrawingToolMode.ERASER) {
                                                        onUpdate(
                                                            block.copy(
                                                                strokes = NoteWorkspaceOperations.eraseStrokesNear(
                                                                    block.strokes,
                                                                    nx,
                                                                    ny
                                                                )
                                                            )
                                                        )
                                                    } else {
                                                        currentPoints.add(NormalizedPoint(nx, ny).clamped())
                                                    }
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                if (size.width > 0 && size.height > 0) {
                                                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                                    val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                                    if (toolMode == DrawingToolMode.ERASER) {
                                                        onUpdate(
                                                            block.copy(
                                                                strokes = NoteWorkspaceOperations.eraseStrokesNear(
                                                                    block.strokes,
                                                                    nx,
                                                                    ny
                                                                )
                                                            )
                                                        )
                                                    } else {
                                                        currentPoints.add(NormalizedPoint(nx, ny).clamped())
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                if (toolMode != DrawingToolMode.ERASER && currentPoints.isNotEmpty()) {
                                                    val simplified = NoteWorkspaceOperations.simplifyPoints(
                                                        currentPoints.toList()
                                                    )
                                                    if (simplified.isNotEmpty()) {
                                                        val newStroke = DrawingStroke(
                                                            colorHex = selectedColorHex,
                                                            widthDp = strokeWidthDp,
                                                            isHighlighter = toolMode == DrawingToolMode.HIGHLIGHTER,
                                                            points = simplified
                                                        )
                                                        onUpdate(block.copy(strokes = block.strokes + newStroke))
                                                    }
                                                }
                                                currentPoints.clear()
                                            },
                                            onDragCancel = {
                                                currentPoints.clear()
                                            }
                                        )
                                    }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    drawCanvasPaperBackground(block.background)
                    block.strokes.forEach { stroke ->
                        drawNormalizedStroke(stroke)
                    }
                    if (currentPoints.isNotEmpty()) {
                        drawNormalizedStroke(
                            DrawingStroke(
                                colorHex = selectedColorHex,
                                widthDp = strokeWidthDp,
                                isHighlighter = toolMode == DrawingToolMode.HIGHLIGHTER,
                                points = currentPoints
                            )
                        )
                    }
                }

                if (block.strokes.isEmpty() && currentPoints.isEmpty()) {
                    Text(
                        text = if (isDrawingActive) "ارسم بإصبعك هنا…" else "اضغط «تفعيل الرسم» للبدء بالرسم على اللوحة",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF55605A),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/** رسم خلفية الورقة (سادة / مسطر / شبكة) داخل لوحة الرسم */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCanvasPaperBackground(
    background: CanvasBackground
) {
    val lineColor = Color(0xFFB9C2BB).copy(alpha = 0.45f)
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

/** رسم مسار واحد مطبّع على لوحة Canvas */
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

// ============================================================================
// 5) كتلة قائمة المراجعة والمهام (Checklist Block)
// ============================================================================
@Composable
private fun ChecklistBlockEditorCard(
    block: NoteBlock.ChecklistBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (NoteBlock.ChecklistBlock) -> Unit
) {
    val checkedCount = block.items.count { it.isChecked && it.text.isNotBlank() }
    val totalCount = block.items.count { it.text.isNotBlank() }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BlockHeaderBar(
                icon = Icons.Filled.Checklist,
                label = if (totalCount > 0) "نقاط المراجعة ($checkedCount/$totalCount)" else "نقاط المراجعة",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                canDelete = canDelete,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                trailingContent = {
                    TextButton(
                        onClick = {
                            onUpdate(block.copy(items = block.items + ChecklistItem(text = "")))
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("عنصر")
                    }
                }
            )

            OutlinedTextField(
                value = block.title,
                onValueChange = { onUpdate(block.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("عنوان القائمة") },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )

            block.items.forEachIndexed { itemIdx, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.isChecked,
                        onCheckedChange = { checked ->
                            val updatedItems = block.items.mapIndexed { idx, itItem ->
                                if (idx == itemIdx) itItem.copy(isChecked = checked) else itItem
                            }
                            onUpdate(block.copy(items = updatedItems))
                        }
                    )
                    OutlinedTextField(
                        value = item.text,
                        onValueChange = { newText ->
                            val updatedItems = block.items.mapIndexed { idx, itItem ->
                                if (idx == itemIdx) itItem.copy(text = newText) else itItem
                            }
                            onUpdate(block.copy(items = updatedItems))
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("بند مراجعة ${itemIdx + 1}…") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = if (item.isChecked) TextDecoration.LineThrough else null
                        ),
                        shape = MaterialTheme.shapes.extraSmall
                    )
                    if (block.items.size > 1) {
                        IconButton(
                            onClick = {
                                onUpdate(
                                    block.copy(
                                        items = block.items.filterIndexed { idx, _ -> idx != itemIdx }
                                    )
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Remove,
                                contentDescription = "حذف البند",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
