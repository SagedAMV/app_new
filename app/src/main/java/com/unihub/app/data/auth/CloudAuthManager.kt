package com.unihub.app.data.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.unihub.app.core.prefs.CloudAuthPreferences
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.cloud.CloudConflictException
import com.unihub.app.data.cloud.CloudflareR2Client
import com.unihub.app.data.cloud.CloudflareR2Config
import com.unihub.app.data.cloud.R2Credentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * المدير المركزي للمصادقة السحابية وبصمة الجهاز وإدارة المستخدمين والصلاحيات.
 * يتزامن مباشرة مع خادم Cloudflare R2 على المفتاح المحمي [CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY].
 */
@Singleton
class CloudAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val r2Client: CloudflareR2Client,
    private val syncPreferences: CloudSyncPreferences,
    private val authPreferences: CloudAuthPreferences,
    private val deviceFingerprintProvider: DeviceFingerprintProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val initialized = AtomicBoolean(false)

    private val _sessionState = MutableStateFlow<AuthSessionState>(AuthSessionState.Initializing)
    val sessionState: StateFlow<AuthSessionState> = _sessionState.asStateFlow()

    private val _registryState = MutableStateFlow<CloudAuthRegistry>(CloudAuthRules.createInitialRegistry())
    val registryState: StateFlow<CloudAuthRegistry> = _registryState.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        scope.launch { initializeIfNeeded() }
    }

    /**
     * تهيئة حالة الجلسة عند إقلاع التطبيق:
     * - إن وُجدت جلسة موثقة سابقة على نفس هذا الجهاز (بصمة مطابقة)، يفتح التطبيق تلقائياً
     *   ثم يتحقق من السحابة في الخلفية عند توفر الإنترنت لتحديث الصلاحيات وطلبات الأجهزة.
     * - إن كان الجهاز غريباً أو لم يسجل الدخول من قبل، يوجّه المستخدم لشاشة تسجيل الدخول السحابية.
     */
    suspend fun initializeIfNeeded() = withContext(Dispatchers.IO) {
        if (!initialized.compareAndSet(false, true)) return@withContext
        val device = deviceFingerprintProvider.getDeviceInfo()
        val snap = authPreferences.snapshot()
        val cachedRegistry = CloudAuthRules.fromJson(snap.cachedRegistryJson)
        _registryState.value = cachedRegistry

        if (snap.hasAuthenticatedSession) {
            if (snap.boundDeviceFingerprint != device.fingerprint) {
                authPreferences.clearSession()
                _sessionState.value = AuthSessionState.Unauthenticated(
                    "تم اكتشاف جهاز غير معرف لهذا الحساب؛ يرجى تسجيل الدخول عبر السحابة"
                )
                return@withContext
            }
            val validation = CloudAuthRules.validateExistingSession(
                registry = cachedRegistry,
                username = snap.loggedInUsername,
                currentDevice = device
            )
            validation.fold(
                onSuccess = { account ->
                    _sessionState.value = AuthSessionState.Authenticated(
                        user = account,
                        currentDevice = device,
                        pendingAdminRequests = if (account.isAdmin) cachedRegistry.pendingDeviceRequests else emptyList()
                    )
                    if (isOnline()) {
                        scope.launch { verifyActiveSessionWithCloud() }
                    }
                },
                onFailure = { error ->
                    authPreferences.clearSession()
                    _sessionState.value = AuthSessionState.Unauthenticated(error.message)
                }
            )
        } else if (snap.pendingUsername.isNotBlank()) {
            _sessionState.value = AuthSessionState.WaitingAdminApproval(
                username = snap.pendingUsername,
                requestedDevice = device,
                message = CloudAuthRules.PENDING_APPROVAL_MESSAGE
            )
            if (isOnline()) {
                scope.launch { checkPendingApprovalStatus() }
            }
        } else {
            _sessionState.value = AuthSessionState.Unauthenticated()
        }
    }

    /** الحصول على بيانات الجهاز الحالي وبصمته. */
    suspend fun getCurrentDeviceInfo(): BoundDeviceInfo =
        deviceFingerprintProvider.getDeviceInfo()

    /** هل توجد جلسة مستخدم مصادق عليها حالياً على هذا الجهاز؟ */
    fun isAuthenticatedNow(): Boolean =
        _sessionState.value is AuthSessionState.Authenticated

    /** المستخدم المصادق عليه حالياً (أو null إن لم يسجل الدخول). */
    fun currentAuthenticatedUser(): CloudUserAccount? =
        (_sessionState.value as? AuthSessionState.Authenticated)?.user

    /** التحقق من أن المستخدم الحالي هو المشرف الرئيسي (saged). */
    fun requireAdmin(): Result<CloudUserAccount> {
        val user = currentAuthenticatedUser()
            ?: return Result.failure(IOException("يجب تسجيل الدخول أولاً"))
        if (!user.isAdmin) {
            return Result.failure(IOException("هذا الإجراء متاح للمشرف فقط"))
        }
        return Result.success(user)
    }

    /** فك تشفير كلمة المرور الحالية للمستخدم لعرضها للمشرف (حتى لو غيّرها المستخدم). */
    fun revealUserPasswordForAdmin(account: CloudUserAccount): String =
        CloudAuthRules.decryptPasswordForAdmin(account.encryptedPassword)
            ?.takeIf { it.isNotBlank() }
            ?: "غير متاح"

    /**
     * التحقق من امتلاك المستخدم الحالي لصلاحية معينة قبل تنفيذ عملية سحابية.
     */
    suspend fun requirePermission(permission: AuthPermission): Result<CloudUserAccount> {
        initializeIfNeeded()
        val currentState = _sessionState.value
        if (currentState !is AuthSessionState.Authenticated) {
            return Result.failure(IOException("يجب تسجيل الدخول أولاً للوصول إلى السحابة"))
        }
        val device = deviceFingerprintProvider.getDeviceInfo()
        if (!currentState.user.isAdmin &&
            currentState.user.boundDevice?.fingerprint != device.fingerprint
        ) {
            authPreferences.clearSession()
            _sessionState.value = AuthSessionState.Unauthenticated("جهاز غير معتمد لهذا الحساب")
            return Result.failure(IOException("هذا الجهاز غير معتمد لحسابك؛ يرجى تسجيل الدخول"))
        }
        return CloudAuthRules.checkPermission(currentState.user, permission)
            .map { currentState.user }
    }

    /**
     * تسجيل الدخول عبر الإنترنت (السحابة هي التي تقرر في التسجيل وربط بصمة الجهاز).
     */
    suspend fun login(usernameInput: String, passwordInput: String): Result<AuthLoginOutcome> =
        withBusyLock {
            val online = isOnline()
            if (!online) {
                val msg = "يجب توفر اتصال بالإنترنت لتسجيل الدخول؛ السحابة هي التي تقرر في التسجيل"
                return@withBusyLock Result.failure(IOException(msg))
            }
            val creds = syncPreferences.snapshot().credentials
            if (!creds.isConfigured) {
                return@withBusyLock Result.failure(IOException("إعدادات خادم السحابة غير مضبوطة"))
            }
            val device = deviceFingerprintProvider.getDeviceInfo()

            var attempt = 0
            while (true) {
                attempt++
                val remoteObj = r2Client.downloadTextObject(
                    creds,
                    CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY
                ).getOrElse { error ->
                    return@withBusyLock Result.failure(
                        IOException("تعذّر الاتصال بالسحابة للتحقق من الحساب: ${error.message}")
                    )
                }

                val existingRegistry = remoteObj?.text?.let { CloudAuthRules.fromJson(it) }
                val outcome = CloudAuthRules.evaluateLogin(
                    registry = existingRegistry,
                    usernameInput = usernameInput,
                    passwordInput = passwordInput,
                    currentDevice = device,
                    isOnline = true
                )

                when (outcome) {
                    is AuthLoginOutcome.Rejected -> {
                        return@withBusyLock Result.failure(IOException(outcome.reason))
                    }

                    is AuthLoginOutcome.Authenticated -> {
                        val json = CloudAuthRules.toJson(outcome.registry)
                        if (outcome.registryChanged || remoteObj == null) {
                            val uploadRes = r2Client.uploadText(
                                credentials = creds,
                                objectKey = CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                                text = json,
                                ifMatch = remoteObj?.etag?.takeIf { it.isNotBlank() },
                                ifNoneMatch = remoteObj == null
                            )
                            if (uploadRes.isFailure) {
                                val err = uploadRes.exceptionOrNull()
                                if (err is CloudConflictException && attempt < 3) continue
                                return@withBusyLock Result.failure(
                                    IOException("تعذّر تثبيت بصمة الجهاز في السحابة: ${err?.message}")
                                )
                            }
                        }
                        _registryState.value = outcome.registry
                        authPreferences.saveAuthenticatedSession(
                            username = outcome.user.username,
                            deviceFingerprint = device.fingerprint,
                            registryJson = json
                        )
                        _sessionState.value = AuthSessionState.Authenticated(
                            user = outcome.user,
                            currentDevice = device,
                            pendingAdminRequests = if (outcome.user.isAdmin) {
                                outcome.registry.pendingDeviceRequests
                            } else emptyList()
                        )
                        return@withBusyLock Result.success(outcome)
                    }

                    is AuthLoginOutcome.PendingAdminApproval -> {
                        val json = CloudAuthRules.toJson(outcome.registry)
                        val uploadRes = r2Client.uploadText(
                            credentials = creds,
                            objectKey = CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                            text = json,
                            ifMatch = remoteObj?.etag?.takeIf { it.isNotBlank() },
                            ifNoneMatch = remoteObj == null
                        )
                        if (uploadRes.isFailure) {
                            val err = uploadRes.exceptionOrNull()
                            if (err is CloudConflictException && attempt < 3) continue
                            return@withBusyLock Result.failure(
                                IOException("تعذّر إرسال طلب موافقة الجهاز للمشرف: ${err?.message}")
                            )
                        }
                        _registryState.value = outcome.registry
                        authPreferences.savePendingApprovalState(
                            username = outcome.username,
                            registryJson = json
                        )
                        _sessionState.value = AuthSessionState.WaitingAdminApproval(
                            username = outcome.username,
                            requestedDevice = device,
                            message = outcome.message
                        )
                        return@withBusyLock Result.success(outcome)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IOException("تعذّر إتمام تسجيل الدخول"))
        }

    /**
     * فحص السحابة لمعرفة ما إذا وافق المشرف (saged) على الجهاز الجديد أم رفضه.
     */
    suspend fun checkPendingApprovalStatus(): Result<PendingApprovalCheckOutcome> =
        withBusyLock {
            val waiting = _sessionState.value as? AuthSessionState.WaitingAdminApproval
                ?: return@withBusyLock Result.failure(IOException("لا يوجد طلب جهاز معلق حالياً"))

            if (!isOnline()) {
                return@withBusyLock Result.failure(IOException("لا يتوفر اتصال بالإنترنت للتحقق من رد المشرف"))
            }
            val creds = syncPreferences.snapshot().credentials
            val remoteObj = r2Client.downloadTextObject(
                creds,
                CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY
            ).getOrElse { error ->
                return@withBusyLock Result.failure(IOException("تعذّر الاتصال بالسحابة: ${error.message}"))
            }

            val registry = CloudAuthRules.fromJson(remoteObj?.text)
            val json = CloudAuthRules.toJson(registry)
            _registryState.value = registry
            authPreferences.updateCachedRegistry(json)

            val device = deviceFingerprintProvider.getDeviceInfo()
            val check = CloudAuthRules.checkPendingApproval(
                registry = registry,
                username = waiting.username,
                currentDevice = device
            )
            when (check) {
                is PendingApprovalCheckOutcome.Approved -> {
                    authPreferences.saveAuthenticatedSession(
                        username = check.user.username,
                        deviceFingerprint = device.fingerprint,
                        registryJson = json
                    )
                    _sessionState.value = AuthSessionState.Authenticated(
                        user = check.user,
                        currentDevice = device,
                        pendingAdminRequests = if (check.user.isAdmin) registry.pendingDeviceRequests else emptyList()
                    )
                }

                is PendingApprovalCheckOutcome.StillPending -> {
                    _sessionState.value = waiting.copy(
                        message = check.message,
                        statusNote = "لا يزال الطلب قيد المراجعة — سيرد لك مشرف"
                    )
                }

                is PendingApprovalCheckOutcome.Rejected -> {
                    authPreferences.clearSession()
                    _sessionState.value = AuthSessionState.Unauthenticated(check.reason)
                }
            }
            Result.success(check)
        }

    /**
     * التحقق الخلفي المستمر من السحابة لتحديث صلاحيات المستخدم الحالي أو طلبات الأجهزة للمشرف،
     * أو إبطال الجلسة فوراً إذا أوقف المشرف الحساب أو غيّر الجهاز المعتمد.
     */
    suspend fun verifyActiveSessionWithCloud(): Result<CloudAuthRegistry> = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext Result.failure(IOException("غير متصل بالإنترنت"))
        mutex.withLock {
            runCatching {
                val creds = syncPreferences.snapshot().credentials
                if (!creds.isConfigured) throw IOException("بيانات السحابة غير مضبوطة")
                val remoteObj = r2Client.downloadTextObject(
                    creds,
                    CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY
                ).getOrThrow()

                val registry = if (remoteObj == null) {
                    val seeded = CloudAuthRules.createInitialRegistry()
                    val seededJson = CloudAuthRules.toJson(seeded)
                    r2Client.uploadText(
                        credentials = creds,
                        objectKey = CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                        text = seededJson,
                        ifNoneMatch = true
                    )
                    seeded
                } else {
                    CloudAuthRules.fromJson(remoteObj.text)
                }

                val json = CloudAuthRules.toJson(registry)
                _registryState.value = registry
                authPreferences.updateCachedRegistry(json)

                val device = deviceFingerprintProvider.getDeviceInfo()
                when (val current = _sessionState.value) {
                    is AuthSessionState.Authenticated -> {
                        CloudAuthRules.validateExistingSession(
                            registry = registry,
                            username = current.user.username,
                            currentDevice = device
                        ).fold(
                            onSuccess = { refreshedUser ->
                                _sessionState.value = AuthSessionState.Authenticated(
                                    user = refreshedUser,
                                    currentDevice = device,
                                    pendingAdminRequests = if (refreshedUser.isAdmin) {
                                        registry.pendingDeviceRequests
                                    } else emptyList()
                                )
                            },
                            onFailure = { error ->
                                authPreferences.clearSession()
                                _sessionState.value = AuthSessionState.Unauthenticated(error.message)
                            }
                        )
                    }

                    is AuthSessionState.WaitingAdminApproval -> {
                        val check = CloudAuthRules.checkPendingApproval(
                            registry = registry,
                            username = current.username,
                            currentDevice = device
                        )
                        if (check is PendingApprovalCheckOutcome.Approved) {
                            authPreferences.saveAuthenticatedSession(
                                username = check.user.username,
                                deviceFingerprint = device.fingerprint,
                                registryJson = json
                            )
                            _sessionState.value = AuthSessionState.Authenticated(
                                user = check.user,
                                currentDevice = device,
                                pendingAdminRequests = if (check.user.isAdmin) {
                                    registry.pendingDeviceRequests
                                } else emptyList()
                            )
                        } else if (check is PendingApprovalCheckOutcome.Rejected) {
                            authPreferences.clearSession()
                            _sessionState.value = AuthSessionState.Unauthenticated(check.reason)
                        }
                    }

                    else -> Unit
                }
                registry
            }
        }
    }

    /** تسجيل الخروج أو العودة لشاشة تسجيل الدخول لتبديل الحساب. */
    suspend fun logout(reason: String? = null) {
        authPreferences.clearSession()
        _sessionState.value = AuthSessionState.Unauthenticated(reason)
    }

    // ─── عمليات إدارة المستخدمين والصلاحيات وكلمات المرور والأجهزة ───────────

    suspend fun addUser(
        newUsername: String,
        password: String,
        permissions: UserPermissions
    ): Result<Unit> = mutateCloudRegistry { registry, actor ->
        CloudAuthRules.addUser(registry, actor.username, newUsername, password, permissions)
    }

    suspend fun deleteUser(targetUsername: String): Result<Unit> =
        mutateCloudRegistry { registry, actor ->
            CloudAuthRules.deleteUser(registry, actor.username, targetUsername)
        }

    suspend fun setUserActive(targetUsername: String, active: Boolean): Result<Unit> =
        mutateCloudRegistry { registry, actor ->
            CloudAuthRules.setUserActive(registry, actor.username, targetUsername, active)
        }

    suspend fun updateUserPermissions(
        targetUsername: String,
        permissions: UserPermissions
    ): Result<Unit> = mutateCloudRegistry { registry, actor ->
        CloudAuthRules.updateUserPermissions(registry, actor.username, targetUsername, permissions)
    }

    suspend fun adminChangeUserPassword(
        targetUsername: String,
        newPassword: String
    ): Result<Unit> = mutateCloudRegistry { registry, actor ->
        CloudAuthRules.adminChangeUserPassword(registry, actor.username, targetUsername, newPassword)
    }

    suspend fun userChangeOwnPassword(
        currentPassword: String,
        newPassword: String
    ): Result<Unit> = mutateCloudRegistry { registry, actor ->
        CloudAuthRules.userChangeOwnPassword(registry, actor.username, currentPassword, newPassword)
    }

    suspend fun approveDeviceRequest(requestId: String): Result<Unit> =
        mutateCloudRegistry { registry, actor ->
            CloudAuthRules.approveDeviceRequest(registry, actor.username, requestId)
        }

    suspend fun rejectDeviceRequest(requestId: String): Result<Unit> =
        mutateCloudRegistry { registry, actor ->
            CloudAuthRules.rejectDeviceRequest(registry, actor.username, requestId)
        }

    suspend fun resetUserBoundDevice(targetUsername: String): Result<Unit> =
        mutateCloudRegistry { registry, actor ->
            CloudAuthRules.resetUserBoundDevice(registry, actor.username, targetUsername)
        }

    private suspend fun mutateCloudRegistry(
        mutation: (CloudAuthRegistry, CloudUserAccount) -> Result<CloudAuthRegistry>
    ): Result<Unit> = withBusyLock {
        val currentAuth = _sessionState.value as? AuthSessionState.Authenticated
            ?: return@withBusyLock Result.failure(IOException("يجب تسجيل الدخول أولاً"))

        if (!isOnline()) {
            return@withBusyLock Result.failure(
                IOException("يجب توفر اتصال بالإنترنت لحفظ التغييرات في السحابة")
            )
        }
        val creds: R2Credentials = syncPreferences.snapshot().credentials
        if (!creds.isConfigured) {
            return@withBusyLock Result.failure(IOException("إعدادات خادم السحابة غير مضبوطة"))
        }

        var attempt = 0
        while (true) {
            attempt++
            val remoteObj = r2Client.downloadTextObject(
                creds,
                CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY
            ).getOrElse { error ->
                return@withBusyLock Result.failure(
                    IOException("تعذّر قراءة سجل المستخدمين من السحابة: ${error.message}")
                )
            }

            val baseRegistry = remoteObj?.text?.let { CloudAuthRules.fromJson(it) }
                ?: CloudAuthRules.createInitialRegistry()

            val updatedRegistry = mutation(baseRegistry, currentAuth.user).getOrElse { error ->
                return@withBusyLock Result.failure(error)
            }

            val json = CloudAuthRules.toJson(updatedRegistry)
            val uploaded = r2Client.uploadText(
                credentials = creds,
                objectKey = CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                text = json,
                ifMatch = remoteObj?.etag?.takeIf { it.isNotBlank() },
                ifNoneMatch = remoteObj == null
            )
            if (uploaded.isFailure) {
                val err = uploaded.exceptionOrNull()
                if (err is CloudConflictException && attempt < 3) continue
                return@withBusyLock Result.failure(
                    IOException("تعذّر حفظ التعديلات في السحابة: ${err?.message}")
                )
            }

            _registryState.value = updatedRegistry
            authPreferences.updateCachedRegistry(json)

            val device = deviceFingerprintProvider.getDeviceInfo()
            val refreshedCurrentUser = updatedRegistry.users.firstOrNull {
                CloudAuthRules.normalizeUsername(it.username) ==
                    CloudAuthRules.normalizeUsername(currentAuth.user.username)
            } ?: currentAuth.user

            _sessionState.value = AuthSessionState.Authenticated(
                user = refreshedCurrentUser,
                currentDevice = device,
                pendingAdminRequests = if (refreshedCurrentUser.isAdmin) {
                    updatedRegistry.pendingDeviceRequests
                } else emptyList()
            )
            return@withBusyLock Result.success(Unit)
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IOException("تعذّر تحديث السجل السحابي"))
    }

    private suspend fun <T> withBusyLock(block: suspend () -> Result<T>): Result<T> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _isBusy.value = true
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                } finally {
                    _isBusy.value = false
                }
            }
        }

    fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false)
            ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
