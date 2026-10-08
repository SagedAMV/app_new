package com.unihub.app.feature.settings

import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.data.auth.AuthLoginOutcome
import com.unihub.app.data.auth.BoundDeviceInfo
import com.unihub.app.data.auth.CloudAuthRules
import com.unihub.app.data.auth.CloudUserAccount
import com.unihub.app.data.auth.UserPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الوحدة المحلية (Local Unit Tests) لتعديلات جلسة تعليمات.md وفق منهجية تفكير.md:
 * - تنظيم واجهة الإعدادات في 5 أصناف مستقلة والتنقل للأمام والخلف (S1, S3, S6, Inv1, Inv2).
 * - طي بطاقات المستخدمين افتراضياً وتوسيعها عند النقر لـ 50+ مستخدم (S1, S4, S5, Inv3, Inv4, Inv5, MR1..MR3).
 * - حذف نص نوع الجهاز وإصدار الأندرويد من واجهة تسجيل الدخول مع بقاء التوثيق السحابي فاعلاً (S2, S7, S8, Inv6).
 * - علاجات محاكمة الحلقة 5 وثقوب التغطية (H1, H2, H3, Holes 1..3).
 */
class SettingsCatalogAndUserCardsTest {

    private fun sampleDevice(
        fp: String = "fp_device_01",
        manufacturer: String = "Samsung",
        model: String = "SM-S928B",
        os: String = "14"
    ) = BoundDeviceInfo(
        fingerprint = fp,
        deviceName = "$manufacturer $model",
        manufacturer = manufacturer,
        model = model,
        androidVersion = os,
        boundAt = 10_000L
    )

    private fun sampleUser(
        username: String,
        isAdmin: Boolean = false,
        isActive: Boolean = true,
        perms: UserPermissions = UserPermissions.FULL,
        device: BoundDeviceInfo? = null
    ) = CloudUserAccount(
        username = username,
        displayName = username,
        passwordHash = CloudAuthRules.hashPassword(username, "pass123"),
        encryptedPassword = CloudAuthRules.encryptPassword("pass123"),
        isAdmin = isAdmin,
        isActive = isActive,
        permissions = perms,
        boundDevice = device,
        createdAt = 1_000L,
        updatedAt = 2_000L
    )

    // ── S1 + Inv1 + Inv2 + Inv3 + Inv4: رحلة المشرف في الأصناف الخمسة وتوسيع مستخدم بين 60 مستخدماً ──
    @Test
    fun s1_admin_navigates_five_categories_and_expands_single_user_among_60_collapsed_users() {
        // Arrange: الأصناف الخمسة و60 مستخدماً
        val categories = SettingsCatalogRules.orderedCategories()
        assertEquals(5, categories.size)
        assertEquals(
            listOf(
                SettingsCategory.USER_MANAGEMENT,
                SettingsCategory.APPEARANCE,
                SettingsCategory.AUTO_BACKUP,
                SettingsCategory.DATA,
                SettingsCategory.ABOUT
            ),
            categories
        )
        assertEquals("إعدادات المستخدمين وطلبات الأجهزة (للمشرف)", categories[0].title)
        assertEquals("المظهر", categories[1].title)
        assertEquals("النسخ الاحتياطي التلقائي", categories[2].title)
        assertEquals("البيانات", categories[3].title)
        assertEquals("حول التطبيق", categories[4].title)

        val users = (1..60).map { i -> sampleUser("student_$i") }
        var state = UserCardsExpansionState()

        // Assert Inv3: جميع الـ 60 مستخدماً مطويون افتراضياً
        assertTrue(state.expandedUsernames.isEmpty())
        users.forEach { u -> assertFalse(state.isExpanded(u.username)) }

        // Act: الدخول لصنف إدارة المستخدمين ثم النقر على student_42 لتوسيعه
        val openedCategory = SettingsCatalogRules.openCategory(SettingsCategory.USER_MANAGEMENT)
        assertEquals(SettingsCategory.USER_MANAGEMENT, openedCategory)

        state = SettingsCatalogRules.toggleUserExpanded(state, "student_42")

        // Assert Inv4: student_42 فقط متوسع، والـ 59 الآخرون مطويون
        assertTrue(state.isExpanded("student_42"))
        assertEquals(1, state.expandedUsernames.size)
        assertFalse(state.isExpanded("student_1"))
        assertFalse(state.isExpanded("student_60"))

        // Act: النقر مرة ثانية على student_42 لطيه، ثم الضغط على زر الرجوع
        state = SettingsCatalogRules.toggleUserExpanded(state, "student_42")
        assertFalse(state.isExpanded("student_42"))
        assertTrue(state.expandedUsernames.isEmpty())

        // Assert Inv2: الرجوع من داخل الصنف يعيد إلى قائمة الأصناف الرئيسية ولا يخرج من الإعدادات
        assertEquals(
            SettingsBackOutcome.ReturnToCategoriesRoot,
            SettingsCatalogRules.handleBack(openedCategory)
        )
        assertEquals(
            SettingsBackOutcome.ExitSettingsScreen,
            SettingsCatalogRules.handleBack(null)
        )
    }

