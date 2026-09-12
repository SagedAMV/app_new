package com.unihub.app.feature.planner.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.Weekday
import com.unihub.app.data.repository.LectureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val lectureRepository: LectureRepository
) : ViewModel() {

    val messenger = UiMessenger()

    val lectures: StateFlow<List<LectureEntity>> =
        lectureRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(
        editing: LectureEntity?,
        subject: String,
        doctor: String,
        day: Weekday,
        timeFrom: String?,
        timeTo: String?,
        room: String
    ) {
        viewModelScope.launch {
            val validSubject = InputValidator.validateTitle(subject)
                .onFailure { messenger.notifyError(it.message ?: "اسم المادة مطلوب") }
                .getOrNull() ?: return@launch

            if (timeFrom.isNullOrBlank()) {
                messenger.notifyError("حدد وقت بداية المحاضرة")
                return@launch
            }

            InputValidator.validateTimeRange(timeFrom, timeTo)
                .onFailure { messenger.notifyError(it.message ?: "نطاق وقت غير صالح") }
                .getOrNull() ?: return@launch

            runCatching {
                if (editing == null) {
                    lectureRepository.create(
                        LectureEntity(
                            subject = validSubject,
                            doctor = InputValidator.sanitizeName(doctor),
                            day = day,
                            timeFrom = timeFrom,
                            timeTo = timeTo.orEmpty(),
                            room = InputValidator.sanitizeName(room)
                        )
                    )
                } else {
                    lectureRepository.update(
                        editing.copy(
                            subject = validSubject,
                            doctor = InputValidator.sanitizeName(doctor),
                            day = day,
                            timeFrom = timeFrom,
                            timeTo = timeTo.orEmpty(),
                            room = InputValidator.sanitizeName(room)
                        )
                    )
                }
            }.onSuccess { messenger.notify(if (editing == null) "أُضيفت المحاضرة" else "تم التحديث") }
                .onFailure { messenger.notifyError("تعذّر حفظ المحاضرة") }
        }
    }

    fun delete(lecture: LectureEntity) {
        viewModelScope.launch {
            runCatching { lectureRepository.delete(lecture) }
                .onSuccess { messenger.notify("حُذفت المحاضرة") }
                .onFailure { messenger.notifyError("تعذّر حذف المحاضرة") }
        }
    }
}
