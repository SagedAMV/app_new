package com.unihub.app.feature.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.common.Formatters
import com.unihub.app.data.cloud.CloudDeleteRules
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.data.cloud.CloudFolderTree
import com.unihub.app.data.cloud.CloudPresenceMatcher
import com.unihub.app.data.cloud.CloudScanPhase
import com.unihub.app.data.cloud.CloudTransferState
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.RemoteCloudFolder
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.UiMessagesHost

/**
 * زر السحابة في الشريط العلوي:
 * عندما توجد ملفات جديدة على خادم Cloudflare R2 غير موجودة في الهاتف ([newFilesCount] > 0)،
 * يضيء الزر وينبض بلون مميز مع شارة تعرض عدد الملفات الجديدة.
 *
 * جولة تعليمات.md: هذا الزر — مع مثيله في أعلى شاشة الملفات — نقطة الدخول الوحيدة
 * إلى شاشة السحابة (أزيلت الأزرار السفلية وزر النسخ الاحتياطي وتوجيه الإشعار).
 */
@Composable
fun GlowingCloudTopBarButton(
    newFilesCount: Int,
    onClick: () -> Unit
) {
    val hasNew = newFilesCount > 0
    if (!hasNew) {
        FilledTonalIconButton(onClick = onClick, colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            Icon(Icons.Filled.CloudQueue, contentDescription = "السحابة")
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "cloud_glow")
    val glowColor by transition.animateColor(
        initialValue = MaterialTheme.colorScheme.primaryContainer,
        targetValue = MaterialTheme.colorScheme.tertiaryContainer,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_color"
    )
    // بعد العودة المبكرة أعلاه (!hasNew) تكون hasNew هنا حقيقةً دائمة؛ لذلك حُذفت
    // الفروع الميتة التي كانت تفحصها مرة أخرى — جولة تدقيق تعليمات.md.
    val pulseScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    BadgedBox(
        modifier = Modifier
            .padding(end = 4.dp)
            .scale(pulseScale),
        badge = {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                Text(newFilesCount.toString())
            }
        }
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = glowColor,
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = "ملفات سحابية جديدة"
            )
        }
    }
}

