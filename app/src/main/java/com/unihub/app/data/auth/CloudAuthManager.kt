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
import java.util.concurrent.atomic.AtomicLong
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
    private val initMutex = Mutex()
    private val mutex = Mutex()
    private val initialized = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private var lastRemoteEtag: String? = null
    private val maxAuthBytes = 1024L * 1024L

    private val _sessionState = MutableStateFlow<AuthSessionState>(AuthSessionState.Initializing)
    val sessionState: StateFlow<AuthSessionState> = _sessionState.asStateFlow()

    private val _registryState = MutableStateFlow<CloudAuthRegistry>(CloudAuthRules.fromJson(null))
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
        if (initialized.get()) return@withContext
        var verifyAfterInitialization = false
        initMutex.withLock {
            if (initialized.get()) return@withLock
            mutex.withLock initialization@ {
                try {
                    val epoch = generation.get()
                    val device = deviceFingerprintProvider.getDeviceInfo()
                    val snap = authPreferences.snapshot()
                    snap.storageError?.let { throw IOException(it) }
                    var registry = CloudAuthRules.fromJson(snap.cachedRegistryJson)
                    if (snap.hasAuthenticatedSession) {
                        if (snap.boundDeviceFingerprint != device.fingerprint) {
                            authPreferences.clearSession()
                            _sessionState.value = AuthSessionState.Unauthenticated("الجهاز غير مطابق للجلسة المحفوظة؛ سجّل الدخول مجددًا")
                            initialized.set(true)
                            return@initialization
                        }
                        val fresh = CloudAuthRules.isCachedSessionFresh(snap.lastVerifiedAt)
                        if (!fresh) {
                            if (!isOnline()) throw IOException("انتهت صلاحية الجلسة دون اتصال؛ اتصل بالإنترنت ثم أعد المحاولة")
                            val creds = syncPreferences.snapshot().credentials
                            val remote = r2Client.downloadTextObject(creds, CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                                maxResponseBytes = maxAuthBytes).getOrThrow()
                                ?: throw IOException("سجل الحسابات غير موجود؛ يلزم تدخل المالك")
                            registry = CloudAuthRules.fromJson(remote.text)
                        }
                        val account = CloudAuthRules.validateExistingSession(registry, snap.loggedInUsername, device).getOrThrow()
                        ensureGeneration(epoch)
                        if (!fresh) authPreferences.saveAuthenticatedSession(account.username, device.fingerprint, CloudAuthRules.toJson(registry))
                        ensureGeneration(epoch)
                        val visible = publishRegistry(registry, account.username)
                        publishSession(epoch, AuthSessionState.Authenticated(account.copy(passwordHash = ""), device,
                            if (account.isAdmin) visible.pendingDeviceRequests else emptyList()))
                        initialized.set(true)
                        verifyAfterInitialization = fresh && isOnline()
                    } else if (snap.pendingUsername.isNotBlank()) {
                        publishRegistry(registry, snap.pendingUsername)
                        publishSession(epoch, AuthSessionState.WaitingAdminApproval(snap.pendingUsername, device))
                        initialized.set(true)
                        // The lifecycle-aware waiting screen owns polling; do not launch a competing job here.
                    } else {
                        _registryState.value = CloudAuthRegistry()
                        _sessionState.value = AuthSessionState.Unauthenticated()
                        initialized.set(true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _registryState.value = CloudAuthRegistry()
                    _sessionState.value = AuthSessionState.Unauthenticated(
                        "تعذّر استعادة الجلسة المحمية؛ اتصل بالإنترنت وأعد المحاولة أو سجّل الدخول مجددًا"
                    )
                    initialized.set(true)
                }
            }
        }
        if (verifyAfterInitialization) scope.launch { verifyActiveSessionWithCloud() }
    }

    suspend fun retryInitialization() {
        initMutex.withLock { initialized.set(false) }
        initializeIfNeeded()
    }

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

    /**
     * التحقق من امتلاك المستخدم الحالي لصلاحية معينة قبل تنفيذ عملية سحابية.
     */
    suspend fun requirePermission(permission: AuthPermission): Result<CloudUserAccount> {
        initializeIfNeeded()
        val epoch = generation.get()
        if (_sessionState.value is AuthSessionState.Authenticated) {
            val snapshot = authPreferences.snapshot()
            if (!CloudAuthRules.isCachedSessionFresh(snapshot.lastVerifiedAt)) {
                if (!isOnline()) {
                    logout("انتهت صلاحية الجلسة دون اتصال؛ أعد التحقق عبر الإنترنت")
                    return Result.failure(IOException("يلزم تجديد التحقق من الحساب"))
                }
                verifyActiveSessionWithCloud().getOrElse { return Result.failure(it) }
            }
        }
        val device = deviceFingerprintProvider.getDeviceInfo()
        if (generation.get() != epoch) return Result.failure(IOException("انتهت الجلسة أثناء فحص الصلاحية"))
        val currentState = _sessionState.value
        if (currentState !is AuthSessionState.Authenticated) {
            return Result.failure(IOException("يجب تسجيل الدخول أولاً للوصول إلى السحابة"))
        }
        if (!currentState.user.isAdmin &&
            currentState.user.boundDevice?.fingerprint != device.fingerprint
        ) {
            logout("جهاز غير معتمد لهذا الحساب")
            return Result.failure(IOException("هذا الجهاز غير معتمد لحسابك؛ يرجى تسجيل الدخول"))
        }
        return CloudAuthRules.checkPermission(currentState.user, permission)
            .map { currentState.user }
    }

    /**
     * تسجيل الدخول عبر الإنترنت (السحابة هي التي تقرر في التسجيل وربط بصمة الجهاز).
     */
    suspend fun login(usernameInput: String, passwordInput: String, allowBootstrap: Boolean = false): Result<AuthLoginOutcome> =
        withBusyLock {
            val epoch = generation.get()
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
                ensureGeneration(epoch)
                attempt++
                val remoteObj = r2Client.downloadTextObject(
                    creds,
                    CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                    maxResponseBytes = maxAuthBytes
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
                    isOnline = true,
                    allowBootstrap = allowBootstrap
                )

                ensureGeneration(epoch)
                when (outcome) {
                    is AuthLoginOutcome.Rejected -> {
                        return@withBusyLock Result.failure(IOException(outcome.reason))
                    }

                    is AuthLoginOutcome.Authenticated -> {
                        val json = CloudAuthRules.toJson(outcome.registry)
                        if (outcome.registryChanged || remoteObj == null) {
                            ensureGeneration(epoch)
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
                        ensureGeneration(epoch)
                        val visible = publishRegistry(outcome.registry, outcome.user.username)
                        lastRemoteEtag = null
                        authPreferences.saveAuthenticatedSession(
                            username = outcome.user.username,
                            deviceFingerprint = device.fingerprint,
                            registryJson = json
                        )
                        ensureGeneration(epoch)
                        publishSession(epoch, AuthSessionState.Authenticated(
                            user = outcome.user.copy(passwordHash = ""),
                            currentDevice = device,
                            pendingAdminRequests = if (outcome.user.isAdmin) {
                                visible.pendingDeviceRequests
                            } else emptyList()
                        ))
                        return@withBusyLock Result.success(outcome.copy(user = outcome.user.copy(passwordHash = ""), registry = visible))
                    }

                    is AuthLoginOutcome.PendingAdminApproval -> {
                        ensureGeneration(epoch)
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
                        ensureGeneration(epoch)
                        val visible = publishRegistry(outcome.registry, outcome.username)
                        lastRemoteEtag = null
                        authPreferences.savePendingApprovalState(
                            username = outcome.username,
                            registryJson = json
                        )
                        ensureGeneration(epoch)
                        publishSession(epoch, AuthSessionState.WaitingAdminApproval(
                            username = outcome.username,
                            requestedDevice = device,
                            message = outcome.message
                        ))
                        return@withBusyLock Result.success(outcome.copy(registry = visible))
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
            val epoch = generation.get()
            val waiting = _sessionState.value as? AuthSessionState.WaitingAdminApproval
                ?: return@withBusyLock Result.failure(IOException("لا يوجد طلب جهاز معلق حالياً"))

            if (!isOnline()) {
                return@withBusyLock Result.failure(IOException("لا يتوفر اتصال بالإنترنت للتحقق من رد المشرف"))
            }
            val creds = syncPreferences.snapshot().credentials
            val remoteObj = r2Client.downloadTextObject(
                creds,
                CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                ifNoneMatchEtag = lastRemoteEtag,
                maxResponseBytes = maxAuthBytes
            ).getOrElse { error ->
                return@withBusyLock Result.failure(IOException("تعذّر الاتصال بالسحابة: ${error.message}"))
            }

            ensureGeneration(epoch)
            val registry = if (remoteObj?.notModified == true) _registryState.value else CloudAuthRules.fromJson(remoteObj?.text)
            val previous = _registryState.value
            val visible = publishRegistry(registry, waiting.username)
            lastRemoteEtag = remoteObj?.etag?.takeIf { it.isNotBlank() }
            val json = CloudAuthRules.toJson(visible)
            if (visible != previous) authPreferences.updateCachedRegistry(json)

            val device = deviceFingerprintProvider.getDeviceInfo()
            ensureGeneration(epoch)
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
                    ensureGeneration(epoch)
                    publishSession(epoch, AuthSessionState.Authenticated(
                        user = check.user.copy(passwordHash = ""),
                        currentDevice = device,
                        pendingAdminRequests = if (check.user.isAdmin) registry.pendingDeviceRequests else emptyList()
                    ))
                }

                is PendingApprovalCheckOutcome.StillPending -> {
                    publishSession(epoch, waiting.copy(
                        message = check.message,
                        statusNote = "لا يزال الطلب قيد المراجعة — سيرد لك مشرف"
                    ))
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
        initializeIfNeeded()
        val state = _sessionState.value
        if (state !is AuthSessionState.Authenticated && state !is AuthSessionState.WaitingAdminApproval) {
            return@withContext Result.failure(IOException("لا توجد جلسة للتحقق منها"))
        }
        if (!isOnline()) return@withContext Result.failure(IOException("غير متصل بالإنترنت"))
        if (!mutex.tryLock()) return@withContext Result.failure(IOException("جارٍ تنفيذ عملية مصادقة أخرى"))
        try {
            val epoch = generation.get()
            val creds = syncPreferences.snapshot().credentials
            val remote = r2Client.downloadTextObject(creds, CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                ifNoneMatchEtag = lastRemoteEtag, maxResponseBytes = maxAuthBytes).getOrThrow()
            ensureGeneration(epoch)
            if (remote == null) {
                authPreferences.clearSession()
                lastRemoteEtag = null
                _registryState.value = CloudAuthRegistry()
                _sessionState.value = AuthSessionState.Unauthenticated("سجل المصادقة مفقود؛ استعد نسخة موثوقة بواسطة المالك")
                throw IOException("سجل المصادقة غير موجود؛ لم يُنشأ مالك افتراضي")
            }
            val registry = if (remote.notModified) _registryState.value else CloudAuthRules.fromJson(remote.text)
            if (!remote.notModified && registry.users.none { it.username == CloudAuthRules.ADMIN_USERNAME }) {
                authPreferences.clearSession()
                _registryState.value = CloudAuthRegistry()
                _sessionState.value = AuthSessionState.Unauthenticated("سجل المصادقة لا يحتوي حساب المالك؛ استعد نسخة موثوقة")
                throw IOException("سجل المصادقة غير صالح")
            }
            val device = deviceFingerprintProvider.getDeviceInfo()
            when (val current = _sessionState.value) {
                is AuthSessionState.Authenticated -> {
                    val result = CloudAuthRules.validateExistingSession(registry, current.user.username, device)
                    val refreshed = result.getOrElse { error ->
                        authPreferences.clearSession()
                        _registryState.value = CloudAuthRegistry()
                        _sessionState.value = AuthSessionState.Unauthenticated(error.message)
                        throw error
                    }
                    val visible = publishRegistry(registry, refreshed.username)
                    if (remote.notModified) authPreferences.markVerified()
                    else authPreferences.updateCachedRegistry(CloudAuthRules.toJson(visible))
                    ensureGeneration(epoch)
                    publishSession(epoch, AuthSessionState.Authenticated(refreshed.copy(passwordHash = ""), device,
                        if (refreshed.isAdmin) visible.pendingDeviceRequests else emptyList()))
                }
                is AuthSessionState.WaitingAdminApproval -> {
                    when (val check = CloudAuthRules.checkPendingApproval(registry, current.username, device)) {
                        is PendingApprovalCheckOutcome.Approved -> {
                            val visible = publishRegistry(registry, check.user.username)
                            authPreferences.saveAuthenticatedSession(check.user.username, device.fingerprint, CloudAuthRules.toJson(visible))
                            ensureGeneration(epoch)
                            publishSession(epoch, AuthSessionState.Authenticated(check.user.copy(passwordHash = ""), device,
                                if (check.user.isAdmin) visible.pendingDeviceRequests else emptyList()))
                        }
                        is PendingApprovalCheckOutcome.Rejected -> {
                            authPreferences.clearSession()
                            _registryState.value = CloudAuthRegistry()
                            _sessionState.value = AuthSessionState.Unauthenticated(check.reason)
                        }
                        is PendingApprovalCheckOutcome.StillPending -> Unit
                    }
                }
                else -> throw IOException("انتهت الجلسة أثناء التحقق")
            }
            lastRemoteEtag = remote.etag.takeIf { it.isNotBlank() }
            Result.success(_registryState.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            mutex.unlock()
        }
    }

    /** Invalidate in-flight results before waiting for the serialized persistent cleanup. */
    suspend fun logout(reason: String? = null) = withContext(Dispatchers.IO) {
        synchronized(generation) {
            generation.incrementAndGet()
            _sessionState.value = AuthSessionState.Unauthenticated(reason)
        }
        mutex.withLock {
            lastRemoteEtag = null
            authPreferences.clearSession()
            _registryState.value = CloudAuthRegistry()
            _sessionState.value = AuthSessionState.Unauthenticated(reason)
        }
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
        val epoch = generation.get()
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
            ensureGeneration(epoch)
            attempt++
            val remoteObj = r2Client.downloadTextObject(
                creds,
                CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                maxResponseBytes = maxAuthBytes
            ).getOrElse { error ->
                return@withBusyLock Result.failure(
                    IOException("تعذّر قراءة سجل المستخدمين من السحابة: ${error.message}")
                )
            }

            val remoteRegistryObject = remoteObj ?: return@withBusyLock Result.failure(
                IOException("سجل المصادقة غير موجود على السحابة؛ لن يُعاد إنشاؤه تلقائيًا")
            )
            val baseRegistry = CloudAuthRules.fromJson(remoteRegistryObject.text)
            if (baseRegistry.users.none { CloudAuthRules.normalizeUsername(it.username) == CloudAuthRules.ADMIN_USERNAME }) {
                return@withBusyLock Result.failure(IOException("سجل المصادقة لا يحتوي على حساب المشرف؛ استعد نسخة موثوقة أولًا"))
            }

            ensureGeneration(epoch)
            val updatedRegistry = mutation(baseRegistry, currentAuth.user).getOrElse { error ->
                return@withBusyLock Result.failure(error)
            }

            ensureGeneration(epoch)
            val json = CloudAuthRules.toJson(updatedRegistry)
            val uploaded = r2Client.uploadText(
                credentials = creds,
                objectKey = CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY,
                text = json,
                ifMatch = remoteRegistryObject.etag.takeIf { it.isNotBlank() },
                ifNoneMatch = false
            )
            if (uploaded.isFailure) {
                val err = uploaded.exceptionOrNull()
                if (err is CloudConflictException && attempt < 3) continue
                return@withBusyLock Result.failure(
                    IOException("تعذّر حفظ التعديلات في السحابة: ${err?.message}")
                )
            }

            ensureGeneration(epoch)
            val visible = publishRegistry(updatedRegistry, currentAuth.user.username)
            lastRemoteEtag = null
            authPreferences.updateCachedRegistry(CloudAuthRules.toJson(visible))

            val device = deviceFingerprintProvider.getDeviceInfo()
            val refreshedCurrentUser = updatedRegistry.users.firstOrNull {
                CloudAuthRules.normalizeUsername(it.username) ==
                    CloudAuthRules.normalizeUsername(currentAuth.user.username)
            } ?: currentAuth.user

            ensureGeneration(epoch)
            publishSession(epoch, AuthSessionState.Authenticated(
                user = refreshedCurrentUser.copy(passwordHash = ""),
                currentDevice = device,
                pendingAdminRequests = if (refreshedCurrentUser.isAdmin) {
                    visible.pendingDeviceRequests
                } else emptyList()
            ))
            return@withBusyLock Result.success(Unit)
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IOException("تعذّر تحديث السجل السحابي"))
    }

    private fun publishSession(expected: Long, state: AuthSessionState) = synchronized(generation) {
        ensureGeneration(expected)
        _sessionState.value = state
    }

    private fun ensureGeneration(expected: Long) {
        if (generation.get() != expected) throw IOException("أُلغيت الجلسة أثناء العملية؛ أعد المحاولة")
    }

    private fun publishRegistry(registry: CloudAuthRegistry, username: String): CloudAuthRegistry {
        val user = registry.users.firstOrNull { CloudAuthRules.normalizeUsername(it.username) == CloudAuthRules.normalizeUsername(username) }
        val visible = CloudAuthRules.redactedRegistry(registry, username, includeAllUsers = user?.isAdmin == true)
        _registryState.value = visible
        return visible
    }

    private suspend fun <T> withBusyLock(block: suspend () -> Result<T>): Result<T> = withContext(Dispatchers.IO) {
        initializeIfNeeded()
        if (!mutex.tryLock()) return@withContext Result.failure(IOException("جارٍ تنفيذ عملية أخرى؛ انتظر اكتمالها"))
        _isBusy.value = true
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        finally {
            _isBusy.value = false
            mutex.unlock()
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
