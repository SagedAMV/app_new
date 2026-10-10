package com.unihub.app.feature.settings

import com.unihub.app.core.prefs.AutoBackupPreferences
import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.data.auth.CloudAuthRules
import com.unihub.app.data.auth.CloudUserAccount

/**
 * الأصناف الخمسة المعتمدة في واجهة الإعدادات وفق تعليمات.md:
 * 1) إعدادات المستخدمين وطلبات الأجهزة (للمشرف)
 * 2) المظهر
 * 3) النسخ الاحتياطي التلقائي
 * 4) البيانات
 * 5) حول التطبيق
 */
enum class SettingsCategory(
    val id: String,
    val title: String,
    val defaultSubtitle: String
) {
    USER_MANAGEMENT(
        id = "USER_MANAGEMENT",
        title = "إعدادات المستخدمين وطلبات الأجهزة (للمشرف)",
        defaultSubtitle = "الحساب الحالي، إدارة المستخدمين، الصلاحيات، وطلبات الأجهزة"
    ),
    APPEARANCE(
        id = "APPEARANCE",
        title = "المظهر",
        defaultSubtitle = "الوضع الفاتح والداكن وألوان Material You الديناميكية"
    ),
    AUTO_BACKUP(
        id = "AUTO_BACKUP",
        title = "النسخ الاحتياطي التلقائي",
        defaultSubtitle = "جدولة النسخ التلقائي الدوري وحفظ التعديلات"
    ),
    DATA(
        id = "DATA",
        title = "البيانات",
        defaultSubtitle = "تصدير واستعادة النسخ الاحتياطية أو مسح البيانات"
    ),
    ABOUT(
        id = "ABOUT",
        title = "حول التطبيق",
        defaultSubtitle = "معلومات UniHub وإصدار التطبيق"
    );

    companion object {
        fun fromSavedName(raw: String?): SettingsCategory? {
            if (raw.isNullOrBlank()) return null
            val clean = raw.trim()
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) || it.id.equals(clean, ignoreCase = true) }
        }
    }
}

/**
 * قرار زر الرجوع داخل شاشة الإعدادات:
 * - إن كان المستخدم داخل واجهة صنف فرعي -> يعود إلى قائمة الأصناف الرئيسية.
 * - إن كان في الشاشة الرئيسية للأصناف -> يخرج من شاشة الإعدادات.
 */
sealed interface SettingsBackOutcome {
    data object ReturnToCategoriesRoot : SettingsBackOutcome
    data object ExitSettingsScreen : SettingsBackOutcome
}

/**
 * الحالة النقية لطي وتوسيع بطاقات المستخدمين وبحث المستخدمين
 * في قسم «إعدادات المستخدمين وطلبات الأجهزة (للمشرف)».
 *
 * جميع البطاقات تبدأ مطوية افتراضياً (expandedUsernames = emptySet()) لمنع الازدحام
 * عند وجود 50+ مستخدم، وتتوسع فقط البطاقة التي ينقر عليها المشرف.
 */
data class UserCardsExpansionState(
    val expandedUsernames: Set<String> = emptySet(),
    val searchQuery: String = ""
) {
    fun isExpanded(username: String): Boolean {
        val norm = CloudAuthRules.normalizeUsername(username)
        return norm.isNotBlank() && norm in expandedUsernames
    }
}

/**
 * ملخص البطاقة المطوية للمستخدم لعرض أهم المعلومات بنظرة سريعة دون توسيع.
 */
data class CollapsedUserSummary(
    val username: String,
    val statusBadgeText: String,
    val isStatusActive: Boolean,
    val isAdmin: Boolean,
    val boundDeviceShortText: String,
    val permissionsSummaryText: String,
    val enabledPermissionsCount: Int
)

/**
 * القواعد النقية لتنظيم واجهة الإعدادات (الأصناف الخمسة)، وإدارة طي/توسيع بطاقات المستخدمين،
 * وقواعد عرض واجهة تسجيل الدخول (حذف نص نوع الجهاز وإصدار الأندرويد من واجهة الدخول).
 */
object SettingsCatalogRules {

    /**
     * قاعدة صارمة وفق تعليمات.md:
     * واجهة تسجيل الدخول لا تعرض نص نوع الجهاز أو إصدار الأندرويد.
     */
    const val SHOW_DEVICE_INFO_IN_LOGIN_UI: Boolean = false