    // ── S2 + Inv6: عدم عرض نوع الجهاز وإصدار الأندرويد في واجهة الدخول مع بقاء التوثيق السحابي فاعلاً ──
    @Test
    fun s2_login_ui_hides_device_and_android_version_while_backend_device_binding_remains_intact() {
        // Assert Inv6: القاعدة الصارمة تمنع عرض نوع الجهاز وإصدار الأندرويد في واجهة تسجيل الدخول
        assertFalse(SettingsCatalogRules.SHOW_DEVICE_INFO_IN_LOGIN_UI)

        // Arrange & Act: تسجيل دخول مستخدم جديد يربط بصمة جهازه في الخلفية بنجاح
        val reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "omar",
            password = "pass123"
        ).getOrThrow()

        val loginDevice = sampleDevice(fp = "fp_omar_s24", manufacturer = "Samsung", model = "S24", os = "14")
        val outcome = CloudAuthRules.evaluateLogin(
            registry = reg,
            usernameInput = "omar",
            passwordInput = "pass123",
            currentDevice = loginDevice,
            isOnline = true,
            now = 2_000L
        )

        // Assert
        assertTrue(outcome is AuthLoginOutcome.Authenticated)
        val authenticated = outcome as AuthLoginOutcome.Authenticated
        assertNotNull(authenticated.user.boundDevice)
        assertEquals("fp_omar_s24", authenticated.user.boundDevice?.fingerprint)
    }

    // ── S3: التنقل المتسلسل بين الأصناف الخمسة وتحديث الأوصاف الحية للمظهر والنسخ التلقائي ──
    @Test
    fun s3_sequential_navigation_across_all_categories_and_live_subtitles_update() {
        for (category in SettingsCatalogRules.orderedCategories()) {
            val active = SettingsCatalogRules.openCategory(category)
            assertEquals(category, active)
            assertEquals(
                SettingsBackOutcome.ReturnToCategoriesRoot,
                SettingsCatalogRules.handleBack(active)
            )
        }

        val darkSubtitle = SettingsCatalogRules.buildCategoryLiveSubtitle(
            category = SettingsCategory.APPEARANCE,
            isAdmin = false,
            currentUsername = "omar",
            totalUsersCount = 10,
            pendingDeviceRequestsCount = 0,
            themeMode = ThemeMode.DARK,
            useDynamicColor = true,
            isBackupFolderConfigured = true,
            backupIntervalDays = 3
        )
        assertTrue(darkSubtitle.contains("الوضع الداكن"))
        assertTrue(darkSubtitle.contains("ألوان ديناميكية"))

        val backupSubtitle = SettingsCatalogRules.buildCategoryLiveSubtitle(
            category = SettingsCategory.AUTO_BACKUP,
            isAdmin = false,
            currentUsername = "omar",
            totalUsersCount = 10,
            pendingDeviceRequestsCount = 0,
            themeMode = ThemeMode.DARK,
            useDynamicColor = false,
            isBackupFolderConfigured = true,
            backupIntervalDays = 3
        )
        assertTrue(backupSubtitle.contains("مفعّل"))
        assertTrue(backupSubtitle.contains("كل 3 أيام"))
    }

    // ── S4 + MR3: إدارة 75 مستخدماً مع البحث الفوري وتوسيع عدة بطاقات وزر طي الكل ──
    @Test
    fun s4_large_list_75_users_search_filtering_monotonicity_and_collapse_all() {
        val admin = sampleUser("saged", isAdmin = true)
        val students = (1..74).map { idx ->
            sampleUser(
                username = "user_$idx",
                device = if (idx % 2 == 0) sampleDevice(fp = "fp_$idx", model = "Pixel_$idx") else null
            )
        }
        val allUsers = students + admin // 75 مستخدماً، المشرف في آخر القائمة لاختبار الترتيب

        // المشرف يوضع دائماً في المقدمة ثم بقية المستخدمين مرتبين أبجدياً
        val sortedAll = SettingsCatalogRules.filterAndSortUsers(allUsers, "")
        assertEquals(75, sortedAll.size)
        assertEquals("saged", sortedAll.first().username)

        // MR3: أحادية التصفية (تضييق البحث يقلل أو يساوي عدد النتائج دائماً)
        val filteredBroad = SettingsCatalogRules.filterAndSortUsers(allUsers, "user_")
        val filteredNarrow = SettingsCatalogRules.filterAndSortUsers(allUsers, "user_5")
        val filteredExact = SettingsCatalogRules.filterAndSortUsers(allUsers, "user_55")
        assertTrue(filteredBroad.size > filteredNarrow.size)
        assertTrue(filteredNarrow.size > filteredExact.size)
        assertEquals(1, filteredExact.size)
        assertEquals("user_55", filteredExact.first().username)

        // توسيع بطاقتين ثم طي الكل
        var state = UserCardsExpansionState()
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_50")
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_55")
        assertEquals(2, state.expandedUsernames.size)

        state = SettingsCatalogRules.collapseAll(state)
        assertTrue(state.expandedUsernames.isEmpty())
    }

    // ── S5 + Inv5: حذف مستخدم متوسع ينظف حالته دون إغلاق بطاقات المستخدمين الآخرين ──
    @Test
    fun s5_deleting_expanded_user_prunes_only_deleted_user_from_expansion_state() {
        val initialUsers = (1..52).map { sampleUser("user_$it") }
        var state = UserCardsExpansionState()
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_10")
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_20")
        state = SettingsCatalogRules.togglePasswordVisibility(state, "user_10")
        state = SettingsCatalogRules.togglePasswordVisibility(state, "user_20")

        // حذف user_10 من السجل
        val remainingUsers = initialUsers.filterNot { it.username == "user_10" }
        state = SettingsCatalogRules.reconcileWithRegistry(state, remainingUsers)

        assertFalse(state.isExpanded("user_10"))
        assertFalse(state.isPasswordRevealed("user_10"))
        assertTrue(state.isExpanded("user_20"))
        assertTrue(state.isPasswordRevealed("user_20"))
        assertEquals(setOf("user_20"), state.expandedUsernames)
    }

    // ── S6: استعادة الصنف المحفوظ وحدود فاصل النسخ الاحتياطي المخصص (1..365) ──
    @Test
    fun s6_saved_category_restoration_and_custom_backup_days_boundaries() {
        for (cat in SettingsCategory.entries) {
            assertEquals(cat, SettingsCategory.fromSavedName(cat.name))
            assertEquals(cat, SettingsCategory.fromSavedName("  ${cat.name.lowercase()}  "))
        }
        assertNull(SettingsCategory.fromSavedName(null))
        assertNull(SettingsCategory.fromSavedName(""))

        assertEquals(0, SettingsCatalogRules.computeBackupChipIndex(1))
        assertEquals(1, SettingsCatalogRules.computeBackupChipIndex(3))
        assertEquals(2, SettingsCatalogRules.computeBackupChipIndex(7))
        assertEquals(3, SettingsCatalogRules.computeBackupChipIndex(30))
        assertEquals(4, SettingsCatalogRules.computeBackupChipIndex(15))

        assertTrue(SettingsCatalogRules.isValidCustomBackupDays("1"))
        assertTrue(SettingsCatalogRules.isValidCustomBackupDays("365"))
        assertTrue(SettingsCatalogRules.isValidCustomBackupDays(" 90 "))

        // التحليل الآمن (جلسة التحقق العميق 2026-10-09): يعيد القيمة الصالحة نفسها بلا !!
        assertEquals(1, SettingsCatalogRules.parseCustomBackupDays("1"))
        assertEquals(365, SettingsCatalogRules.parseCustomBackupDays("365"))
        assertEquals(90, SettingsCatalogRules.parseCustomBackupDays(" 90 "))
    }

    // ── S7: تسجيل الدخول من جهاز جديد ينتج طلب موافقة معلق وينعكس في ملخص الصنف الأول للمشرف ──
    @Test
    fun s7_foreign_device_login_creates_pending_request_reflected_in_admin_category_subtitle() {
        var reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "khalid",
            password = "secret123"
        ).getOrThrow()

        reg = (CloudAuthRules.evaluateLogin(
            registry = reg,
            usernameInput = "khalid",
            passwordInput = "secret123",
            currentDevice = sampleDevice(fp = "fp_khalid_old"),
            isOnline = true
        ) as AuthLoginOutcome.Authenticated).registry

        val pendingOutcome = CloudAuthRules.evaluateLogin(
            registry = reg,
            usernameInput = "khalid",
            passwordInput = "secret123",
            currentDevice = sampleDevice(fp = "fp_khalid_new"),
            isOnline = true
        )
        assertTrue(pendingOutcome is AuthLoginOutcome.PendingAdminApproval)
        val updatedReg = (pendingOutcome as AuthLoginOutcome.PendingAdminApproval).registry

        val adminSubtitle = SettingsCatalogRules.buildCategoryLiveSubtitle(
            category = SettingsCategory.USER_MANAGEMENT,
            isAdmin = true,
            currentUsername = "saged",
            totalUsersCount = updatedReg.users.size,
            pendingDeviceRequestsCount = updatedReg.pendingDeviceRequests.size,
            themeMode = ThemeMode.SYSTEM,
            useDynamicColor = false,
            isBackupFolderConfigured = false,
            backupIntervalDays = 1
        )
        assertTrue(adminSubtitle.contains("2 مستخدم مسجل"))
        assertTrue(adminSubtitle.contains("1 طلب جهاز معلق"))
    }

    // ── S8: رفض تسجيل الدخول عند انقطاع الإنترنت أو إيقاف الحساب مع ملخص البطاقة المطوية للموقوف ──
    @Test
    fun s8_offline_or_suspended_account_rejected_and_collapsed_summary_marks_suspended() {
        var reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "suspended_student",
            password = "pass123"
        ).getOrThrow()

        reg = CloudAuthRules.setUserActive(reg, "saged", "suspended_student", active = false).getOrThrow()
        val suspendedAccount = reg.users.first { it.username == "suspended_student" }

        val offlineAttempt = CloudAuthRules.evaluateLogin(
            registry = reg,
            usernameInput = "suspended_student",
            passwordInput = "pass123",
            currentDevice = sampleDevice(),
            isOnline = false
        )
        assertTrue(offlineAttempt is AuthLoginOutcome.Rejected)

        val suspendedAttempt = CloudAuthRules.evaluateLogin(
            registry = reg,
            usernameInput = "suspended_student",
            passwordInput = "pass123",
            currentDevice = sampleDevice(),
            isOnline = true
        )
        assertTrue(suspendedAttempt is AuthLoginOutcome.Rejected)

        val summary = SettingsCatalogRules.buildCollapsedUserSummary(suspendedAccount)
        assertEquals("موقوف", summary.statusBadgeText)
        assertFalse(summary.isStatusActive)
    }

    // ── S9: حماية حساب المشرف العام saged من ظهور أدوات الحذف أو الإيقاف أو تقليص الصلاحيات ──
    @Test
    fun s9_admin_account_hides_destructive_controls_and_displays_admin_summary() {
        val adminAccount = CloudAuthRules.createDefaultAdminAccount()
        val normalAccount = sampleUser(
            username = "student_a",
            perms = UserPermissions(canDownload = true, canUpload = false, canModify = false)
        )

        assertFalse(SettingsCatalogRules.canShowDestructiveControlsForUser(adminAccount))
        assertTrue(SettingsCatalogRules.canShowDestructiveControlsForUser(normalAccount))

        val adminSummary = SettingsCatalogRules.buildCollapsedUserSummary(adminAccount)
        assertEquals("مشرف عام", adminSummary.statusBadgeText)
        assertEquals("كامل الصلاحيات", adminSummary.permissionsSummaryText)
        assertTrue(adminSummary.isAdmin)

        val normalSummary = SettingsCatalogRules.buildCollapsedUserSummary(normalAccount)
        assertEquals("نشط", normalSummary.statusBadgeText)
        assertEquals("1/3 صلاحيات", normalSummary.permissionsSummaryText)
        assertEquals(1, normalSummary.enabledPermissionsCount)
    }

    // ── S10 + MR1 + MR2: صمود القواعد أمام المدخلات الفاسدة وتعاكس الطي/التوسيع وثبات التطبيع ──
    @Test
    fun s10_corrupt_inputs_resilience_and_metamorphic_involution_and_normalization() {
        assertNull(SettingsCategory.fromSavedName("CORRUPT_CATEGORY_999"))
        assertFalse(SettingsCatalogRules.isValidCustomBackupDays("0"))
        assertFalse(SettingsCatalogRules.isValidCustomBackupDays("-15"))
        assertFalse(SettingsCatalogRules.isValidCustomBackupDays("366"))
        assertFalse(SettingsCatalogRules.isValidCustomBackupDays("abc"))
        assertFalse(SettingsCatalogRules.isValidCustomBackupDays(null))

        // التحليل الآمن يمتص كل المدخلات الفاسدة ويعيد null بدل الانهيار (بلا !!)
        assertNull(SettingsCatalogRules.parseCustomBackupDays("0"))
        assertNull(SettingsCatalogRules.parseCustomBackupDays("-15"))
        assertNull(SettingsCatalogRules.parseCustomBackupDays("366"))
        assertNull(SettingsCatalogRules.parseCustomBackupDays("abc"))
        assertNull(SettingsCatalogRules.parseCustomBackupDays(null))
        assertNull(SettingsCatalogRules.parseCustomBackupDays("   "))

        val users = listOf(sampleUser("ahmed"), sampleUser("sara"))
        // استعلام مسافات فقط يعيد كل القائمة، واستعلام رموز غير موجودة يعيد قائمة فارغة بأمان
        assertEquals(2, SettingsCatalogRules.filterAndSortUsers(users, "   ").size)
        assertTrue(SettingsCatalogRules.filterAndSortUsers(users, "🔥🔥🔥").isEmpty())

        // MR1 (Involution) + MR2 (Case/Whitespace Invariance):
        var state = UserCardsExpansionState()
        state = SettingsCatalogRules.toggleUserExpanded(state, "  AHMED  ")
        assertTrue(state.isExpanded("ahmed"))
        assertTrue(state.isExpanded("  Ahmed "))

        state = SettingsCatalogRules.toggleUserExpanded(state, "ahmed")
        assertFalse(state.isExpanded("ahmed"))
        assertTrue(state.expandedUsernames.isEmpty())

        // 10 نقرات متتالية (زوجي) -> مطوي، 11 نقرة (فردي) -> متوسع
        repeat(10) {
            state = SettingsCatalogRules.toggleUserExpanded(state, "sara")
        }
        assertFalse(state.isExpanded("sara"))
        state = SettingsCatalogRules.toggleUserExpanded(state, "sara")
        assertTrue(state.isExpanded("sara"))
    }

    // ── H1 (محاكمة الحلقة 5): طي بطاقة المستخدم أو طي الكل يخفي الرمز المكشوف تلقائياً ──
    @Test
    fun h1_collapsing_user_card_or_collapse_all_automatically_hides_revealed_passwords() {
        var state = UserCardsExpansionState()

        // لا يمكن كشف رمز لمستخدم مطوي أصلاً
        state = SettingsCatalogRules.togglePasswordVisibility(state, "user_1")
        assertFalse(state.isPasswordRevealed("user_1"))

        // توسيع user_1 و user_2 وكشف رمزيهما
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_1")
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_2")
        state = SettingsCatalogRules.togglePasswordVisibility(state, "user_1")
        state = SettingsCatalogRules.togglePasswordVisibility(state, "user_2")
        assertTrue(state.isPasswordRevealed("user_1"))
        assertTrue(state.isPasswordRevealed("user_2"))

        // طي user_1 ثم إعادة توسيعه -> يجب أن يعود الرمز مخفياً!
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_1")
        assertFalse(state.isPasswordRevealed("user_1"))
        state = SettingsCatalogRules.toggleUserExpanded(state, "user_1")
        assertFalse(state.isPasswordRevealed("user_1"))

        // طي الكل يخفي جميع الرموز المكشوفة
        state = SettingsCatalogRules.collapseAll(state)
        assertFalse(state.isPasswordRevealed("user_2"))
        assertTrue(state.revealedPasswordUsernames.isEmpty())
    }

    // ── H3 (محاكمة الحلقة 5): البحث المؤقت لا يمسح حالة التوسيع للمستخدمين غير المطابقين للبحث ──
    @Test
    fun h3_search_filtering_never_wipes_expansion_state_of_non_matching_users() {
        val allUsers = (1..60).map { sampleUser("student_$it") }
        var state = UserCardsExpansionState()

        // المشرف يوسع student_5 و student_50
        state = SettingsCatalogRules.toggleUserExpanded(state, "student_5")
        state = SettingsCatalogRules.toggleUserExpanded(state, "student_50")

        // المشرف يبحث عن "student_50" فقط ثم يتحدث السجل السحابي
        state = SettingsCatalogRules.updateSearchQuery(state, "student_50")
        val visibleInSearch = SettingsCatalogRules.filterAndSortUsers(allUsers, state.searchQuery)
        assertEquals(1, visibleInSearch.size)

        // المزامنة تتم مع allUsers وليس visibleInSearch
        state = SettingsCatalogRules.reconcileWithRegistry(state, allUsers)

        // student_5 لا يزال متوسعاً رغم أنه لم يكن ظاهراً في نتيجة البحث المؤقتة
        assertTrue(state.isExpanded("student_5"))
        assertTrue(state.isExpanded("student_50"))
    }
}
