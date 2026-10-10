package com.unihub.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.auth.AuthLoginOutcome
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.auth.DeviceChangeRequest
import com.unihub.app.data.auth.PendingApprovalCheckOutcome
import com.unihub.app.data.cloud.CloudflareR2Client
import com.unihub.app.data.cloud.R2Credentials
import com.unihub.app.data.provision.CloudProvisioningManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: CloudAuthManager,
    private val cloudSyncPreferences: CloudSyncPreferences,
    private val r2Client: CloudflareR2Client,
    private val provisioning: CloudProvisioningManager
) : ViewModel() {
    val sessionState: StateFlow<AuthSessionState> = authManager.sessionState
    val registry = authManager.registryState
    private val submitting = MutableStateFlow(false)
    private val saving = MutableStateFlow(false)
    val isBusy = combine(authManager.isBusy, submitting, saving) { auth, login, setup -> auth || login || setup }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isSavingCloudSettings = saving.asStateFlow()
    val cloudSettings = cloudSyncPreferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val serviceAvailable: Boolean get() = provisioning.serviceAvailable

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _pendingNote = MutableStateFlow<String?>(null)
    val pendingNote = _pendingNote.asStateFlow()
    private val _setupSuccessVersion = MutableStateFlow(0)
    val setupSuccessVersion = _setupSuccessVersion.asStateFlow()
    private val _sessionMessageDismissed = MutableStateFlow(false)
    val sessionMessageDismissed = _sessionMessageDismissed.asStateFlow()
    private val _dismissedRequestIds = MutableStateFlow<Set<String>>(emptySet())
    val dismissedRequestIds = _dismissedRequestIds.asStateFlow()
    private var loginJob: Job? = null
    private var saveJob: Job? = null
    private var manualCheckJob: Job? = null
    private val pendingGate = PendingCheckGate()

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
        _sessionMessageDismissed.value = true
    }

    /** Recovery is explicit; ordinary users use the broker or a sealed device-bound QR. */
    fun saveInitialCloudCredentials(accountId: String, bucketName: String, accessKeyId: String, secretAccessKey: String) {
        if (saving.value || submitting.value || authManager.isBusy.value) return
        clearMessages()
        saving.value = true
        saveJob = viewModelScope.launch {
            try {
                if (authManager.isAuthenticatedNow()) throw IOException("استيراد الاتصال متاح من شاشة الدخول فقط")
                val existing = cloudSyncPreferences.settings.first().credentials
                val account = accountId.trim().lowercase(java.util.Locale.ROOT)
                val bucket = bucketName.trim()
                val sameCloud = existing.accountId == account && existing.bucketName == bucket
                val candidate = R2Credentials(accountId = account, endpointUrl = "", bucketName = bucket,
                    accessKeyId = accessKeyId.trim().ifBlank { if (sameCloud) existing.accessKeyId else "" },
                    secretAccessKey = secretAccessKey.trim().ifBlank { if (sameCloud) existing.secretAccessKey else "" })
                if (!candidate.isConfigured) throw IOException("تحقق من بيانات اتصال المالك؛ يلزم المفتاحان عند تغيير الحساب أو الحاوية")
                r2Client.testConnection(candidate).getOrThrow()
                authManager.logout()
                cloudSyncPreferences.saveCredentials(candidate)
                _setupSuccessVersion.value += 1
                _statusMessage.value = "تم التحقق من الاتصال وحفظه مشفرًا؛ يمكنك تسجيل الدخول الآن"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errorMessage.value = error.message ?: "تعذّر التحقق من اتصال السحابة؛ لم تُستبدل المفاتيح"
            } finally { saving.value = false }
        }
    }

    fun cancelConnectionOperation() { saveJob?.cancel() }

    fun login(username: String, password: String, allowBootstrap: Boolean = false, invitation: String = "") {
        if (submitting.value || saving.value || authManager.isBusy.value) return
        clearMessages()
        AuthInteractionRules.inputError(username, password)?.let { _errorMessage.value = it; return }
        submitting.value = true
        loginJob = viewModelScope.launch {
            try {
                if (!cloudSyncPreferences.settings.first().isConfigured) {
                    if (invitation.isBlank()) throw IOException("فعّل الاتصال برمز المالك أو امسح الباركود المشفر أولًا")
                    provisioning.receiveFromService(invitation)
                }
                authManager.login(username, password, allowBootstrap).fold(
                    onSuccess = { outcome ->
                        when (outcome) {
                            is AuthLoginOutcome.Authenticated -> {
                                _dismissedRequestIds.value = emptySet()
                                _pendingNote.value = null
                            }
                            is AuthLoginOutcome.PendingAdminApproval -> _statusMessage.value = outcome.message
                            is AuthLoginOutcome.Rejected -> _errorMessage.value = outcome.reason
                        }
                    },
                    onFailure = { _errorMessage.value = it.message ?: "تعذّر تسجيل الدخول" }
                )
            } catch (_: TimeoutCancellationException) {
                _errorMessage.value = "انتهت مهلة الاتصال؛ أعد المحاولة"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errorMessage.value = error.message ?: "تعذّر تسجيل الدخول"
            } finally { submitting.value = false }
        }
    }

    /** Awaited by repeatOnLifecycle; cancellation closes the HTTP socket, not just a timer. */
    suspend fun awaitPendingCheck(silent: Boolean = true): Boolean = pendingGate.runIfIdle {
        if (!silent) clearMessages()
        val result = authManager.checkPendingApprovalStatus()
        result.fold(onSuccess = { outcome ->
            _pendingNote.value = null
            when (outcome) {
                is PendingApprovalCheckOutcome.Approved -> {
                    _statusMessage.value = "تمت موافقة المالك على جهازك"
                    _errorMessage.value = null
                }
                is PendingApprovalCheckOutcome.StillPending -> if (!silent) _statusMessage.value = outcome.message
                is PendingApprovalCheckOutcome.Rejected -> _errorMessage.value = outcome.reason
            }
        }, onFailure = {
            if (silent) _pendingNote.value = "تعذّر الفحص الآن؛ ستتم إعادة المحاولة تدريجيًا أو يمكنك الفحص يدويًا"
            else _errorMessage.value = it.message ?: "تعذّر فحص الموافقة"
        })
        result.isSuccess
    } ?: true

    fun checkPendingStatus(silent: Boolean = false) {
        if (manualCheckJob?.isActive == true) return
        manualCheckJob = viewModelScope.launch { awaitPendingCheck(silent) }
    }

    fun cancelPendingAndBackToLogin() {
        loginJob?.cancel()
        manualCheckJob?.cancel()
        clearMessages()
        viewModelScope.launch { authManager.logout() }
    }

    fun retryInitialization() {
        if (submitting.value || saving.value) return
        clearMessages()
        _sessionMessageDismissed.value = false
        viewModelScope.launch { authManager.retryInitialization() }
    }

    fun approveDeviceRequest(request: DeviceChangeRequest) {
        viewModelScope.launch {
            authManager.approveDeviceRequest(request.requestId).onSuccess {
                _dismissedRequestIds.value = _dismissedRequestIds.value + request.requestId
            }.onFailure { _errorMessage.value = it.message ?: "تعذّر اعتماد الجهاز" }
        }
    }

    fun rejectDeviceRequest(request: DeviceChangeRequest) {
        viewModelScope.launch {
            authManager.rejectDeviceRequest(request.requestId).onSuccess {
                _dismissedRequestIds.value = _dismissedRequestIds.value + request.requestId
            }.onFailure { _errorMessage.value = it.message ?: "تعذّر رفض الطلب" }
        }
    }

    fun dismissDeviceRequestPopup(requestId: String) { _dismissedRequestIds.value = _dismissedRequestIds.value + requestId }
}
