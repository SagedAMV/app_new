package com.unihub.app.feature.files

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.data.cloud.CloudFolderTree
import com.unihub.app.data.cloud.CloudPresenceMatcher
import com.unihub.app.data.cloud.CloudScanPhase
import com.unihub.app.data.cloud.CloudTransferState
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.RemoteCloudFolder
import com.unihub.app.data.local.entity.FileKind

/**
 * زر السحابة في الشريط العلوي:
 * عندما توجد ملفات جديدة على خادم Cloudflare R2 غير موجودة في الهاتف ([newFilesCount] > 0)،
 * يضيء الزر وينبض بلون مميز مع شارة تعرض عدد الملفات الجديدة.
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
            Icon(Icons.Filled.CloudQueue, contentDescription = "فحص قائمة السحابة")
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
    val pulseScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (hasNew) 1.12f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    BadgedBox(
        modifier = Modifier
            .padding(end = 4.dp)
            .scale(if (hasNew) pulseScale else 1f),
        badge = {
            if (hasNew) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    Text(newFilesCount.toString())
                }
            }
        }
    ) {
        if (hasNew) {
            FilledTonalIconButton(
                onClick = onClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = glowColor,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = "ملفات سحابية جديدة متاحة للسحب"
                )
            }
        } else {
            FilledTonalIconButton(
                onClick = onClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudQueue,
                    contentDescription = "فحص ملفات السحابة"
                )
            }
        }
    }
}

/**
 * زر عائم مضيء يظهر في شاشة الملفات عندما تتوفر ملفات سحابية جديدة على الخادم.
 */
@Composable
fun GlowingCloudFloatingButton(
    newFilesCount: Int,
    onClick: () -> Unit
) {
    if (newFilesCount <= 0) return
    val transition = rememberInfiniteTransition(label = "fab_cloud_glow")
    val glowColor by transition.animateColor(
        initialValue = MaterialTheme.colorScheme.primary,
        targetValue = MaterialTheme.colorScheme.tertiary,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_glow_color"
    )

    BadgedBox(
        badge = {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                Text(newFilesCount.toString())
            }
        }
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = glowColor,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = "ملفات جديدة في الخادم"
            )
        }
    }
}

/**
 * شريط إشعار داخلي مضيء يعرض اسم الملف الجديد وحجمه (أو عدد الملفات الجديدة وأحجامها)
 * ويفتح نافذة الاختيار الانتقائي فور النقر عليه.
 */