/**
 * شاشة السحابة المستقلة — وجهة تنقل كاملة (راوت CloudFilesRoute في مخطط التنقل) بشريط
 * علوي وزر رجوع، وتقرأ حالتها مباشرةً من [CloudFilesViewModel] بمصدر حقيقة واحد
 * (نفس مدير المزامنة @Singleton) بدل تمرير نحو عشرين معاملاً من كل شاشة.
 *
 * الوظائف (جولة تعليمات.md — تسميات شائعة + تحكم كامل داخل السحابة نفسها):
 * تصفح المجلدات، الفلترة (غير المنزَّل/الجميع)، التحديد الجماعي، تنزيل المحدد أو
 * المجلد كاملاً، الفحص وإعادة المحاولة، إيقاف النقل، وتقرير آخر تنزيل — مع حوار
 * اختيار الوجهة عند التنزيل. وأضيفت إعادة تسمية الملفات والمجلدات وتغيير مسار
 * الملفات (نقلها بين مجلدات السحابة) بتحرير بيان الفهرس على الخادم.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudFilesScreen(
    onBack: () -> Unit,
    viewModel: CloudFilesViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val remoteFiles by viewModel.files.collectAsStateWithLifecycle()
    val remoteFolders by viewModel.folders.collectAsStateWithLifecycle()
    val localFolders by viewModel.localFolders.collectAsStateWithLifecycle()
    val verifyingKeys by viewModel.verifying.collectAsStateWithLifecycle()
    val localVerification by viewModel.verification.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val transferState by viewModel.transfer.collectAsStateWithLifecycle()
    val isOnline by viewModel.online.collectAsStateWithLifecycle()
    val downloadReport by viewModel.report.collectAsStateWithLifecycle()
    val availableFiles by viewModel.available.collectAsStateWithLifecycle()
    val downloadableKeys = remember(availableFiles) {
        availableFiles.mapTo(mutableSetOf()) { it.remoteKey }
    }
    val onRefresh: () -> Unit = viewModel::refresh
    val onDownloadSelected: (List<RemoteCloudFile>, CloudDownloadDestination) -> Unit = viewModel::download
    val onDownloadFolder: (String, CloudDownloadDestination) -> Unit = viewModel::downloadFolder
    val onCancelDownloads: () -> Unit = viewModel::cancel
    var currentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var missingOnly by rememberSaveable { mutableStateOf(true) }
    var requestFiles by remember { mutableStateOf<List<RemoteCloudFile>?>(null) }
    var requestFolder by remember { mutableStateOf<RemoteCloudFolder?>(null) }
    // التحكم داخل السحابة: قوائم الإجراءات وإعادة التسمية وتغيير المسار
    var fileMenu by remember { mutableStateOf<RemoteCloudFile?>(null) }
    var folderMenu by remember { mutableStateOf<RemoteCloudFolder?>(null) }
    var renameFileTarget by remember { mutableStateOf<RemoteCloudFile?>(null) }
    var renameFolderTarget by remember { mutableStateOf<RemoteCloudFolder?>(null) }
    var moveTargets by remember { mutableStateOf<List<RemoteCloudFile>?>(null) }
    // الحذف من داخل السحابة: تحديد ملفات (أو ملف واحد) / مجلد فارغ — مع تأكيد صريح
    var deleteFileTargets by remember { mutableStateOf<List<RemoteCloudFile>?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<RemoteCloudFolder?>(null) }
    val folders = remember(remoteFolders) { CloudFolderTree.normalise(remoteFolders) }
    val current = folders.firstOrNull { it.key == currentKey }
    val foldersWithMissing = remember(folders, remoteFiles, downloadableKeys, verifyingKeys) {
        CloudPresenceMatcher.foldersForMissing(folders, remoteFiles, downloadableKeys + verifyingKeys)
    }
    val children = folders.filter { it.parentKey == currentKey && (!missingOnly || it.key in foldersWithMissing) }
    val visibleFiles = remoteFiles.filter { it.cloudFolderKey == currentKey && (!missingOnly || it.remoteKey in downloadableKeys || it.remoteKey in verifyingKeys) }
    val selected = remoteFiles.filter { it.remoteKey in selectedKeys && it.remoteKey in downloadableKeys }
    val canChoose = !transferState.active
    LaunchedEffect(Unit) { onRefresh() }
    LaunchedEffect(remoteFiles, folders, downloadableKeys) {
        selectedKeys = selectedKeys.intersect(downloadableKeys)
        if (currentKey != null && current == null) currentKey = null
    }
    // جولة تعليمات.md — حل مشكلة زر الرجوع الخاص بنظام التشغيل في واجهة الملفات
    // السحابية: كان يُخرج من الشاشة كاملة. الآن يتراجع تدريجياً: إلغاء التحديد
    // أولاً، ثم الصعود للمجلد الأب، ثم الخروج من الشاشة عند الجذر بلا تحديد.
    // النوافذ والأوراق المفتوحة تعالج رجوعها بنفسها لأن مستمعها يُسجَّل بعد هذا
    // المستمع في OnBackPressedDispatcher فتُقدَّم عليه.
    val handleBack: () -> Unit = {
        when (CloudBackPolicy.step(hasSelection = selectedKeys.isNotEmpty(), isInsideFolder = currentKey != null)) {
            BackStep.CLEARED_SELECTION -> selectedKeys = emptySet()
            BackStep.WENT_UP -> currentKey = current?.parentKey
            BackStep.EXITED -> onBack()
        }
    }
    BackHandler(onBack = handleBack)
    if (requestFiles != null || requestFolder != null) {
        CloudDownloadDestinationDialog(localFolders, requestFolder?.name,
            onConfirm = { destination ->
                val folder = requestFolder
                if (folder != null) onDownloadFolder(folder.key, destination)
                else onDownloadSelected(requestFiles.orEmpty(), destination)
                requestFiles = null; requestFolder = null
            }, onDismiss = { requestFiles = null; requestFolder = null })
    }

    // أوراق التحكم داخل السحابة (إعادة تسمية / تغيير مسار) — تفتح من قوائم الصفوف
    fileMenu?.let { file ->
        CloudFileMenuSheet(
            file = file,
            canDownload = isOnline && canChoose && file.remoteKey in downloadableKeys,
            canDelete = isOnline && canChoose,
            onRename = { renameFileTarget = file; fileMenu = null },
            onMove = { moveTargets = listOf(file); fileMenu = null },
            onDownload = { requestFiles = listOf(file); fileMenu = null },
            onDelete = { deleteFileTargets = listOf(file); fileMenu = null },
            onDismiss = { fileMenu = null }
        )
    }
    folderMenu?.let { folder ->
        CloudFolderMenuSheet(
            folder = folder,
            deleteBlockReason = if (isOnline && canChoose) {
                CloudDeleteRules.folderDeletionBlockReason(folder, remoteFiles, folders)
            } else {
                "الحذف متاح عندما يكون الاتصال متوفراً ولا توجد عملية نقل جارية"
            },
            onRename = { renameFolderTarget = folder; folderMenu = null },
            onDelete = { deleteFolderTarget = folder; folderMenu = null },
            onDismiss = { folderMenu = null }
        )
    }
    renameFileTarget?.let { file ->
        CloudRenameSheet(
            title = "إعادة تسمية الملف",
            initialName = file.fullDisplayName,
            fieldLabel = "الاسم الجديد (مع الامتداد)",
            onSave = { newName -> viewModel.renameFile(file.remoteKey, newName); renameFileTarget = null },
            onDismiss = { renameFileTarget = null }
        )
    }
    renameFolderTarget?.let { folder ->
        CloudRenameSheet(
            title = "إعادة تسمية المجلد",
            initialName = folder.name,
            fieldLabel = "الاسم الجديد",
            onSave = { newName -> viewModel.renameFolder(folder.key, newName); renameFolderTarget = null },
            onDismiss = { renameFolderTarget = null }
        )
    }
    moveTargets?.let { targets ->
        CloudMoveSheet(
            folders = folders,
            currentKey = targets.firstOrNull()?.cloudFolderKey,
            onConfirm = { destinationKey -> viewModel.moveFiles(targets, destinationKey); moveTargets = null },
            onDismiss = { moveTargets = null }
        )
    }

    // تأكيد الحذف من السحابة — لا حذف بلا تأكيد، والرسالة تصرّح بأن النسخ المحلية لا تُمس.
    deleteFileTargets?.let { targets ->
        val label = if (targets.size == 1) "«${targets.first().fullDisplayName}»" else Formatters.fileCountLabel(targets.size)
        ConfirmDialog(
            title = "حذف من السحابة؟",
            message = "سيُحذف $label نهائيًا من السحابة ولا يمكن التراجع. نسخك المحلية على هذا الجهاز لن تُحذف.",
            confirmLabel = "حذف من السحابة",
            onConfirm = { viewModel.deleteFiles(targets); deleteFileTargets = null },
            onDismiss = { deleteFileTargets = null }
        )
    }
    deleteFolderTarget?.let { folder ->
        ConfirmDialog(
            title = "حذف المجلد من السحابة؟",
            message = "سيُحذف المجلد «${folder.name}» من قائمة السحابة. المجلد فارغ من الملفات، ولن تُحذف أي نسخة محلية.",
            confirmLabel = "حذف المجلد",
            onConfirm = { viewModel.deleteFolder(folder); deleteFolderTarget = null },
            onDismiss = { deleteFolderTarget = null }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("الملفات السحابية") },
                navigationIcon = {
                    // نفس سلّم الرجوع الموحّد — زر الشريط يتطابق مع زر النظام
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = scanState.phase != CloudScanPhase.SCANNING) {
                        Icon(Icons.Filled.Refresh, contentDescription = "تحديث القائمة")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isOnline) scanState.message else "بدون إنترنت — تعرض آخر قائمة محفوظة",
                style = MaterialTheme.typography.bodySmall,
                color = if (scanState.phase == CloudScanPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (scanState.phase == CloudScanPhase.SCANNING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (localVerification.active) Text("جارٍ التحقق من النسخ المحلية (${localVerification.completed}/${localVerification.total}): ${localVerification.fileName}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            localVerification.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = missingOnly, onClick = { missingOnly = true }, label = { Text("لم تُنزَّل") })
                FilterChip(selected = !missingOnly, onClick = { missingOnly = false }, label = { Text("كل الملفات") })
            }
            CloudTransferProgressCard(transferState, onCancelDownloads)
            downloadReport?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
            // لقطة قراءة واحدة للمفتاح الحالي (smart cast بدل force-unwrap — بوابة «صفر !!» في تعليمات.md)
            val key = currentKey
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (key != null) IconButton(onClick = { currentKey = current?.parentKey }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "المجلد الأب")
                }
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: "الرئيسية", fontWeight = FontWeight.Bold)
                    if (current != null) Text(CloudFolderTree.ancestors(folders, key).joinToString(" / ") { it.name },
                        style = MaterialTheme.typography.bodySmall)
                }
                if (key != null) TextButton(onClick = { requestFolder = current }, enabled = isOnline && canChoose && key in foldersWithMissing &&
                    CloudFolderTree.filesWithin(remoteFiles, folders, key).none { it.remoteKey in verifyingKeys }) {
                    Text("تنزيل المجلد")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    // قراءة المفتاح لحظة النقر نفسها — بلا !! وبلا افتراض ثباته منذ التركيب
                    val target = currentKey
                    val within = if (target == null) remoteFiles else CloudFolderTree.filesWithin(remoteFiles, folders, target)
                    selectedKeys = selectedKeys + within.filter { it.remoteKey in downloadableKeys }.map { it.remoteKey }
                }, enabled = canChoose) { Text("تحديد الكل") }
                TextButton(onClick = { selectedKeys = emptySet() }, enabled = canChoose && selectedKeys.isNotEmpty()) { Text("إلغاء التحديد") }
                TextButton(onClick = { moveTargets = selected }, enabled = selected.isNotEmpty() && isOnline && canChoose) { Text("نقل المحدد") }
                TextButton(onClick = { deleteFileTargets = selected }, enabled = selected.isNotEmpty() && isOnline && canChoose) {
                    Text("حذف المحدد", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Text("${selected.size} • ${Formatters.fileSize(selected.sumOf { it.size })}", style = MaterialTheme.typography.labelMedium)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (children.isEmpty() && visibleFiles.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (scanState.phase == CloudScanPhase.ERROR) Icons.Filled.CloudOff else Icons.Filled.CloudQueue,
                            contentDescription = null, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(when {
                            missingOnly && scanState.phase == CloudScanPhase.READY && verifyingKeys.isEmpty() -> "لا توجد ملفات للتنزيل — كل المحتوى على جهازك"
                            currentKey != null -> "لا توجد ملفات ضمن هذا العرض"
                            scanState.phase == CloudScanPhase.SCANNING -> "جارٍ التحقق من محتويات الخادم…"
                            scanState.phase == CloudScanPhase.EMPTY -> "لا توجد ملفات أو مجلدات في السحابة"
                            scanState.phase == CloudScanPhase.ERROR -> "تعذّر التحقق — ليس معنى ذلك أن السحابة فارغة"
                            scanState.phase == CloudScanPhase.BUSY -> "النقل جارٍ؛ يمكنك تصفح القائمة المحفوظة أو إيقاف النقل"
                            else -> "لا توجد قائمة محفوظة؛ اضغط فحص"
                        }, style = MaterialTheme.typography.bodyMedium)
                        if (scanState.phase == CloudScanPhase.ERROR || scanState.phase == CloudScanPhase.IDLE) {
                            TextButton(onClick = onRefresh, enabled = isOnline) { Text("إعادة المحاولة") }
                        }
                    }
                }
                items(children, key = { "folder:${it.key}" }) { folder ->
                    val all = CloudFolderTree.filesWithin(remoteFiles, folders, folder.key)
                    val pending = all.filter { it.remoteKey in downloadableKeys }.mapTo(mutableSetOf()) { it.remoteKey }
                    val verifying = all.count { it.remoteKey in verifyingKeys }
                    val checked = pending.intersect(selectedKeys)
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f))) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(state = when {
                                checked.isEmpty() -> ToggleableState.Off
                                checked.size == pending.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }, onClick = { selectedKeys = if (checked.size == pending.size) selectedKeys - pending else selectedKeys + pending },
                                enabled = canChoose && pending.isNotEmpty())
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).clickable { currentKey = folder.key }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(folder.name, fontWeight = FontWeight.SemiBold)
                                Text("${all.size} ملف • ${Formatters.fileSize(all.sumOf { it.size })} • ${pending.size} غير منزَّل" + if (verifying > 0) " • $verifying قيد المقارنة" else "",
                                    style = MaterialTheme.typography.bodySmall)
                                Text("افتح المجلد للاختيار والتنزيل", style = MaterialTheme.typography.labelSmall)
                            }
                            // أيقونة إجراءات موحّدة مع صفوف الملفات: ثلاث نقاط بدل قلم رصاص
                            IconButton(onClick = { folderMenu = folder }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "إجراءات ${folder.name}")
                            }
                            IconButton(onClick = { requestFolder = folder }, enabled = isOnline && canChoose && verifying == 0 &&
                                (pending.isNotEmpty() || (all.isEmpty() && !missingOnly))) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = "تنزيل ${folder.name}")
                            }
                        }
                    }
                }
                items(visibleFiles, key = { "file:${it.remoteKey}" }) { file ->
                    val pending = file.remoteKey in downloadableKeys
                    val checked = file.remoteKey in selectedKeys
                    Card(Modifier.fillMaxWidth().clickable(enabled = pending && canChoose) {
                        selectedKeys = if (checked) selectedKeys - file.remoteKey else selectedKeys + file.remoteKey
                    }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, enabled = pending && canChoose, onCheckedChange = { value ->
                                selectedKeys = if (value) selectedKeys + file.remoteKey else selectedKeys - file.remoteKey
                            })
                            Icon(file.kind.icon(), contentDescription = null, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(file.fullDisplayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${Formatters.fileSize(file.size)} • " + if (file.remoteKey in verifyingKeys) "جارٍ التحقق من النسخة المحلية" else if (pending) "متاح للتنزيل" else "منزَّل ✓",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { fileMenu = file }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "إجراءات ${file.fullDisplayName}")
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { requestFiles = selected }, enabled = selected.isNotEmpty() && isOnline && canChoose) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp)); Text("تنزيل المحدد (${selected.size})")
                }
            }
        }
    }
}

/** قائمة إجراءات ملف سحابي: تنزيل، إعادة تسمية، نقل إلى مجلد، حذف من السحابة. */
@Composable
private fun CloudFileMenuSheet(
    file: RemoteCloudFile,
    canDownload: Boolean,
    canDelete: Boolean,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AppSheet(
        title = file.fullDisplayName,
        onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CloudMenuActionRow(icon = Icons.Filled.Edit, text = "إعادة تسمية الملف", onClick = onRename)
            CloudMenuActionRow(icon = Icons.AutoMirrored.Filled.DriveFileMove, text = "نقل إلى مجلد", onClick = onMove)
            CloudMenuActionRow(icon = Icons.Filled.CloudDownload, text = "تنزيل", enabled = canDownload, onClick = onDownload)
            CloudMenuActionRow(icon = Icons.Filled.Delete, text = "حذف من السحابة", enabled = canDelete, danger = true, onClick = onDelete)
            if (!canDownload) Text(
                "التنزيل متاح عندما يكون الملف غير موجود على جهازك والاتصال متوفراً",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        }
    }
}

