package com.unihub.app.feature.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.CloudDownloadDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CloudFilesViewModel @Inject constructor(private val manager: CloudSyncManager) : ViewModel() {
    val files = manager.allRemoteFiles
    val localFolders = manager.localFolders
    val verifying = manager.verifyingKeys
    val verification = manager.localVerification
    val available = manager.availableRemoteFiles
    val folders = manager.remoteFolders
    val scanState = manager.scanState
    val transfer = manager.transferState
    val online = manager.isOnline
    val busy = manager.isSyncing
    val downloading = manager.downloadingKeys
    val progress = manager.downloadProgress
    val error = manager.lastScanError
    val report = manager.lastDownloadReport
    fun refresh() { viewModelScope.launch { manager.scanRemoteFilesAndSyncMetadata(false) } }
    fun download(files: List<RemoteCloudFile>, destination: CloudDownloadDestination) { viewModelScope.launch { manager.downloadSelectedFiles(files, destination = destination) } }
    fun downloadFolder(key: String, destination: CloudDownloadDestination) { viewModelScope.launch { manager.downloadFolder(key, destination) } }
    fun cancel() = manager.cancelDownloads()
}

/** يفتح الإشعار القائمة فوق أية وجهة، سواء كان التطبيق مغلقاً أو مفتوحاً. */
@Composable
fun CloudNotificationPickerHost(request: Int, viewModel: CloudFilesViewModel = hiltViewModel()) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val files by viewModel.files.collectAsStateWithLifecycle()
    val localFolders by viewModel.localFolders.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request > 0) {
            visible = true
        }
    }
    if (visible) CloudFilesPickerSheet(
        remoteFiles = files, remoteFolders = folders, localFolders = localFolders,
        verifyingKeys = verifying, localVerification = verification,
        downloadableKeys = available.mapTo(mutableSetOf()) { it.remoteKey },
        scanState = scanState, transferState = transfer, onDownloadFolder = viewModel::downloadFolder,
        downloadingKeys = downloading,
        isSyncing = busy, isOnline = online,
        onRefresh = viewModel::refresh, onDownloadSelected = viewModel::download,
        onDismiss = { visible = false }, downloadProgress = progress,
        lastScanError = error, downloadReport = report, onCancelDownloads = viewModel::cancel
    )
}
