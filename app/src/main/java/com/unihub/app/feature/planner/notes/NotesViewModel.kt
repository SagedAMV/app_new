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

    private val filter = MutableStateFlow(NoteFilter.ALL)
    val currentFilter: StateFlow<NoteFilter> = filter.asStateFlow()

    private val allNotes = noteRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * الملاحظات المعروضة بعد تطبيق مرشح النوع والبحث الذكي.
     * يبحث في العنوان والملخص والنصوص الفعلية داخل الفقرات والبطاقات والجداول والمهام
     * دون مطابقة مفاتيح JSON الداخلية أو نقاط الرسم.
     */
    val notes: StateFlow<List<NoteEntity>> =
        combine(allNotes, query, filter) { notes, q, currentFilter ->
            notes.filter { note ->
                NoteWorkspaceCodec.matchesFilterAndQuery(
                    title = note.title,
                    rawContent = note.content,
                    isPinned = note.isPinned,
                    filter = currentFilter,
                    query = q
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** تخزين نص البحث كما كُتب — القص يحدث عند الاستخدام داخل المرشح فقط */
    fun setSearchQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: NoteFilter) {
        filter.value = value
    }

    /**
     * حفظ وثيقة ملاحظة غنية (إنشاء أو تعديل).
     * يعيد `true` إذا قُبل الحفظ، و`false` إذا كانت الملاحظة فارغة تماماً.
     */
    fun saveDocument(
        editing: NoteEntity?,
        title: String,
        isPinned: Boolean,
        document: NoteWorkspaceDocument
    ): Boolean {
        val hasTitle = title.isNotBlank()
        val hasContent = document.hasMeaningfulContent()
        if (!hasTitle && !hasContent) {
            messenger.notifyError("اكتب عنواناً أو أضف محتوى للملاحظة أولاً")
            return false
        }

        val candidateTitle = title.trim().ifBlank { document.suggestedFallbackTitle() }
        val validTitle = InputValidator.validateTitle(candidateTitle)
            .getOrElse { candidateTitle.take(InputValidator.MAX_TITLE_LENGTH).ifBlank { "ملاحظة بلا عنوان" } }

        val encodedContent = NoteWorkspaceCodec.encode(document)

        viewModelScope.launch {
            runCatching {
                if (editing == null) {
                    noteRepository.create(
                        NoteEntity(
                            title = validTitle,
                            content = encodedContent,
                            isPinned = isPinned
                        )
                    )
                } else {
                    noteRepository.update(
                        editing.copy(
                            title = validTitle,
                            content = encodedContent,
                            isPinned = isPinned
                        )
                    )
                }
            }.onSuccess {
                messenger.notify(if (editing == null) "أُضيفت الملاحظة" else "تم حفظ التعديلات")
            }.onFailure {
                messenger.notifyError("تعذّر حفظ الملاحظة")
            }
        }
        return true
    }

    fun togglePin(note: NoteEntity) {
        viewModelScope.launch {
            runCatching { noteRepository.togglePinned(note) }
                .onFailure { messenger.notifyError("تعذّر تحديث التثبيت") }
        }
    }

    fun duplicate(note: NoteEntity) {
        viewModelScope.launch {
            val copyTitle = InputValidator.validateTitle("${note.title} (نسخة)")
                .getOrElse { note.title.take(InputValidator.MAX_TITLE_LENGTH) }
            runCatching {
                noteRepository.create(
                    NoteEntity(
                        title = copyTitle,
                        content = note.content,
                        isPinned = note.isPinned
                    )
                )
            }.onSuccess {
                messenger.notify("تم نسخ الملاحظة")
            }.onFailure {
                messenger.notifyError("تعذّر نسخ الملاحظة")
            }
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