/**
 * قائمة إجراءات مجلد سحابي: إعادة تسمية المجلد، وحذفه من قائمة السحابة.
 * المجلدات المبنية على مسار التخزين (path:) أو على مجلدات المكتبة المحلية (legacy:)
 * لا تُعاد تسميتها ولا تُحذف من السحابة — الاسم والحذف هناك يتبعان مصدرهما،
 * والرسالة توضح السبب. والحذف يُرفض أيضاً للمجلد الذي يحوي ملفات: تُشرح الخطوة الصحيحة.
 */
@Composable
private fun CloudFolderMenuSheet(
    folder: RemoteCloudFolder,
    deleteBlockReason: String?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val canRename = folder.key.startsWith("folder:")
    AppSheet(
        title = folder.name,
        onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CloudMenuActionRow(icon = Icons.Filled.Edit, text = "إعادة تسمية المجلد", enabled = canRename, onClick = onRename)
            CloudMenuActionRow(icon = Icons.Filled.Delete, text = "حذف المجلد من السحابة", enabled = deleteBlockReason == null, danger = true, onClick = onDelete)
            if (!canRename) Text(
                "هذا المجلد مبني على مسار التخزين أو على مجلد محلي؛ إعادة التسمية متاحة للمجلدات التي أنشأها التطبيق في السحابة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
            deleteBlockReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                )
            }
        }
    }
}

