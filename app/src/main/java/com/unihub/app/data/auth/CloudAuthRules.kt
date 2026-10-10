package com.unihub.app.data.auth

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * القواعد النقية لنظام المصادقة السحابية وإدارة المستخدمين والأجهزة والصلاحيات.
 * صُممت بلا أي اعتماد على إطار أندرويد لتُختبر بالكامل على JVM وتعمل بنفس السلوك الحتمي.
 */
object CloudAuthRules {

    const val ADMIN_USERNAME: String = "saged"
    const val PENDING_APPROVAL_MESSAGE: String = "سيرد لك مشرف"
    const val MAX_RESOLVED_DEVICE_REQUESTS: Int = 50
    const val MAX_PENDING_DEVICE_REQUESTS: Int = 100
    const val MAX_USER_ACCOUNTS: Int = 500
    const val MIN_USER_PASSWORD_LENGTH: Int = 10
    const val MIN_ADMIN_BOOTSTRAP_PASSWORD_LENGTH: Int = 12
    const val MAX_PASSWORD_LENGTH: Int = 256
    const val MAX_USERNAME_LENGTH: Int = 64

    private const val SCHEMA_VERSION: Int = 2
    private const val PASSWORD_HASH_PREFIX: String = "pbkdf2-sha256"
    private const val PASSWORD_HASH_ITERATIONS: Int = 210_000
    private const val PASSWORD_SALT_BYTES: Int = 16
    private const val PASSWORD_HASH_BYTES: Int = 32
    // Compatibility only: used to validate old SHA-256 hashes during one-time password migration.
    // This value is public application code, not a secret or a cryptographic protection boundary.
    private const val LEGACY_HASH_PEPPER: String = "UniHub::CloudAuth::2026::SagedAdminVaultKey::v1"

    /** تطبيع اسم المستخدم للمقارنة المفتاحية مع إزالة المسافات الزائدة وتوحيد الأحرف اللاتينية. */
    fun normalizeUsername(raw: String): String = raw.trim().lowercase(Locale.US)

    /** حساب بصمة الجهاز الموحدة من مُعرّفات العتاد والنظام. */
    fun computeDeviceFingerprint(
        androidId: String,
        manufacturer: String,
        model: String,
        brand: String = "",
        device: String = ""
    ): String {
        val canonical = listOf(
            androidId.trim().lowercase(Locale.US),
            manufacturer.trim().lowercase(Locale.US),
            model.trim().lowercase(Locale.US),
            brand.trim().lowercase(Locale.US),
            device.trim().lowercase(Locale.US)
        ).joinToString("|")
        return sha256Hex("unihub-device-fp::$canonical")
    }

