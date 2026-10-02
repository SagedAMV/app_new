package com.unihub.app.data.auth

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * القواعد النقية لنظام المصادقة السحابية وإدارة المستخدمين والأجهزة والصلاحيات.
 * صُممت بلا أي اعتماد على إطار أندرويد لتُختبر بالكامل على JVM وتعمل بنفس السلوك الحتمي.
 */
object CloudAuthRules {

    const val ADMIN_USERNAME: String = "saged"
    const val DEFAULT_ADMIN_PASSWORD: String = "192168"
    const val PENDING_APPROVAL_MESSAGE: String = "سيرد لك مشرف"
    const val MAX_RESOLVED_DEVICE_REQUESTS: Int = 50

    private const val SCHEMA_VERSION: Int = 1
    private const val GCM_IV_BYTES: Int = 12
    private const val GCM_TAG_BITS: Int = 128
    private const val CRYPTO_PEPPER: String = "UniHub::CloudAuth::2026::SagedAdminVaultKey::v1"

    /** تطبيع اسم المستخدم للمقارنة المفتاحية مع إزالة المسافات الزائدة وتوحيد الأحرف اللاتينية. */
    fun normalizeUsername(raw: String): String =
        raw.trim().lowercase(Locale.US)

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

    /** بصمة تحقق أحادية الاتجاه لكلمة المرور مرتبطة باسم المستخدم المطبّع. */
    fun hashPassword(username: String, plainPassword: String): String {
        val normalized = normalizeUsername(username)
        return sha256Hex("$CRYPTO_PEPPER::$normalized::${plainPassword.trim()}")
    }

    /**
     * تشفير موثق (AES-GCM) لكلمة المرور بحيث لا تُحفظ كنص مكشوف في JSON السحابي،
     * مع تمكين المشرف الرئيسي (saged) من فك تشفيرها ورؤيتها حتى لو غيّرها المستخدم.
     */
    fun encryptPassword(plainPassword: String): String {
        val clean = plainPassword.trim()
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveAesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    /** فك تشفير كلمة المرور لعرضها للمشرف الرئيسي في واجهة إدارة المستخدمين. */
    fun decryptPasswordForAdmin(encryptedPassword: String): String = runCatching {
        if (encryptedPassword.isBlank()) return@runCatching ""
        val combined = Base64.getDecoder().decode(encryptedPassword.trim())
        if (combined.size <= GCM_IV_BYTES) return@runCatching ""
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val cipherBytes = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }.getOrDefault("")

    /** التحقق من تطابق كلمة المرور المدخلة مع حساب المستخدم. */
    fun verifyPassword(plainPassword: String, account: CloudUserAccount): Boolean {
        val candidate = plainPassword.trim()
        if (candidate.isEmpty()) return false
        val expectedHash = hashPassword(account.username, candidate)
        if (MessageDigest.isEqual(
                expectedHash.toByteArray(Charsets.UTF_8),
                account.passwordHash.toByteArray(Charsets.UTF_8)
            )
        ) {
            return true
        }
        val decrypted = decryptPasswordForAdmin(account.encryptedPassword)
        return decrypted.isNotEmpty() && MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            decrypted.toByteArray(Charsets.UTF_8)
        )
    }

    /** إنشاء حساب المشرف الرئيسي الافتراضي (saged / 192168) بكامل الصلاحيات. */
    fun createDefaultAdminAccount(now: Long = System.currentTimeMillis()): CloudUserAccount =
        CloudUserAccount(
            username = ADMIN_USERNAME,
            displayName = ADMIN_USERNAME,
            passwordHash = hashPassword(ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD),
            encryptedPassword = encryptPassword(DEFAULT_ADMIN_PASSWORD),
            isAdmin = true,
            isActive = true,
            permissions = UserPermissions.FULL,
            boundDevice = null,
            createdAt = now,
            updatedAt = now
        )

