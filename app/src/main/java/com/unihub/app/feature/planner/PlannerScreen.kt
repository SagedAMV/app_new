package com.unihub.app.feature.planner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.unihub.app.feature.planner.exams.ExamsTab
import com.unihub.app.feature.planner.notes.NotesTab
import com.unihub.app.feature.planner.schedule.ScheduleTab
import com.unihub.app.feature.planner.tasks.TasksTab
import com.unihub.app.ui.navigation.PlannerTab

/**
 * شاشة المخطط: تبويبات المهام/الجدول/الملاحظات/الامتحانات.
 * كل تبويب له ViewModel مستقل — على عكس الشاشة "الموحّدة" في التطبيق المرجعي
 * التي كانت تعيد تركيب كل شيء معاً في ملف واحد من 1100 سطر.
 */
@Composable
fun PlannerScreen(initialTab: PlannerTab) {
    val tabs = PlannerTab.entries
    var selectedIndex by rememberSaveable { mutableStateOf(initialTab.ordinal) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top
    ) {
        TabRow(
            selectedTabIndex = selectedIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    text = { Text(tab.label()) }
                )
            }
        }

        when (tabs[selectedIndex]) {
            PlannerTab.TASKS -> TasksTab()
            PlannerTab.SCHEDULE -> ScheduleTab()
            PlannerTab.NOTES -> NotesTab()
            PlannerTab.EXAMS -> ExamsTab()
        }
    }
}

private fun PlannerTab.label(): String = when (this) {
    PlannerTab.TASKS -> "المهام"
    PlannerTab.SCHEDULE -> "الجدول"
    PlannerTab.NOTES -> "الملاحظات"
    PlannerTab.EXAMS -> "الامتحانات"
}
