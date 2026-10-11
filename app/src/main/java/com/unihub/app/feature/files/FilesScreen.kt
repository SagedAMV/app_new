package com.unihub.app.feature.files

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
import com.unihub.app.feature.share.InboxItem
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.FeatureHeroCard
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.SectionHeader
import com.unihub.app.ui.components.UiMessagesHost
import com.unihub.app.ui.theme.FolderPalette
import com.unihub.app.ui.theme.toComposeColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** حامل بسيط لآخر قيمة غير فارغة — يُبقي محتوى العناصر الخارجة ثابتاً أثناء حركة الخروج. */
private class LastValue<T>(var value: T)

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
    onOpenCloud: () -> Unit,
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

    // مفاتيح العناصر التي أنهت حركة دخولها: لا تُعاد الحركة كلما خرج العنصر من الشاشة ورجع بالتمرير
    val played = remember { mutableSetOf<Any>() }
    // آخر تحديد غير فارغ: يبقى معروضاً في شريط التحديد/العنوان أثناء حركة الخروج
    val lastSelectionCount = remember { LastValue(0) }
    val lastSelectedFiles = remember { LastValue<List<FileEntity>>(emptyList()) }
    SideEffect {
        if (selection.isNotEmpty()) lastSelectionCount.value = selection.size
        if (selectedFiles.isNotEmpty()) lastSelectedFiles.value = selectedFiles
    }
    val shownSelectionCount = if (selection.isNotEmpty()) selection.size else lastSelectionCount.value
    val barFiles = if (selectedFiles.isNotEmpty()) selectedFiles else lastSelectedFiles.value

    // صندوق المشاركة: ملفات واردة من تطبيقات النظام تنتظر اختيار مجلدها
    val inboxItems by viewModel.inboxItems.collectAsStateWithLifecycle()
    val recentlyPlaced by viewModel.recentlyPlaced.collectAsStateWithLifecycle()

    // السحابة هنا: ما تحتاجه هذه الشاشة فقط (شارة الزر المضيء وحالة الرفع) —
    // أما تصفح السحابة وتنزيلها فانتقل إلى الشاشة المستقلة CloudFilesScreen
    val cloudTransfer by viewModel.cloudTransferState.collectAsStateWithLifecycle()
    val cloudQueue by viewModel.cloudTransfers.collectAsStateWithLifecycle()
    val availableRemoteFiles by viewModel.availableRemoteFiles.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val uploadPlan by viewModel.uploadPlan.collectAsStateWithLifecycle()
    val uploadReport by viewModel.cloudUploadReport.collectAsStateWithLifecycle()

    var searchActive by remember { mutableStateOf(false) }
    var showAddMenuSheet by remember { mutableStateOf(false) }
    var showInboxSheet by remember { mutableStateOf(false) }
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

    // جولة تعليمات.md (دمج الجولتين — معالجة واحدة لا اثنتين): زر الرجوع الخاص
    // بالنظام يفكّك طبقة واحدة في كل ضغطة عبر السلّم الموحّد CloudBackPolicy —
    // التحديد الجماعي أولاً، ثم شريط البحث، ولا خروج من الشاشة وأحدهما نشط.
    // عمق المجلدات هنا راوتات تنقل مستقلة (FilesRoute) فيتولاه الرجوع الافتراضي
    // بعد تصفية الطبقتين.
    BackHandler(enabled = isSelecting || searchActive) {
        when (CloudBackPolicy.step(hasSelection = isSelecting, isInsideFolder = false)) {
            BackStep.CLEARED_SELECTION -> viewModel.clearSelection()
            BackStep.WENT_UP -> onBack() // لا تنطبق: لا عمق داخل الشاشة (راوتات تنقل)
            BackStep.EXITED ->
                if (searchActive) {
                    searchActive = false
                    viewModel.setSearchQuery("")
                } else onBack()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.importFiles(uris) }

    val folderImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.importDeviceFolder(uri)
        }
    }

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
            // تبديل شريط التحديد ↔ الشريط العادي بانزلاق عمودي وتلاشٍ بدل القفز المفاجئ
            AnimatedContent(
                targetState = isSelecting,
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(300)) { -it / 2 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 2 })
                },
                label = "files-top-bar"
            ) { selecting ->
                if (selecting) {
                    // شريط وضع التحديد: العدد + إغلاق + تحديد الكل
                    TopAppBar(
                        title = {
                            // العدّاد يتبدّل بانزلاق عمودي: للأعلى عند الزيادة وللأسفل عند النقص
                            AnimatedContent(
                                targetState = shownSelectionCount,
                                transitionSpec = {
                                    if (targetState > initialState) {
                                        (slideInVertically(tween(260)) { it } + fadeIn(tween(200))) togetherWith
                                            (slideOutVertically(tween(260)) { -it } + fadeOut(tween(140)))
                                    } else {
                                        (slideInVertically(tween(260)) { -it } + fadeIn(tween(200))) togetherWith
                                            (slideOutVertically(tween(260)) { it } + fadeOut(tween(140)))
                                    }
                                },
                                label = "selection-count"
                            ) { count ->
                                Text(
                                    text = when (count) {
                                        1 -> "ملف واحد محدد"
                                        2 -> "ملفان محددان"
                                        else -> "$count ملفات محددة"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
                        title = {
                            // اسم المجلد يصل بعد التحميل: ينزلق بدل أن يستبدل «الملفات» فجأة
                            AnimatedContent(
                                targetState = currentFolder?.name ?: "الملفات",
                                transitionSpec = {
                                    (fadeIn(tween(280, delayMillis = 70)) + slideInVertically(tween(320)) { it / 2 }) togetherWith
                                        (fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 2 })
                                },
                                label = "files-title"
                            ) { title -> Text(title) }
                        },
                        navigationIcon = {
                            if (folderId != null) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                                }
                            }
                        },
                        actions = {
                            if (currentFolder != null) IconButton(onClick = {
                                currentFolder?.let { viewModel.previewFolderUpload(it.id, it.name) }
                            }, enabled = !cloudTransfer.active) {
                                Icon(Icons.Filled.CloudUpload, contentDescription = "رفع المجلد إلى السحابة")
                            }
                            GlowingCloudTopBarButton(
                                newFilesCount = availableRemoteFiles.size,
                                onClick = onOpenCloud
                            )
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
            if (folderId == null && !isSelecting && !searchActive) {
                item(key = "files_overview") {
                    FeatureHeroCard(
                        title = "مكتبتك الجامعية",
                        subtitle = "ملفاتك ومجلداتك مرتبة محلياً، مع إمكانية الوصول إلى السحابة.",
                        icon = Icons.Filled.Folder,
                        badge = if (isOnline) "${files.size} ملف" else "دون اتصال",
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .reveal("hero", 0, played, RevealStyle.Hero)
                    )
                }
            }

            // الكتلة العلوية المتحركة: شريط الملفات المشتركة + حقل البحث + مؤشر الاستيراد.
            // عنصر واحد دائم الوجود (كان شريط الوارد وحده كذلك) فلا يتغيّر عدد الفواصل.
            item(key = "top_stack") {
                Column {
                    // شريط وضع الملفات المشتركة — يظهر بأي مجلد دخلته لتضعها فيه
                    AnimatedVisibility(
                        visible = inboxItems.isNotEmpty() && !isSelecting,
                        enter = fadeIn(tween(280)) + expandVertically(spring(dampingRatio = 0.8f, stiffness = 380f)),
                        exit = fadeOut(tween(220)) + shrinkVertically(tween(260))
                    ) {
                        InboxPlacementBanner(
                            count = inboxItems.size,
                            onPlace = viewModel::placeSharedFilesHere
                        )
                    }

                    // حقل البحث ينفتح بتمدّد عمودي ناعم وينطوي عند الإغلاق
                    AnimatedVisibility(
                        visible = searchActive,
                        enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 420f)) +
                            fadeIn(tween(240)) + slideInVertically(tween(280)) { -it / 2 },
                        exit = shrinkVertically(tween(220)) + fadeOut(tween(160))
                    ) {
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

                    AnimatedVisibility(
                        visible = isImporting,
                        enter = fadeIn(tween(220)) + expandVertically(tween(260)),
                        exit = fadeOut(tween(180)) + shrinkVertically(tween(220))
                    ) {
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
            }

            // المجلدات
            if (folders.isNotEmpty()) {
                item(key = "folders_header") {
                    SectionHeader(
                        title = "المجلدات",
                        modifier = Modifier.reveal("header_folders", 0, played, RevealStyle.Header)
                    )
                }
                item(key = "folders_grid") {
                    FolderGrid(
                        folders = folders,
                        played = played,
                        onOpen = onOpenFolder,
                        onLongPress = { menuFolder = it }
                    )
                }
            }

            // الملفات
            if (files.isNotEmpty()) {
                item(key = "files_header") {
                    SectionHeader(
                        title = "الملفات (${files.size})",
                        modifier = Modifier.reveal("header_files", 0, played, RevealStyle.Header)
                    )
                }
                itemsIndexed(files, key = { _, file -> file.id }) { index, file ->
                    // الصور تدخل بتكبير نابض، والملفات الأخرى بانزلاق أفقي — لكل نوع حركته
                    val style = if (file.kind == FileKind.IMAGE) RevealStyle.Image else RevealStyle.File
                    // الملفات الموضوعة للتو من صندوق المشاركة تدخل بتلاشي واضح
                    FadeInIfHighlighted(highlight = file.id in recentlyPlaced) {
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
                            onLongPress = { viewModel.toggleSelection(file.id) },
                            // animateItem: انزلاق سلس عند الحذف/الفلترة/إعادة الترتيب
                            modifier = Modifier
                                .animateItem(fadeInSpec = null)
                                .reveal("file_${file.id}", index, played, style)
                        )
                    }
                }
            }

            if (folders.isEmpty() && files.isEmpty() && !isImporting) {
                item(key = "empty_state") {
                    EmptyState(
                        icon = Icons.Filled.Folder,
                        title = if (favoritesOnly) "لا ملفات مفضلة هنا" else "المجلد فارغ",
                        subtitle = if (favoritesOnly) {
                            "اضغط النجمة أعلاه لعرض كل الملفات"
                        } else {
                            "أنشئ مجلداً لمادة دراسية أو ارفع ملفاتك من زر الإضافة"
                        },
                        modifier = Modifier.reveal("empty_$favoritesOnly", 0, played, RevealStyle.Hero)
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
        }

        // الأزرار العائمة: زر الإضافة + أيقونة الملفات الواردة عبر المشاركة فقط.
        // جولة تعليمات.md: أزيل الزر السحابي السفلي — فتح السحابة محصور في زرّي
        // الشريط العلوي (أعلى هذه الشاشة وأعلى الشاشة الرئيسية).
        // يظهر الزر بقفزة نابضة عند دخول الشاشة، ويغوص عند بدء التحديد.
        FilesFloatingActions(
            visible = !isSelecting,
            padding = padding,
            inboxCount = inboxItems.size,
            onOpenInbox = { showInboxSheet = true },
            onOpenMenu = { showAddMenuSheet = true }
        )

        // شريط إجراءات التحديد: مشاركة/إرسال + مفضلة + نقل + حذف — يصعد من الأسفل بنبضة نابضة
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = isSelecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(padding),
                enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 380f)) { it } +
                    fadeIn(tween(180)) + scaleIn(initialScale = 0.9f, animationSpec = tween(260)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160))
            ) {
                SelectionBar(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    singleFile = barFiles.singleOrNull(),
                    onOpenSingle = { file ->
                        viewModel.clearSelection()
                        openFile(file)
                    },
                    onRenameSingle = { file ->
                        viewModel.clearSelection()
                        renameTarget = RenameTarget.FileRename(file)
                    },
                    onUpload = { viewModel.previewSelectedUpload(selectedFiles) },
                    uploadEnabled = !cloudTransfer.active && !isImporting,
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
            onUpload = { folder ->
                viewModel.previewFolderUpload(folder.id, folder.name)
                menuFolder = null
            },
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
                onImportFolder = { showAddMenuSheet = false; folderImportLauncher.launch(null) },
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

        // لوحة صندوق المشاركة — الملفات الواردة من تطبيقات النظام
        if (showInboxSheet) {
            InboxSheet(
                items = inboxItems,
                onDismiss = { showInboxSheet = false },
                onRemove = viewModel::removeInboxItem
            )
        }

        uploadPlan?.let { plan ->
            CloudUploadConfirmationSheet(plan, isOnline, cloudTransfer, uploadReport,
                onConfirm = viewModel::confirmUpload,
                onDismiss = viewModel::closeUploadPlan,
                queue = cloudQueue,
                onPause = viewModel::pauseCloudTransfers,
                onResume = viewModel::resumeCloudTransfers,
                onRetryFailed = viewModel::retryCloudTransfers,
                onDropPending = viewModel::cancelCloudQueued)
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
                message = "سيُحذف المجلد «${folder.name}» وكل محتوياته.",
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
                message = "ستُحذف الملفات المحددة نهائياً.",
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
 * الأزرار العائمة أسفل شاشة الملفات:
 *  - زر «إضافة» الموسع يفتح قائمة الإضافة المنبثقة.
 *  - أيقونة صغيرة جنبه تظهر فقط عندما توجد ملفات واردة عبر مشاركة النظام
 *    (واتساب وغيره)، بشارة تعرض عددها — نقرة تفتح لوحة الوارد.
 */
@Composable
private fun FilesFloatingActions(
    visible: Boolean,
    padding: androidx.compose.foundation.layout.PaddingValues,
    inboxCount: Int,
    onOpenInbox: () -> Unit,
    onOpenMenu: () -> Unit
) {
    // دخول الشاشة: الأزرار تتأخر قليلاً ثم تقفز بنبضة نابضة بعد استقرار المحتوى
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(280)
        arrived = true
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible && arrived,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(padding)
                .padding(16.dp),
            enter = scaleIn(initialScale = 0.3f, animationSpec = spring(dampingRatio = 0.5f, stiffness = 380f)) +
                fadeIn(tween(200)) +
                slideInVertically(spring(dampingRatio = 0.7f, stiffness = 380f)) { it / 2 },
            exit = scaleOut(targetScale = 0.6f, animationSpec = tween(160)) +
                fadeOut(tween(140)) +
                slideOutVertically(tween(180)) { it / 2 }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                AnimatedVisibility(
                    visible = inboxCount > 0,
                    enter = fadeIn(tween(320)) + scaleIn(initialScale = 0.5f, animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f)),
                    exit = fadeOut(tween(300)) + scaleOut(targetScale = 0.7f, animationSpec = tween(260))
                ) {
                    BadgedBox(
                        badge = {
                            Badge { Text(inboxCount.toString()) }
                        }
                    ) {
                        SmallFloatingActionButton(
                            onClick = onOpenInbox,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.DriveFileMove,
                                contentDescription = "الملفات الواردة عبر المشاركة"
                            )
                        }
                    }
                }

                ExtendedFloatingActionButton(
                    onClick = onOpenMenu,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("إضافة") }
                )
            }
        }
    }
}

