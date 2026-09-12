package com.unihub.app.feature.planner.notes

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.local.entity.NoteEntity
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.UiMessagesHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** تبويب الملاحظات: بطاقات قابلة للبحث مع تثبيت الملاحظات المهمة */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesTab(viewModel: NotesViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var sheetNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NoteEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                sheetNote = null
                showSheet = true
            }) {
                Icon(Icons.Filled.EditNote, contentDescription = "ملاحظة جديدة")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    placeholder = { Text("ابحث في الملاحظات…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
            }

            if (notes.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.StickyNote2,
                        title = if (searchQuery.isBlank()) "لا ملاحظات بعد" else "لا نتائج للبحث",
                        subtitle = if (searchQuery.isBlank()) {
                            "اكتب أفكارك وملخصاتك — الملاحظات المثبتة تظهر أولاً دائماً"
                        } else {
                            "جرّب كلمات أخرى"
                        }
                    )
                }
            }

            items(notes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onClick = {
                        sheetNote = note
                        showSheet = true
                    },
                    onTogglePin = { viewModel.togglePin(note) },
                    onLongPress = { deleteTarget = note }
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }

        if (showSheet) {
            NoteSheet(
                editing = sheetNote,
                onDismiss = { showSheet = false },
                onSave = { title, content ->
                    viewModel.save(sheetNote, title, content)
                    showSheet = false
                },
                onTogglePin = sheetNote?.let { existing ->
                    {
                        viewModel.togglePin(existing)
                        showSheet = false
                    }
                },
                onDelete = sheetNote?.let { existing ->
                    {
                        deleteTarget = existing
                        showSheet = false
                    }
                }
            )
        }

        deleteTarget?.let { note ->
            ConfirmDialog(
                title = "حذف الملاحظة؟",
                message = "ستُحذف \"${note.title}\" نهائياً.",
                onConfirm = {
                    viewModel.delete(note)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onLongPress: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    Card(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.isPinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "مثبتة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(note.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onTogglePin) {
                    Text(if (note.isPinned) "إلغاء التثبيت" else "تثبيت")
                }
            }
        }
    }
}

@Composable
private fun NoteSheet(
    editing: NoteEntity?,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit,
    onTogglePin: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var content by remember { mutableStateOf(editing?.content ?: "") }

    AppSheet(
        title = if (editing == null) "ملاحظة جديدة" else "تعديل الملاحظة",
        onDismiss = onDismiss,
        actions = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
            if (onTogglePin != null && editing != null) {
                TextButton(onClick = onTogglePin) {
                    Text(if (editing.isPinned) "إلغاء التثبيت" else "تثبيت")
                }
            }
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(onClick = { onSave(title, content) }) { Text("حفظ") }
        }
    ) {
        Field(label = "العنوان", value = title, onValueChange = { title = it })
        Field(
            label = "المحتوى",
            value = content,
            onValueChange = { content = it },
            singleLine = false
        )
    }
}
