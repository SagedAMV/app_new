package com.unihub.app.feature.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
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
 * بطاقة "المحاضرة التالية" — القلب العملي للشاشة الرئيسية.
 *
 * التصميم رقم 8 «العدّاد الكبير» (جلسة مقترحات تصاميم البطاقة): بطاقة بلون
 * أساسي موحّد تتوسطها أرقام العدّ التنازلي الضخمة. العدّاد حيّ فعلاً: ينبض
 * محلياً كل ثانية انطلاقاً من وقت بداية المحاضرة الفعلي، تنزلق الخانات عند
 * تغيّرها وتومض النقطتان بنبض هادئ انسجاماً مع هوية التطبيق المريحة للعين.
 * إن كانت المحاضرة أبعد من اليوم يُعرض «غداً/بعد يومين» بدل الأرقام، وإن
 * كانت جارية يظهر «جارية الآن». عند فراغ الجدول تتحول إلى ملخص اليوم مع
 * دعوة واضحة لبناء الجدول — لا مساحة مهدورة على ترحيب ثابت.
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
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (next == null) {
                EmptyScheduleHero(pendingTasks, upcomingExams)
            } else {
                NextLectureHero(next)
            }
        }
    }
}

/**
 * محتوى البطاقة عند وجود محاضرة: ترويسة اليوم، العدّاد الضخم، تحته وصف
 * قصير، ثم المادة والدكتور والقاعة وشارة وقت البداية.
 */
@Composable
private fun NextLectureHero(next: NextLecture) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val lecture = next.lecture
    val ongoing = next.status == NextLecture.Status.ONGOING

    Text(
        text = if (ongoing) "المحاضرة التالية · جارية الآن"
        else "المحاضرة التالية · ${NextLectureResolver.dayLabel(next.dayOffset)}",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = onPrimary.copy(alpha = 0.85f)
    )
    Spacer(Modifier.height(10.dp))

    when {
        ongoing -> HeroWord("جارية الآن")
        next.dayOffset > 0 -> HeroWord(NextLectureResolver.dayLabel(next.dayOffset))
        else -> LiveDigitCountdown(next)
    }

    Text(
        text = when {
            ongoing && lecture.timeTo.isNotBlank() -> "تنتهي ${lecture.timeTo}"
            ongoing -> "بدأت ${lecture.timeFrom}"
            next.dayOffset > 0 -> "تبدأ ${lecture.timeFrom}"
            else -> "متبقٍ على بداية المحاضرة"
        },
        style = MaterialTheme.typography.bodySmall,
        color = onPrimary.copy(alpha = 0.75f)
    )
    Spacer(Modifier.height(14.dp))

    Text(
        text = lecture.subject,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = onPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(4.dp))

    val meta = listOfNotNull(
        lecture.doctor.takeIf { it.isNotBlank() },
        lecture.room.takeIf { it.isNotBlank() }?.let { "قاعة $it" }
    ).joinToString("  ·  ")
    if (meta.isNotBlank()) {
        Text(
            text = meta,
            style = MaterialTheme.typography.bodyMedium,
            color = onPrimary.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
    } else {
        Spacer(Modifier.height(8.dp))
    }

    val timeChip = if (ongoing) {
        "بدأت ${lecture.timeFrom}"
    } else {
        buildString {
            append("تبدأ ").append(lecture.timeFrom)
            if (lecture.timeTo.isNotBlank()) append(" – ").append(lecture.timeTo)
        }
    }
    TintChip(
        text = timeChip,
        containerColor = onPrimary.copy(alpha = 0.16f),
        contentColor = onPrimary
    )
}

/** كلمة كبيرة بدل الأرقام (جارية الآن / غداً / بعد يومين) مع انتقال انزلاقي */
@Composable
private fun HeroWord(text: String) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(animationSpec = tween(300)) { -it } +
                fadeIn(animationSpec = tween(300)))
                .togetherWith(
                    slideOutVertically(animationSpec = tween(300)) { it } +
                        fadeOut(animationSpec = tween(300))
                )
        },
        label = "hero-word"
    ) { current ->
        Text(
            text = current,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.ExtraBold
            ),
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * العدّاد الحي: يحسب محلياً الثواني المتبقية حتى وقت بداية المحاضرة
 * (حالة اليوم فقط؛ الأيام الأبعد تتولاها [HeroWord]) ويعرضها بصيغة
 * «د:ث» أو «س:د:ث». كل خانة تنزلق عند تغيّرها والنقطتان تومضان بنبض
 * هادئ. إن تعذّر تفسير وقت البداية يعود إلى [NextLectureResolver.formatCountdown]
 * بدل التخمين.
 */
@Composable
private fun LiveDigitCountdown(next: NextLecture) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000L)
        }
    }
    // وميض النقطتين يُعلَن قبل أي فرع مبكر حتى يبقى عدد الـ hooks ثابتاً
    val colonAlpha by rememberInfiniteTransition(label = "countdown-colon").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "colon-alpha"
    )

    val startAt = remember(next) {
        DateFormats.parseTimeOrNull(next.lecture.timeFrom)?.let { from ->
            LocalDate.now().plusDays(next.dayOffset.toLong()).atTime(from)
        }
    }
    val remainingSeconds = startAt?.let { ChronoUnit.SECONDS.between(now, it) } ?: 0L
    if (startAt == null || remainingSeconds <= 0L) {
        HeroWord(if (startAt == null) NextLectureResolver.formatCountdown(next) else "تبدأ الآن")
        return
    }

    val hours = remainingSeconds / 3_600
    val minutes = (remainingSeconds % 3_600) / 60
    val seconds = remainingSeconds % 60
    val text = if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "الوقت المتبقي حتى بداية المحاضرة $text"
            },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (char in text) {
                if (char == ':') {
                    Text(
                        text = ":",
                        style = heroDigitStyle(),
                        color = onPrimary.copy(alpha = colonAlpha)
                    )
                } else {
                    SlidingDigit(char)
                }
            }
        }
    }
}

/** نمط الأرقام الضخم الموحّد للعدّاد */
@Composable
private fun heroDigitStyle() = MaterialTheme.typography.displaySmall.copy(
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 1.sp
)

/** خانة واحدة من العدّاد تنزلق عمودياً عند تغيّر قيمتها */
@Composable
private fun SlidingDigit(char: Char) {
    AnimatedContent(
        targetState = char,
        transitionSpec = {
            (slideInVertically(animationSpec = tween(300)) { -it } +
                fadeIn(animationSpec = tween(300)))
                .togetherWith(
                    slideOutVertically(animationSpec = tween(300)) { it } +
                        fadeOut(animationSpec = tween(300))
                )
        },
        label = "digit"
    ) { current ->
        Text(
            text = current.toString(),
            style = heroDigitStyle(),
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** حالة الجدول الفارغ داخل البطاقة الأساسية بنفس لونها */
@Composable
private fun EmptyScheduleHero(pendingTasks: Int, upcomingExams: Int) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Icon(
        imageVector = Icons.Outlined.Schedule,
        contentDescription = null,
        modifier = Modifier.size(34.dp),
        tint = onPrimary.copy(alpha = 0.9f)
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = "جدولك فارغ",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = onPrimary
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = buildSummaryText(pendingTasks, upcomingExams),
        style = MaterialTheme.typography.bodyMedium,
        color = onPrimary.copy(alpha = 0.85f),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(12.dp))
    TintChip(
        text = "أضف محاضراتك من الجدول ليظهر العدّ التنازلي هنا",
        containerColor = onPrimary.copy(alpha = 0.16f),
        contentColor = onPrimary
    )
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
