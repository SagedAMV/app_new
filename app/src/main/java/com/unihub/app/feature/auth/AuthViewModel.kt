package com.unihub.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.auth.AuthLoginOutcome
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.cloud.R2Credentials
import java.io.IOException
import com.unihub.app.data.auth.DeviceChangeRequest
import com.unihub.app.data.auth.PendingApprovalCheckOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: CloudAuthManager,
    private val cloudSyncPreferences: CloudSyncPreferences
) : ViewModel() {

    val sessionState: StateFlow<AuthSessionState> = authManager.sessionState
    val registry = authManager.registryState
    val isBusy: StateFlow<Boolean> = authManager.isBusy
    val cloudSettings = cloudSyncPreferences.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    private val _isSavingCloudSettings = MutableStateFlow(false)
    val isSavingCloudSettings: StateFlow<Boolean> = _isSavingCloudSettings.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** معرّف الطلبات التي أغلق المشرف نافذتها المنبثقة مؤقتاً في هذه الجلسة */
    private val _dismissedRequestIds = MutableStateFlow<Set<String>>(emptySet())
    val dismissedRequestIds: StateFlow<Set<String>> = _dismissedRequestIds.asStateFlow()

    private fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    /**
     * Allows initial R2 setup before login because the auth registry itself is stored in R2.
     * While unauthenticated, it also provides a recovery path for revoked or mistyped keys;
     * ordinary changes after login are still handled from authenticated admin settings.
     */
    fun saveInitialCloudCredentials(
        accountId: String,
        bucketName: String,
        accessKeyId: String,
        secretAccessKey: String
    ) {
        if (_isSavingCloudSettings.value) return
        clearMessages()
        viewModelScope.launch {
            _isSavingCloudSettings.value = true
            try {
                if (authManager.isAuthenticatedNow()) {
                    throw IOException("إعداد الاتصال من هذه الشاشة متاح قبل تسجيل الدخول فقط")
                }
                val existing = cloudSyncPreferences.snapshot().credentials
                // This form is reachable only while no account is authenticated. It doubles
                // as recovery when a token is revoked or mistyped and the user cannot reach Settings.
                val candidate = R2Credentials(
                    accountId = accountId.trim(),
                    endpointUrl = "",
                    bucketName = bucketName.trim(),
                    accessKeyId = accessKeyId.trim().ifBlank { existing.accessKeyId },
                    secretAccessKey = secretAccessKey.trim().ifBlank { existing.secretAccessKey }
                )
                if (!candidate.isConfigured) {
                    throw IOException("تحقق من معرّف حساب Cloudflare واسم الحاوية ومفتاحي R2")
                }
                cloudSyncPreferences.saveCredentials(candidate)
                if (!cloudSyncPreferences.snapshot().credentials.isConfigured) {
                    throw IOException("تعذّر التحقق من إعدادات الاتصال بعد حفظها")
                }
                _statusMessage.value = "حُفظ اتصال Cloudflare R2 مشفّرًا على هذا الجهاز. حاول تسجيل الدخول الآن."
                _errorMessage.value = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _errorMessage.value = error.message ?: "تعذّر حفظ إعدادات السحابة"
            } finally {
                _isSavingCloudSettings.value = false
            }
        }
    }

    fun login(username: String, password: String) {
        clearMessages()
        viewModelScope.launch {
            authManager.login(username, password).fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is AuthLoginOutcome.Authenticated -> {
                            _statusMessage.value = null
                            _errorMessage.value = null
                        }
                        is AuthLoginOutcome.PendingAdminApproval -> {
                            _statusMessage.value = outcome.message
                            _errorMessage.value = null
                        }
                        is AuthLoginOutcome.Rejected -> {
                            _errorMessage.value = outcome.reason
                        }
                    }
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "تعذّر تسجيل الدخول"
                }
            )
        }
    }

    fun checkPendingStatus(silent: Boolean = false) {
        if (!silent) clearMessages()
        viewModelScope.launch {
            authManager.checkPendingApprovalStatus().fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is PendingApprovalCheckOutcome.Approved -> {
                            _statusMessage.value = "تمت موافقة المشرف على جهازك؛ مرحباً بك"
                            _errorMessage.value = null
                        }
                        is PendingApprovalCheckOutcome.StillPending -> {
                            if (!silent) _statusMessage.value = outcome.message
                        }
                        is PendingApprovalCheckOutcome.Rejected -> {
                            _errorMessage.value = outcome.reason
                        }
                    }
                },
                onFailure = { error ->
                    if (!silent) _errorMessage.value = error.message
                }
            )
        }
    }

    fun cancelPendingAndBackToLogin() {
        clearMessages()
        viewModelScope.launch {
            authManager.logout()
        }
    }

    fun approveDeviceRequest(request: DeviceChangeRequest) {
        viewModelScope.launch {
            authManager.approveDeviceRequest(request.requestId)
                .onSuccess {
                    _dismissedRequestIds.value = _dismissedRequestIds.value + request.requestId
                }
                .onFailure {
                    _errorMessage.value = it.message
                }
        }
    }

    fun rejectDeviceRequest(request: DeviceChangeRequest) {
        viewModelScope.launch {
            authManager.rejectDeviceRequest(request.requestId)
                .onSuccess {
                    _dismissedRequestIds.value = _dismissedRequestIds.value + request.requestId
                }
                .onFailure {
                    _errorMessage.value = it.message
                }
        }
    }

    fun dismissDeviceRequestPopup(requestId: String) {
        _dismissedRequestIds.value = _dismissedRequestIds.value + requestId
    }
}
