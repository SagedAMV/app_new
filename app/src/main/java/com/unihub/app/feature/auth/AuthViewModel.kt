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
                // إصلاح 2026-10-25: كانت هذه الدالة تستبدل أي سبب فشل حقيقي
                // (بريد/كلمة مرور خاطئة، لا إنترنت، حساب موقوف، محاولات كثيرة...)
                // برسالة عامة ثابتة واحدة، فلا يعرف المستخدم أبدًا سبب العطل
                // الحقيقي ولا كيف يصلحه. الرسائل العربية الدقيقة مبنية فعلاً في
                // طبقة البيانات (CloudAuthManager/SupabaseAuthRepository) — تُعرض الآن.
                authManager.login(email, password).onFailure {
                    _errorMessage.value = it.message?.takeIf(String::isNotBlank) ?: "تعذّر تسجيل الدخول"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errorMessage.value = error.message?.takeIf(String::isNotBlank) ?: "تعذّر اتصال المستخدمين"
            } finally { submitting.value = false }
        }
    }

    /**
     * تسجيل حساب عادي ذاتيًا — هذه هي واجهة "تسجيل دخول طبيعي" غير المرتبطة
     * بمسار إنشاء الحساب الإداري المقيَّد بمشرف. متاحة لأي مستخدم، في أي وقت،
     * بلا شرط "أول استخدام". عند النجاح يدخل المستخدم تلقائيًا (لا حاجة لخطوة
     * دخول منفصلة بعدها).
     */
    fun register(email: String, password: String, confirmPassword: String) {
        if (submitting.value || authManager.isBusy.value) return
        clearMessages()
        AuthInteractionRules.inputError(email, password)?.let { _errorMessage.value = it; return }
        if (password.length < 10) { _errorMessage.value = "كلمة المرور يجب أن تكون 10 خانات على الأقل"; return }
        if (password != confirmPassword) { _errorMessage.value = "كلمتا المرور غير متطابقتين"; return }
        submitting.value = true
        viewModelScope.launch {
            try {
                authManager.register(email, password).onFailure {
                    _errorMessage.value = it.message?.takeIf(String::isNotBlank) ?: "تعذّر إنشاء الحساب"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errorMessage.value = error.message?.takeIf(String::isNotBlank) ?: "تعذّر اتصال المستخدمين"
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
