package com.unihub.app.feature.planner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.unihub.app.feature.planner.exams.ExamsTab
import com.unihub.app.feature.planner.notes.NotesTab
import com.unihub.app.feature.planner.schedule.ScheduleTab
import com.unihub.app.feature.planner.tasks.TasksTab
import com.unihub.app.ui.navigation.PlannerTab
import kotlinx.coroutines.launch

/**
 * شاشة المخطط: تبويبات المهام/الجدول/الملاحظات/الامتحانات.
 * كل تبويب له ViewModel مستقل — على عكس الشاشة "الموحّدة" في التطبيق المرجعي
 * التي كانت تعيد تركيب كل شيء معاً في ملف واحد من 1100 سطر.
 *
 * التنقل بطريقتين معاً:
 * - الضغط على التبويب في الشريط العلوي (كما كان).
 * - السحب أفقياً يميناً/يساراً بين الصفحات (HorizontalPager) — الطلب الذي
 *   يريح اليد من الوصول للشريط العلوي في الشاشات الكبيرة.
 * المؤشر العلوي يتزامن مع الصفحة بالاتجاهين: السحب يحرّك المؤشر، والضغط
 * على تبويب يمرّر الصفحة بأنيميشن انزلاقي.
 */
@Composable
fun PlannerScreen(initialTab: PlannerTab) {
    val tabs = PlannerTab.entries
    var selectedIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }

    // حالة الصفحات: rememberPagerState تحفظ الصفحة الحالية عبر rememberSaveable
    // داخلياً، فتصمد أمام تدوير الشاشة وموت العملية.
    val pagerState = rememberPagerState(
        initialPage = initialTab.ordinal,
        pageCount = { tabs.size }
    )
    val scope = rememberCoroutineScope()

    // صفحة ← مؤشر: عند استقرار السحب على صفحة جديدة نتبنّاها في المؤشر
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (selectedIndex != page) selectedIndex = page
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = {
                        // مؤشر ← صفحة: ضغط التبويب يمرّر الصفحة بأنيميشن،
                        // والتحديث الفوري لـ selectedIndex يستجيب له المؤشر فوراً
                        selectedIndex = index
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = { Text(tab.label()) }
                )
            }
        }

        // السحب الأفقي بين التبويبات — يحترم اتجاه التخطيط (RTL) تلقائياً
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (tabs[page]) {
                PlannerTab.TASKS -> TasksTab()
                PlannerTab.SCHEDULE -> ScheduleTab()
                PlannerTab.NOTES -> NotesTab()
                PlannerTab.EXAMS -> ExamsTab()
            }
        }
    }
}

private fun PlannerTab.label(): String = when (this) {
    PlannerTab.TASKS -> "المهام"
    PlannerTab.SCHEDULE -> "الجدول"
    PlannerTab.NOTES -> "الملاحظات"
    PlannerTab.EXAMS -> "الامتحانات"
}