/**
 * شريط «نقل إلى هذا المجلد»: يظهر أعلى أي مجلد عندما تكون هناك ملفات واردة
 * عبر المشاركة — ضغطة واحدة تنسخها كلها إلى المجلد المفتوح.
 */
@Composable
private fun InboxPlacementBanner(
    count: Int,
    onPlace: () -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.DriveFileMove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = Formatters.fileCountLabel(count) + " واردة عبر المشاركة",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = onPlace) {
                Text("نقل إلى هذا المجلد")
            }
        }
    }
}

/** غلاف تلاشي: يُظهر المحتوى بانميشن دخول عندما يكون مُبرزاً، ومباشرة وإلا */
@Composable
private fun FadeInIfHighlighted(highlight: Boolean, content: @Composable () -> Unit) {
    if (highlight) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 18 } + scaleIn(initialScale = 0.97f, animationSpec = tween(420)),
            // AnimatedVisibility يتوقع لامدا بمستقبل AnimatedVisibilityScope،
            // فغلّفنا محتوى الدالة (Function0) بلامدا مستقبِلة تتوافق مع التوقيع.
            content = { content() }
        )
    } else {
        content()
    }
}

/**
 * لوحة صندوق المشاركة: قائمة الملفات الواردة من تطبيقات النظام مع إمكانية
 * إهمال أي ملف. العناصر تدخل بتلاشي متدرج — «انميشن دخولها بشكل تلاشي».
 * الوضع الفعلي في المجلدات يتم عبر شريط «نقل إلى هذا المجلد».
 */
