package com.unihub.app.feature.planner.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.common.DateFormats
import com.unihub.app.data.local.entity.TaskEntity
import com.unihub.app.data.local.entity.TaskPriority
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ChoiceChips
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.DateField
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.TintChip
import com.unihub.app.ui.components.UiMessagesHost
import com.unihub.app.ui.theme.SemanticDanger
import com.unihub.app.ui.theme.SemanticSuccess
import com.unihub.app.ui.theme.SemanticWarning

/** تبويب المهام: قائمة بمرشحات + ورقة إضافة/تعديل سفلية */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksTab(viewModel: TasksViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val filter by viewModel.currentFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var sheetTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TaskEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                sheetTask = null
                showSheet = true
            }) {
                Icon(Icons.Outlined.AddTask, contentDescription = "مهمة جديدة")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ابحث في المهام…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )
            Spacer(Modifier.height(10.dp))
            ChoiceChips(
                labels = listOf("الكل", "قيد التنفيذ", "منجزة"),
                selectedIndex = when (filter) {
                    TaskFilter.ALL -> 0
                    TaskFilter.PENDING -> 1
                    TaskFilter.DONE -> 2
                },
                onSelect = { index ->
                    viewModel.setFilter(TaskFilter.entries[index])
                }
            )
            Spacer(Modifier.height(12.dp))

            if (tasks.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = if (searchQuery.isNotBlank()) "لا نتائج للبحث"
                    else when (filter) {
                        TaskFilter.PENDING -> "لا مهام قيد التنفيذ"
                        TaskFilter.DONE -> "لا مهام منجزة بعد"
                        TaskFilter.ALL -> "لا مهام بعد"
                    },
                    subtitle = if (searchQuery.isNotBlank()) {
                        "جرّب كلمات بحث أخرى أو غيّر المرشح"
                    } else {
                        "أضف مهمة من زر + وحدد أولويتها وموعد استحقاقها"
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { viewModel.toggle(task) },
                            onEdit = {
                                sheetTask = task
                                showSheet = true
                            },
                            onLongPress = { deleteTarget = task }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        if (showSheet) {
            TaskSheet(
                editing = sheetTask,
                onDismiss = { showSheet = false },
                onSave = { title, description, priority, dueDate ->
                    viewModel.save(sheetTask, title, description, priority, dueDate)
                    showSheet = false
                },
                onDelete = sheetTask?.let { existing ->
                    {
                        deleteTarget = existing
                        showSheet = false
                    }
                }
            )
        }

        deleteTarget?.let { task ->
            ConfirmDialog(
                title = "حذف المهمة؟",
                message = "ستُحذف \"${task.title}\" مع تذكيرها المجدول.",
                onConfirm = {
                    viewModel.delete(task)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    task: TaskEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onEdit, onLongClick = onLongPress)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = task.isDone, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    color = if (task.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (task.description.isNotBlank()) {
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PriorityChip(task.priority)
                DateFormats.friendlyDueLabel(task.dueDate)?.let { label ->
                    TintChip(
                        text = label,
                        containerColor = if (task.isOverdue) {
                            SemanticDanger.copy(alpha = 0.15f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (task.isOverdue) {
                            SemanticDanger
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PriorityChip(priority: TaskPriority) {
    val (container, content) = when (priority) {
        TaskPriority.HIGH -> SemanticDanger.copy(alpha = 0.15f) to SemanticDanger
        TaskPriority.MEDIUM -> SemanticWarning.copy(alpha = 0.18f) to SemanticWarning
        TaskPriority.LOW -> SemanticSuccess.copy(alpha = 0.15f) to SemanticSuccess
    }
    TintChip(text = priority.label, containerColor = container, contentColor = content)
}

@Composable
private fun TaskSheet(
    editing: TaskEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, priority: TaskPriority, dueDate: String?) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var description by remember { mutableStateOf(editing?.description ?: "") }
    var priority by remember { mutableStateOf(editing?.priority ?: TaskPriority.MEDIUM) }
    var dueDate by remember { mutableStateOf(editing?.dueDate) }
    var titleError by remember { mutableStateOf<String?>(null) }

    AppSheet(
        title = if (editing == null) "مهمة جديدة" else "تعديل المهمة",
        onDismiss = onDismiss,
        actions = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(onClick = {
                if (title.isBlank()) {
                    titleError = "عنوان المهمة مطلوب"
                } else {
                    onSave(title, description, priority, dueDate)
                }
            }) { Text("حفظ") }
        }
    ) {
        Field(
            label = "عنوان المهمة",
            value = title,
            onValueChange = {
                title = it
                titleError = null
            },
            errorText = titleError
        )
        Field(
            label = "وصف (اختياري)",
            value = description,
            onValueChange = { description = it },
            singleLine = false
        )
        Text("الأولوية", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            labels = TaskPriority.entries.map { it.label },
            selectedIndex = TaskPriority.entries.indexOf(priority),
            onSelect = { priority = TaskPriority.entries[it] }
        )
        DateField(
            label = "موعد الاستحقاق (اختياري)",
            valueIso = dueDate,
            onPick = { dueDate = it },
            onClear = { dueDate = null }
        )
    }
}
