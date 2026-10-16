package com.unihub.app.feature.auth

import com.unihub.app.data.auth.CloudAuthRules

internal object AuthInteractionRules {
    fun inputError(username: String, password: String): String? = when {
        username.isBlank() || password.isBlank() -> "أكمل بيانات الدخول"
        username.length > CloudAuthRules.MAX_USERNAME_LENGTH -> "البريد طويل جدًا"
        !CloudAuthRules.isValidEmail(username) -> "بريد غير صالح"
        password.length > CloudAuthRules.MAX_PASSWORD_LENGTH -> "كلمة المرور طويلة"
        else -> null
    }
}
