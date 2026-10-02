package com.unihub.app.data.auth

import com.unihub.app.data.cloud.CloudDeleteRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات قواعد المصادقة السحابية وبصمة الجهاز وإدارة المستخدمين والصلاحيات.
 * تغطي السيناريوهات العشرة (S1..S10) والثوابت (Inv1..Inv5) والعلاقات (MR1..MR3).
 */
class CloudAuthRulesTest {

    private fun device(
        fp: String = "fp_device_1",
        name: String = "Galaxy S24",
        manufacturer: String = "Samsung",
        model: String = "SM-S921B",
        os: String = "15"
    ) = BoundDeviceInfo(
        fingerprint = fp,
        deviceName = name,
        manufacturer = manufacturer,
        model = model,
        androidVersion = os,
        boundAt = 1_000L
    )

    // ── S1 + Inv1: تهيئة السجل الأولي وحساب المشرف الرئيسي saged / 192168 وإضافة مستخدم ──
    @Test
    fun s1_initial_registry_seeds_admin_saged_with_192168_and_full_permissions() {
        val outcome = CloudAuthRules.evaluateLogin(
            registry = null,
            usernameInput = "saged",
            passwordInput = "192168",
            currentDevice = device("fp_saged"),
            isOnline = true,
            now = 5_000L
        )
        assertTrue(outcome is AuthLoginOutcome.Authenticated)
        val auth = outcome as AuthLoginOutcome.Authenticated
        assertEquals("saged", auth.user.username)
        assertTrue(auth.user.isAdmin)
        assertTrue(auth.user.isActive)
        assertEquals(UserPermissions.FULL, auth.user.effectivePermissions)
        assertEquals("fp_saged", auth.user.boundDevice?.fingerprint)
        assertTrue(auth.registryChanged)

        // إضافة مستخدم جديد بصلاحيات مخصصة
        val withAhmed = CloudAuthRules.addUser(
            registry = auth.registry,
            actorUsername = "saged",
            newUsername = "ahmed",
            password = "pass123",
            permissions = UserPermissions(canDownload = true, canUpload = false, canModify = false),
            now = 6_000L
        ).getOrThrow()

        val ahmed = withAhmed.users.firstOrNull { it.username == "ahmed" }
        assertNotNull(ahmed)
        assertFalse(ahmed?.isAdmin ?: true)
        assertTrue(ahmed?.permissions?.canDownload == true)
        assertFalse(ahmed?.permissions?.canUpload ?: true)
        assertFalse(ahmed?.permissions?.canModify ?: true)
        assertNull(ahmed?.boundDevice)
    }

    // ── S2 + Inv3 + MR2: ربط الجهاز الأول وتغيير كلمة المرور وظهورها للمشرف بعد فك التشفير ──
    @Test
    fun s2_user_first_login_binds_device_and_password_change_is_visible_to_admin() {
        val base = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "ahmed",
            password = "pass123",
            now = 2_000L
        ).getOrThrow()

        // أول تسجيل دخول يربط الجهاز الأول تلقائياً
        val firstLogin = CloudAuthRules.evaluateLogin(
            registry = base,
            usernameInput = "ahmed",
            passwordInput = "pass123",
            currentDevice = device("fp_ahmed_1"),
            isOnline = true,
            now = 3_000L
        )
        assertTrue(firstLogin is AuthLoginOutcome.Authenticated)
        val regAfterBind = (firstLogin as AuthLoginOutcome.Authenticated).registry
        assertEquals("fp_ahmed_1", firstLogin.user.boundDevice?.fingerprint)

        // المستخدم يغير كلمة مروره الخاصة
        val regAfterPassChange = CloudAuthRules.userChangeOwnPassword(
            registry = regAfterBind,
            username = "ahmed",
            currentPassword = "pass123",
            newPassword = "newSecret#2026",
            now = 4_000L
        ).getOrThrow()