    /** إرجاع قائمة الأصناف الخمسة المعتمدة بالترتيب الرسمي. */
    fun orderedCategories(): List<SettingsCategory> = SettingsCategory.entries

    /** فتح صنف معين من الشاشة الرئيسية للإعدادات. */
    fun openCategory(category: SettingsCategory): SettingsCategory = category

    /** معالجة حدث الرجوع (سواء من زر الشريط العلوي أو BackHandler للنظام). */
    fun handleBack(currentCategory: SettingsCategory?): SettingsBackOutcome =
        if (currentCategory != null) {
            SettingsBackOutcome.ReturnToCategoriesRoot
        } else {
            SettingsBackOutcome.ExitSettingsScreen
        }

    /**
     * تبديل حالة توسيع/طي بطاقة مستخدم عند النقر عليها:
     * - إذا كانت مطوية: تتوسع لعرض خيارات المستخدم.
     * - إذا كانت متوسعة: تُطوى لإبقاء الواجهة مختصرة.
     */
    fun toggleUserExpanded(
        state: UserCardsExpansionState,
        username: String
    ): UserCardsExpansionState {
        val norm = CloudAuthRules.normalizeUsername(username)
        if (norm.isBlank()) return state
        return if (norm in state.expandedUsernames) {
            state.copy(
                expandedUsernames = state.expandedUsernames - norm
            )
        } else {
            state.copy(
                expandedUsernames = state.expandedUsernames + norm
            )
        }
    }

    /** طي جميع بطاقات المستخدمين المفتوحة دفعة واحدة. */
    fun collapseAll(state: UserCardsExpansionState): UserCardsExpansionState =
        state.copy(expandedUsernames = emptySet())

    /** تحديث نص البحث عن المستخدمين دون المساس بحالة البطاقات المتوسعة (H3). */
    fun updateSearchQuery(
        state: UserCardsExpansionState,
        query: String
    ): UserCardsExpansionState = state.copy(searchQuery = query)

    /**
     * مزامنة حالة التوسيع مع قائمة المستخدمين الكاملة في السجل السحابي (علاج المحاكمة H3):
     * تحذف فقط الحسابات التي حُذفت فعلياً من السجل، ولا تتأثر بفلتر البحث المؤقت.
     */
    fun reconcileWithRegistry(
        state: UserCardsExpansionState,
        allRegistryUsers: List<CloudUserAccount>
    ): UserCardsExpansionState {
        val activeNames = allRegistryUsers
            .mapTo(mutableSetOf()) { CloudAuthRules.normalizeUsername(it.username) }
        val cleanExpanded = state.expandedUsernames.intersect(activeNames)
        return state.copy(expandedUsernames = cleanExpanded)
    }

    /**
     * ترتيب المستخدمين للعرض: المشرف العام أولاً، ثم بقية المستخدمين مرتبين أبجدياً،
     * مع تطبيق فلتر البحث إن وُجد.
     */
    fun filterAndSortUsers(
        allUsers: List<CloudUserAccount>,
        query: String
    ): List<CloudUserAccount> {
        val sorted = allUsers.sortedWith(
            compareByDescending<CloudUserAccount> { it.isAdmin }
                .thenBy { CloudAuthRules.normalizeUsername(it.username) }
        )
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return sorted
        return sorted.filter { account ->
            account.username.lowercase().contains(cleanQuery) ||
                account.displayName.lowercase().contains(cleanQuery) ||
                (account.boundDevice?.summaryLabel?.lowercase()?.contains(cleanQuery) == true)
        }
    }

    /**
     * بناء ملخص البطاقة المطوية للمستخدم بحيث يعرض الحالة والملخص دون إزعاج بصري.
     */
    fun buildCollapsedUserSummary(account: CloudUserAccount): CollapsedUserSummary {
        val statusBadge = when {
            account.isAdmin -> "مشرف عام"
            account.isActive -> "نشط"
            else -> "موقوف"
        }
        val perms = account.effectivePermissions
        val enabledCount = listOf(perms.canDownload, perms.canUpload, perms.canModify).count { it }
        val permText = if (account.isAdmin) {
            "كامل الصلاحيات"
        } else {
            "$enabledCount/3 صلاحيات"
        }
        val deviceText = account.boundDevice?.let { device ->
            val modelPart = listOf(device.manufacturer.trim(), device.model.trim())
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
                .ifBlank { device.deviceName.ifBlank { "جهاز مرتبط" } }
            "مرتبط: $modelPart"
        } ?: "غير مرتبط بجهاز"

        return CollapsedUserSummary(
            username = account.username,
            statusBadgeText = statusBadge,
            isStatusActive = account.isAdmin || account.isActive,
            isAdmin = account.isAdmin,
            boundDeviceShortText = deviceText,
            permissionsSummaryText = permText,
            enabledPermissionsCount = enabledCount
        )
    }

