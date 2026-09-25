package com.unihub.app.feature.planner.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.data.local.entity.NoteEntity
import com.unihub.app.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val noteRepository: NoteRepository
) : ViewModel() {

    val messenger = UiMessenger()

    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query.asStateFlow()

    private val allNotes = noteRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** الملاحظات المعروضة بعد البحث — البحث يشمل العنوان والمحتوى */
    val notes: StateFlow<List<NoteEntity>> =
        combine(allNotes, query) { notes, q ->
            // القص عند الاستخدام لا أثناء الكتابة (انظر setSearchQuery) حتى
            // تبقى مسافة لوحة المفاتيح قابلة للكتابة في البحث متعدد الكلمات
            val trimmed = q.trim()
            if (trimmed.isBlank()) notes
            else notes.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                    it.content.contains(trimmed, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** تخزين نص البحث كما كُتب — القص يحدث عند الاستخدام داخل المرشح فقط */
    fun setSearchQuery(value: String) {
        query.value = value
    }

    fun save(editing: NoteEntity?, title: String, content: String) {
        viewModelScope.launch {
            // ملاحظة فارغة تماماً = خطأ مستخدم — نرفضها بلطف بدل حفظ ورقة بيضاء
            if (editing == null && title.isBlank() && content.isBlank()) {
                messenger.notifyError("اكتب عنواناً أو محتوى للملاحظة أولاً")
                return@launch
            }

            val validTitle = InputValidator.validateTitle(title.ifBlank { "ملاحظة بلا عنوان" })
                .getOrElse { "ملاحظة بلا عنوان" }

            runCatching {
                if (editing == null) {
                    noteRepository.create(
                        NoteEntity(
                            title = validTitle,
                            content = InputValidator.sanitizeText(content)
                        )
                    )
                } else {
                    noteRepository.update(
                        editing.copy(
                            title = validTitle,
                            content = InputValidator.sanitizeText(content)
                        )
                    )
                }
            }.onSuccess { messenger.notify(if (editing == null) "أُضيفت الملاحظة" else "تم الحفظ") }
                .onFailure { messenger.notifyError("تعذّر حفظ الملاحظة") }
        }
    }

    fun togglePin(note: NoteEntity) {
        viewModelScope.launch {
            runCatching { noteRepository.togglePinned(note) }
                .onFailure { messenger.notifyError("تعذّر تحديث التثبيت") }
        }
    }

    fun delete(note: NoteEntity) {
        viewModelScope.launch {
            runCatching { noteRepository.delete(note) }
                .onSuccess { messenger.notify("حُذفت الملاحظة") }
                .onFailure { messenger.notifyError("تعذّر حذف الملاحظة") }
        }
    }
}