    /** Store salted PBKDF2-HMAC-SHA256 verifiers; no code path can recover the original password. */
    fun hashPassword(username: String, plainPassword: String): String {
        val normalized = normalizeUsername(username)
        val password = plainPassword.trim()
        require(password.isNotEmpty() && password.length <= MAX_PASSWORD_LENGTH) {
            "كلمة المرور يجب ألا تتجاوز $MAX_PASSWORD_LENGTH حرفًا"
        }
        val salt = ByteArray(PASSWORD_SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derivePasswordHash(normalized, password, salt, PASSWORD_HASH_ITERATIONS)
        return listOf(
            PASSWORD_HASH_PREFIX,
            PASSWORD_HASH_ITERATIONS.toString(),
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(derived)
        ).joinToString("\$")
    }

    fun isCurrentPasswordHash(encoded: String): Boolean = encoded.startsWith("${PASSWORD_HASH_PREFIX}\$")

    /** Legacy SHA-256 hashes are accepted only to upgrade a user's verifier at the next successful login. */
    fun verifyPassword(plainPassword: String, account: CloudUserAccount): Boolean {
        val candidate = plainPassword.trim()
        if (candidate.isEmpty() || candidate.length > MAX_PASSWORD_LENGTH || account.passwordHash.isBlank()) return false
        if (isCurrentPasswordHash(account.passwordHash)) {
            val parts = account.passwordHash.split('$')
            if (parts.size != 4 || parts[0] != PASSWORD_HASH_PREFIX) return false
            val iterations = parts[1].toIntOrNull() ?: return false
            // Bound encoded work factors to prevent a corrupt registry from forcing unbounded CPU work.
            if (iterations !in MIN_PASSWORD_HASH_ITERATIONS..MAX_PASSWORD_HASH_ITERATIONS) return false
            val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
            val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
            if (salt.size !in 12..32 || expected.size != PASSWORD_HASH_BYTES) return false
            val actual = runCatching {
                derivePasswordHash(normalizeUsername(account.username), candidate, salt, iterations)
            }.getOrNull() ?: return false
            return MessageDigest.isEqual(expected, actual)
        }
        if (!account.passwordHash.matches(Regex("[0-9a-fA-F]{64}"))) return false
        val legacy = sha256Hex("$LEGACY_HASH_PEPPER::${normalizeUsername(account.username)}::$candidate")
        return MessageDigest.isEqual(
            legacy.toByteArray(Charsets.US_ASCII),
            account.passwordHash.lowercase(Locale.US).toByteArray(Charsets.US_ASCII)
        )
    }

    private fun derivePasswordHash(username: String, password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec("$username\u0000$password".toCharArray(), salt, iterations, PASSWORD_HASH_BYTES * 8)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val MIN_PASSWORD_HASH_ITERATIONS = 100_000
    private const val MAX_PASSWORD_HASH_ITERATIONS = 500_000

    /** إنشاء حساب مشرف بكلمة مرور اختارها مالك التطبيق، وليس بكلمة افتراضية مضمنة. */
    fun createDefaultAdminAccount(password: String, now: Long = System.currentTimeMillis()): CloudUserAccount {
        val cleanPassword = password.trim()
        require(cleanPassword.length >= MIN_ADMIN_BOOTSTRAP_PASSWORD_LENGTH) {
            "كلمة مرور المشرف يجب أن تتكون من 12 خانة على الأقل"
        }
        require(cleanPassword.length <= MAX_PASSWORD_LENGTH) {
            "كلمة مرور المشرف يجب ألا تتجاوز $MAX_PASSWORD_LENGTH حرفًا"
        }
        return CloudUserAccount(
            username = ADMIN_USERNAME,
            displayName = ADMIN_USERNAME,
            passwordHash = hashPassword(ADMIN_USERNAME, cleanPassword),
            isAdmin = true,
            isActive = true,
            permissions = UserPermissions.FULL,
            boundDevice = null,
            createdAt = now,
            updatedAt = now
        )
    }

    /** Create a new auth registry with a password supplied by the owner on first setup. */
    fun createInitialRegistry(adminPassword: String, now: Long = System.currentTimeMillis()): CloudAuthRegistry =
        CloudAuthRegistry(
            schemaVersion = SCHEMA_VERSION,
            updatedAt = now,
            users = listOf(createDefaultAdminAccount(adminPassword, now)),
            deviceRequests = emptyList()
        )

    private fun emptyRegistry(now: Long = System.currentTimeMillis()): CloudAuthRegistry =
        CloudAuthRegistry(schemaVersion = SCHEMA_VERSION, updatedAt = now, users = emptyList(), deviceRequests = emptyList())

    /**
     * فرض ثوابت حساب المشرف (Inv1):
     * حساب saged موجود دائماً، نشط دائماً، يملك كامل الصلاحيات دائماً.
     */
    fun ensureAdminInvariants(
        registry: CloudAuthRegistry,
        now: Long = System.currentTimeMillis()
    ): CloudAuthRegistry {
        val existingAdmin = registry.users.firstOrNull {
            normalizeUsername(it.username) == ADMIN_USERNAME
        }
        // Never recreate a missing administrator with a known default password.
        // An absent admin is treated as a damaged/uninitialized registry by the login flow.
        val enforcedAdmin = existingAdmin?.copy(
            username = ADMIN_USERNAME,
            displayName = existingAdmin.displayName.ifBlank { ADMIN_USERNAME },
            isAdmin = true,
            isActive = true,
            permissions = UserPermissions.FULL
        )
        val otherUsers = registry.users
            .filterNot { normalizeUsername(it.username) == ADMIN_USERNAME }
            .filter { normalizeUsername(it.username).length <= MAX_USERNAME_LENGTH }
            .groupBy { normalizeUsername(it.username) }
            .mapNotNull { (normName, accounts) ->
                if (normName.isBlank()) null
                else accounts.maxByOrNull { it.updatedAt }?.copy(username = normName)
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_USER_ACCOUNTS - 1)
        val allUsers = listOfNotNull(enforcedAdmin) + otherUsers
        val validUsernames = allUsers.mapTo(mutableSetOf()) { normalizeUsername(it.username) }
        val validRequests = registry.deviceRequests.filter {
            normalizeUsername(it.username) in validUsernames
        }
        val pendingRequests = validRequests
            .filter { it.status == DeviceApprovalStatus.PENDING }
            .sortedByDescending { it.requestedAt }
            .take(MAX_PENDING_DEVICE_REQUESTS)
            .sortedBy { it.requestedAt }
        val cappedResolved = validRequests
            .filter { it.status != DeviceApprovalStatus.PENDING }
            .sortedByDescending { maxOf(it.decidedAt, it.requestedAt) }
            .take(MAX_RESOLVED_DEVICE_REQUESTS)
            .sortedBy { it.requestedAt }

        return registry.copy(
            schemaVersion = SCHEMA_VERSION,
            users = allUsers,
            deviceRequests = cappedResolved + pendingRequests
        )
    }

    /**
     * تقييم محاولة تسجيل الدخول وفق شروط السحابة وبصمة الجهاز:
     * 1) يُشترط توفر الإنترنت لأن السحابة هي من تقرر في التسجيل.
     * 2) يُفحص وجود المستخدم وصحة كلمة المرور أولاً (قبل فحص/ربط الجهاز).
     * 3) يُفحص ما إذا كان الحساب نشطاً أم أوقفه المشرف.
     * 4) إن كان الجهاز هو الأول للمستخدم (أو مطابقاً لبصمته، أو كان المشرف saged) يُعتمد الدخول.
     * 5) إن كان جهازاً مختلفاً ("جهاز غريب") يُمنع الدخول الفوري ويُسجل طلب موافقة للمشرف
     *    وتُعاد رسالة "سيرد لك مشرف".
     */
    fun evaluateLogin(
        registry: CloudAuthRegistry?,
        usernameInput: String,
        passwordInput: String,
        currentDevice: BoundDeviceInfo,
        isOnline: Boolean,
        now: Long = System.currentTimeMillis()
    ): AuthLoginOutcome {
        if (!isOnline) {
            return AuthLoginOutcome.Rejected("يجب توفر اتصال بالإنترنت لتسجيل الدخول؛ السحابة هي التي تقرر في التسجيل")
        }
        val normalizedUser = normalizeUsername(usernameInput)
        val cleanPass = passwordInput.trim()
        if (normalizedUser.isBlank() || cleanPass.isBlank()) {
            return AuthLoginOutcome.Rejected("أدخل اسم المستخدم وكلمة المرور")
        }
        if (cleanPass.length > MAX_PASSWORD_LENGTH || normalizedUser.length > MAX_USERNAME_LENGTH) {
            return AuthLoginOutcome.Rejected("اسم المستخدم أو كلمة المرور أطول من الحد المسموح")
        }
        if (currentDevice.fingerprint.isBlank()) {
            return AuthLoginOutcome.Rejected("تعذّر التحقق من بصمة الجهاز")
        }

        // First-run bootstrap is allowed only when the auth object is genuinely absent.
        // A malformed/present registry must not silently reset ownership.
        if (registry == null) {
            if (normalizedUser != ADMIN_USERNAME) {
                return AuthLoginOutcome.Rejected("إعداد السحابة الأولي يتطلب اسم المشرف saged")
            }
            if (cleanPass.length < MIN_ADMIN_BOOTSTRAP_PASSWORD_LENGTH) {
                return AuthLoginOutcome.Rejected("أنشئ كلمة مرور للمشرف من 12 خانة على الأقل")
            }
            val admin = createDefaultAdminAccount(cleanPass, now).copy(
                boundDevice = currentDevice.copy(boundAt = currentDevice.boundAt.takeIf { it > 0L } ?: now)
            )
            val initial = CloudAuthRegistry(
                schemaVersion = SCHEMA_VERSION,
                updatedAt = now,
                users = listOf(admin),
                deviceRequests = emptyList()
            )
            return AuthLoginOutcome.Authenticated(admin, initial, registryChanged = true)
        }

        var baseRegistry = ensureAdminInvariants(registry, now)
        if (baseRegistry.users.none { normalizeUsername(it.username) == ADMIN_USERNAME }) {
            return AuthLoginOutcome.Rejected(
                "سجل المصادقة الموجود لا يحتوي على حساب المشرف؛ أعده من نسخة موثوقة بدل إعادة تهيئته تلقائيًا"
            )
        }
        var account = baseRegistry.users.firstOrNull {
            normalizeUsername(it.username) == normalizedUser
        } ?: return AuthLoginOutcome.Rejected("اسم المستخدم أو كلمة المرور غير صحيحة")

        // Verify the password before exposing account status or creating a device request.
        if (!verifyPassword(cleanPass, account)) {
            return AuthLoginOutcome.Rejected("اسم المستخدم أو كلمة المرور غير صحيحة")
        }

        // Migrate legacy fast SHA-256 hashes after (and only after) a successful verification.
        val passwordHashUpgraded = !isCurrentPasswordHash(account.passwordHash)
        if (passwordHashUpgraded) {
            account = account.copy(
                passwordHash = hashPassword(account.username, cleanPass),
                updatedAt = now
            )
            baseRegistry = baseRegistry.copy(
                updatedAt = now,
                users = baseRegistry.users.map {
                    if (normalizeUsername(it.username) == normalizedUser) account else it
                }
            )
        }

        if (!account.isActive && !account.isAdmin) {
            return AuthLoginOutcome.Rejected("تم إيقاف هذا الحساب من قبل المشرف")
        }

        val stampedDevice = currentDevice.copy(
            boundAt = currentDevice.boundAt.takeIf { it > 0L } ?: now
        )

        // Snapshot the nullable property before branching. `account` is reassigned during
        // legacy hash migration, so Kotlin correctly refuses to smart-cast account.boundDevice.
        val boundDevice = account.boundDevice
        if (account.isAdmin || boundDevice == null) {
            val deviceChanged = boundDevice?.fingerprint != stampedDevice.fingerprint
            val updatedAccount = if (deviceChanged) {
                account.copy(boundDevice = stampedDevice, updatedAt = now)
            } else account
            val updatedRegistry = baseRegistry.copy(
                updatedAt = if (deviceChanged) now else baseRegistry.updatedAt,
                users = baseRegistry.users.map {
                    if (normalizeUsername(it.username) == normalizedUser) updatedAccount else it
                }
            )
            return AuthLoginOutcome.Authenticated(
                user = updatedAccount,
                registry = updatedRegistry,
                registryChanged = deviceChanged || passwordHashUpgraded
            )
        }

        if (boundDevice.fingerprint == stampedDevice.fingerprint) {
            return AuthLoginOutcome.Authenticated(
                user = account,
                registry = baseRegistry,
                registryChanged = passwordHashUpgraded
            )
        }

        // Unknown device: store one pending request per (user, device) pair.
        val existingPending = baseRegistry.deviceRequests.firstOrNull {
            normalizeUsername(it.username) == normalizedUser &&
                it.requestedDevice.fingerprint == stampedDevice.fingerprint &&
                it.status == DeviceApprovalStatus.PENDING
        }
        val request = existingPending?.copy(
            currentBoundDevice = account.boundDevice,
            requestedDevice = stampedDevice,
            requestedAt = now
        ) ?: DeviceChangeRequest(
            requestId = "req_${UUID.randomUUID()}",
            username = account.username,
            currentBoundDevice = account.boundDevice,
            requestedDevice = stampedDevice,
            requestedAt = now,
            status = DeviceApprovalStatus.PENDING
        )
        val updatedRequests = baseRegistry.deviceRequests.filterNot {
            normalizeUsername(it.username) == normalizedUser &&
                it.requestedDevice.fingerprint == stampedDevice.fingerprint &&
                it.status == DeviceApprovalStatus.PENDING
        } + request
        val updatedRegistry = baseRegistry.copy(updatedAt = now, deviceRequests = updatedRequests)
        return AuthLoginOutcome.PendingAdminApproval(
            username = account.username,
            message = PENDING_APPROVAL_MESSAGE,
            request = request,
            registry = updatedRegistry
        )
    }

    /**
     * التحقق من حالة طلب الجهاز المعلق عندما ينتظر المستخدم رد المشرف.
     */
    fun checkPendingApproval(
        registry: CloudAuthRegistry,
        username: String,
        currentDevice: BoundDeviceInfo
    ): PendingApprovalCheckOutcome {
        val normalized = normalizeUsername(username)
        val cleanRegistry = ensureAdminInvariants(registry)
        val account = cleanRegistry.users.firstOrNull {
            normalizeUsername(it.username) == normalized
        } ?: return PendingApprovalCheckOutcome.Rejected("لم يعد الحساب موجوداً لدى المشرف")

        if (!account.isActive && !account.isAdmin) {
            return PendingApprovalCheckOutcome.Rejected("تم إيقاف الحساب من قبل المشرف")
        }

        if (account.isAdmin || account.boundDevice?.fingerprint == currentDevice.fingerprint) {
            return PendingApprovalCheckOutcome.Approved(account)
        }

        val matchingRequests = cleanRegistry.deviceRequests.filter {
            normalizeUsername(it.username) == normalized &&
                it.requestedDevice.fingerprint == currentDevice.fingerprint
        }
        val pending = matchingRequests.firstOrNull { it.status == DeviceApprovalStatus.PENDING }
        if (pending != null) {
            return PendingApprovalCheckOutcome.StillPending(PENDING_APPROVAL_MESSAGE, pending)
        }

        val rejected = matchingRequests.maxByOrNull { it.decidedAt }
        if (rejected?.status == DeviceApprovalStatus.REJECTED) {
            return PendingApprovalCheckOutcome.Rejected("رفض المشرف طلب تسجيل الدخول من هذا الجهاز")
        }

        return PendingApprovalCheckOutcome.Rejected("انتهت صلاحية الطلب؛ يرجى تسجيل الدخول مجدداً")
    }

    /**
     * التحقق المستمر من صلاحية الجلسة المحفوظة محلياً مقابل أحدث سجل في السحابة:
     * يضمن إخراج المستخدم فوراً إذا أوقفه المشرف أو حذفه أو تغيّر جهازه المعتمد.
     */
    fun validateExistingSession(
        registry: CloudAuthRegistry,
        username: String,
        currentDevice: BoundDeviceInfo
    ): Result<CloudUserAccount> {
        val normalized = normalizeUsername(username)
        val cleanRegistry = ensureAdminInvariants(registry)
        val account = cleanRegistry.users.firstOrNull {
            normalizeUsername(it.username) == normalized
        } ?: return Result.failure(IOException("حُذف هذا الحساب من قبل المشرف"))

        if (!account.isActive && !account.isAdmin) {
            return Result.failure(IOException("تم إيقاف حسابك من قبل المشرف"))
        }

        if (!account.isAdmin) {
            val boundFp = account.boundDevice?.fingerprint
            if (boundFp.isNullOrBlank() || boundFp != currentDevice.fingerprint) {
                return Result.failure(IOException("هذا الجهاز لم يعد معتمداً لهذا الحساب؛ سجل الدخول لطلب موافقة المشرف"))
            }
        }

        return Result.success(account)
    }

    /**
     * حارس الصلاحيات المركزي قبل تنفيذ أي عملية سحابية (سحب / رفع / تعديل أو حذف).
     */
    fun checkPermission(
        user: CloudUserAccount?,
        permission: AuthPermission
    ): Result<Unit> {
        if (user == null) {
            return Result.failure(IOException("يجب تسجيل الدخول أولاً للوصول إلى السحابة"))
        }
        if (!user.isActive && !user.isAdmin) {
            return Result.failure(IOException("تم إيقاف حسابك من قبل المشرف"))
        }
        if (user.isAdmin) return Result.success(Unit)

        val allowed = user.effectivePermissions.allows(permission)
        if (!allowed) {
            val message = when (permission) {
                AuthPermission.DOWNLOAD -> "أوقف المشرف صلاحية السحب والتنزيل من السحابة لحسابك"
                AuthPermission.UPLOAD -> "ليس لديك صلاحية رفع الملفات إلى السحابة — تواصل مع المشرف"
                AuthPermission.MODIFY -> "ليس لديك صلاحية التعديل أو الحذف أو النقل في الحساب — تواصل مع المشرف"
            }
            return Result.failure(IOException(message))
        }
        return Result.success(Unit)
    }

    // ─── عمليات المشرف وإدارة المستخدمين ─────────────────────────────────

    private fun requireAdminActor(registry: CloudAuthRegistry, actorUsername: String): Result<CloudUserAccount> {
        val normalizedActor = normalizeUsername(actorUsername)
        val actor = ensureAdminInvariants(registry).users.firstOrNull {
            normalizeUsername(it.username) == normalizedActor
        }
        if (actor == null || !actor.isAdmin) {
            return Result.failure(IOException("هذه العملية متاحة للمشرف الرئيسي فقط"))
        }
        return Result.success(actor)
    }

    /** إضافة مستخدم جديد من قبل المشرف بصلاحيات مخصصة. */
    fun addUser(
        registry: CloudAuthRegistry,
        actorUsername: String,
        newUsername: String,
        password: String,
        permissions: UserPermissions = UserPermissions.FULL,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val cleanName = normalizeUsername(newUsername)
        val cleanPass = password.trim()
        if (cleanName.length < 2) {
            return Result.failure(IOException("اسم المستخدم يجب أن يتكون من حرفين على الأقل"))
        }
        if (cleanName.length > MAX_USERNAME_LENGTH) {
            return Result.failure(IOException("اسم المستخدم يجب ألا يتجاوز $MAX_USERNAME_LENGTH حرفًا"))
        }
        if (cleanName.any { it.isWhitespace() || it == '/' || it == '\\' }) {
            return Result.failure(IOException("اسم المستخدم لا يجب أن يحتوي على مسافات أو شرطات مائلة"))
        }
        if (cleanPass.length < MIN_USER_PASSWORD_LENGTH) {
            return Result.failure(IOException("كلمة المرور يجب أن تتكون من 10 خانات على الأقل"))
        }
        if (cleanPass.length > MAX_PASSWORD_LENGTH) {
            return Result.failure(IOException("كلمة المرور يجب ألا تتجاوز $MAX_PASSWORD_LENGTH حرفًا"))
        }
        val base = ensureAdminInvariants(registry, now)
        if (base.users.any { normalizeUsername(it.username) == cleanName }) {
            return Result.failure(IOException("اسم المستخدم '$cleanName' موجود مسبقاً"))
        }

        val newAccount = CloudUserAccount(
            username = cleanName,
            displayName = newUsername.trim(),
            passwordHash = hashPassword(cleanName, cleanPass),
            isAdmin = false,
            isActive = true,
            permissions = permissions,
            boundDevice = null,
            createdAt = now,
            updatedAt = now
        )
        return Result.success(
            base.copy(
                updatedAt = now,
                users = base.users + newAccount
            )
        )
    }

    /** حذف مستخدم من قبل المشرف (يُمنع حذف حساب المشرف الرئيسي saged). */
    fun deleteUser(
        registry: CloudAuthRegistry,
        actorUsername: String,
        targetUsername: String,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val target = normalizeUsername(targetUsername)
        if (target == ADMIN_USERNAME) {
            return Result.failure(IOException("لا يمكن حذف حساب المشرف الرئيسي saged"))
        }
        val base = ensureAdminInvariants(registry, now)
        if (base.users.none { normalizeUsername(it.username) == target }) {
            return Result.failure(IOException("المستخدم المطلوب غير موجود"))
        }
        return Result.success(
            base.copy(
                updatedAt = now,
                users = base.users.filterNot { normalizeUsername(it.username) == target },
                deviceRequests = base.deviceRequests.filterNot { normalizeUsername(it.username) == target }
            )
        )
    }

    /** إيقاف أو إعادة تفعيل مستخدم من قبل المشرف (يُمنع إيقاف المشرف الرئيسي saged). */
    fun setUserActive(
        registry: CloudAuthRegistry,
        actorUsername: String,
        targetUsername: String,
        active: Boolean,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val target = normalizeUsername(targetUsername)
        if (target == ADMIN_USERNAME && !active) {
            return Result.failure(IOException("لا يمكن إيقاف حساب المشرف الرئيسي saged"))
        }
        val base = ensureAdminInvariants(registry, now)
        if (base.users.none { normalizeUsername(it.username) == target }) {
            return Result.failure(IOException("المستخدم المطلوب غير موجود"))
        }
        return Result.success(
            base.copy(
                updatedAt = now,
                users = base.users.map {
                    if (normalizeUsername(it.username) == target) {
                        it.copy(isActive = active, updatedAt = now)
                    } else it
                },
                deviceRequests = if (!active) {
                    base.deviceRequests.map {
                        if (normalizeUsername(it.username) == target && it.status == DeviceApprovalStatus.PENDING) {
                            it.copy(status = DeviceApprovalStatus.REJECTED, decidedAt = now)
                        } else it
                    }
                } else {
                    base.deviceRequests
                }
            )
        )
    }

    /** تحديث صلاحيات مستخدم (السحب/الرفع/التعديل) من قبل المشرف. */
    fun updateUserPermissions(
        registry: CloudAuthRegistry,
        actorUsername: String,
        targetUsername: String,
        permissions: UserPermissions,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val target = normalizeUsername(targetUsername)
        if (target == ADMIN_USERNAME && permissions != UserPermissions.FULL) {
            return Result.failure(IOException("حساب المشرف الرئيسي saged يحتفظ دائماً بكامل الصلاحيات"))
        }
        val base = ensureAdminInvariants(registry, now)
        if (base.users.none { normalizeUsername(it.username) == target }) {
            return Result.failure(IOException("المستخدم المطلوب غير موجود"))
        }
        return Result.success(
            base.copy(
                updatedAt = now,
                users = base.users.map {
                    if (normalizeUsername(it.username) == target) {
                        it.copy(permissions = permissions, updatedAt = now)
                    } else it
                }
            )
        )
    }

    /** تغيير كلمة مرور أي مستخدم من قبل المشرف. */
    fun adminChangeUserPassword(
        registry: CloudAuthRegistry,
        actorUsername: String,
        targetUsername: String,
        newPassword: String,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val target = normalizeUsername(targetUsername)
        val cleanPass = newPassword.trim()
        if (cleanPass.length < MIN_USER_PASSWORD_LENGTH) {
            return Result.failure(IOException("كلمة المرور الجديدة يجب أن تتكون من 10 خانات على الأقل"))
        }
        if (cleanPass.length > MAX_PASSWORD_LENGTH) {
            return Result.failure(IOException("كلمة المرور الجديدة يجب ألا تتجاوز $MAX_PASSWORD_LENGTH حرفًا"))
        }
        val base = ensureAdminInvariants(registry, now)
        if (base.users.none { normalizeUsername(it.username) == target }) {
            return Result.failure(IOException("المستخدم المطلوب غير موجود"))
        }
        return Result.success(
            base.copy(
                updatedAt = now,
                users = base.users.map {
                    if (normalizeUsername(it.username) == target) {
                        it.copy(
                            passwordHash = hashPassword(target, cleanPass),
                            updatedAt = now
                        )
                    } else it
                }
            )
        )
    }

    /** تغيير المستخدم لكلمة مروره الخاصة؛ تُخزَّن بصمة مملّحة ولا يمكن للمشرف استرجاعها. */
    fun userChangeOwnPassword(
        registry: CloudAuthRegistry,
        username: String,
        currentPassword: String,
        newPassword: String,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        val target = normalizeUsername(username)
        val cleanNew = newPassword.trim()
        if (cleanNew.length < MIN_USER_PASSWORD_LENGTH) {
            return Result.failure(IOException("كلمة المرور الجديدة يجب أن تتكون من 10 خانات على الأقل"))
        }
        if (cleanNew.length > MAX_PASSWORD_LENGTH) {
            return Result.failure(IOException("كلمة المرور الجديدة يجب ألا تتجاوز $MAX_PASSWORD_LENGTH حرفًا"))
        }
        val base = ensureAdminInvariants(registry, now)
        val account = base.users.firstOrNull { normalizeUsername(it.username) == target }
            ?: return Result.failure(IOException("الحساب غير موجود"))

        if (!verifyPassword(currentPassword, account)) {
            return Result.failure(IOException("كلمة المرور الحالية غير صحيحة"))
        }

        return Result.success(
            base.copy(
                updatedAt = now,
                users = base.users.map {
                    if (normalizeUsername(it.username) == target) {
                        it.copy(
                            passwordHash = hashPassword(target, cleanNew),
                            updatedAt = now
                        )
                    } else it
                }
            )
        )
    }

    /** موافقة المشرف على طلب تسجيل دخول مستخدم من جهاز جديد. */
    fun approveDeviceRequest(
        registry: CloudAuthRegistry,
        actorUsername: String,
        requestId: String,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val base = ensureAdminInvariants(registry, now)
        val request = base.deviceRequests.firstOrNull { it.requestId == requestId }
            ?: return Result.failure(IOException("طلب الجهاز غير موجود"))

        val targetUser = normalizeUsername(request.username)
        val targetAccount = base.users.firstOrNull { normalizeUsername(it.username) == targetUser }
            ?: return Result.failure(IOException("حساب المستخدم صاحب الطلب غير موجود"))
        if (!targetAccount.isActive && !targetAccount.isAdmin) {
            return Result.failure(IOException("لا يمكن اعتماد جهاز لحساب موقوف؛ فعّل الحساب أولاً"))
        }
        val approvedDevice = request.requestedDevice.copy(boundAt = now)

        val updatedUsers = base.users.map {
            if (normalizeUsername(it.username) == targetUser) {
                it.copy(boundDevice = approvedDevice, updatedAt = now)
            } else it
        }

        val updatedRequests = base.deviceRequests.map {
            when {
                it.requestId == requestId -> it.copy(status = DeviceApprovalStatus.APPROVED, decidedAt = now)
                normalizeUsername(it.username) == targetUser && it.status == DeviceApprovalStatus.PENDING ->
                    it.copy(status = DeviceApprovalStatus.REJECTED, decidedAt = now)
                else -> it
            }
        }

        return Result.success(
            base.copy(
                updatedAt = now,
                users = updatedUsers,
                deviceRequests = updatedRequests
            )
        )
    }

    /** رفض المشرف لطلب تسجيل دخول مستخدم من جهاز غريب. */
    fun rejectDeviceRequest(
        registry: CloudAuthRegistry,
        actorUsername: String,
        requestId: String,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val base = ensureAdminInvariants(registry, now)
        if (base.deviceRequests.none { it.requestId == requestId }) {
            return Result.failure(IOException("طلب الجهاز غير موجود"))
        }
        val updatedRequests = base.deviceRequests.map {
            if (it.requestId == requestId) {
                it.copy(status = DeviceApprovalStatus.REJECTED, decidedAt = now)
            } else it
        }
        return Result.success(
            base.copy(
                updatedAt = now,
                deviceRequests = updatedRequests
            )
        )
    }

    /** إلغاء ربط الجهاز الحالي لمستخدم من قبل المشرف ليتمكن من التسجيل بجهاز جديد مباشرة. */
    fun resetUserBoundDevice(
        registry: CloudAuthRegistry,
        actorUsername: String,
        targetUsername: String,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        requireAdminActor(registry, actorUsername).getOrElse { return Result.failure(it) }
        val target = normalizeUsername(targetUsername)
        val base = ensureAdminInvariants(registry, now)
        if (base.users.none { normalizeUsername(it.username) == target }) {
            return Result.failure(IOException("المستخدم المطلوب غير موجود"))
        }
        return Result.success(
            base.copy(
                updatedAt = now,
                users = base.users.map {
                    if (normalizeUsername(it.username) == target) {
                        it.copy(boundDevice = null, updatedAt = now)
                    } else it
                },
                deviceRequests = base.deviceRequests.filterNot {
                    normalizeUsername(it.username) == target && it.status == DeviceApprovalStatus.PENDING
                }
            )
        )
    }

    // ─── التحويل من وإلى JSON (متوافق مع R8 و JVM) ─────────────────────────

    fun toJson(registry: CloudAuthRegistry): String {
        val clean = ensureAdminInvariants(registry)
        val root = JSONObject()
        root.put("app", "unihub-auth")
        root.put("schemaVersion", clean.schemaVersion)
        root.put("updatedAt", clean.updatedAt)

        val usersArray = JSONArray()
        for (user in clean.users) {
            val u = JSONObject()
            u.put("username", user.username)
            u.put("displayName", user.displayName)
            u.put("passwordHash", user.passwordHash)
            u.put("isAdmin", user.isAdmin)
            u.put("isActive", user.isActive)
            u.put("permissions", JSONObject().apply {
                put("canDownload", user.effectivePermissions.canDownload)
                put("canUpload", user.effectivePermissions.canUpload)
                put("canModify", user.effectivePermissions.canModify)
            })
            user.boundDevice?.let { u.put("boundDevice", deviceToJson(it)) }
            u.put("createdAt", user.createdAt)
            u.put("updatedAt", user.updatedAt)
            usersArray.put(u)
        }
        root.put("users", usersArray)

        val requestsArray = JSONArray()
        for (req in clean.deviceRequests) {
            val r = JSONObject()
            r.put("requestId", req.requestId)
            r.put("username", req.username)
            req.currentBoundDevice?.let { r.put("currentBoundDevice", deviceToJson(it)) }
            r.put("requestedDevice", deviceToJson(req.requestedDevice))
            r.put("requestedAt", req.requestedAt)
            r.put("status", req.status.name)
            r.put("decidedAt", req.decidedAt)
            requestsArray.put(r)
        }
        root.put("deviceRequests", requestsArray)
        return root.toString()
    }

    fun fromJson(jsonText: String?, now: Long = System.currentTimeMillis()): CloudAuthRegistry {
        if (jsonText.isNullOrBlank()) return emptyRegistry(now)
        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            return emptyRegistry(now)
        }
        val schemaVersion = root.optInt("schemaVersion", SCHEMA_VERSION)
        val updatedAt = root.optLong("updatedAt", now)

        val usersArray = root.optJSONArray("users") ?: JSONArray()
        val users = mutableListOf<CloudUserAccount>()
        for (i in 0 until usersArray.length()) {
            val u = usersArray.optJSONObject(i) ?: continue
            val username = normalizeUsername(u.optString("username"))
            if (username.isBlank() || username.length > MAX_USERNAME_LENGTH) continue
            val permsObj = u.optJSONObject("permissions")
            val isAdmin = u.optBoolean("isAdmin", username == ADMIN_USERNAME)
            val perms = if (isAdmin) {
                UserPermissions.FULL
            } else {
                UserPermissions(
                    canDownload = permsObj?.optBoolean("canDownload", true) ?: true,
                    canUpload = permsObj?.optBoolean("canUpload", true) ?: true,
                    canModify = permsObj?.optBoolean("canModify", true) ?: true
                )
            }
            val boundObj = u.optJSONObject("boundDevice")
            users += CloudUserAccount(
                username = username,
                displayName = u.optString("displayName", username).ifBlank { username },
                passwordHash = u.optString("passwordHash"),
                isAdmin = isAdmin,
                isActive = if (isAdmin) true else u.optBoolean("isActive", true),
                permissions = perms,
                boundDevice = boundObj?.let(::deviceFromJson),
                createdAt = u.optLong("createdAt", now),
                updatedAt = u.optLong("updatedAt", now)
            )
        }

        val requestsArray = root.optJSONArray("deviceRequests") ?: JSONArray()
        val requests = mutableListOf<DeviceChangeRequest>()
        for (i in 0 until requestsArray.length()) {
            val r = requestsArray.optJSONObject(i) ?: continue
            val reqId = r.optString("requestId")
            val username = normalizeUsername(r.optString("username"))
            val reqDeviceObj = r.optJSONObject("requestedDevice") ?: continue
            val reqDevice = deviceFromJson(reqDeviceObj) ?: continue
            if (reqId.isBlank() || username.isBlank()) continue
            val status = runCatching {
                DeviceApprovalStatus.valueOf(r.optString("status", DeviceApprovalStatus.PENDING.name))
            }.getOrDefault(DeviceApprovalStatus.PENDING)
            requests += DeviceChangeRequest(
                requestId = reqId,
                username = username,
                currentBoundDevice = r.optJSONObject("currentBoundDevice")?.let(::deviceFromJson),
                requestedDevice = reqDevice,
                requestedAt = r.optLong("requestedAt", now),
                status = status,
                decidedAt = r.optLong("decidedAt", 0L)
            )
        }

        return ensureAdminInvariants(
            CloudAuthRegistry(
                schemaVersion = schemaVersion,
                updatedAt = updatedAt,
                users = users,
                deviceRequests = requests
            ),
            now
        )
    }

    private fun deviceToJson(device: BoundDeviceInfo): JSONObject = JSONObject().apply {
        put("fingerprint", device.fingerprint)
        put("deviceName", device.deviceName)
        put("manufacturer", device.manufacturer)
        put("model", device.model)
        put("androidVersion", device.androidVersion)
        put("boundAt", device.boundAt)
    }

    private fun deviceFromJson(obj: JSONObject): BoundDeviceInfo? {
        val fp = obj.optString("fingerprint").trim()
        if (fp.isBlank()) return null
        return BoundDeviceInfo(
            fingerprint = fp,
            deviceName = obj.optString("deviceName"),
            manufacturer = obj.optString("manufacturer"),
            model = obj.optString("model"),
            androidVersion = obj.optString("androidVersion"),
            boundAt = obj.optLong("boundAt", 0L)
        )
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}
