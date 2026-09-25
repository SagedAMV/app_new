package com.unihub.app.feature.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.delay
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
    val nearestTask by viewModel.nearestTask.collectAsStateWithLifecycle()
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

            // بطاقة «لقطة اليوم»: النصف الأيسر لجدول المحاضرات وتوقيتها، والنصف
            // الأيمن لأقرب امتحان وأقرب مهمة مطلوبة، يفصل بينهما خط رأسي — وتدخل
            // الشاشة بأنيميشن متدرج هادئ. أقرب امتحان من رأس قائمة الامتحانات
            // المرتبة تصاعدياً، وأقرب مهمة من استعلام مفرد مخصص (ماضٍ أو مستقبل)
            DaySnapshotCard(
                next = nextLecture,
                todayLectures = todayLectures,
                nearestExam = upcomingExams.firstOrNull(),
                nearestTask = nearestTask,
                onOpenSchedule = { onOpenPlanner(PlannerTab.SCHEDULE) },
                onOpenExams = { onOpenPlanner(PlannerTab.EXAMS) },
                onOpenTasks = { onOpenPlanner(PlannerTab.TASKS) }
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
 * البطاقة العلوية للشاشة الرئيسية — «لقطة اليوم» العملية.
 *
 * مقسومة نصفين يفصل بينهما خط فاصل رأسي:
 * - النصف الأيسر: جدول المحاضرات وتوقيتها — المحاضرة الأقرب مع حالتها الحية
 *   (عدّ تنازلي بالدقائق من تدفق المستودع) وتحته حتى محاضرتين من بقية اليوم.
 *   الضغط على النصف يفتح الجدول الأسبوعي.
 * - النصف الأيمن: أقرب امتحان قادم وأقرب مهمة مطلوبة، وكل صف اختصار مباشر
 *   لتبويبه. الامتحان من رأس قائمة الامتحانات المرتبة تصاعدياً بالتاريخ من
 *   DAO، والمهمة من استعلام Room مفرد (LIMIT 1) يعيد أقرب مهمة غير منجزة
 *   بتاريخ استحقاق — ماضٍ أو مستقبل — فلا تختفي عند غياب مهام اليوم.
 *
 * أنيميشن الدخول: تظهر البطاقة أولاً (تلاشٍ مع ارتفاع خفيف)، ثم ينمو الخط
 * الفاصل من منتصفه وينزلق النصفان من الخارج نحوه بتتابع سريع هادئ —
 * دخول سلس ينسجم مع هوية التطبيق المريحة للعين بدل الأنيميشن المفرط.
 */
@Composable
private fun DaySnapshotCard(
    next: NextLecture?,
    todayLectures: List<LectureEntity>,
    nearestExam: ExamEntity?,
    nearestTask: TaskEntity?,
    onOpenSchedule: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenTasks: () -> Unit
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    // حالتا الدخول: البطاقة أولاً ثم النصفان والفاصل بعد تأخير قصير
    var cardEntered by remember { mutableStateOf(false) }
    var halvesEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cardEntered = true
        delay(160)
        halvesEntered = true
    }

    val cardAlpha by animateFloatAsState(
        targetValue = if (cardEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "card-alpha"
    )
    val cardOffsetY by animateFloatAsState(
        targetValue = if (cardEntered) 0f else 26f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "card-offset-y"
    )
    val leftAlpha by animateFloatAsState(
        targetValue = if (halvesEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 380),
        label = "left-alpha"
    )
    val leftOffsetX by animateFloatAsState(
        targetValue = if (halvesEntered) 0f else -20f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "left-offset-x"
    )
    val rightAlpha by animateFloatAsState(
        targetValue = if (halvesEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 380),
        label = "right-alpha"
    )
    val rightOffsetX by animateFloatAsState(
        targetValue = if (halvesEntered) 0f else 20f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "right-offset-x"
    )
    val dividerGrow by animateFloatAsState(
        targetValue = if (halvesEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "divider-grow"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = cardAlpha
                translationY = cardOffsetY
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        // في RTL يُصفّ الطفل الأول يميناً: نصف الاستحقاقات أولاً (يمين
        // البطاقة) ونصف المحاضرات أخيراً (يسارها) — فيكون التقسيم كما يراه
        // المستخدم: محاضرات | فاصل | استحقاقات.
        //
        // height(IntrinsicSize.Min) يمنح الصف ارتفاعاً محدداً (ارتفاع أطول نصف)،
        // فيعمل fillMaxHeight() للفاصل الرأسي داخل الشاشة القابلة للتمرير
        // ويتساوى النصفان ارتفاعاً — هذا هو النمط المعياري في Compose إذ لا
        // يوجد محاذاة «تمديد»، وبدونه ينهار الفاصل إلى ارتفاع صفر.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            DeadlinesHalf(
                nearestExam = nearestExam,
                nearestTask = nearestTask,
                onOpenExams = onOpenExams,
                onOpenTasks = onOpenTasks,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = rightAlpha
                        translationX = rightOffsetX
                    }
            )

            // الخط الفاصل الرأسي ينمو من منتصفه نحو الطرفين
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = dividerGrow
                        alpha = dividerGrow
                    }
                    .background(onPrimary.copy(alpha = 0.28f))
            )

            LecturesHalf(
                next = next,
                todayLectures = todayLectures,
                onOpenSchedule = onOpenSchedule,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = leftAlpha
                        translationX = leftOffsetX
                    }
            )
        }
    }
}

