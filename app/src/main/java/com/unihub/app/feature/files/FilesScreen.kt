package com.unihub.app.feature.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.common.Formatters
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.data.local.entity.FolderEntity
import com.unihub.app.data.repository.FolderTree
import com.unihub.app.feature.files.capture.AddMenuSheet
import com.unihub.app.feature.files.capture.AudioRecorderSheet
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.SectionHeader
import com.unihub.app.ui.components.UiMessagesHost
import com.unihub.app.ui.theme.FolderPalette
import com.unihub.app.ui.theme.toComposeColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** هدف إعادة التسمية */
private sealed interface RenameTarget {
    data class FolderRename(val folder: FolderEntity) : RenameTarget
    data class FileRename(val file: FileEntity) : RenameTarget
}

/** هدف النقل إلى مجلد آخر: ملفات محددة أو مجلد كامل */
private sealed interface MoveTarget {
    data class FilesMove(val files: List<FileEntity>) : MoveTarget
    data class FolderMove(val folder: FolderEntity) : MoveTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    folderId: Long?,
    onOpenFolder: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenCamera: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnlyState.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val isSelecting = selection.isNotEmpty()
    val selectedFiles = remember(files, selection) { files.filter { it.id in selection } }

    var searchActive by remember { mutableStateOf(false) }
    var showAddMenuSheet by remember { mutableStateOf(false) }
    var showAddFolderSheet by remember { mutableStateOf(false) }
    var showAudioRecorder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var menuFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<MoveTarget?>(null) }
    var pickerFolders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }

    // جلب لقطة المجلدات الكاملة عند فتح منتقي النقل فقط (لا اشتراك دائم)
    LaunchedEffect(moveTarget != null) {
        if (moveTarget != null) pickerFolders = viewModel.allFoldersOnce()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.importFiles(uris) }

    /** فتح ملف مع معالجة ذكية لكل سبب فشل */
    val openFile: (FileEntity) -> Unit = { file ->
        when (val result = FileOpener.open(context, file)) {
            FileOpResult.Success -> Unit
            FileOpResult.MissingFile -> {
                viewModel.messenger.notifyError(result.errorMessage().orEmpty())
                viewModel.healMissingRecord(file)
            }
            else -> viewModel.messenger.notifyError(result.errorMessage().orEmpty())
        }
    }

    /** مشاركة/إرسال ملف أو أكثر — مع شفاء السجلات اليتيمة عند الفقدان الكلي */
    val shareFiles: (List<FileEntity>) -> Unit = { list ->
        val result = if (list.size == 1) FileOpener.share(context, list.first())
        else FileOpener.shareMultiple(context, list)
        when (result) {
            FileOpResult.Success -> viewModel.clearSelection()
            FileOpResult.MissingFile -> {
                viewModel.messenger.notifyError(result.errorMessage().orEmpty())
                list.forEach { viewModel.healMissingRecord(it) }
            }
            else -> viewModel.messenger.notifyError(result.errorMessage().orEmpty())
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isSelecting) {
                // شريط وضع التحديد: العدد + إغلاق + تحديد الكل
                TopAppBar(
                    title = {
                        Text(
                            text = when (selection.size) {
                                1 -> "ملف واحد محدد"
                                2 -> "ملفان محددان"
                                else -> "${selection.size} ملفات محددة"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "إلغاء التحديد")
                        }
                    },
                    actions = {
                        if (selection.size < files.size && files.isNotEmpty()) {
                            IconButton(onClick = { viewModel.selectAll(files.map { it.id }) }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "تحديد الكل")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(currentFolder?.name ?: "الملفات") },
                    navigationIcon = {
                        if (folderId != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            searchActive = !searchActive
                            if (!searchActive) viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "بحث")
                        }
                        IconButton(onClick = viewModel::toggleFavoritesFilter) {
                            Icon(
                                imageVector = if (favoritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "المفضلة فقط",
                                tint = if (favoritesOnly) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (searchActive) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        placeholder = { Text("ابحث في هذا المجلد…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small
                    )
                }
            }

            if (isImporting) {
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("جارٍ استيراد الملفات…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // المجلدات
            if (folders.isNotEmpty()) {
                item { SectionHeader(title = "المجلدات") }
                item {
                    FolderGrid(
                        folders = folders,
                        onOpen = onOpenFolder,
                        onLongPress = { menuFolder = it }
                    )
                }
            }

            // الملفات
            if (files.isNotEmpty()) {
                item { SectionHeader(title = "الملفات (${files.size})") }
                items(files, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        selectionMode = isSelecting,
                        selected = file.id in selection,
                        onClick = {
                            if (isSelecting) viewModel.toggleSelection(file.id)
                            else openFile(file)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(file) },
                        // نقرة مطولة = بدء/تبديل التحديد المتعدد
                        onLongPress = { viewModel.toggleSelection(file.id) }
                    )
                }
            }

            if (folders.isEmpty() && files.isEmpty() && !isImporting) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Folder,
                        title = if (favoritesOnly) "لا ملفات مفضلة هنا" else "المجلد فارغ",
                        subtitle = if (favoritesOnly) {
                            "اضغط النجمة أعلاه لعرض كل الملفات"
                        } else {
                            "أنشئ مجلداً لمادة دراسية أو استورد ملفاتك عبر زر الإضافة"
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }

        // زر عائم يفتح قائمة الإضافة المنبثقة (يُبنى فوق السقالة لضمان الوصول إليه)
        if (!isSelecting) {
            FloatingAddButton(
                padding = padding,
                onOpenMenu = { showAddMenuSheet = true }
            )
        } else {
            // شريط إجراءات التحديد: مشاركة/إرسال + مفضلة + نقل + حذف
            Box(Modifier.fillMaxSize()) {
                SelectionBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(padding)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    singleFile = selectedFiles.singleOrNull(),
                    onOpenSingle = { file ->
                        viewModel.clearSelection()
                        openFile(file)
                    },
                    onRenameSingle = { file ->
                        viewModel.clearSelection()
                        renameTarget = RenameTarget.FileRename(file)
                    },
                    onShare = { shareFiles(selectedFiles) },
                    onFavorite = { viewModel.setFavoriteFor(selectedFiles) },
                    onMove = { moveTarget = MoveTarget.FilesMove(selectedFiles) },
                    onDelete = { confirmBulkDelete = true }
                )
            }
        }

        // قائمة سياق المجلدات (الملفات تديرها شريط التحديد)
        FolderContextMenu(
            folder = menuFolder,
            onOpenFolder = onOpenFolder,
            onDismiss = { menuFolder = null },
            onRename = { folder ->
                renameTarget = RenameTarget.FolderRename(folder)
                menuFolder = null
            },
            onMove = { folder ->
                moveTarget = MoveTarget.FolderMove(folder)
                menuFolder = null
            },
            onDelete = { folder ->
                deleteFolderTarget = folder
                menuFolder = null
            }
        )

        // أوراق الحوار
        // قائمة الإضافة المنبثقة: 4 خيارات (رفع ملف، مجلد، صورة بالكاميرا، تسجيل صوتي)
        if (showAddMenuSheet) {
            AddMenuSheet(
                onDismiss = { showAddMenuSheet = false },
                onImportFiles = {
                    showAddMenuSheet = false
                    importLauncher.launch("*/*") // السلوك السابق كما هو
                },
                onNewFolder = {
                    showAddMenuSheet = false
                    showAddFolderSheet = true // السلوك السابق كما هو
                },
                onCapturePhoto = {
                    showAddMenuSheet = false
                    onOpenCamera() // وجهة كاميرا بملء الشاشة فوق شاشة الملفات
                },
                onRecordAudio = {
                    showAddMenuSheet = false
                    showAudioRecorder = true
                }
            )
        }

        if (showAddFolderSheet) {
            AddFolderSheet(
                onDismiss = { showAddFolderSheet = false },
                onCreate = { name, description, color ->
                    viewModel.createFolder(name, description, color)
                    showAddFolderSheet = false
                }
            )
        }

        // المسجل الصوتي — الحفظ يمر عبر ViewModel ليتخذ رسائل النجاح/الفشل نمطاً واحداً
        if (showAudioRecorder) {
            AudioRecorderSheet(
                onDismiss = { showAudioRecorder = false },
                onSave = { file, name ->
                    showAudioRecorder = false
                    viewModel.saveAudioRecording(file, name)
                }
            )
        }

        renameTarget?.let { target ->
            RenameSheet(
                initialName = when (target) {
                    is RenameTarget.FolderRename -> target.folder.name
                    is RenameTarget.FileRename -> target.file.name
                },
                onDismiss = { renameTarget = null },
                onSave = { newName ->
                    when (target) {
                        is RenameTarget.FolderRename ->
                            viewModel.renameFolder(target.folder, newName)
                        is RenameTarget.FileRename ->
                            viewModel.renameFile(target.file, newName)
                    }
                    renameTarget = null
                }
            )
        }

        deleteFolderTarget?.let { folder ->
            ConfirmDialog(
                title = "حذف المجلد؟",
                message = "سيُحذف المجلد \"${folder.name}\" مع كل المجلدات والملفات داخله نهائياً.",
                onConfirm = {
                    viewModel.deleteFolder(folder)
                    deleteFolderTarget = null
                },
                onDismiss = { deleteFolderTarget = null }
            )
        }

        // تأكيد الحذف الجماعي من وضع التحديد (يشمل حذف ملف واحد محدد)
        if (confirmBulkDelete) {
            ConfirmDialog(
                title = if (selectedFiles.size == 1) "حذف الملف؟"
                else "حذف " + Formatters.fileCountLabel(selectedFiles.size) + "؟",
                message = "ستُحذف الملفات المحددة نهائياً من التخزين ولا يمكن التراجع.",
                onConfirm = {
                    viewModel.deleteFiles(selectedFiles)
                    confirmBulkDelete = false
                },
                onDismiss = { confirmBulkDelete = false }
            )
        }

        // منتقي «نقل إلى مجلد» — مشترك بين نقل الملفات المحددة ونقل مجلد كامل
        moveTarget?.let { target ->
            MoveToFolderSheet(
                title = when (target) {
                    is MoveTarget.FilesMove ->
                        "نقل " + Formatters.fileCountLabel(target.files.size)
                    is MoveTarget.FolderMove -> "نقل المجلد"
                },
                folders = pickerFolders,
                blockedFolderIds = when (target) {
                    is MoveTarget.FilesMove -> emptySet()
                    is MoveTarget.FolderMove ->
                        FolderTree.subtreeIds(pickerFolders, target.folder.id)
                },
                // الموقع الحالي: معطّل كوجهة لأن «النقل إليه» عملية بلا أثر
                currentLocationId = when (target) {
                    is MoveTarget.FilesMove -> folderId
                    is MoveTarget.FolderMove -> target.folder.parentId
                },
                onDismiss = { moveTarget = null },
                onConfirm = { destination ->
                    when (target) {
                        is MoveTarget.FilesMove -> viewModel.moveFiles(target.files, destination)
                        is MoveTarget.FolderMove -> viewModel.moveFolder(target.folder, destination)
                    }
                    moveTarget = null
                }
            )
        }
    }
}

/**
 * الزر العائم للإضافة — يفتح قائمة منبثقة سفلية (AddMenuSheet) بأربعة خيارات
 * بدل القائمة المنسدلة القديمة: رفع ملف، مجلد جديد، التقاط صورة، تسجيل صوتي.
 */
@Composable
private fun FloatingAddButton(
    padding: androidx.compose.foundation.layout.PaddingValues,
    onOpenMenu: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        ExtendedFloatingActionButton(
            onClick = onOpenMenu,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(padding)
                .padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("إضافة") }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FolderGrid(
    folders: List<FolderEntity>,
    onOpen: (Long) -> Unit,
    onLongPress: (FolderEntity) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        folders.forEach { folder ->
            ElevatedCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .width(150.dp)
                    .combinedClickable(
                        onClick = { onOpen(folder.id) },
                        onLongClick = { onLongPress(folder) }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(folder.color.toComposeColor(), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongPress: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                // مؤشر التحديد بدل أيقونة النوع
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle
                    else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (selected) "محدد" else "غير محدد",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = fileIcon(file.kind),
                    contentDescription = null,
                    tint = fileColor(file.kind),
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.extension.uppercase()} • ${Formatters.fileSize(file.size)} • " +
                        dateFormat.format(Date(file.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!selectionMode) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (file.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (file.isFavorite) "إزالة من المفضلة" else "إضافة للمفضلة",
                        tint = if (file.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * شريط إجراءات وضع التحديد — يظهر أسفل الشاشة بدل الزر العائم:
 * مشاركة/إرسال، تفضيل جماعي، حذف (بتأكيد)، وللملف الواحد: فتح وإعادة تسمية.
 */
@Composable
private fun SelectionBar(
    modifier: Modifier = Modifier,
    singleFile: FileEntity?,
    onOpenSingle: (FileEntity) -> Unit,
    onRenameSingle: (FileEntity) -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (singleFile != null) {
                IconButton(onClick = { onOpenSingle(singleFile) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "فتح الملف",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "مشاركة أو إرسال",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "إضافة للمفضلة",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMove) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = "نقل إلى مجلد",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            if (singleFile != null) {
                IconButton(onClick = { onRenameSingle(singleFile) }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "إعادة التسمية",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "حذف المحدد",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** قائمة سياق المجلد — نقرة مطولة على مجلد (الملفات لها وضع التحديد المتعدد) */
@Composable
private fun FolderContextMenu(
    folder: FolderEntity?,
    onOpenFolder: (Long) -> Unit,
    onDismiss: () -> Unit,
    onRename: (FolderEntity) -> Unit,
    onMove: (FolderEntity) -> Unit,
    onDelete: (FolderEntity) -> Unit
) {
    if (folder == null) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenFolder(folder.id)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("فتح المجلد") }
                TextButton(
                    onClick = { onRename(folder) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إعادة التسمية") }
                TextButton(
                    onClick = { onMove(folder) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("نقل إلى مجلد…") }
                TextButton(
                    onClick = { onDelete(folder) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * منتقي وجهة النقل: ورقة سفلية واحدة تُبحر في شجرة المجلدات — نقر على مجلد
 * للدخول إليه، زر صعود للمستوى الأعلى، وزر «نقل إلى هنا» يثبّت الوجهة الحالية.
 * الوجهات المحظورة (المجلد المنقول وأحفاده) تظهر باهتة غير قابلة للنقر،
 * والمستوى المطابق لموقع المصدر يُعطَّل فيه زر التأكيد لأنه عملية بلا أثر.
 */
@Composable
private fun MoveToFolderSheet(
    title: String,
    folders: List<FolderEntity>,
    blockedFolderIds: Set<Long>,
    currentLocationId: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit
) {
    var browseId by remember { mutableStateOf(currentLocationId) }
    val browseFolder = remember(folders, browseId) {
        folders.firstOrNull { it.id == browseId }
    }
    val children = remember(folders, browseId) {
        folders.filter { it.parentId == browseId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    val onBlockedLevel = browseId != null && browseId in blockedFolderIds
    val sameAsSource = browseId == currentLocationId

    AppSheet(
        title = title,
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(
                onClick = { onConfirm(browseId) },
                enabled = !onBlockedLevel && !sameAsSource
            ) { Text("نقل إلى هنا") }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { browseId = browseFolder?.parentId },
                enabled = browseId != null
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "المستوى الأعلى",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = browseFolder?.name ?: "المستوى الرئيسي",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        when {
            onBlockedLevel -> Text(
                "لا يمكن نقل المجلد داخل نفسه أو داخل أحد فروعه",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            sameAsSource -> Text(
                "العناصر موجودة في هذا المجلد بالفعل",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (children.isEmpty()) {
            Text(
                "لا مجلدات فرعية هنا",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(children, key = { it.id }) { folder ->
                    FolderPickerRow(
                        folder = folder,
                        blocked = folder.id in blockedFolderIds,
                        onClick = { browseId = folder.id }
                    )
                }
            }
        }
    }
}

/** صف واحد في منتقي النقل: لون المجلد + اسمه، باهت إن كان وجهة محظورة */
@Composable
private fun FolderPickerRow(
    folder: FolderEntity,
    blocked: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = !blocked,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (blocked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else folder.color.toComposeColor(),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AddFolderSheet(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, color: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(FolderPalette.first()) }

    AppSheet(
        title = "مجلد جديد",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(
                onClick = { onCreate(name, description, color) },
                enabled = name.isNotBlank()
            ) { Text("إنشاء") }
        }
    ) {
        Field(label = "اسم المجلد (المادة)", value = name, onValueChange = { name = it })
        Field(
            label = "وصف (اختياري)",
            value = description,
            onValueChange = { description = it },
            singleLine = false
        )
        Text("اللون", style = MaterialTheme.typography.labelLarge)
        FolderColorPicker(selected = color, onSelect = { color = it })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderColorPicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FolderPalette.forEach { hex ->
            val isSelected = hex == selected
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(hex.toComposeColor(), CircleShape)
                    .combinedClickableSafely(onClick = { onSelect(hex) }),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color.White, CircleShape)
                    )
                }
            }
        }
    }
}

/** clickable بسيط لاختيار الألوان */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSafely(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

@Composable
private fun RenameSheet(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AppSheet(
        title = "إعادة التسمية",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text("حفظ")
            }
        }
    ) {
        Field(label = "الاسم الجديد", value = name, onValueChange = { name = it })
    }
}

private fun fileIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.PDF -> Icons.Filled.PictureAsPdf
    FileKind.DOCUMENT -> Icons.Filled.Description
    FileKind.SPREADSHEET -> Icons.Filled.GridOn
    FileKind.PRESENTATION -> Icons.Filled.Slideshow
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.AUDIO -> Icons.Filled.Audiotrack
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.TEXT -> Icons.AutoMirrored.Outlined.Article
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun fileColor(kind: FileKind): Color = when (kind) {
    FileKind.PDF -> Color(0xFFB05243)
    FileKind.DOCUMENT -> Color(0xFF5B7FA6)
    FileKind.SPREADSHEET -> Color(0xFF4C8B6E)
    FileKind.PRESENTATION -> Color(0xFFC79A4B)
    FileKind.IMAGE -> Color(0xFF7D5A7A)
    FileKind.VIDEO -> Color(0xFF5B6478)
    FileKind.AUDIO -> Color(0xFF46707D)
    FileKind.ARCHIVE -> Color(0xFF7A6A4F)
    FileKind.TEXT -> Color(0xFF6E7A4E)
    FileKind.OTHER -> Color(0xFF8A8F8A)
}