        val updatedAhmed = regAfterPassChange.users.first { it.username == "ahmed" }
        // Inv3: لا تُحفظ كنص مكشوف ولكن المشرف يستطيع رؤيتها
        assertNotEquals("newSecret#2026", updatedAhmed.encryptedPassword)
        assertEquals("newSecret#2026", CloudAuthRules.decryptPasswordForAdmin(updatedAhmed.encryptedPassword))
        // ربط الجهاز الأول يبقى محفوظاً
        assertEquals("fp_ahmed_1", updatedAhmed.boundDevice?.fingerprint)
        // كلمة المرور القديمة لم تعد تعمل
        val oldPassAttempt = CloudAuthRules.evaluateLogin(
            registry = regAfterPassChange,
            usernameInput = "ahmed",
            passwordInput = "pass123",
            currentDevice = device("fp_ahmed_1"),
            isOnline = true
        )
        assertTrue(oldPassAttempt is AuthLoginOutcome.Rejected)
    }

    // ── S3 + Inv2: تسجيل الدخول من جهاز غريب يظهر "سيرد لك مشرف" ويعتمد بعد موافقة المشرف ──
    @Test
    fun s3_foreign_device_blocks_login_with_pending_admin_message_until_admin_approves() {
        val base = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "ahmed",
            password = "pass123"
        ).getOrThrow()

        // ربط الجهاز الأول
        val regBound1 = (CloudAuthRules.evaluateLogin(
            registry = base,
            usernameInput = "ahmed",
            passwordInput = "pass123",
            currentDevice = device("fp_phone_1", "Phone 1"),
            isOnline = true,
            now = 2_000L
        ) as AuthLoginOutcome.Authenticated).registry

        // محاولة الدخول من جهاز ثانٍ (غريب)
        val foreignAttempt = CloudAuthRules.evaluateLogin(
            registry = regBound1,
            usernameInput = "ahmed",
            passwordInput = "pass123",
            currentDevice = device("fp_phone_2", "Pixel 9 Pro", "Google", "Pixel 9 Pro", "15"),
            isOnline = true,
            now = 3_000L
        )
        assertTrue(foreignAttempt is AuthLoginOutcome.PendingAdminApproval)
        val pending = foreignAttempt as AuthLoginOutcome.PendingAdminApproval
        assertEquals("سيرد لك مشرف", pending.message)
        assertEquals(1, pending.registry.pendingDeviceRequests.size)
        assertEquals("fp_phone_2", pending.request.requestedDevice.fingerprint)
        assertEquals("Pixel 9 Pro", pending.request.requestedDevice.model)

        // قبل موافقة المشرف، فحص الحالة يعيد StillPending
        val checkBefore = CloudAuthRules.checkPendingApproval(
            registry = pending.registry,
            username = "ahmed",
            currentDevice = device("fp_phone_2")
        )
        assertTrue(checkBefore is PendingApprovalCheckOutcome.StillPending)

        // موافقة المشرف saged على الجهاز الجديد
        val regApproved = CloudAuthRules.approveDeviceRequest(
            registry = pending.registry,
            actorUsername = "saged",
            requestId = pending.request.requestId,
            now = 4_000L
        ).getOrThrow()

        assertTrue(regApproved.pendingDeviceRequests.isEmpty())
        val checkAfter = CloudAuthRules.checkPendingApproval(
            registry = regApproved,
            username = "ahmed",
            currentDevice = device("fp_phone_2")
        )
        assertTrue(checkAfter is PendingApprovalCheckOutcome.Approved)

        // الجهاز القديم لم يعد معتمداً في الجلسة
        val oldDeviceSession = CloudAuthRules.validateExistingSession(
            registry = regApproved,
            username = "ahmed",
            currentDevice = device("fp_phone_1")
        )
        assertTrue(oldDeviceSession.isFailure)
    }

    // ── S4 + MR1 + MR3: تطبيع اسم المستخدم ومنع تكرار طلبات الجهاز الغريب ورفض المشرف ──
    @Test
    fun s4_username_normalization_and_idempotent_foreign_device_requests_and_rejection() {
        val base = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "  SAGED ",
            newUsername = "  Ahmed_User  ",
            password = "pass123"
        ).getOrThrow()

        val boundReg = (CloudAuthRules.evaluateLogin(
            registry = base,
            usernameInput = "AHMED_USER",
            passwordInput = "pass123",
            currentDevice = device("fp_1"),
            isOnline = true
        ) as AuthLoginOutcome.Authenticated).registry

        // MR3: 4 محاولات متتالية من نفس الجهاز الغريب تنتج طلباً معلقاً واحداً فقط
        var currentReg = boundReg
        var lastRequestId = ""
        repeat(4) { index ->
            val out = CloudAuthRules.evaluateLogin(
                registry = currentReg,
                usernameInput = "  ahmed_user ",
                passwordInput = "pass123",
                currentDevice = device("fp_2"),
                isOnline = true,
                now = 5_000L + index
            ) as AuthLoginOutcome.PendingAdminApproval
            currentReg = out.registry
            lastRequestId = out.request.requestId
        }
        assertEquals(1, currentReg.pendingDeviceRequests.size)

        // رفض المشرف للطلب
        val rejectedReg = CloudAuthRules.rejectDeviceRequest(
            registry = currentReg,
            actorUsername = "saged",
            requestId = lastRequestId,
            now = 9_000L
        ).getOrThrow()

        val checkRejected = CloudAuthRules.checkPendingApproval(
            registry = rejectedReg,
            username = "ahmed_user",
            currentDevice = device("fp_2")
        )
        assertTrue(checkRejected is PendingApprovalCheckOutcome.Rejected)
    }

    // ── S5 + Inv5 (أعلى خطر Risk=100): فرض الصلاحيات الانتقائية (السحب، الرفع، التعديل) ──
    @Test
    fun s5_granular_permissions_enforce_download_upload_and_modify_independently() {
        var reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "reader",
            password = "123",
            permissions = UserPermissions(canDownload = true, canUpload = false, canModify = false)
        ).getOrThrow()

        val reader = reg.users.first { it.username == "reader" }
        assertTrue(CloudAuthRules.checkPermission(reader, AuthPermission.DOWNLOAD).isSuccess)
        assertTrue(CloudAuthRules.checkPermission(reader, AuthPermission.UPLOAD).isFailure)
        assertTrue(CloudAuthRules.checkPermission(reader, AuthPermission.MODIFY).isFailure)

        // تحديث الصلاحيات من المشرف لمنع السحب والسماح بالرفع فقط
        reg = CloudAuthRules.updateUserPermissions(
            registry = reg,
            actorUsername = "saged",
            targetUsername = "reader",
            permissions = UserPermissions(canDownload = false, canUpload = true, canModify = false)
        ).getOrThrow()

        val updatedReader = reg.users.first { it.username == "reader" }
        assertTrue(CloudAuthRules.checkPermission(updatedReader, AuthPermission.DOWNLOAD).isFailure)
        assertTrue(CloudAuthRules.checkPermission(updatedReader, AuthPermission.UPLOAD).isSuccess)
        assertTrue(CloudAuthRules.checkPermission(updatedReader, AuthPermission.MODIFY).isFailure)
    }

    // ── S6 (ثاني أعلى خطر Risk=80): إيقاف المستخدم أو حذفه يبطل الجلسة والصلاحيات فوراً ──
    @Test
    fun s6_suspending_or_deleting_user_immediately_invalidates_session_and_permissions() {
        var reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "student",
            password = "123"
        ).getOrThrow()

        reg = (CloudAuthRules.evaluateLogin(
            registry = reg,
            usernameInput = "student",
            passwordInput = "123",
            currentDevice = device("fp_student"),
            isOnline = true
        ) as AuthLoginOutcome.Authenticated).registry

        // الجلسة صالحة قبل الإيقاف
        assertTrue(CloudAuthRules.validateExistingSession(reg, "student", device("fp_student")).isSuccess)

        // المشرف يوقف المستخدم
        val suspendedReg = CloudAuthRules.setUserActive(reg, "saged", "student", active = false).getOrThrow()
        assertTrue(CloudAuthRules.validateExistingSession(suspendedReg, "student", device("fp_student")).isFailure)

        val suspendedUser = suspendedReg.users.first { it.username == "student" }
        assertTrue(CloudAuthRules.checkPermission(suspendedUser, AuthPermission.DOWNLOAD).isFailure)

        // المشرف يحذف المستخدم نهائياً
        val deletedReg = CloudAuthRules.deleteUser(suspendedReg, "saged", "student").getOrThrow()
        assertTrue(CloudAuthRules.validateExistingSession(deletedReg, "student", device("fp_student")).isFailure)
    }

    // ── S7: منع تسجيل الدخول الأول بدون اتصال بالإنترنت ──
    @Test
    fun s7_login_without_internet_is_rejected_immediately() {
        val outcome = CloudAuthRules.evaluateLogin(
            registry = CloudAuthRules.createInitialRegistry(),
            usernameInput = "saged",
            passwordInput = "192168",
            currentDevice = device("fp_1"),
            isOnline = false
        )
        assertTrue(outcome is AuthLoginOutcome.Rejected)
        assertTrue((outcome as AuthLoginOutcome.Rejected).reason.contains("إنترنت"))
    }

    // ── S8 (ثالث أعلى خطر Risk=60): كلمة المرور الخاطئة لا تربط الجهاز ولا ترسل طلبات للمشرف ──
    @Test
    fun s8_wrong_password_never_binds_device_or_creates_pending_request() {
        val reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(),
            actorUsername = "saged",
            newUsername = "ahmed",
            password = "correctPassword"
        ).getOrThrow()

        val attempt = CloudAuthRules.evaluateLogin(
            registry = reg,
            usernameInput = "ahmed",
            passwordInput = "wrongPassword",
            currentDevice = device("fp_attacker"),
            isOnline = true
        )
        assertTrue(attempt is AuthLoginOutcome.Rejected)
        val ahmedAfter = reg.users.first { it.username == "ahmed" }
        assertNull(ahmedAfter.boundDevice)
        assertTrue(reg.pendingDeviceRequests.isEmpty())
    }

    // ── S9 + Inv1 + Inv4: حماية حساب المشرف saged وحماية ملف المصادقة السحابي من الحذف ──
    @Test
    fun s9_admin_saged_cannot_be_deleted_suspended_or_restricted_and_auth_file_is_protected() {
        val reg = CloudAuthRules.createInitialRegistry()

        assertTrue(CloudAuthRules.deleteUser(reg, "saged", "saged").isFailure)
        assertTrue(CloudAuthRules.setUserActive(reg, "saged", "saged", active = false).isFailure)
        assertTrue(
            CloudAuthRules.updateUserPermissions(
                reg, "saged", "saged",
                UserPermissions(canDownload = false, canUpload = true, canModify = true)
            ).isFailure
        )

        // Inv4: ملف سجل المصادقة السحابي محمي من الحذف عبر CloudDeleteRules
        assertFalse(CloudDeleteRules.isDeletableObjectKey("unihub_auth_registry.json"))
    }

    // ── S10 + JSON Roundtrip: منع غير المشرف من إدارة المستخدمين وثبات التحويل من وإلى JSON ──
    @Test
    fun s10_non_admin_cannot_manage_users_and_json_serialization_preserves_all_fields() {
        var reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "normal_user",
            password = "userPass99",
            permissions = UserPermissions(canDownload = true, canUpload = true, canModify = false),
            now = 2_000L
        ).getOrThrow()

        // مستخدم عادي يحاول إضافة أو حذف مستخدم أو تغيير كلمة مرور غيره -> يُرفض
        assertTrue(CloudAuthRules.addUser(reg, "normal_user", "hacker", "123").isFailure)
        assertTrue(CloudAuthRules.deleteUser(reg, "normal_user", "saged").isFailure)
        assertTrue(CloudAuthRules.adminChangeUserPassword(reg, "normal_user", "saged", "000").isFailure)

        // المشرف يغير كلمة مرور المستخدم ويتحقق من حفظها واسترجاعها عبر JSON
        reg = CloudAuthRules.adminChangeUserPassword(reg, "saged", "normal_user", "adminSetPass77", 3_000L).getOrThrow()

        val json = CloudAuthRules.toJson(reg)
        val restored = CloudAuthRules.fromJson(json, 4_000L)

        val restoredUser = restored.users.first { it.username == "normal_user" }
        assertEquals("adminSetPass77", CloudAuthRules.decryptPasswordForAdmin(restoredUser.encryptedPassword))
        assertTrue(CloudAuthRules.verifyPassword("adminSetPass77", restoredUser))
        assertFalse(restoredUser.permissions.canModify)
    }

    // ── H1 (محاكمة الحلقة 5): إيقاف المستخدم أو فك ربط جهازه يلغي طلبات الأجهزة المعلقة اليتيمة ──
    @Test
    fun h1_suspending_or_resetting_user_clears_pending_device_requests() {
        var reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "khaled",
            password = "pass123",
            now = 1_100L
        ).getOrThrow()

        // ربط الجهاز الأول ثم إرسال طلب من جهاز ثانٍ
        reg = (CloudAuthRules.evaluateLogin(reg, "khaled", "pass123", device("fp_1"), true, 1_200L)
            as AuthLoginOutcome.Authenticated).registry
        val pendingOutcome = CloudAuthRules.evaluateLogin(reg, "khaled", "pass123", device("fp_2"), true, 1_300L)
            as AuthLoginOutcome.PendingAdminApproval
        reg = pendingOutcome.registry
        assertEquals(1, reg.pendingDeviceRequests.size)

        // إذا أوقف المشرف حساب خالد، يجب ألا يبقى طلبه معلقاً في قائمة انتظار المشرف، ويجب رفض الموافقة عليه وهو موقوف
        val suspendedReg = CloudAuthRules.setUserActive(reg, "saged", "khaled", active = false, now = 1_400L).getOrThrow()
        assertTrue(suspendedReg.pendingDeviceRequests.isEmpty())
        assertTrue(CloudAuthRules.approveDeviceRequest(suspendedReg, "saged", pendingOutcome.request.requestId, 1_500L).isFailure)

        // وإذا فك المشرف ربط جهاز مستخدم نشط لديه طلب معلق، يجب تنظيف الطلبات المعلقة القديمة
        val resetReg = CloudAuthRules.resetUserBoundDevice(reg, "saged", "khaled", now = 1_600L).getOrThrow()
        assertTrue(resetReg.pendingDeviceRequests.isEmpty())
    }

    // ── H2 (محاكمة الحلقة 5): دمج السجلات المكررة لنفس المستخدم وتنظيف طلبات المستخدمين غير الموجودين ──
    @Test
    fun h2_ensure_invariants_deduplicates_users_and_prunes_orphan_requests() {
        val base = CloudAuthRules.createInitialRegistry(1_000L)
        val dupOlder = CloudUserAccount(
            username = "ali",
            displayName = "Ali Old",
            passwordHash = CloudAuthRules.hashPassword("ali", "oldPass"),
            encryptedPassword = CloudAuthRules.encryptPassword("oldPass"),
            updatedAt = 1_000L
        )
        val dupNewer = CloudUserAccount(
            username = "ALI",
            displayName = "Ali New",
            passwordHash = CloudAuthRules.hashPassword("ali", "newPass"),
            encryptedPassword = CloudAuthRules.encryptPassword("newPass"),
            updatedAt = 2_000L
        )
        val orphanRequest = DeviceChangeRequest(
            requestId = "req_ghost",
            username = "ghost_user",
            currentBoundDevice = null,
            requestedDevice = device("fp_ghost"),
            requestedAt = 1_500L,
            status = DeviceApprovalStatus.PENDING
        )
        val dirtyRegistry = base.copy(
            users = base.users + listOf(dupOlder, dupNewer),
            deviceRequests = listOf(orphanRequest)
        )

        val cleaned = CloudAuthRules.ensureAdminInvariants(dirtyRegistry, 3_000L)
        val aliAccounts = cleaned.users.filter { CloudAuthRules.normalizeUsername(it.username) == "ali" }
        assertEquals(1, aliAccounts.size)
        assertTrue(CloudAuthRules.verifyPassword("newPass", aliAccounts.first()))
        assertTrue(cleaned.deviceRequests.isEmpty())
    }

    // ── H3 (محاكمة الحلقة 5): تقليم سجل طلبات الأجهزة المحسومة بسقف 50 مع حفظ جميع الطلبات المعلقة ──
    @Test
    fun h3_resolved_device_requests_are_capped_while_preserving_all_pending() {
        var reg = CloudAuthRules.addUser(
            registry = CloudAuthRules.createInitialRegistry(1_000L),
            actorUsername = "saged",
            newUsername = "noor",
            password = "pass123",
            now = 1_100L
        ).getOrThrow()

        val resolvedList = (1..65).map { idx ->
            DeviceChangeRequest(
                requestId = "req_resolved_$idx",
                username = "noor",
                currentBoundDevice = device("fp_base"),
                requestedDevice = device("fp_old_$idx"),
                requestedAt = idx * 100L,
                status = DeviceApprovalStatus.REJECTED,
                decidedAt = idx * 100L + 50L
            )
        }
        val activePending = DeviceChangeRequest(
            requestId = "req_pending_active",
            username = "noor",
            currentBoundDevice = device("fp_base"),
            requestedDevice = device("fp_new_live"),
            requestedAt = 99_000L,
            status = DeviceApprovalStatus.PENDING
        )

        val cleaned = CloudAuthRules.ensureAdminInvariants(
            reg.copy(deviceRequests = resolvedList + activePending),
            100_000L
        )
        val pendingCount = cleaned.deviceRequests.count { it.status == DeviceApprovalStatus.PENDING }
        val resolvedCount = cleaned.deviceRequests.count { it.status != DeviceApprovalStatus.PENDING }
        assertEquals(1, pendingCount)
        assertEquals(50, resolvedCount)
        assertTrue(cleaned.deviceRequests.any { it.requestId == "req_resolved_65" })
        assertFalse(cleaned.deviceRequests.any { it.requestId == "req_resolved_1" })
    }
}
