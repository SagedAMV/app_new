package com.unihub.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.auth.CloudAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(private val authManager: CloudAuthManager) : ViewModel() {
    val sessionState = authManager.sessionState
    private val submitting = MutableStateFlow(false)
    val isBusy = combine(authManager.isBusy, submitting) { auth, login -> auth || login }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _sessionMessageDismissed = MutableStateFlow(false)
    val sessionMessageDismissed = _sessionMessageDismissed.asStateFlow()

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
        _sessionMessageDismissed.value = true
    }

    fun login(email: String, password: String) {
        if (submitting.value || authManager.isBusy.value) return
        clearMessages()
        AuthInteractionRules.inputError(email, password)?.let { _errorMessage.value = it; return }
        submitting.value = true
        viewModelScope.launch {
            try {
                authManager.login(email, password).onFailure {
                    _errorMessage.value = it.message ?: "تعذّر تسجيل الدخول إلى Appwrite"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _errorMessage.value = "تعذّر الاتصال بمشروع المستخدمين"
            } finally { submitting.value = false }
        }
    }

    fun retryInitialization() {
        if (submitting.value || authManager.isBusy.value) return
        clearMessages()
        _sessionMessageDismissed.value = false
        viewModelScope.launch { authManager.retryInitialization() }
    }
}
