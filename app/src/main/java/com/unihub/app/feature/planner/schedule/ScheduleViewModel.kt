package com.unihub.app.feature.planner.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.DateFormats
import com.unihub.app.core.common.NextLectureResolver
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.core.validation.UserInputRules
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
        room: String,
        onResult: (error: String?) -> Unit = {}
    ) {
        viewModelScope.launch {
            // إصلاح: كل الأخطاء تعود للوح المفتوح عبر [onResult]؛ سابقاً كان اللوح
            // يُغلق فوراً ثم يُرفض الحفظ (نسيان وقت البداية / تعارض) فيضيع الإدخال كله.
            val validSubject = InputValidator.validateTitle(subject)
                .getOrElse {
                    onResult(it.message ?: "اسم المادة مطلوب")
                    return@launch
                }

            UserInputRules.lectureFormError(validSubject, timeFrom, timeTo)?.let {
                onResult(it)
                return@launch
            }
            if (timeFrom.isNullOrBlank()) return@launch // مضمون أعلاه؛ لتضييق النوع فقط

            val validDoctor = InputValidator.sanitizeName(doctor)
            val validRoom = InputValidator.sanitizeName(room)
            if (validDoctor.length > InputValidator.MAX_NAME_LENGTH || validRoom.length > InputValidator.MAX_NAME_LENGTH) {
                onResult("اسم الدكتور أو القاعة أطول من المسموح")
                return@launch
            }

            // ذكاء ضد أخطاء المستخدم: رفض محاضرة تتعارض زمنياً مع أخرى في نفس اليوم
            // إصلاح: فشل قراءة الجدول كان يُبتلع (getOrNull) فتُحفظ المحاضرة دون فحص التعارض
            val existing = runCatching { lectureRepository.allOnce() }.getOrElse {
                onResult("تعذّر التحقق من تعارض المواعيد، حاول مجدداً")
                return@launch
            }
            val clash = existing.firstOrNull { other ->
                other.day == day &&
                    other.id != (editing?.id ?: -1L) &&
                    NextLectureResolver.timeRangesOverlap(
                        other.timeFrom, other.timeTo,
                        timeFrom, timeTo.orEmpty()
                    )
            }
            if (clash != null) {
                val clashEnd = clash.timeTo.takeIf { it.isNotBlank() }
                    ?.let { " حتى ${DateFormats.formatTime12(it)}" }.orEmpty()
                onResult(
                    "تعارض: «${clash.subject}» من ${DateFormats.formatTime12(clash.timeFrom)}" +
                        "$clashEnd في نفس اليوم"
                )
                return@launch
            }

            runCatching {
                if (editing == null) {
                    lectureRepository.create(
                        LectureEntity(
                            subject = validSubject,
                            doctor = validDoctor,
                            day = day,
                            timeFrom = timeFrom,
                            timeTo = timeTo.orEmpty(),
                            room = validRoom
                        )
                    )
                } else {
                    lectureRepository.update(
                        editing.copy(
                            subject = validSubject,
                            doctor = validDoctor,
                            day = day,
                            timeFrom = timeFrom,
                            timeTo = timeTo.orEmpty(),
                            room = validRoom
                        )
                    )
                }
            }.onSuccess {
                messenger.notify(if (editing == null) "أُضيفت المحاضرة" else "تم التحديث")
                onResult(null)
            }.onFailure { onResult("تعذّر حفظ المحاضرة، حاول مجدداً") }
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

