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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.common.Formatters
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.data.local.entity.FolderEntity
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

/** هدف قائمة السياق — ملف أم مجلد */
private sealed interface MenuTarget {
    data class FolderMenu(val folder: FolderEntity) : MenuTarget
    data class FileMenu(val file: FileEntity) : MenuTarget
}

/** هدف إعادة التسمية */
private sealed interface RenameTarget {
    data class FolderRename(val folder: FolderEntity) : RenameTarget
    data class FileRename(val file: FileEntity) : RenameTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    folderId: Long?,
    onOpenFolder: (Long) -> Unit,
    onBack: () -> Unit,
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

    var searchActive by remember { mutableStateOf(false) }
    var showAddFolderSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var deleteFileTarget by remember { mutableStateOf<FileEntity?>(null) }
    var menuTarget by remember { mutableStateOf<MenuTarget?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentMultiple()
    ) { uris -> viewModel.importFiles(uris) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
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
                        onLongPress = { menuTarget = MenuTarget.FolderMenu(it) }
                    )
                }
            }

            // الملفات
            if (files.isNotEmpty()) {
                item { SectionHeader(title = "الملفات (${files.size})") }
                items(files, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        onOpen = {
                            if (!FileOpener.open(context, file)) {
                                viewModel.messenger.notifyError("لا يوجد تطبيق يفتح هذا النوع من الملفات")
                            }
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(file) },
                        onLongPress = { menuTarget = MenuTarget.FileMenu(file) }
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

        // زر عائم حقيقي بقائمة خيارات (يُبنى فوق السقالة لضمان الوصول إليه)
        FloatingAddMenu(
            padding = padding,
            onNewFolder = { showAddFolderSheet = true },
            onImportFiles = { importLauncher.launch(arrayOf("*/*")) }
        )

        // قائمة السياق (ملف أو مجلد)
        ContextMenus(
            target = menuTarget,
            onOpenFolder = onOpenFolder,
            onDismiss = { menuTarget = null },
            onRename = { target ->
                renameTarget = target
                menuTarget = null
            },
            onDelete = { target ->
                when (target) {
                    is MenuTarget.FolderMenu -> deleteFolderTarget = target.folder
                    is MenuTarget.FileMenu -> deleteFileTarget = target.file
                }
                menuTarget = null
            },
            onOpenFile = { file ->
                menuTarget = null
                if (!FileOpener.open(context, file)) {
                    viewModel.messenger.notifyError("لا يوجد تطبيق يفتح هذا النوع من الملفات")
                }
            },
            onShareFile = { file ->
                menuTarget = null
                if (!FileOpener.share(context, file)) {
                    viewModel.messenger.notifyError("تعذّرت مشاركة الملف")
                }
            },
            onToggleFavorite = { file ->
                viewModel.toggleFavorite(file)
                menuTarget = null
            }
        )

        // أوراق الحوار
        if (showAddFolderSheet) {
            AddFolderSheet(
                onDismiss = { showAddFolderSheet = false },
                onCreate = { name, description, color ->
                    viewModel.createFolder(name, description, color)
                    showAddFolderSheet = false
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

        deleteFileTarget?.let { file ->
            ConfirmDialog(
                title = "حذف الملف؟",
                message = "سيُحذف \"${file.name}\" نهائياً من التخزين.",
                onConfirm = {
                    viewModel.deleteFile(file)
                    deleteFileTarget = null
                },
                onDismiss = { deleteFileTarget = null }
            )
        }
    }
}

/** الزر العائم مع قائمة (مجلد جديد / استيراد ملفات) */
@Composable
private fun FloatingAddMenu(
    padding: androidx.compose.foundation.layout.PaddingValues,
    onNewFolder: () -> Unit,
    onImportFiles: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("مجلد جديد") },
                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onNewFolder()
                    }
                )
                DropdownMenuItem(
                    text = { Text("استيراد ملفات من الجهاز") },
                    leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onImportFiles()
                    }
                )
            }
            ExtendedFloatingActionButton(
                onClick = { expanded = true },
                icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                text = { Text("إضافة") }
            )
        }
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
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongPress: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = fileIcon(file.kind),
                contentDescription = null,
                tint = fileColor(file.kind),
                modifier = Modifier.size(26.dp)
            )
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

/** قائمة السياق — تُعرض كحوار خيارات بسيط يعمل مع أي عنصر */
@Composable
private fun ContextMenus(
    target: MenuTarget?,
    onOpenFolder: (Long) -> Unit,
    onDismiss: () -> Unit,
    onRename: (RenameTarget) -> Unit,
    onDelete: (MenuTarget) -> Unit,
    onOpenFile: (FileEntity) -> Unit,
    onShareFile: (FileEntity) -> Unit,
    onToggleFavorite: (FileEntity) -> Unit
) {
    when (target) {
        null -> Unit
        is MenuTarget.FolderMenu -> {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(target.folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                onDismiss()
                                onOpenFolder(target.folder.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("فتح المجلد") }
                        TextButton(
                            onClick = { onRename(RenameTarget.FolderRename(target.folder)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("إعادة التسمية") }
                        TextButton(
                            onClick = { onDelete(target) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {}
            )
        }
        is MenuTarget.FileMenu -> {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(target.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        TextButton(
                            onClick = { onOpenFile(target.file) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("فتح") }
                        TextButton(
                            onClick = { onShareFile(target.file) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("مشاركة") }
                        TextButton(
                            onClick = { onToggleFavorite(target.file) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (target.file.isFavorite) "إزالة من المفضلة" else "إضافة للمفضلة")
                        }
                        TextButton(
                            onClick = { onRename(RenameTarget.FileRename(target.file)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("إعادة التسمية") }
                        TextButton(
                            onClick = { onDelete(target) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {}
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
    FileKind.TEXT -> Icons.Outlined.Article
    FileKind.OTHER -> Icons.Filled.InsertDriveFile
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
