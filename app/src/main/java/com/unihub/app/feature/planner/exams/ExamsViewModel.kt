package com.unihub.app.feature.planner.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.DateFormats
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.core.validation.UserInputRules
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
        notes: String,
        onResult: (error: String?) -> Unit = {}
    ) {
        viewModelScope.launch {
            // إصلاح: الأخطاء تعود للنموذج المفتوح بدل Snackbar خلف لوح مغلق
            val validSubject = InputValidator.validateTitle(subject)
                .getOrElse {
                    onResult(it.message ?: "اسم المادة مطلوب")
                    return@launch
                }

            val validDate = InputValidator.validateDate(date.orEmpty())
                .getOrElse {
                    onResult("حدد تاريخ الامتحان")
                    return@launch
                }

            val validRoom = InputValidator.sanitizeName(room)
            if (validRoom.length > InputValidator.MAX_NAME_LENGTH) {
                onResult("اسم القاعة أطول من المسموح")
                return@launch
            }

            // إصلاح: الضغط مرتين على «حفظ» أو إدخال نفس الامتحان مرتين كان يُنشئ
            // نسخاً مكررة وتذكيرات مضاعفة لنفس الموعد.
            val duplicate = runCatching { examRepository.allOnce() }.getOrDefault(emptyList())
                .firstOrNull { other ->
                    other.id != (editing?.id ?: -1L) &&
                        UserInputRules.isDuplicateExam(
                            validSubject, type.name, validDate, time.orEmpty(),
                            other.subject, other.type.name, other.date, other.time
                        )
                }
            if (duplicate != null) {
                onResult("يوجد امتحان «${duplicate.subject}» من نفس النوع في نفس التاريخ مسبقاً")
                return@launch
            }

            runCatching {
                if (editing == null) {
                    examRepository.create(
                        ExamEntity(
                            subject = validSubject,
                            type = type,
                            date = validDate,
                            time = time.orEmpty(),
                            room = validRoom,
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
                            room = validRoom,
                            notes = InputValidator.sanitizeText(notes)
                        )
                    )
                }
            }.onSuccess {
                val past = DateFormats.daysUntil(validDate)?.let { it < 0 } == true
                messenger.notify(
                    when {
                        editing != null -> "تم التحديث"
                        past -> "أُضيف الامتحان (تاريخه مضى فلن تصلك تذكيرات)"
                        else -> "أُضيف الامتحان وستصلك تذكيرات به"
                    }
                )
                onResult(null)
            }.onFailure { onResult("تعذّر حفظ الامتحان، حاول مجدداً") }
        }
    }

    fun delete(exam: ExamEntity) {
        viewModelScope.launch {
            runCatching { examRepository.delete(exam) }
                .onSuccess { messenger.notify("حُذف الامتحان") }
                .onFailure { messenger.notifyError("تعذّر حذف الامتحان") }
        }
    }
}

