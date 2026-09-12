package com.unihub.app.feature.planner.exams

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.common.DateFormats
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.ExamType
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ChoiceChips
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.DateField
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.SectionHeader
import com.unihub.app.ui.components.TintChip
import com.unihub.app.ui.components.TimeField
import com.unihub.app.ui.components.UiMessagesHost
import com.unihub.app.ui.theme.SemanticDanger
import com.unihub.app.ui.theme.SemanticWarning

/** تبويب الامتحانات: القادمة أولاً مع عدّاد الأيام، والسابقة أسفلها */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsTab(viewModel: ExamsViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val exams by viewModel.exams.collectAsStateWithLifecycle()
    val upcoming = exams.filterNot { it.isPast }
    val past = exams.filter { it.isPast }.sortedByDescending { it.date }

    var sheetExam by remember { mutableStateOf<ExamEntity?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ExamEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                sheetExam = null
                showSheet = true
            }) {
                Icon(Icons.Outlined.Event, contentDescription = "امتحان جديد")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            if (exams.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.School,
                    title = "لا امتحانات مسجلة",
                    subtitle = "أضف امتحاناتك وستصلك تذكيرات قبلها بيوم وساعة وعند البدء"
                )
            } else {
                if (upcoming.isNotEmpty()) {
                    SectionHeader(title = "القادمة (${upcoming.size})")
                    upcoming.forEach { exam ->
                        ExamRow(
                            exam = exam,
                            onEdit = {
                                sheetExam = exam
                                showSheet = true
                            },
                            onLongPress = { deleteTarget = exam }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (past.isNotEmpty()) {
                    SectionHeader(title = "انتهت (${past.size})")
                    past.forEach { exam ->
                        ExamRow(
                            exam = exam,
                            dimmed = true,
                            onEdit = {
                                sheetExam = exam
                                showSheet = true
                            },
                            onLongPress = { deleteTarget = exam }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }

        if (showSheet) {
            ExamSheet(
                editing = sheetExam,
                onDismiss = { showSheet = false },
                onSave = { subject, type, date, time, room, notes ->
                    viewModel.save(sheetExam, subject, type, date, time, room, notes)
                    showSheet = false
                },
                onDelete = sheetExam?.let { existing ->
                    {
                        deleteTarget = existing
                        showSheet = false
                    }
                }
            )
        }

        deleteTarget?.let { exam ->
            ConfirmDialog(
                title = "حذف الامتحان؟",
                message = "سيُحذف امتحان \"${exam.subject}\" وتُلغى كل تذكيراته.",
                onConfirm = {
                    viewModel.delete(exam)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExamRow(
    exam: ExamEntity,
    dimmed: Boolean = false,
    onEdit: () -> Unit,
    onLongPress: () -> Unit
) {
    val days = exam.daysRemaining
    Card(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onEdit, onLongClick = onLongPress)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = exam.subject,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TintChip(
                        text = exam.type.label,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = DateFormats.format(exam.date) +
                            if (exam.time.isNotBlank()) " • ${exam.time}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                dimmed -> TintChip(
                    text = "انتهى",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                days != null -> {
                    val urgent = days <= 2
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = when {
                                days == 0L -> "اليوم!"
                                days == 1L -> "غداً"
                                days == 2L -> "بعد يومين"
                                else -> "بعد $days أيام"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (urgent) SemanticDanger
                            else if (days <= 7) SemanticWarning
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamSheet(
    editing: ExamEntity?,
    onDismiss: () -> Unit,
    onSave: (subject: String, type: ExamType, date: String?, time: String?, room: String, notes: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var subject by remember { mutableStateOf(editing?.subject ?: "") }
    var type by remember { mutableStateOf(editing?.type ?: ExamType.FINAL) }
    var date by remember { mutableStateOf(editing?.date) }
    var time by remember { mutableStateOf(editing?.time?.takeIf { it.isNotBlank() }) }
    var room by remember { mutableStateOf(editing?.room ?: "") }
    var notes by remember { mutableStateOf(editing?.notes ?: "") }
    var subjectError by remember { mutableStateOf<String?>(null) }
    var dateError by remember { mutableStateOf<String?>(null) }

    AppSheet(
        title = if (editing == null) "امتحان جديد" else "تعديل الامتحان",
        onDismiss = onDismiss,
        actions = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(onClick = {
                subjectError = if (subject.isBlank()) "اسم المادة مطلوب" else null
                dateError = if (date == null) "حدد تاريخ الامتحان" else null
                if (subjectError == null && dateError == null) {
                    onSave(subject, type, date, time, room, notes)
                }
            }) { Text("حفظ") }
        }
    ) {
        Field(
            label = "اسم المادة",
            value = subject,
            onValueChange = {
                subject = it
                subjectError = null
            },
            errorText = subjectError
        )
        Text("نوع الامتحان", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            labels = ExamType.entries.map { it.label },
            selectedIndex = ExamType.entries.indexOf(type),
            onSelect = { type = ExamType.entries[it] }
        )
        DateField(
            label = "التاريخ",
            valueIso = date,
            onPick = {
                date = it
                dateError = null
            }
        )
        if (dateError != null) {
            Text(dateError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
        TimeField(
            label = "الوقت (اختياري — يُفترض 9:00)",
            value = time,
            onPick = { time = it },
            onClear = { time = null }
        )
        Field(label = "القاعة (اختياري)", value = room, onValueChange = { room = it })
        Field(
            label = "ملاحظات (اختياري)",
            value = notes,
            onValueChange = { notes = it },
            singleLine = false
        )
    }
}