/** صف إجراء واحد داخل قوائم السحابة. danger = إجراء تدميري (حذف) بلون الخطأ. */
@Composable
private fun CloudMenuActionRow(
    icon: ImageVector,
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val activeColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) activeColor
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = when {
                enabled && danger -> MaterialTheme.colorScheme.error
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
        )
    }
}

/** ورقة إعادة تسمية موحدة لملف أو مجلد سحابي */
@Composable
private fun CloudRenameSheet(
    title: String,
    initialName: String,
    fieldLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AppSheet(
        title = title,
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("حفظ") }
        }
    ) {
        Field(label = fieldLabel, value = name, onValueChange = { name = it })
    }
}

/**
 * منتقي وجهة «نقل إلى مجلد» داخل السحابة: يبحر في شجرة المجلدات السحابية
 * (نقر للدخول، زر صعود للأعلى)، وزر «نقل إلى هنا» يثبّت المستوى الحالي وجهةً
 * (المستوى الرئيسي = بلا مجلد). مجلدات legacy: مرتبطة بالمكتبة المحلية فلا تُختار
 * وجهة، ويُعطَّل التأكيد أيضاً عند البقاء في مجلد الملفات الحالي نفسه.
 */
@Composable
private fun CloudMoveSheet(
    folders: List<RemoteCloudFolder>,
    currentKey: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var browseKey by remember { mutableStateOf(currentKey) }
    val browseFolder = remember(folders, browseKey) { folders.firstOrNull { it.key == browseKey } }
    val children = remember(folders, browseKey) {
        folders.filter { it.parentKey == browseKey }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    val onLegacyLevel = browseKey?.startsWith("legacy:") == true
    val sameAsCurrent = browseKey == currentKey
    AppSheet(
        title = "نقل إلى مجلد",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(
                onClick = { onConfirm(browseKey) },
                enabled = !sameAsCurrent && !onLegacyLevel
            ) { Text("نقل إلى هنا") }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { browseKey = browseFolder?.parentKey }, enabled = browseKey != null) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "المستوى الأعلى",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = browseFolder?.name ?: "المستوى الرئيسي",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (browseKey == null) "الوجهة الحالية: أعلى مستوى في السحابة (بلا مجلد)"
                    else "ادخل مجلداً فرعياً أو ثبّت هذا المستوى وجهةً",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when {
            onLegacyLevel -> Text(
                "مجلد مرتبط بالمكتبة المحلية؛ لا يُستخدم وجهةً للنقل داخل السحابة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            sameAsCurrent -> Text(
                "الملفات موجودة في هذا المجلد بالفعل",
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
                items(children, key = { it.key }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { browseKey = folder.key }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (folder.key.startsWith("legacy:")) Text(
                                "مرتبط بمجلد محلي — للملاحة فقط",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloudTransferProgressCard(state: CloudTransferState, onCancel: () -> Unit) {
    if (!state.active) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            val progress = state.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (state.bytesTotal > 0) Text("${Formatters.fileSize(state.bytesDone)} / ${Formatters.fileSize(state.bytesTotal)}",
                style = MaterialTheme.typography.bodySmall)
            // «إيقاف» بلا كلمة «النقل»: كلمة النقل صارت محجوزة لمعنى «نقل إلى مجلد»
            TextButton(onClick = onCancel) { Text("إيقاف") }
        }
    }
}

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.PDF -> Icons.Filled.PictureAsPdf
    FileKind.DOCUMENT, FileKind.TEXT -> Icons.Filled.Description
    FileKind.SPREADSHEET -> Icons.Filled.GridOn
    FileKind.PRESENTATION -> Icons.Filled.Slideshow
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.AUDIO -> Icons.Filled.Audiotrack
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}