    /** إنشاء سجل مصادقة سحابي ابتدائي يحتوي على حساب المشرف الرئيسي saged. */
    fun createInitialRegistry(now: Long = System.currentTimeMillis()): CloudAuthRegistry =
        CloudAuthRegistry(
            schemaVersion = SCHEMA_VERSION,
            updatedAt = now,
            users = listOf(createDefaultAdminAccount(now)),
            deviceRequests = emptyList()
        )

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
        val enforcedAdmin = if (existingAdmin == null) {
            createDefaultAdminAccount(now)
        } else {
            val validPasswordHash = existingAdmin.passwordHash.ifBlank {
                hashPassword(ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD)
            }
            val validEncrypted = existingAdmin.encryptedPassword.ifBlank {
                encryptPassword(DEFAULT_ADMIN_PASSWORD)
            }
            existingAdmin.copy(
                username = ADMIN_USERNAME,
                displayName = existingAdmin.displayName.ifBlank { ADMIN_USERNAME },
                passwordHash = validPasswordHash,
                encryptedPassword = validEncrypted,
                isAdmin = true,
                isActive = true,
                permissions = UserPermissions.FULL
            )
        }
        val otherUsers = registry.users
            .filterNot { normalizeUsername(it.username) == ADMIN_USERNAME }
            .groupBy { normalizeUsername(it.username) }
            .mapNotNull { (normName, accounts) ->
                if (normName.isBlank()) null
                else accounts.maxByOrNull { it.updatedAt }?.copy(username = normName)
            }
        val allUsers = listOf(enforcedAdmin) + otherUsers
        val validUsernames = allUsers.mapTo(mutableSetOf()) { normalizeUsername(it.username) }
        val validRequests = registry.deviceRequests.filter {
            normalizeUsername(it.username) in validUsernames
        }
        val pendingRequests = validRequests.filter { it.status == DeviceApprovalStatus.PENDING }
        val cappedResolved = validRequests
            .filter { it.status != DeviceApprovalStatus.PENDING }
            .sortedByDescending { maxOf(it.decidedAt, it.requestedAt) }
            .take(MAX_RESOLVED_DEVICE_REQUESTS)
            .sortedBy { it.requestedAt }

        return registry.copy(
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
            return AuthLoginOutcome.Rejected(
                "يجب توفر اتصال بالإنترنت لتسجيل الدخول؛ السحابة هي التي تقرر في التسجيل"
            )
        }
        val normalizedUser = normalizeUsername(usernameInput)
        val cleanPass = passwordInput.trim()
        if (normalizedUser.isBlank() || cleanPass.isBlank()) {
            return AuthLoginOutcome.Rejected("أدخل اسم المستخدم وكلمة المرور")
        }
        if (currentDevice.fingerprint.isBlank()) {
            return AuthLoginOutcome.Rejected("تعذّر التحقق من بصمة الجهاز")
        }

        val baseRegistry = ensureAdminInvariants(registry ?: createInitialRegistry(now), now)
        val wasUninitialized = registry == null || registry.users.none {
            normalizeUsername(it.username) == ADMIN_USERNAME
        }

        val account = baseRegistry.users.firstOrNull {
            normalizeUsername(it.username) == normalizedUser
        } ?: return AuthLoginOutcome.Rejected("اسم المستخدم أو كلمة المرور غير صحيحة")

        // التحقق من كلمة المرور يسبق فحص الجهاز والحالة لمنع ربط جهاز مهاجم أو إرسال طلبات كاذبة
        if (!verifyPassword(cleanPass, account)) {
            return AuthLoginOutcome.Rejected("اسم المستخدم أو كلمة المرور غير صحيحة")
        }

        if (!account.isActive && !account.isAdmin) {
            return AuthLoginOutcome.Rejected("تم إيقاف هذا الحساب من قبل المشرف")
        }

        val stampedDevice = currentDevice.copy(boundAt = if (currentDevice.boundAt > 0L) currentDevice.boundAt else now)