@Composable
private fun InboxSheet(
    items: List<InboxItem>,
    onDismiss: () -> Unit,
    onRemove: (InboxItem) -> Unit
) {
    AppSheet(
        title = "الملفات الواردة عبر المشاركة",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        },
        content = {
            if (items.isEmpty()) {
                Text(
                    "لا ملفات واردة حالياً — شارك ملفات من أي تطبيق وستظهر هنا.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "ادخل المجلد الذي تريده واضغط «نقل إلى هذا المجلد» — أو أهمل ما لا تريده.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEachIndexed { index, item ->
                        InboxSheetRow(item = item, index = index, onRemove = { onRemove(item) })
                    }
                }
            }
        }
    )
}

/** صف ملف واحد في لوحة الصندوق — دخول متدرج بتلاشي + تكبير */
@Composable
private fun InboxSheetRow(
    item: InboxItem,
    index: Int,
    onRemove: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120L + index * 70L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(360)) + slideInVertically(tween(360)) { it / 20 } + scaleIn(initialScale = 0.92f, animationSpec = tween(360))
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = fileIcon(item.kind),
                    contentDescription = null,
                    tint = fileColor(item.kind),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.extension.uppercase()} • ${Formatters.fileSize(item.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "إهمال الملف المشترك",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderGrid(
    folders: List<FolderEntity>,
    played: MutableSet<Any>,
    onOpen: (Long) -> Unit,
    onLongPress: (FolderEntity) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        folders.forEachIndexed { index, folder ->
            key(folder.id) {
                FolderCard(
                    folder = folder,
                    index = index,
                    played = played,
                    onOpen = onOpen,
                    onLongPress = onLongPress
                )
            }
        }
    }
}

/**
 * بطاقة مجلد واحدة:
 *  - دخول: قفزة نابضة من الأسفل بتدرّج زمني، ثم تنبثق نقطة اللون بنبضة.
 *  - لمس: تنضغط البطاقة بنابض.
 *  - نقر: تتسع موجة لون المجلد من تحت الإصبع، تنبض النقطة، وترتفع البطاقة
 *    لحظة قبل الانتقال — ثم يتولى الانتقال انزلاق الشاشة.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: FolderEntity,
    index: Int,
    played: MutableSet<Any>,
    onOpen: (Long) -> Unit,
    onLongPress: (FolderEntity) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 520f),
        label = "folder-press"
    )
    val wash = rememberTapWash(interactionSource)
    val lift = remember { Animatable(0f) }
    val dotPulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    val color = folder.color.toComposeColor()

    // يُقرأ قبل rememberReveal لأن الأخيرة تسجّل المفتاح في المجموعة
    val wasPlayed = remember(folder.id) { "folder_${folder.id}" in played }
    val reveal = rememberReveal("folder_${folder.id}", index, played, RevealStyle.Folder)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dotIn = remember(folder.id) { Animatable(if (wasPlayed) 1f else 0f) }
    LaunchedEffect(folder.id) {
        if (dotIn.value < 1f) {
            delay(index.coerceAtMost(10) * 45L + 260L)
            dotIn.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 380f))
        }
    }

    ElevatedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .width(150.dp)
            .revealTransform(RevealStyle.Folder, reveal, rtl)
            .graphicsLayer {
                val s = pressScale * (1f + 0.07f * lift.value)
                scaleX = s
                scaleY = s
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (!opening) {
                        opening = true
                        scope.launch {
                            launch { wash.play() }
                            launch {
                                dotPulse.animateTo(1f, tween(160))
                                dotPulse.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 400f))
                            }
                            lift.animateTo(1f, tween(140))
                            onOpen(folder.id)
                            // إن لم تغادر الشاشة (إلغاء الانتقال) تعود البطاقة لحجمها
                            delay(500)
                            lift.animateTo(0f, tween(220))
                            opening = false
                        }
                    }
                },
                onLongClick = { onLongPress(folder) }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tapWash(wash, color)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        val s = dotIn.value * (1f + 0.9f * dotPulse.value)
                        scaleX = s
                        scaleY = s
                    }
                    .background(color, CircleShape)
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

/**
 * صف ملف واحد:
 *  - دخول: ملف عادي = انزلاق أفقي، صورة = تكبير نابض (من revealTransform في الاستدعاء).
 *  - مصغّرة الصورة تتكشّف بعد تحميلها؛ بقية الأنواع تظهر أيقونتها في مربع ملوّن.
 *  - لمس: ينضغط الصف بنابض. نقر: موجة لون النوع من موضع الإصبع + قفزة للأيقونة،
 *    ثم يُفتح الملف بعد لحظة قصيرة ليُرى الانميشن.
 *  - التحديد: الأيقونة ↔ دائرة الاختيار بقفزة نابضة، ولون الصف يتدرّج، والنجمة تنطوي.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.96f
            selected -> 0.985f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 480f),
        label = "file-row-scale"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        animationSpec = tween(240),
        label = "file-row-container"
    )
    val accent = fileColor(file.kind)
    val wash = rememberTapWash(interactionSource)
    val pulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var launching by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = rowScale
                scaleY = rowScale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (selectionMode) {
                        onClick() // تبديل التحديد فوري، وحركته في الأيقونة واللون
                    } else if (!launching) {
                        launching = true
                        scope.launch {
                            launch { wash.play() }
                            launch {
                                pulse.animateTo(1f, tween(120))
                                pulse.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 420f))
                            }
                            delay(150) // مهلة قصيرة ليظهر الانميشن قبل مغادرة التطبيق إلى عارض الملف
                            onClick()
                            launching = false
                        }
                    }
                },
                onLongClick = onLongPress
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tapWash(wash, accent)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = selectionMode,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(
                            initialScale = 0.4f,
                            animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f)
                        )) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.4f, animationSpec = tween(120)))
                    },
                    label = "file-leading"
                ) { selecting ->
                    if (selecting) SelectionCheck(selected)
                    else FileLeading(file = file, accent = accent, pulse = pulse)
                }
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
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.5f) + expandHorizontally(),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.5f) + shrinkHorizontally()
            ) {
                AnimatedStarButton(isFavorite = file.isFavorite, onToggle = onToggleFavorite)
            }
        }
    }
}

/** أيقونة النوع أو مصغّرة الصورة — تقفز وتميل قليلاً عند النقر على الصف. */
@Composable
private fun FileLeading(
    file: FileEntity,
    accent: Color,
    pulse: Animatable<Float, AnimationVector1D>
) {
    val pop = Modifier.graphicsLayer {
        val s = 1f + 0.22f * pulse.value
        scaleX = s
        scaleY = s
        rotationZ = -8f * pulse.value
    }
    if (file.kind == FileKind.IMAGE) {
        ImageThumbnail(path = file.filePath, sizeBytes = file.size, modifier = pop) {
            Icon(
                imageVector = fileIcon(file.kind),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp)
            )
        }
    } else {
        Box(
            modifier = pop
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fileIcon(file.kind),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/** دائرة الاختيار: تقفز بنبضة نابضة عند التحديد/إلغائه. */
@Composable
private fun SelectionCheck(selected: Boolean) {
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (scaleIn(initialScale = 0.3f, animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f)) +
                fadeIn(tween(120))) togetherWith
                (scaleOut(targetScale = 0.3f, animationSpec = tween(90)) + fadeOut(tween(90)))
        },
        label = "selection-check"
    ) { isSelected ->
        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (isSelected) "محدد" else "غير محدد",
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp)
        )
    }
}

