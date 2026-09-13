package com.unihub.app.feature.dashboard

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.common.DateFormats
import com.unihub.app.core.common.Formatters
import com.unihub.app.core.common.NextLecture
import com.unihub.app.core.common.NextLectureResolver
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.TaskEntity
import com.unihub.app.ui.components.EmptyState
import com.unihub.app.ui.components.SectionHeader
import com.unihub.app.ui.components.StatCard
import com.unihub.app.ui.components.TintChip
import com.unihub.app.ui.components.UiMessagesHost
import com.unihub.app.ui.navigation.PlannerTab
import com.unihub.app.ui.theme.SemanticDanger
import com.unihub.app.ui.theme.SemanticInfo
import com.unihub.app.ui.theme.SemanticSuccess
import com.unihub.app.ui.theme.SemanticWarning
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * الشاشة الرئيسية: لقطة اليوم العملية — محاضرات اليوم، مهام مستحقة، امتحانات قريبة،
 * وإحصاءات سريعة. تصميم هادئ ببطاقات واضحة بدل الأنيميشن المفرط في المرجع.
 */
@Composable
fun DashboardScreen(
    onOpenPlanner: (PlannerTab) -> Unit,
    onOpenFiles: (Long?) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val nextLecture by viewModel.nextLecture.collectAsStateWithLifecycle()
    val todayLectures by viewModel.todayLectures.collectAsStateWithLifecycle()
    val dueSoonTasks by viewModel.dueSoonTasks.collectAsStateWithLifecycle()
    val upcomingExams by viewModel.upcomingExams.collectAsStateWithLifecycle()
    val pendingTaskCount by viewModel.pendingTaskCount.collectAsStateWithLifecycle()
    val upcomingExamCount by viewModel.upcomingExamCount.collectAsStateWithLifecycle()
    val noteCount by viewModel.noteCount.collectAsStateWithLifecycle()
    val fileCount by viewModel.fileCount.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            // الترويسة: تحية + التاريخ + الإعدادات
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = Formatters.greeting(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = LocalDate.now().format(
                            DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy", Locale("ar"))
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "الإعدادات")
                }
            }

            Spacer(Modifier.height(16.dp))

            // بطاقة المحاضرة التالية — تقرأ الجدول الأسبوعي مباشرة بدل ترحيب ثابت
            NextLectureCard(
                next = nextLecture,
                pendingTasks = pendingTaskCount,
                upcomingExams = upcomingExamCount,
                onOpenSchedule = { onOpenPlanner(PlannerTab.SCHEDULE) }
            )

            Spacer(Modifier.height(16.dp))

            // الإحصاءات السريعة
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    icon = Icons.Outlined.TaskAlt,
                    value = pendingTaskCount.toString(),
                    label = "مهمة معلقة",
                    tint = SemanticWarning,
                    onClick = { onOpenPlanner(PlannerTab.TASKS) },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Outlined.EventAvailable,
                    value = upcomingExamCount.toString(),
                    label = "امتحان قادم",
                    tint = SemanticDanger,
                    onClick = { onOpenPlanner(PlannerTab.EXAMS) },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Outlined.Description,
                    value = noteCount.toString(),
                    label = "ملاحظة",
                    tint = SemanticInfo,
                    onClick = { onOpenPlanner(PlannerTab.NOTES) },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = Icons.Outlined.Folder,
                    value = fileCount.toString(),
                    label = "ملف",
                    tint = SemanticSuccess,
                    onClick = { onOpenFiles(null) },
                    modifier = Modifier.weight(1f)
                )
            }

            // محاضرات اليوم
            SectionHeader(
                title = "محاضرات اليوم",
                action = {
                    FilledTonalButton(onClick = { onOpenPlanner(PlannerTab.SCHEDULE) }) {
                        Text("الجدول")
                    }
                }
            )
            if (todayLectures.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.MenuBook,
                    title = "لا محاضرات اليوم",
                    subtitle = "استمتع بيومك أو أضف محاضراتك من الجدول الأسبوعي"
                )
            } else {
                todayLectures.forEach { lecture ->
                    TodayLectureRow(lecture)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // المهام المستحقة
            SectionHeader(
                title = "مهام مستحقة",
                action = {
                    FilledTonalButton(onClick = { onOpenPlanner(PlannerTab.TASKS) }) {
                        Text("الكل")
                    }
                }
            )
            if (dueSoonTasks.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = "لا مهام مستحقة الآن",
                    subtitle = "كل شيء تحت السيطرة — أضف مهمة جديدة من تبويب المهام"
                )
            } else {
                dueSoonTasks.forEach { task ->
                    DueTaskRow(
                        task = task,
                        onToggle = { viewModel.toggleTask(task) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // الامتحانات القادمة
            SectionHeader(
                title = "امتحانات قادمة",
                action = {
                    FilledTonalButton(onClick = { onOpenPlanner(PlannerTab.EXAMS) }) {
                        Text("الكل")
                    }
                }
            )
            if (upcomingExams.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.School,
                    title = "لا امتحانات قريبة",
                    subtitle = "أضف امتحاناتك لتصلك تذكيرات قبلها بيوم وساعة"
                )
            } else {
                upcomingExams.forEach { exam ->
                    UpcomingExamRow(exam)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // إجراءات سريعة
            SectionHeader(title = "إجراء سريع")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { onOpenPlanner(PlannerTab.TASKS) },
                    modifier = Modifier.weight(1f)
                ) { Text("مهمة جديدة") }
                FilledTonalButton(
                    onClick = { onOpenPlanner(PlannerTab.NOTES) },
                    modifier = Modifier.weight(1f)
                ) { Text("ملاحظة جديدة") }
                FilledTonalButton(
                    onClick = { onOpenFiles(null) },
                    modifier = Modifier.weight(1f)
                ) { Text("استيراد ملف") }
            }
        }
    }
}

/**
 * بطاقة "المحاضرة التالية" — القلب العملي للشاشة الرئيسية:
 * تقرأ الجدول الأسبوعي وتعرض أقرب محاضرة باسمها وقاعتها ووقتها مع عدّ تنازلي
 * حيّ (يتحدث كل 30 ثانية من المستودع). عند فراغ الجدول تتحول إلى ملخص اليوم
 * مع دعوة واضحة لبناء الجدول — لا مساحة مهدورة على ترحيب ثابت.
 */
@Composable
private fun NextLectureCard(
    next: NextLecture?,
    pendingTasks: Int,
    upcomingExams: Int,
    onOpenSchedule: () -> Unit
) {
    ElevatedCard(
        onClick = onOpenSchedule,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            if (next == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "جدولك فارغ",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildSummaryText(pendingTasks, upcomingExams),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "أضف محاضراتك ليظهر هنا أقرب موعد مع القاعة والعد التنازلي",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                val lecture = next.lecture
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "المحاضرة التالية",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.weight(1f))
                    TintChip(
                        text = if (next.status == NextLecture.Status.ONGOING) "الآن"
                        else NextLectureResolver.dayLabel(next.dayOffset),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lecture.subject,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        lecture.timeFrom +
                            (if (lecture.timeTo.isNotBlank()) " – ${lecture.timeTo}" else ""),
                        lecture.room.takeIf { it.isNotBlank() }?.let { "قاعة $it" },
                        lecture.doctor.takeIf { it.isNotBlank() }
                    ).joinToString("  •  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = NextLectureResolver.formatCountdown(next),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

private fun buildSummaryText(pendingTasks: Int, upcomingExams: Int): String {
    val parts = mutableListOf<String>()
    parts += when (pendingTasks) {
        0 -> "لا مهام معلقة"
        1 -> "عندك مهمة واحدة معلقة"
        2 -> "عندك مهمتان معلقتان"
        else -> "عندك $pendingTasks مهام معلقة"
    }
    parts += when (upcomingExams) {
        0 -> "ولا امتحانات قريبة"
        1 -> "وامتحان واحد قادم"
        2 -> "وامتحانان قادمات"
        else -> "و$upcomingExams امتحانات قادمة"
    }
    return parts.joinToString(" ") + " 💪"
}

@Composable
private fun TodayLectureRow(lecture: LectureEntity) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(64.dp)) {
                Text(
                    text = lecture.timeFrom,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
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
                Text(lecture.subject, style = MaterialTheme.typography.titleSmall)
                val meta = listOfNotNull(
                    lecture.doctor.takeIf { it.isNotBlank() },
                    lecture.room.takeIf { it.isNotBlank() }?.let { "قاعة $it" }
                ).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DueTaskRow(task: TaskEntity, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = task.isDone, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall)
                DateFormats.friendlyDueLabel(task.dueDate)?.let { label ->
                    val overdue = task.isOverdue
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overdue) SemanticDanger else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (task.isOverdue) {
                TintChip(
                    text = "متأخرة",
                    containerColor = SemanticDanger.copy(alpha = 0.15f),
                    contentColor = SemanticDanger,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun UpcomingExamRow(exam: ExamEntity) {
    val days = exam.daysRemaining ?: 0
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(exam.subject, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TintChip(
                        text = exam.type.label,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (exam.room.isNotBlank()) {
                        TintChip(
                            text = "قاعة ${exam.room}",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.EventNote,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    days == 0L -> "اليوم"
                    days == 1L -> "غداً"
                    days == 2L -> "بعد يومين"
                    else -> "بعد $days أيام"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (days <= 2) SemanticDanger else MaterialTheme.colorScheme.primary
            )
        }
    }
}
