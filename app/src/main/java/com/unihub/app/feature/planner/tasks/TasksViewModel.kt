package com.unihub.app.feature.planner.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.data.local.entity.TaskEntity
import com.unihub.app.data.local.entity.TaskPriority
import com.unihub.app.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** مرشح عرض المهام */
enum class TaskFilter { ALL, PENDING, DONE }

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository
) : ViewModel() {

    val messenger = UiMessenger()

    private val filter = MutableStateFlow(TaskFilter.ALL)
    val currentFilter: StateFlow<TaskFilter> = filter.asStateFlow()

    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query.asStateFlow()

    private val allTasks = taskRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** القائمة المعروضة بعد تطبيق المرشح ثم البحث (العنوان والوصف) */
    val tasks: StateFlow<List<TaskEntity>> =
        combine(allTasks, filter, query) { tasks, f, q ->
            val filtered = when (f) {
                TaskFilter.ALL -> tasks
                TaskFilter.PENDING -> tasks.filterNot { it.isDone }
                TaskFilter.DONE -> tasks.filter { it.isDone }
            }
            if (q.isBlank()) filtered
            else filtered.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(value: TaskFilter) {
        filter.value = value
    }

    fun setSearchQuery(value: String) {
        query.value = value.trim()
    }

    fun toggle(task: TaskEntity) {
        viewModelScope.launch {
            runCatching { taskRepository.toggleDone(task) }
                .onFailure { messenger.notifyError("تعذّر تحديث المهمة") }
        }
    }

    /** إنشاء أو تعديل حسب وجود معرّف */
    fun save(
        editing: TaskEntity?,
        title: String,
        description: String,
        priority: TaskPriority,
        dueDate: String?
    ) {
        viewModelScope.launch {
            val validTitle = InputValidator.validateTitle(title)
                .onFailure { messenger.notifyError(it.message ?: "عنوان غير صالح") }
                .getOrNull() ?: return@launch

            runCatching {
                if (editing == null) {
                    taskRepository.create(
                        TaskEntity(
                            title = validTitle,
                            description = InputValidator.sanitizeText(description),
                            priority = priority,
                            dueDate = dueDate
                        )
                    )
                } else {
                    taskRepository.update(
                        editing.copy(
                            title = validTitle,
                            description = InputValidator.sanitizeText(description),
                            priority = priority,
                            dueDate = dueDate
                        )
                    )
                }
            }.onSuccess { messenger.notify(if (editing == null) "أُضيفت المهمة" else "تم التحديث") }
                .onFailure { messenger.notifyError("تعذّر حفظ المهمة") }
        }
    }

    fun delete(task: TaskEntity) {
        viewModelScope.launch {
            runCatching { taskRepository.delete(task) }
                .onSuccess { messenger.notify("حُذفت المهمة") }
                .onFailure { messenger.notifyError("تعذّر حذف المهمة") }
        }
    }
}
