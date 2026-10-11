package com.unihub.app.feature.auth

import com.unihub.app.data.auth.CloudAuthRules
import kotlinx.coroutines.sync.Mutex

internal class PendingCheckGate {
    private val mutex = Mutex()
    suspend fun <T> runIfIdle(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try { block() } finally { mutex.unlock() }
    }
}

internal object AuthInteractionRules {
    fun inputError(username: String, password: String): String? = when {
        username.isBlank() || password.isBlank() -> "أدخل اسم المستخدم وكلمة المرور"
        username.length > CloudAuthRules.MAX_USERNAME_LENGTH -> "اسم المستخدم أطول من الحد المسموح"
        password.length > CloudAuthRules.MAX_PASSWORD_LENGTH -> "كلمة المرور أطول من الحد المسموح"
        else -> null
    }
}

/** A new instance belongs to one STARTED lifecycle interval. No jobs are launched by this policy. */
internal class ApprovalPollingBackoff {
    private var healthyChecks = 0
    private var failures = 0

    fun nextDelayMs(succeeded: Boolean): Long {
        if (succeeded) {
            failures = 0
            healthyChecks = (healthyChecks + 1).coerceAtMost(3)
            return when (healthyChecks) { 1 -> 15_000L; 2 -> 30_000L; else -> 60_000L }
        }
        failures = (failures + 1).coerceAtMost(4)
        return (15_000L * (1L shl failures)).coerceAtMost(120_000L)
    }
}
