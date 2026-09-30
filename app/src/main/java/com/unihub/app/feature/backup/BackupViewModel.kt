package com.unihub.app.feature.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.backup.BackupRepository
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.R2Credentials
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.CloudDownloadDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val cloudSyncManager: CloudSyncManager,
    private val cloudSyncPreferences: CloudSyncPreferences
) : ViewModel() {
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val isOnline = cloudSyncManager.isOnline
    val isSyncing = cloudSyncManager.isSyncing
    val allCloudFiles = cloudSyncManager.allRemoteFiles
    val localDownloadFolders = cloudSyncManager.localFolders
    val cloudVerifyingKeys = cloudSyncManager.verifyingKeys
    val cloudLocalVerification = cloudSyncManager.localVerification
    val cloudFolders = cloudSyncManager.remoteFolders
    val cloudScanState = cloudSyncManager.scanState
    val cloudTransferState = cloudSyncManager.transferState
    fun downloadCloudFolder(key: String, destination: CloudDownloadDestination) {
        viewModelScope.launch {
            cloudSyncManager.downloadFolder(key, destination)
                .onSuccess { report -> _status.value = report.message }
                .onFailure { error -> _status.value = error.message }
        }
    }
    val availableRemoteFiles = cloudSyncManager.availableRemoteFiles
    val downloadingRemoteKeys = cloudSyncManager.downloadingKeys
    val cloudDownloadProgress = cloudSyncManager.downloadProgress
    val cloudScanError = cloudSyncManager.lastScanError
    val cloudDownloadReport = cloudSyncManager.lastDownloadReport
    val cloudSettings = cloudSyncPreferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun cancelCloudDownloads() = cloudSyncManager.cancelDownloads()

    private fun runBusy(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() } finally { _busy.value = false }
        }
    }

    fun syncWithCloud() = runBusy {
        _status.value = null
        cloudSyncManager.syncWithServer(true)
            .onSuccess { _status.value = it }
            .onFailure { _status.value = it.message ?: "تعذّرت المزامنة" }
    }

    fun refreshCloudFiles() = runBusy {
        cloudSyncManager.scanRemoteFilesAndSyncMetadata(false)
            .onSuccess { _status.value = "${it.size} ملف متاح للتنزيل الاختياري" }
            .onFailure { _status.value = it.message ?: "تعذّر فحص الخادم" }
    }

    fun downloadSelectedCloudFiles(files: List<RemoteCloudFile>, destination: CloudDownloadDestination) = runBusy {
        _status.value = null
        cloudSyncManager.downloadSelectedFiles(files, destination = destination)
            .onSuccess { _status.value = it.message }
            .onFailure { _status.value = it.message ?: "تعذّر التنزيل" }
    }

    fun pushToCloud() = runBusy {
        cloudSyncManager.pushToServer()
            .onSuccess { _status.value = "تم تحديث بيانات الخادم ($it عنصر) دون حذف الملفات غير المنزّلة" }
            .onFailure { _status.value = it.message ?: "تعذّر الرفع" }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            cloudSyncPreferences.setAutoSyncEnabled(enabled)
            if (enabled && cloudSyncManager.checkIsOnline()) cloudSyncManager.syncWithServer()
        }
    }

    fun saveCloudCredentials(accountId: String, endpointUrl: String, bucketName: String, accessKeyId: String, secretAccessKey: String) {
        viewModelScope.launch {
            val creds = R2Credentials(accountId.trim(), endpointUrl.trim(), bucketName.trim(), accessKeyId.trim(), secretAccessKey.trim())
            cloudSyncPreferences.saveCredentials(creds)
            _status.value = if (creds.isConfigured) "تم حفظ إعدادات R2" else "أكمل بيانات R2؛ التطبيق يعمل محلياً"
        }
    }

    fun exportTo(uri: Uri) = runBusy {
        backupRepository.export(uri)
            .onSuccess { _status.value = "تم تصدير النسخة المحلية ($it عنصر)" }
            .onFailure { _status.value = "فشل التصدير: ${it.message}" }
    }

    fun importFrom(uri: Uri) = runBusy {
        backupRepository.import(uri)
            .onSuccess { _status.value = "تم استيراد $it عنصر وأُعيدت جدولة التذكيرات" }
            .onFailure { _status.value = "فشل الاستيراد: ${it.message}" }
    }
}
