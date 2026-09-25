package com.unihub.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val dashboardRepository: DashboardRepository
) : ViewModel() {

    val messenger = UiMessenger()

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
