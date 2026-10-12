package com.unihub.app.feature.auth

import com.unihub.app.data.auth.CloudAuthRules

internal object AuthInteractionRules {
    fun inputError(username: String, password: String): String? = when {
        username.isBlank() || password.isBlank() -> "أدخل البريد الإلكتروني وكلمة المرور"
        username.length > CloudAuthRules.MAX_USERNAME_LENGTH -> "البريد الإلكتروني أطول من الحد المسموح"
        !CloudAuthRules.isValidEmail(username) -> "أدخل بريدًا إلكترونيًا صحيحًا"
        password.length > CloudAuthRules.MAX_PASSWORD_LENGTH -> "كلمة المرور أطول من الحد المسموح"
        else -> null
    }
}