        // المشرف الرئيسي saged هو مالك التطبيق، أو مستخدم يسجل من جهازه الأول
        if (account.isAdmin || account.boundDevice == null) {
            val deviceChanged = account.boundDevice?.fingerprint != stampedDevice.fingerprint
            val updatedAccount = if (deviceChanged) {
                account.copy(boundDevice = stampedDevice, updatedAt = now)
            } else {
                account
            }
            val updatedRegistry = baseRegistry.copy(
                updatedAt = if (deviceChanged || wasUninitialized) now else baseRegistry.updatedAt,
                users = baseRegistry.users.map {
                    if (normalizeUsername(it.username) == normalizedUser) updatedAccount else it
                }
            )
            return AuthLoginOutcome.Authenticated(
                user = updatedAccount,
                registry = updatedRegistry,
                registryChanged = deviceChanged || wasUninitialized
            )
        }

        // الجهاز مطابق للجهاز المعتمد في السحابة
        if (account.boundDevice.fingerprint == stampedDevice.fingerprint) {
            return AuthLoginOutcome.Authenticated(
                user = account,
                registry = baseRegistry,
                registryChanged = wasUninitialized
            )
        }

        // جهاز مختلف ("جهاز غريب") -> لا يُسمح بالدخول إلا بموافقة المشرف
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

        val updatedRegistry = baseRegistry.copy(
            updatedAt = now,
            deviceRequests = updatedRequests
        )

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
        if (cleanName.any { it.isWhitespace() || it == '/' || it == '\\' }) {
            return Result.failure(IOException("اسم المستخدم لا يجب أن يحتوي على مسافات أو شرطات مائلة"))
        }
        if (cleanPass.length < 3) {
            return Result.failure(IOException("كلمة المرور يجب أن تتكون من 3 خانات على الأقل"))
        }
        val base = ensureAdminInvariants(registry, now)
        if (base.users.any { normalizeUsername(it.username) == cleanName }) {
            return Result.failure(IOException("اسم المستخدم '$cleanName' موجود مسبقاً"))
        }

        val newAccount = CloudUserAccount(
            username = cleanName,
            displayName = newUsername.trim(),
            passwordHash = hashPassword(cleanName, cleanPass),
            encryptedPassword = encryptPassword(cleanPass),
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
        if (cleanPass.length < 3) {
            return Result.failure(IOException("كلمة المرور الجديدة يجب أن تتكون من 3 خانات على الأقل"))
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
                            encryptedPassword = encryptPassword(cleanPass),
                            updatedAt = now
                        )
                    } else it
                }
            )
        )
    }

    /** تغيير المستخدم لكلمة مروره الخاصة (وتحفظ مشفرة بحيث تظهر للمشرف أيضاً). */
    fun userChangeOwnPassword(
        registry: CloudAuthRegistry,
        username: String,
        currentPassword: String,
        newPassword: String,
        now: Long = System.currentTimeMillis()
    ): Result<CloudAuthRegistry> {
        val target = normalizeUsername(username)
        val cleanNew = newPassword.trim()
        if (cleanNew.length < 3) {
            return Result.failure(IOException("كلمة المرور الجديدة يجب أن تتكون من 3 خانات على الأقل"))
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
                            encryptedPassword = encryptPassword(cleanNew),
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
            u.put("encryptedPassword", user.encryptedPassword)
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
        if (jsonText.isNullOrBlank()) return createInitialRegistry(now)
        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            return createInitialRegistry(now)
        }
        val schemaVersion = root.optInt("schemaVersion", SCHEMA_VERSION)
        val updatedAt = root.optLong("updatedAt", now)

        val usersArray = root.optJSONArray("users") ?: JSONArray()
        val users = mutableListOf<CloudUserAccount>()
        for (i in 0 until usersArray.length()) {
            val u = usersArray.optJSONObject(i) ?: continue
            val username = normalizeUsername(u.optString("username"))
            if (username.isBlank()) continue
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
                encryptedPassword = u.optString("encryptedPassword"),
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

    private fun deriveAesKey(): SecretKeySpec {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(CRYPTO_PEPPER.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}
