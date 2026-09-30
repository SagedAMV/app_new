package com.unihub.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.core.common.NextLecture
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.TaskEntity
import com.unihub.app.data.repository.DashboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel مخصص للشاشة الرئيسية فقط (بدل الـ AppViewModel العام في المرجع
 * الذي كان يحمل كل عمليات التطبيق في كائن واحد).
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dashboardRepository: DashboardRepository,
    private val cloudSyncManager: CloudSyncManager
) : ViewModel() {

    val messenger = UiMessenger()
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
                .onSuccess { report -> messenger.notify(report.message) }
                .onFailure { error -> messenger.notifyError(error.message ?: "تعذّر تنزيل المجلد") }
        }
    }
    val availableRemoteFiles = cloudSyncManager.availableRemoteFiles
    val downloadingRemoteKeys = cloudSyncManager.downloadingKeys
    val isCloudSyncing = cloudSyncManager.isSyncing
    val isOnline = cloudSyncManager.isOnline
    val cloudDownloadProgress = cloudSyncManager.downloadProgress
    val cloudScanError = cloudSyncManager.lastScanError
    val cloudDownloadReport = cloudSyncManager.lastDownloadReport
    fun cancelCloudDownloads() = cloudSyncManager.cancelDownloads()

    fun refreshCloudFiles() {
        viewModelScope.launch {
            cloudSyncManager.scanRemoteFilesAndSyncMetadata(false)
                .onFailure { messenger.notifyError(it.message ?: "تعذّر فحص السحابة") }
        }
    }

    fun downloadSelectedCloudFiles(files: List<RemoteCloudFile>, destination: CloudDownloadDestination) {
        viewModelScope.launch {
            cloudSyncManager.downloadSelectedFiles(files, destination = destination)
                .onSuccess { report ->
                    if (report.failedNames.isEmpty()) messenger.notify(report.message)
                    else messenger.notifyError(report.message)
                }
                .onFailure { messenger.notifyError(it.message ?: "تعذّر التنزيل") }
        }
    }

    val todayLectures: StateFlow<List<LectureEntity>> =
        dashboardRepository.observeTodayLectures()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** المحاضرة الأقرب من كامل الجدول — حية (تتحدث كل 30 ثانية) */
    val nextLecture: StateFlow<NextLecture?> =
        dashboardRepository.observeNextLecture()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dueSoonTasks: StateFlow<List<TaskEntity>> =
        dashboardRepository.observeDueSoonTasks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** أقرب مهمة مطلوبة (ماضية أو مستقبلية) — للنصف الأيمن من بطاقة «لقطة اليوم» */
    val nearestTask: StateFlow<TaskEntity?> =
        dashboardRepository.observeNearestTask()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val upcomingExams: StateFlow<List<ExamEntity>> =
        dashboardRepository.observeUpcomingExams()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingTaskCount: StateFlow<Int> =
        dashboardRepository.observePendingTaskCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val upcomingExamCount: StateFlow<Int> =
        dashboardRepository.observeUpcomingExamCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val noteCount: StateFlow<Int> =
        dashboardRepository.observeNoteCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val fileCount: StateFlow<Int> =
        dashboardRepository.observeFileCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun toggleTask(task: TaskEntity) {
        viewModelScope.launch {
            runCatching { dashboardRepository.toggleTask(task) }
                .onFailure { messenger.notifyError("تعذّر تحديث المهمة") }
        }
    }
}
