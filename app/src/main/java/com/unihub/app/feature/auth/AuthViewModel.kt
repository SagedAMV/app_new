package com.unihub.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.auth.AuthLoginOutcome
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.auth.DeviceChangeRequest
import com.unihub.app.data.auth.PendingApprovalCheckOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: CloudAuthManager
) : ViewModel() {

    val sessionState: StateFlow<AuthSessionState> = authManager.sessionState
    val registry = authManager.registryState
    val isBusy: StateFlow<Boolean> = authManager.isBusy

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