/** نجمة المفضلة: عند التفضيل تدور وتقفز بنابض، وعند الإزالة تنكمش نبضة ثم ترتد. */
@Composable
private fun AnimatedStarButton(isFavorite: Boolean, onToggle: () -> Unit) {
    val pop = remember { Animatable(1f) }
    val spin = remember { Animatable(0f) }
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(isFavorite) {
        if (firstRun) {
            firstRun = false
            return@LaunchedEffect
        }
        if (isFavorite) {
            pop.snapTo(0.4f)
            spin.snapTo(-70f)
            coroutineScope {
                launch { pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 380f)) }
                launch { spin.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
            }
        } else {
            pop.animateTo(0.75f, tween(70))
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
        }
    }
    val tint by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        animationSpec = tween(220),
        label = "star-tint"
    )
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (isFavorite) "إزالة من المفضلة" else "إضافة للمفضلة",
            tint = tint,
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = spin.value
            }
        )
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
    onUpload: () -> Unit,
    uploadEnabled: Boolean,
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
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
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
            IconButton(onClick = onUpload, enabled = uploadEnabled) {
                Icon(Icons.Filled.CloudUpload, contentDescription = "رفع المحدد إلى السحابة", tint = MaterialTheme.colorScheme.primary)
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
            Spacer(Modifier.width(4.dp))
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
    onUpload: (FolderEntity) -> Unit,
    onRename: (FolderEntity) -> Unit,
    onMove: (FolderEntity) -> Unit,
    onDelete: (FolderEntity) -> Unit
) {
    if (folder == null) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            // خيارات القائمة تدخل متدرّجة واحداً بعد الآخر
            Column {
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenFolder(folder.id)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .reveal("menu_open", 0, null, RevealStyle.File)
                ) { Text("فتح المجلد") }
                TextButton(
                    onClick = { onUpload(folder) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .reveal("menu_upload", 1, null, RevealStyle.File)
                ) {
                    Text("رفع المجلد إلى السحابة")
                }
                TextButton(
                    onClick = { onRename(folder) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .reveal("menu_rename", 2, null, RevealStyle.File)
                ) { Text("إعادة التسمية") }
                TextButton(
                    onClick = { onMove(folder) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .reveal("menu_move", 3, null, RevealStyle.File)
                ) { Text("نقل إلى مجلد…") }
                TextButton(
                    onClick = { onDelete(folder) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .reveal("menu_delete", 4, null, RevealStyle.File)
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
    // اتجاه الحركة: true = دخول إلى مجلد أعمق، false = صعود للمستوى الأعلى
    var goingDeeper by remember { mutableStateOf(true) }
    val browseFolder = remember(folders, browseId) {
        folders.firstOrNull { it.id == browseId }
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
                onClick = {
                    goingDeeper = false
                    browseId = browseFolder?.parentId
                },
                enabled = browseId != null
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "المستوى الأعلى",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedContent(
                targetState = browseFolder?.name ?: "المستوى الرئيسي",
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(280)) { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 2 })
                },
                label = "move-level-title"
            ) { levelName ->
                Text(
                    text = levelName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

        // كل مستوى صفحة: الدخول لمجلد = انزلاق للأمام، والصعود = انزلاق للخلف
        AnimatedContent(
            targetState = browseId,
            transitionSpec = {
                val direction = if (goingDeeper) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                (slideIntoContainer(direction, tween(320, easing = EmphasizedDecelerate)) { it / 3 } +
                    fadeIn(tween(260))) togetherWith
                    (slideOutOfContainer(direction, tween(260)) { it / 3 } + fadeOut(tween(160)))
            },
            label = "move-level-content"
        ) { levelId ->
            val children = remember(folders, levelId) {
                folders.filter { it.parentId == levelId }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
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
                    itemsIndexed(children, key = { _, folder -> folder.id }) { index, folder ->
                        FolderPickerRow(
                            folder = folder,
                            blocked = folder.id in blockedFolderIds,
                            onClick = {
                                goingDeeper = true
                                browseId = folder.id
                            },
                            modifier = Modifier.reveal(
                                "picker_${levelId}_${folder.id}", index, null, RevealStyle.File
                            )
                        )
                    }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 480f),
        label = "picker-press"
    )
    Surface(
        onClick = onClick,
        enabled = !blocked,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
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
        title = "إنشاء مجلد",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(
                onClick = { onCreate(name, description, color) },
                enabled = name.isNotBlank()
            ) { Text("إنشاء") }
        }
    ) {
        Field(label = "اسم المجلد", value = name, onValueChange = { name = it })
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
            // اللون المختار يكبر بنابض، ونقطته البيضاء تنبثق بقفزة
            val swatchScale by animateFloatAsState(
                targetValue = if (isSelected) 1.18f else 1f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
                label = "swatch-scale"
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = swatchScale
                        scaleY = swatchScale
                    }
                    .background(hex.toComposeColor(), CircleShape)
                    .combinedClickableSafely(onClick = { onSelect(hex) }),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = isSelected,
                    enter = scaleIn(initialScale = 0.2f, animationSpec = spring(dampingRatio = 0.4f, stiffness = 520f)) + fadeIn(tween(120)),
                    exit = scaleOut(targetScale = 0.2f, animationSpec = tween(100)) + fadeOut(tween(100))
                ) {
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
