package com.unihub.app.feature.planner.schedule

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
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import com.unihub.app.core.common.Weekdays
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.Weekday
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ChoiceChips
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.SectionHeader
import com.unihub.app.ui.components.TintChip
import com.unihub.app.ui.components.TimeField
import com.unihub.app.ui.components.UiMessagesHost

/** تبويب الجدول الأسبوعي: المحاضرات مجمعة حسب اليوم مع إبراز يوم اليوم */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTab(viewModel: ScheduleViewModel = hiltViewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val lectures by viewModel.lectures.collectAsStateWithLifecycle()

    var sheetLecture by remember { mutableStateOf<LectureEntity?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LectureEntity?>(null) }

    val today = Weekdays.today()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                sheetLecture = null
                showSheet = true
            }) {
                Icon(Icons.AutoMirrored.Outlined.EventNote, contentDescription = "محاضرة جديدة")
            }
        }
    ) { padding ->
        if (lectures.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "جدولك فارغ",
                    subtitle = "أضف محاضراتك الأسبوعية لتظهر هنا وفي الرئيسية حسب اليوم"
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
            ) {
                Weekday.entries.forEach { day ->
                    val dayLectures = lectures.filter { it.day == day }
                    if (dayLectures.isEmpty()) return@forEach

                    SectionHeader(
                        title = day.label,
                        action = {
                            if (day == today) {
                                TintChip(
                                    text = "اليوم",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    )
                    dayLectures.forEach { lecture ->
                        LectureRow(
                            lecture = lecture,
                            isToday = day == today,
                            onEdit = {
                                sheetLecture = lecture
                                showSheet = true
                            },
                            onLongPress = { deleteTarget = lecture }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }

        if (showSheet) {
            LectureSheet(
                editing = sheetLecture,
                onDismiss = { showSheet = false },
                onSave = { subject, doctor, day, from, to, room ->
                    viewModel.save(sheetLecture, subject, doctor, day, from, to, room)
                    showSheet = false
                },
                onDelete = sheetLecture?.let { existing ->
                    {
                        deleteTarget = existing
                        showSheet = false
                    }
                }
            )
        }

        deleteTarget?.let { lecture ->
            ConfirmDialog(
                title = "حذف المحاضرة؟",
                message = "ستُحذف محاضرة \"${lecture.subject}\" من الجدول.",
                onConfirm = {
                    viewModel.delete(lecture)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LectureRow(
    lecture: LectureEntity,
    isToday: Boolean,
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(64.dp)) {
                Text(
                    text = lecture.timeFrom,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                if (lecture.timeTo.isNotBlank()) {
                    Text(
                        text = lecture.timeTo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = lecture.subject,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    lecture.doctor.takeIf { it.isNotBlank() },
                    lecture.room.takeIf { it.isNotBlank() }?.let { "قاعة $it" }
                ).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LectureSheet(
    editing: LectureEntity?,
    onDismiss: () -> Unit,
    onSave: (subject: String, doctor: String, day: Weekday, from: String?, to: String?, room: String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var subject by remember { mutableStateOf(editing?.subject ?: "") }
    var doctor by remember { mutableStateOf(editing?.doctor ?: "") }
    var day by remember { mutableStateOf(editing?.day ?: Weekdays.today()) }
    var from by remember { mutableStateOf(editing?.timeFrom) }
    var to by remember { mutableStateOf(editing?.timeTo?.takeIf { it.isNotBlank() }) }
    var room by remember { mutableStateOf(editing?.room ?: "") }
    var subjectError by remember { mutableStateOf<String?>(null) }

    AppSheet(
        title = if (editing == null) "محاضرة جديدة" else "تعديل المحاضرة",
        onDismiss = onDismiss,
        actions = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(onClick = {
                if (subject.isBlank()) subjectError = "اسم المادة مطلوب"
                else onSave(subject, doctor, day, from, to, room)
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
        Field(label = "اسم الدكتور (اختياري)", value = doctor, onValueChange = { doctor = it })
        Text("اليوم", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            labels = Weekday.entries.map { it.shortLabel },
            selectedIndex = Weekday.entries.indexOf(day),
            onSelect = { day = Weekday.entries[it] }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeField(
                label = "من",
                value = from,
                onPick = { from = it },
                onClear = { from = null },
                modifier = Modifier.weight(1f)
            )
            TimeField(
                label = "إلى (اختياري)",
                value = to,
                onPick = { to = it },
                onClear = { to = null },
                modifier = Modifier.weight(1f)
            )
        }
        Field(label = "القاعة (اختياري)", value = room, onValueChange = { room = it })
    }
}
