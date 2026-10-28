package com.unihub.app.data.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.unihub.app.core.prefs.CloudAuthPreferences
import com.unihub.app.core.prefs.LocalAuthSessionSnapshot
import com.unihub.app.core.prefs.UserRegistrationWatchPreferences
import com.unihub.app.data.supabase.SupabaseApiException
import com.unihub.app.data.supabase.SupabaseAuthRepository
import com.unihub.app.data.supabase.SupabaseSessionInvalidException
import com.unihub.app.data.supabase.SupabaseNetworkException
import com.unihub.app.data.supabase.SupabaseStorageAuthorization
import com.unihub.app.data.cloud.cloudAttempt
import com.unihub.app.notifications.NewUserNotificationHelper
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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * المصادقة على Supabase: الرموز والجلسات في `SupabaseAuthRepository`،
 * وإدارة المستخدمين دوال داخل القاعدة (لا مفتاح إداري في التطبيق).
 */
@Singleton
class CloudAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SupabaseAuthRepository,
    private val storageAuthorization: SupabaseStorageAuthorization,
    private val authPreferences: CloudAuthPreferences,
    private val deviceFingerprintProvider: DeviceFingerprintProvider,
    private val registrationWatchPreferences: UserRegistrationWatchPreferences,
    private val newUserNotificationHelper: NewUserNotificationHelper
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    private val mutex = Mutex()
    private val stateLock = Any()
    @Volatile private var initialized = false
    private val _sessionState = MutableStateFlow<AuthSessionState>(AuthSessionState.Initializing)
    val sessionState: StateFlow<AuthSessionState> = _sessionState.asStateFlow()
    private val _registryState = MutableStateFlow(CloudAuthRegistry())
    val registryState: StateFlow<CloudAuthRegistry> = _registryState.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init { scope.launch { initializeIfNeeded() } }

    suspend fun initializeIfNeeded() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        initMutex.withLock {
            if (initialized) return@withLock
            mutex.withLock {
                try {
                    val cached = authPreferences.snapshot()
                    val device = deviceFingerprintProvider.getDeviceInfo()
                    if (!cached.hasAuthenticatedSession || cached.boundDeviceFingerprint != device.fingerprint) {
                        clearLocalSession()
                    } else if (isOnline()) {
                        repository.restoreTokens(cached.accessToken, cached.refreshToken)
                        val user = try {
                            repository.currentUser(expectedUserId = cached.userId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (!isTransientSessionCheckFailure(error)) throw error
                            null
                        }
                        if (user != null) publish(user, device)
                        else restoreCachedOfflineSession(cached, device)
                    } else {
                        restoreCachedOfflineSession(cached, device)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // إصلاح 2026-10-27: كان السبب الحقيقي لعطل الاستعادة يُبتلع
                    // بصمت (_: Exception) فلا يُعرف أبدًا هل كان RLS أم 401 أم
                    // انقطاع شبكة. الآن يُسجَّل في Logcat لتشخيص أي بلاغ قادم.
                    android.util.Log.w("CloudAuthManager", "Session restore failed", error)
                    clearLocalSession("تعذّر استعادة الجلسة؛ أعد تسجيل الدخول")
                } finally { initialized = true }
            }
        }
    }

    suspend fun retryInitialization() {
        initMutex.withLock { initialized = false }
        initializeIfNeeded()
    }
    fun isAuthenticatedNow(): Boolean = _sessionState.value is AuthSessionState.Authenticated
    fun currentAuthenticatedUser(): CloudUserAccount? = (_sessionState.value as? AuthSessionState.Authenticated)?.user

    fun requireAdmin(): Result<CloudUserAccount> {
        val user = currentAuthenticatedUser()
        return if (user != null && user.isActive && user.isAdmin) Result.success(user)
        else Result.failure(IOException("هذا الإجراء متاح للمشرف المصادق عليه فقط"))
    }

    suspend fun requirePermission(permission: AuthPermission): Result<CloudUserAccount> = busyOperation {
        requireAuthenticated()
        requireOnline()
        val user = repository.currentUser()
        publish(user, deviceFingerprintProvider.getDeviceInfo())
        CloudAuthRules.checkPermission(user, permission).getOrThrow()
        user
    }

    suspend fun login(usernameInput: String, passwordInput: String): Result<AuthLoginOutcome> = busyOperation {
        requireOnline()
        storageAuthorization.clear()
        // Password is intentionally not trimmed and is never persisted by this application.
        val user = repository.login(usernameInput, passwordInput)
        publish(user, deviceFingerprintProvider.getDeviceInfo())
        AuthLoginOutcome.Authenticated(user, _registryState.value, registryChanged = false)
    }

    /**
     * تسجيل حساب عادي ذاتيًا ("تسجيل دخول طبيعي" — راجع مراجعة.md للتشخيص
     * الكامل): لا يتطلّب أي مشرف موجود ولا أي دالة إدارية؛ أي شخص يفتح التطبيق
     * يستطيع إنشاء حسابه بنفسه، ويُسجَّل دخوله تلقائيًا فور النجاح (نفس الرد
     * يحمل الجلسة). الحساب الجديد يبدأ بصلاحيات صفرية في السحابة إلى أن
     * يراجعها المشرف من شاشة إدارة المستخدمين — وهو ما يُشعَر به المشرف عبر
     * [notifyAdminOfNewUsersIfAny] في المرة التالية التي يفحص فيها تطبيقه
     * سجلّ المستخدمين (راجع حدود هذه الآلية في توثيق [NewUserNotificationHelper]).
     */
    suspend fun register(email: String, password: String): Result<AuthLoginOutcome> = busyOperation {
        requireOnline()
        storageAuthorization.clear()
        val user = repository.signUp(email, password)
        publish(user, deviceFingerprintProvider.getDeviceInfo())
        AuthLoginOutcome.Authenticated(user, _registryState.value, registryChanged = false)
    }

    /**
     * تمهيد أول حساب مشرف لمشروع Supabase فارغ (لا يحتاج جلسة قائمة — راجع
     * `SupabaseAuthRepository.bootstrapFirstAdmin`). عند النجاح لا تُنشأ جلسة
     * تلقائيًا هنا؛ المستدعي (ViewModel) يستكمل بـ [login] بنفس البيانات حتى
     * يمرّ المستخدم بمسار الدخول الاعتيادي نفسه (نفس حفظ الجلسة والتحقق).
     */
    suspend fun bootstrapFirstAdmin(email: String, password: String): Result<Unit> = busyOperation {
        requireOnline()
        repository.bootstrapFirstAdmin(email, password)
    }

    suspend fun verifyActiveSessionWithCloud(): Result<CloudAuthRegistry> = busyOperation {
        requireAuthenticated()
        requireOnline()
        val user = repository.currentUser()
        publish(user, deviceFingerprintProvider.getDeviceInfo())
        if (user.isAdmin) {
            val version = repository.sessionVersion
            val users = repository.listUsers()
            synchronized(stateLock) {
                if (!repository.isCurrent(version, user.userId)) throw IOException("انتهت الجلسة")
                _registryState.value = CloudAuthRegistry(updatedAt = System.currentTimeMillis(), users = users)
            }
            notifyAdminOfNewUsersIfAny(user, users)
        }
        _registryState.value
    }

    suspend fun logout(reason: String? = null) = withContext(Dispatchers.IO) {
        invalidateState(reason)
        mutex.withLock {
            authPreferences.clearSession()
            // A network failure must never restore a local authenticated state.
            cloudAttempt { repository.deleteRemoteSession() }
        }
    }

    suspend fun addUser(newUsername: String, password: String, permissions: UserPermissions): Result<Unit> = adminOperation {
        repository.addUser(newUsername, password, permissions)
    }
    suspend fun deleteUser(targetUsername: String): Result<Unit> = adminOperation { repository.deleteUser(userId(targetUsername)) }
    suspend fun setUserActive(targetUsername: String, active: Boolean): Result<Unit> = adminOperation {
        repository.setUserActive(userId(targetUsername), active)
    }
    suspend fun updateUserPermissions(targetUsername: String, permissions: UserPermissions): Result<Unit> = adminOperation {
        repository.updatePermissions(userId(targetUsername), permissions)
    }
    suspend fun adminChangeUserPassword(targetUsername: String, newPassword: String): Result<Unit> = adminOperation {
        repository.changeUserPassword(userId(targetUsername), newPassword)
    }
    suspend fun userChangeOwnPassword(currentPassword: String, newPassword: String): Result<Unit> = busyOperation {
        requireAuthenticated(); requireOnline()
        repository.changeOwnPassword(currentPassword, newPassword)
        storageAuthorization.clear()
        publish(repository.currentUser(), deviceFingerprintProvider.getDeviceInfo())
    }
    /** يُبطل الخادم الجلسات نفسها (دالة admin_revoke_sessions) بدل مسح بصمة محلية. */
    suspend fun revokeUserSessions(targetUsername: String): Result<Unit> = adminOperation {
        repository.revokeUserSessions(userId(targetUsername))
    }

    private suspend fun adminOperation(block: suspend () -> Unit): Result<Unit> = busyOperation {
        requireAuthenticated(); requireOnline()
        val actor = repository.currentUser()
        if (!actor.isAdmin || !actor.isActive) throw IOException("إدارة المستخدمين متاحة للمشرف فقط")
        val version = repository.sessionVersion
        block() // The function independently re-checks the actor; this UI check is not authorization.
        val users = repository.listUsers()
        synchronized(stateLock) {
            if (!repository.isCurrent(version, actor.userId)) throw IOException("انتهت الجلسة")
            _registryState.value = CloudAuthRegistry(updatedAt = System.currentTimeMillis(), users = users)
        }
        notifyAdminOfNewUsersIfAny(actor, users)
    }

    /**
     * فحص "هل انضمّ مستخدم جديد منذ آخر مرة رأى فيها هذا المشرف القائمة؟" وإظهار
     * إشعار محلي عند الحاجة. لا يُفشل أي عملية إدارية أو تحقّق جلسة أبدًا — فشل
     * الإشعار نفسه (مثلًا إذن الإشعارات مرفوض) يُبتلَع هنا بتصميم، تمامًا كما
     * تتجاهل بقية طبقة الإشعارات في هذا التطبيق فشل العرض (راجع CloudFileNotificationHelper).
     */
    private suspend fun notifyAdminOfNewUsersIfAny(admin: CloudUserAccount, users: List<CloudUserAccount>) {
        if (!admin.isAdmin) return
        runCatching {
            val known = registrationWatchPreferences.knownUserIds()
            val diff = NewUserWatchRules.diff(known, users, admin.userId)
            if (diff.updatedKnownIds != known) registrationWatchPreferences.saveKnownUserIds(diff.updatedKnownIds)
            if (diff.newUsers.isNotEmpty()) newUserNotificationHelper.notifyNewUsers(diff.newUsers)
        }
    }

    private suspend fun userId(email: String): String = repository.listUsers().firstOrNull {
        CloudAuthRules.normalizeUsername(it.username) == CloudAuthRules.normalizeUsername(email)
    }?.userId ?: throw IOException("المستخدم المطلوب غير موجود")

    private suspend fun publish(user: CloudUserAccount, device: BoundDeviceInfo) {
        val version = repository.sessionVersion
        if (!repository.isCurrent(version, user.userId)) throw IOException("انتهت الجلسة")
        authPreferences.saveAuthenticatedSession(user, device.fingerprint,
            repository.currentAccessToken(), repository.currentRefreshToken())
        publishState(user, device, version)
    }

    private fun publishState(user: CloudUserAccount, device: BoundDeviceInfo, version: Long) = synchronized(stateLock) {
        if (!repository.isCurrent(version, user.userId)) throw IOException("انتهت الجلسة")
        val existing = _registryState.value.users.filter { user.isAdmin && it.userId != user.userId }
        _registryState.value = CloudAuthRegistry(updatedAt = System.currentTimeMillis(), users = existing + user)
        _sessionState.value = AuthSessionState.Authenticated(user, device)
    }

    private fun invalidateState(reason: String? = null) = synchronized(stateLock) {
        repository.invalidateSession()
        storageAuthorization.clear()
        _registryState.value = CloudAuthRegistry()
        _sessionState.value = AuthSessionState.Unauthenticated(reason)
    }
    private suspend fun clearLocalSession(reason: String? = null) {
        invalidateState(reason)
        authPreferences.clearSession()
    }

    /**
     * A momentary outage can coexist with Android's validated-network flag. Keep a fresh,
     * device-bound local session for local app use, but never use it to authorize cloud I/O:
     * requirePermission() still fetches the active account before every transfer.
     */
    private suspend fun restoreCachedOfflineSession(
        cached: LocalAuthSessionSnapshot,
        device: BoundDeviceInfo
    ) {
        val user = CloudAuthRules.fromJson(cached.cachedRegistryJson).users.singleOrNull()
        if (user == null || user.userId != cached.userId || !user.isActive ||
            !CloudAuthRules.isCachedSessionFresh(cached.lastVerifiedAt)) {
            clearLocalSession("اتصل بالإنترنت للتحقق من الجلسة")
            return
        }
        repository.restoreOfflineIdentity(user)
        publishState(user, device, repository.sessionVersion)
    }

    /** Only transport/server-availability failures may fall back to the local profile cache. */
    private fun isTransientSessionCheckFailure(error: Throwable): Boolean {
        val visited = hashSetOf<Throwable>()
        var cause: Throwable? = error
        while (cause != null && visited.add(cause)) {
            if (cause is SupabaseSessionInvalidException) return false
            if (cause is SupabaseApiException) {
                return cause.status == 0 || cause.status == 408 || cause.status == 429 ||
                    cause.status in 500..599
            }
            if (cause is UnknownHostException || cause is ConnectException ||
                cause is NoRouteToHostException || cause is SocketException ||
                cause is SocketTimeoutException || cause is SSLException) return true
            cause = cause.cause
        }
        return false
    }

    private fun requireAuthenticated() {
        if (!isAuthenticatedNow()) throw IOException("سجّل الدخول أولاً")
    }
    private fun requireOnline() { if (!isOnline()) throw IOException("يلزم اتصال بالإنترنت للتحقق من الجلسة") }

    /**
     * إصلاح 2026-10-16: كانت هذه الدالة تستعمل mutex.tryLock() وترفض الطلب فورًا برسالة
     * «جارٍ تنفيذ عملية مصادقة أخرى» إن وجدت القفل مشغولًا. بما أن CloudSyncManager يستدعي
     * verifyActiveSessionWithCloud()/requirePermission() بشكل دوري في الخلفية (نبض كل بضع
     * ثوانٍ طالما الشاشة بالمقدمة) وأيضًا قبل كل رفع/تنزيل يدوي، كان أي تطابق توقيت بسيط بين
     * النبض الدوري وضغطة المستخدم على «رفع/تنزيل» يُسقط عملية المستخدم فورًا دون أي خطأ حقيقي
     * في الشبكة أو الصلاحيات — وهذا ما ظهر في البلاغ: الحالة «متصل» ظاهرة، ومع ذلك يفشل رفع
     * المجلد ويفشل فحص القائمة في اللحظة نفسها. التطابق مؤكد بإعادة إنتاج معزولة (انظر
     * verification/cloud-auth-busy-lock-fix-2026-10-16/).
     *
     * الإصلاح: استبدال «ارفض فورًا» بـ«انتظر دورك» عبر mutex.withLock. هذه العمليات (تحقق من
     * الجلسة، تحقق من صلاحية، تسجيل دخول/خروج) غير متعارضة منطقيًا؛ تنفيذها بالتتابع آمن تمامًا
     * ولا داعي لرفض أي منها لمجرد أن أخرى قيد التنفيذ في تلك اللحظة.
     */
    private suspend fun <T> busyOperation(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        initializeIfNeeded()
        mutex.withLock {
            _isBusy.value = true
            try { Result.success(block()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SupabaseSessionInvalidException) {
                clearLocalSession(error.message)
                Result.failure(error)
            }
            catch (error: SupabaseApiException) {
                if (error.status == 401 || error.status == 403) clearLocalSession("انتهت الجلسة أو رُفض الدخول")
                Result.failure(IOException(when (error.status) {
                    401, 403 -> "تعذّر توثيق الحساب؛ تحقق من البريد وكلمة المرور وصلاحية الحساب"
                    429 -> "محاولات كثيرة؛ انتظر قليلًا ثم أعد المحاولة"
                    else -> "تعذّر الاتصال بخدمة الحسابات"
                }))
            }
            // إصلاح 2026-10-27: العطل العابر (5xx/408/لا استجابة) يُعرض كما هو —
            // بلا مسح الجلسة المحلية، وبأي IOException خام آخر (DNS، مهلة،
            // انقطاع أثناء الإرسال) تُترجم لرسالة عربية ودّية بدل نص نظام
            // إنجليزي تقني يُعرض للمستخدم («Unable to resolve host...»).
            catch (error: SupabaseNetworkException) { Result.failure(error) }
            catch (error: IOException) {
                Result.failure(IOException("تعذّر الاتصال بالخادم؛ تحقق من الإنترنت ثم أعد المحاولة"))
            }
            catch (error: Exception) { Result.failure(error) }
            finally { _isBusy.value = false }
        }
    }

    /**
     * إصلاح 2026-10-27 (سبب بلاغ «يلزم اتصال بالإنترنت» رغم وجود اتصال فعلًا):
     * كان الشرط يتطلب NET_CAPABILITY_VALIDATED — وهو علم يتأخر ثوانٍ بعد الاتصال
     * ويبقى false على بوابات مقيَّدة وشبكات IPv6‑only وبعض VPNs وأجهزة Xiaomi/
     * Huawei عند الإقلاع. النتيجة: رفض تسجيل الدخول/التسجيل على شبكة سليمة،
     * وعند الإقلاع مع جلسة محفوظة يُمسح الدخول المحلي بلا سبب.
     *
     * المعيار الآن: وجود شبكة تُعلن الإنترنت يكفي لمحاولة الطلب؛ أما فشل الشبكة
     * الحقيقي فيُكتشف في طبقة HTTP (مهلة/استجابة خاطئة) ويُعرض برسالة «أعد
     * المحاولة» — لا برفض مسبق. مصادقة الخادم نفسها تبقى حارسة البوابات.
     */
    fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
