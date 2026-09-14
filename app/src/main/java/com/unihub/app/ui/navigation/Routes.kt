package com.unihub.app.ui.navigation

import kotlinx.serialization.Serializable

/**
 * مسارات التنقل الآمنة الأنواع (Type-Safe Navigation عبر kotlinx.serialization).
 * التحسين الجوهري عن التطبيق المرجعي: لا نصوص سحرية ولا مفاتيح وسيطات يدوية؛
 * المترجم يضمن صحة كل انتقال ووسيطاته.
 */

@Serializable
data object DashboardRoute

/**
 * شاشة الملفات داخل مجلد معيّن (أو الجذر عند null).
 * التنقل لمجلد فرعي يدفع وجهة جديدة فوق المكدس — زر الرجوع يعود للمستوى الأب تلقائياً.
 */
@Serializable
data class FilesRoute(val folderId: Long? = null)

/**
 * شاشة التقاط الصور بالكاميرا — تُفتح من زر الإضافة في شاشة الملفات وتعمل
 * ضمن المجلد نفسه الذي كان مفتوحاً (تمرير folderId يحفظ الصور داخله مباشرة).
 * لا تظهر في الشريط السفلي لأنها وجهة عمل مؤقتة فوق شاشة الملفات.
 */
@Serializable
data class CameraCaptureRoute(val folderId: Long? = null)

@Serializable
data object GalaxyRoute

/** تبويبات المخطط: المهام، الجدول، الملاحظات، الامتحانات */
@Serializable
enum class PlannerTab { TASKS, SCHEDULE, NOTES, EXAMS }

@Serializable
data class PlannerRoute(val tab: PlannerTab = PlannerTab.TASKS)

@Serializable
data object SettingsRoute

@Serializable
data object BackupRoute

/** الوجهات التي تظهر في شريط التنقل السفلي */
val bottomBarRoutes = listOf(
    DashboardRoute::class,
    FilesRoute::class,
    PlannerRoute::class,
    GalaxyRoute::class
)