    /**
     * هل يُسمح بعرض أدوات التحكم التدميرية/التقييدية (إيقاف الحساب، تقليص الصلاحيات، حذف المستخدم)؟
     * المشرف العام محمي دائماً (Inv1 / S9).
     */
    fun canShowDestructiveControlsForUser(account: CloudUserAccount): Boolean =
        !account.isAdmin

    /**
     * توليد الوصف الفرعي الحي لكل صنف في الشاشة الرئيسية للإعدادات (H2).
     */
    fun buildCategoryLiveSubtitle(
        category: SettingsCategory,
        isAdmin: Boolean,
        currentUsername: String?,
        totalUsersCount: Int,
        pendingDeviceRequestsCount: Int,
        themeMode: ThemeMode,
        useDynamicColor: Boolean,
        isBackupFolderConfigured: Boolean,
        backupIntervalDays: Int,
        appVersion: String = "1.3.1"
    ): String = when (category) {
        SettingsCategory.USER_MANAGEMENT -> {
            if (isAdmin) {
                val usersPart = "$totalUsersCount مستخدم مسجل"
                val requestsPart = if (pendingDeviceRequestsCount > 0) {
                    " • $pendingDeviceRequestsCount طلب جهاز معلق"
                } else {
                    " • لا طلبات معلقة"
                }
                "$usersPart$requestsPart"
            } else {
                val userLabel = currentUsername?.takeIf { it.isNotBlank() } ?: "مستخدم سحابي"
                "الحساب الحالي: $userLabel • إعدادات الحساب والجهاز"
            }
        }
        SettingsCategory.APPEARANCE -> {
            val modeLabel = when (themeMode) {
                ThemeMode.SYSTEM -> "تلقائي (حسب النظام)"
                ThemeMode.LIGHT -> "الوضع الفاتح"
                ThemeMode.DARK -> "الوضع الداكن"
            }
            val dynamicLabel = if (useDynamicColor) " • ألوان ديناميكية مفعّلة" else ""
            "$modeLabel$dynamicLabel"
        }
        SettingsCategory.AUTO_BACKUP -> {
            if (!isBackupFolderConfigured) {
                "غير مفعّل — لم يتم تحديد مجلد النسخ الاحتياطي بعد"
            } else {
                val intervalLabel = when (backupIntervalDays) {
                    1 -> "يومياً"
                    3 -> "كل 3 أيام"
                    7 -> "أسبوعياً"
                    30 -> "شهرياً"
                    else -> "كل $backupIntervalDays يوم"
                }
                "مفعّل ✓ • الجدولة: $intervalLabel"
            }
        }
        SettingsCategory.DATA -> category.defaultSubtitle
        SettingsCategory.ABOUT -> "UniHub — الإصدار $appVersion"
    }

    /** حساب مؤشر الشريحة المختارة لفاصل النسخ الاحتياطي التلقائي. */
    fun computeBackupChipIndex(intervalDays: Int): Int = when (intervalDays) {
        1 -> 0
        3 -> 1
        7 -> 2
        30 -> 3
        else -> 4
    }

    /** التحقق من صلاحية عدد الأيام المخصص للنسخ الاحتياطي التلقائي (1..365). */
    fun isValidCustomBackupDays(raw: String?): Boolean = parseCustomBackupDays(raw) != null

    /**
     * تحليل عدد الأيام المخصص بأمان (جلسة التحقق العميق 2026-10-09): يعيد القيمة
     * الصالحة أو null — مصدر واحد للحقيقة يشترك فيه التحقق والاستخدام، فلا يحتاج
     * موضع الاستدعاء إلى أي force-unwrap (بوابة «صفر !!» في تعليمات.md).
     */
    fun parseCustomBackupDays(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it in AutoBackupPreferences.MIN_INTERVAL_DAYS..AutoBackupPreferences.MAX_INTERVAL_DAYS }
    }
}