@Composable
fun CloudNewFilesBanner(
    remoteFiles: List<RemoteCloudFile>,
    onClick: () -> Unit
) {
    if (remoteFiles.isEmpty()) return

    val transition = rememberInfiniteTransition(label = "banner_glow")
    val borderColor by transition.animateColor(
        initialValue = MaterialTheme.colorScheme.primary,
        targetValue = MaterialTheme.colorScheme.tertiary,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "banner_border"
    )

    val first = remoteFiles.first()
    val firstSize = Formatters.fileSize(first.size)
    val totalSize = Formatters.fileSize(remoteFiles.sumOf { it.size })

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        ),
        border = BorderStroke(1.5.dp, borderColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (remoteFiles.size == 1) {
                        "ملف جديد على الخادم: ${first.fullDisplayName} ($firstSize)"
                    } else {
                        "توجد ${Formatters.fileCountLabel(remoteFiles.size)} جديدة على الخادم ($totalSize)"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (remoteFiles.size == 1) {
                        "انقر هنا لعرض التفاصيل واختيار سحبه إلى هاتفك"
                    } else {
                        "منها: ${first.fullDisplayName} ($firstSize) — انقر لاختيار ما تريد سحبه"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * شاشة السحابة المستقلة — بديل اللوحة المنبثقة السابقة `CloudFilesPickerSheet`
 * التي كانت تظهر من أسفل الشاشة. صارت وجهة تنقل كاملة (راوت CloudFilesRoute في مخطط التنقل) بشريط
 * علوي وزر رجوع، وتقرأ حالتها مباشرةً من [CloudFilesViewModel] بمصدر حقيقة واحد
 * (نفس مدير المزامنة @Singleton) بدل تمرير نحو عشرين معاملاً من كل شاشة.
 *
 * الوظائف محفوظة كما كانت: تصفح المجلدات، الفلترة (غير الموجود في هاتفي/عرض
 * الجميع)، التحديد الجماعي، تنزيل المجلد كاملًا، الفحص وإعادة المحاولة، إيقاف
 * النقل، وتقرير آخر تنزيل — مع حوار اختيار الوجهة عند التنزيل.
 *
 * تنبيه من جولة التحويل: أُسقطت أربعة معاملات كانت مُعرَّفة في الورقة السابقة
 * ولم تُستخدم في جسمها إطلاقاً (downloadingKeys, isSyncing, downloadProgress,
 * lastScanError) — فلا تُنقل إلى الشاشة الجديدة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudFilesScreen(
    onBack: () -> Unit,
    viewModel: CloudFilesViewModel = hiltViewModel()
) {
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
    if (requestFiles != null || requestFolder != null) {
        CloudDownloadDestinationDialog(localFolders, requestFolder?.name,
            onConfirm = { destination ->
                val folder = requestFolder
                if (folder != null) onDownloadFolder(folder.key, destination)
                else onDownloadSelected(requestFiles.orEmpty(), destination)
                requestFiles = null; requestFolder = null
            }, onDismiss = { requestFiles = null; requestFolder = null })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("السحابة — الملفات والمجلدات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = scanState.phase != CloudScanPhase.SCANNING) {
                        Icon(Icons.Filled.Refresh, contentDescription = "فحص السحابة الآن")
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
            if (localVerification.active) Text("مقارنة الملفات المحلية ${localVerification.completed}/${localVerification.total}: ${localVerification.fileName}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            localVerification.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = missingOnly, onClick = { missingOnly = true }, label = { Text("غير الموجود في هاتفي") })
                FilterChip(selected = !missingOnly, onClick = { missingOnly = false }, label = { Text("عرض الجميع") })
            }
            CloudTransferProgressCard(transferState, onCancelDownloads)
            downloadReport?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (currentKey != null) IconButton(onClick = { currentKey = current?.parentKey }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "المجلد الأب")
                }
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: "الرئيسية", fontWeight = FontWeight.Bold)
                    if (current != null) Text(CloudFolderTree.ancestors(folders, currentKey).joinToString(" / ") { it.name },
                        style = MaterialTheme.typography.bodySmall)
                }
                if (currentKey != null) TextButton(onClick = { requestFolder = current }, enabled = isOnline && canChoose && currentKey in foldersWithMissing &&
                    CloudFolderTree.filesWithin(remoteFiles, folders, currentKey!!).none { it.remoteKey in verifyingKeys }) {
                    Text("تنزيل المجلد كاملًا")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val within = if (currentKey == null) remoteFiles else CloudFolderTree.filesWithin(remoteFiles, folders, currentKey!!)
                    selectedKeys = selectedKeys + within.filter { it.remoteKey in downloadableKeys }.map { it.remoteKey }
                }, enabled = canChoose) { Text("تحديد الكل هنا") }
                TextButton(onClick = { selectedKeys = emptySet() }, enabled = canChoose && selectedKeys.isNotEmpty()) { Text("إلغاء التحديد") }
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
                            missingOnly && scanState.phase == CloudScanPhase.READY && verifyingKeys.isEmpty() -> "لا توجد ملفات ناقصة؛ المحتويات موجودة في هاتفك"
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
                                Text("${all.size} ملف • ${Formatters.fileSize(all.sumOf { it.size })} • ${pending.size} غير منزّل" + if (verifying > 0) " • $verifying قيد المقارنة" else "",
                                    style = MaterialTheme.typography.bodySmall)
                                Text("انقر لعرض المحتويات والاختيار داخل المجلد", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { requestFolder = folder }, enabled = isOnline && canChoose && verifying == 0 &&
                                (pending.isNotEmpty() || (all.isEmpty() && !missingOnly))) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = "تنزيل ${folder.name} كاملًا")
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
                                Text("${Formatters.fileSize(file.size)} • " + if (file.remoteKey in verifyingKeys) "جارٍ التحقق من النسخة المحلية" else if (pending) "متاح للتنزيل" else "موجود في هاتفك ✓",
                                    style = MaterialTheme.typography.bodySmall)
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
            TextButton(onClick = onCancel) { Text("إيقاف النقل — الملفات المكتملة تبقى محفوظة") }
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
