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
import androidx.compose.material.icons.filled.GridOn
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
import com.unihub.app.core.common.Weekdays
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.Weekday
import com.unihub.app.ui.components.AppSheet
import com.unihub.app.ui.components.ChoiceChips
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.FeatureHeroCard
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.FormErrorText
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.core.validation.UserInputRules
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
    // العرض الشبكي: عمود أيام اليمين + صف تواقيت أعلى + محاضرات ممددة
    var showGrid by remember { mutableStateOf(false) }

    val today = Weekdays.today()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // عمود زرين: زر الجدول الشبكي فوق زر «محاضرة جديدة» تماماً كما
            // طلبت التعليمات («زر فوق الذي يضيف توقيت واسم محاضرة جديدة»)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(onClick = { showGrid = !showGrid }) {
                    Icon(
                        imageVector = if (showGrid) Icons.AutoMirrored.Outlined.MenuBook
                        else Icons.Filled.GridOn,
                        contentDescription = if (showGrid) "العودة لقائمة المحاضرات"
                        else "عرض الجدول الزمني الشبكي"
                    )
                }
                FloatingActionButton(onClick = {
                    sheetLecture = null
                    showSheet = true
                }) {
                    Icon(Icons.AutoMirrored.Outlined.EventNote, contentDescription = "محاضرة جديدة")
                }
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
                    subtitle = "أضف محاضرات أسبوعية"
                )
            }
        } else if (showGrid) {
            // الجدول الشبكي: تواقيت مشتقة من المحاضرات نفسها + تمدد أفقي حسب المدة
            WeeklyTimetableGrid(
                lectures = lectures,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 10.dp)
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                FeatureHeroCard(
                    title = "مخطط الأسبوع",
                    subtitle = "مواعيد المحاضرات",
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    badge = "${lectures.size} محاضرة"
                )
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
                onSave = { subject, doctor, day, from, to, room, reportError ->
                    viewModel.save(sheetLecture, subject, doctor, day, from, to, room) { error ->
                        if (error == null) showSheet = false else reportError(error)
                    }
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
                message = "حذف المحاضرة؟",
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
                    text = DateFormats.formatTime12(lecture.timeFrom),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                if (lecture.timeTo.isNotBlank()) {
                    Text(
                        text = DateFormats.formatTime12(lecture.timeTo),
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
    onSave: (
        subject: String,
        doctor: String,
        day: Weekday,
        from: String?,
        to: String?,
        room: String,
        reportError: (String) -> Unit
    ) -> Unit,
    onDelete: (() -> Unit)?
) {
    var subject by remember { mutableStateOf(editing?.subject ?: "") }
    var doctor by remember { mutableStateOf(editing?.doctor ?: "") }
    var day by remember { mutableStateOf(editing?.day ?: Weekdays.today()) }
    var from by remember { mutableStateOf(editing?.timeFrom) }
    var to by remember { mutableStateOf(editing?.timeTo?.takeIf { it.isNotBlank() }) }
    var room by remember { mutableStateOf(editing?.room ?: "") }
    var subjectError by remember { mutableStateOf<String?>(null) }
    var fromError by remember { mutableStateOf<String?>(null) }
    var toError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val hasChanges = subject != (editing?.subject ?: "") ||
        doctor != (editing?.doctor ?: "") ||
        (editing != null && day != editing.day) ||
        from != editing?.timeFrom ||
        to != editing?.timeTo?.takeIf { it.isNotBlank() } ||
        room != (editing?.room ?: "")

    AppSheet(
        title = if (editing == null) "محاضرة جديدة" else "تعديل المحاضرة",
        onDismiss = onDismiss,
        hasUnsavedChanges = hasChanges && !saving,
        actions = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss) { Text("إلغاء") }
            FilledTonalButton(enabled = !saving, onClick = {
                formError = null
                // إصلاح: فحص وقت البداية ونطاق الوقت هنا قبل الإرسال، مع إبراز الحقل المخطئ
                subjectError = if (subject.isBlank()) "اسم المادة مطلوب" else null
                fromError = if (from.isNullOrBlank()) "حدد وقت البداية" else null
                val rangeError = UserInputRules.lectureFormError(subject, from, to)
                toError = if (subjectError == null && fromError == null) rangeError else null
                if (subjectError == null && fromError == null && toError == null) {
                    saving = true
                    onSave(subject, doctor, day, from, to, room) { error ->
                        saving = false
                        formError = error
                    }
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
            errorText = subjectError,
            maxLength = InputValidator.MAX_TITLE_LENGTH
        )
        Field(
            label = "اسم الدكتور (اختياري)",
            value = doctor,
            onValueChange = { doctor = it },
            maxLength = InputValidator.MAX_NAME_LENGTH
        )
        Text("اليوم", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            labels = Weekday.entries.map { it.shortLabel },
            selectedIndex = Weekday.entries.indexOf(day),
            onSelect = {
                day = Weekday.entries[it]
                formError = null
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeField(
                label = "من",
                value = from,
                onPick = {
                    from = it
                    fromError = null
                    toError = null
                },
                onClear = { from = null },
                modifier = Modifier.weight(1f),
                errorText = fromError
            )
            TimeField(
                label = "إلى (اختياري)",
                value = to,
                onPick = {
                    to = it
                    toError = null
                },
                onClear = {
                    to = null
                    toError = null
                },
                modifier = Modifier.weight(1f),
                errorText = toError
            )
        }
        Field(
            label = "القاعة (اختياري)",
            value = room,
            onValueChange = { room = it },
            maxLength = InputValidator.MAX_NAME_LENGTH
        )
        FormErrorText(formError)
    }
}

