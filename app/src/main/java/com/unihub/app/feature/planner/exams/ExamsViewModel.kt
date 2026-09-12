package com.unihub.app.feature.planner.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.ExamType
import com.unihub.app.data.repository.ExamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExamsViewModel @Inject constructor(
    private val examRepository: ExamRepository
) : ViewModel() {

    val messenger = UiMessenger()

    val exams: StateFlow<List<ExamEntity>> =
        examRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(
        editing: ExamEntity?,
        subject: String,
        type: ExamType,
        date: String?,
        time: String?,
        room: String,
        notes: String
    ) {
        viewModelScope.launch {
            val validSubject = InputValidator.validateTitle(subject)
                .onFailure { messenger.notifyError(it.message ?: "اسم المادة مطلوب") }
                .getOrNull() ?: return@launch

            val validDate = InputValidator.validateDate(date.orEmpty())
                .onFailure { messenger.notifyError("حدد تاريخ الامتحان") }
                .getOrNull() ?: return@launch

            runCatching {
                if (editing == null) {
                    examRepository.create(
                        ExamEntity(
                            subject = validSubject,
                            type = type,
                            date = validDate,
                            time = time.orEmpty(),
                            room = InputValidator.sanitizeName(room),
                            notes = InputValidator.sanitizeText(notes)
                        )
                    )
                } else {
                    examRepository.update(
                        editing.copy(
                            subject = validSubject,
                            type = type,
                            date = validDate,
                            time = time.orEmpty(),
                            room = InputValidator.sanitizeName(room),
                            notes = InputValidator.sanitizeText(notes)
                        )
                    )
                }
            }.onSuccess {
                messenger.notify(
                    if (editing == null) "أُضيف الامتحان وستصلك تذكيرات به" else "تم التحديث"
                )
            }.onFailure { messenger.notifyError("تعذّر حفظ الامتحان") }
        }
    }

    fun delete(exam: ExamEntity) {
        viewModelScope.launch {
            runCatching { examRepository.delete(exam) }
                .onSuccess { messenger.notify("حُذف الامتحان وأُلغيت تذكيراته") }
                .onFailure { messenger.notifyError("تعذّر حذف الامتحان") }
        }
    }
}