/**
 * النصف الأيسر من بطاقة اللقطة: جدول المحاضرات وتوقيتها.
 *
 * يبرز المحاضرة الأقرب (اسمها وسطر حالتها الحي: «جارية الآن» أو «اليوم 10:30
 * · بعد 25 دقيقة» أو «غداً · تبدأ 09:00») وتحته حتى محاضرتين من بقية اليوم
 * بصيغة مدمجة «الوقت — المادة». النصف كله اختصار للجدول الأسبوعي. عند فراغ
 * الجدول تظهر حالة مصغرة تدعو لبنائه بدل مساحة صامتة.
 */
@Composable
private fun LecturesHalf(
    next: NextLecture?,
    todayLectures: List<LectureEntity>,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = modifier
            .clickable(
                onClickLabel = "عرض الجدول الأسبوعي",
                role = Role.Button,
                onClick = onOpenSchedule
            )
            .padding(start = 10.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = onPrimary.copy(alpha = 0.85f)
            )
            Text(
                text = "المحاضرات",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = onPrimary.copy(alpha = 0.85f)
            )
        }

        Spacer(Modifier.height(12.dp))

        if (next == null) {
            Text(
                text = "جدولك فارغ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "أضف محاضراتك من تبويب الجدول لتظهر مواعيدها هنا",
                style = MaterialTheme.typography.bodySmall,
                color = onPrimary.copy(alpha = 0.75f)
            )
            return
        }

        // المحاضرة الأقرب: المادة ثم سطر الحالة الحي
        Text(
            text = next.lecture.subject,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = lectureStatusLabel(next),
            style = MaterialTheme.typography.bodySmall,
            color = onPrimary.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // بقية محاضرات اليوم (حتى اثنتين) بصيغة مدمجة: الوقت ثم المادة
        todayLectures
            .filter { it.id != next.lecture.id }
            .take(2)
            .forEach { lecture ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lecture.timeFrom,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = onPrimary.copy(alpha = 0.9f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = lecture.subject,
                        style = MaterialTheme.typography.bodySmall,
                        color = onPrimary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
    }
}

/**
 * النصف الأيمن من بطاقة اللقطة: أقرب امتحان قادم وأقرب مهمة مطلوبة.
 * كل صف اختصار مباشر لتبويبه (الامتحانات/المهام)، وعند الغياب يظهر سطر
 * هادئ يحفظ توازن التقسيم. الشارات تستعمل لون onPrimary شفافاً لتبقى
 * منسجمة مع خلفية البطاقة الأساسية.
 */
@Composable
private fun DeadlinesHalf(
    nearestExam: ExamEntity?,
    nearestTask: TaskEntity?,
    onOpenExams: () -> Unit,
    onOpenTasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = modifier.padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.EventAvailable,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = onPrimary.copy(alpha = 0.85f)
            )
            Text(
                text = "الأقرب موعداً",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = onPrimary.copy(alpha = 0.85f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // أقرب امتحان — القائمة مرتبة تصاعدياً بالتاريخ من DAO
        if (nearestExam != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = "عرض الامتحانات",
                        role = Role.Button,
                        onClick = onOpenExams
                    )
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.School,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = onPrimary.copy(alpha = 0.9f)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = nearestExam.subject,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            nearestExam.type.label,
                            nearestExam.room.takeIf { it.isNotBlank() }?.let { "قاعة $it" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = onPrimary.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
                TintChip(
                    text = dayCountLabel(nearestExam.daysRemaining),
                    containerColor = onPrimary.copy(alpha = 0.16f),
                    contentColor = onPrimary
                )
            }
        } else {
            Text(
                text = "لا امتحانات قريبة",
                style = MaterialTheme.typography.bodySmall,
                color = onPrimary.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 5.dp)
            )
        }

        Spacer(Modifier.height(4.dp))

        // أقرب مهمة مطلوبة — استعلام مفرد من DAO: أقرب استحقاق غير منجز
        // (المتأخرة تتصدر لأن تاريخها أقدم)، أياً كان يوم استحقاقها
        if (nearestTask != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = "عرض المهام",
                        role = Role.Button,
                        onClick = onOpenTasks
                    )
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.TaskAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = onPrimary.copy(alpha = 0.9f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = nearestTask.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                TintChip(
                    text = if (nearestTask.isOverdue) "متأخرة"
                    else DateFormats.friendlyDueLabel(nearestTask.dueDate) ?: "مطلوبة",
                    containerColor = onPrimary.copy(alpha = 0.16f),
                    contentColor = onPrimary
                )
            }
        } else {
            Text(
                text = "لا مهام بموعد قادم",
                style = MaterialTheme.typography.bodySmall,
                color = onPrimary.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 5.dp)
            )
        }
    }
}

/** سطر الحالة الحي للمحاضرة الأقرب داخل نصف المحاضرات */
private fun lectureStatusLabel(next: NextLecture): String = when {
    next.status == NextLecture.Status.ONGOING && next.lecture.timeFrom.isNotBlank() ->
        "جارية الآن · بدأت ${next.lecture.timeFrom}"
    next.status == NextLecture.Status.ONGOING -> "جارية الآن"
    next.dayOffset == 0 ->
        "اليوم ${next.lecture.timeFrom} · ${NextLectureResolver.formatCountdown(next)}"
    else -> "${NextLectureResolver.dayLabel(next.dayOffset)} · تبدأ ${next.lecture.timeFrom}"
}

/** تسمية الأيام النسبية للامتحان: اليوم / غداً / بعد يومين / بعد N أيام */
private fun dayCountLabel(days: Long?): String = when (days) {
    null -> "قريباً"
    0L -> "اليوم"
    1L -> "غداً"
    2L -> "بعد يومين"
    else -> "بعد $days أيام"
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
