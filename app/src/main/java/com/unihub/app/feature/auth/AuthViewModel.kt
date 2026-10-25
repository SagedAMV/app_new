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
     * تمهيد أول حساب مشرف لمشروع Supabase فارغ (راجع تحليل السبب الجذري في
     * مراجعة.md: بلا هذا المسار لا توجد أي طريقة لإنشاء أول حساب يُدخل به
     * أي شخص إلى التطبيق). عند نجاح الإنشاء نكمل تلقائيًا بتسجيل الدخول
     * بنفس البيانات — فيمرّ المستخدم بمسار الدخول الاعتيادي نفسه مباشرة.
     */
    fun bootstrapFirstAdmin(email: String, password: String, confirmPassword: String) {
        if (submitting.value || authManager.isBusy.value) return
        clearMessages()
        AuthInteractionRules.inputError(email, password)?.let { _errorMessage.value = it; return }
        if (password != confirmPassword) { _errorMessage.value = "كلمتا المرور غير متطابقتين"; return }
        submitting.value = true
        viewModelScope.launch {
            try {
                authManager.bootstrapFirstAdmin(email, password)
                    .onSuccess { authManager.login(email, password) }
                    .onFailure { _errorMessage.value = it.message?.takeIf(String::isNotBlank) ?: "تعذّر إعداد حساب المشرف" }
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
