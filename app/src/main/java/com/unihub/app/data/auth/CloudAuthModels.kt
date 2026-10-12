package com.unihub.app.data.auth

enum class AuthPermission { DOWNLOAD, UPLOAD, MODIFY }

data class UserPermissions(
    val canDownload: Boolean = false,
    val canUpload: Boolean = false,
    val canModify: Boolean = false
) {
    fun allows(permission: AuthPermission): Boolean = when (permission) {
        AuthPermission.DOWNLOAD -> canDownload
        AuthPermission.UPLOAD -> canUpload
        AuthPermission.MODIFY -> canModify
    }
    companion object {
        val NONE = UserPermissions()
        val FULL = UserPermissions(canDownload = true, canUpload = true, canModify = true)
    }
}

/** Local device information used to bind the offline profile cache, not a server authorization token. */
data class BoundDeviceInfo(
    val fingerprint: String,
    val deviceName: String,
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val boundAt: Long = 0L
) {
    val shortFingerprint: String get() = fingerprint.take(12).uppercase()
    val summaryLabel: String
        get() {
            val parts = listOf(manufacturer.trim(), model.trim()).filter { it.isNotBlank() }.distinct().joinToString(" ")
            val base = parts.ifBlank { deviceName.ifBlank { "جهاز أندرويد" } }
            val os = androidVersion.trim().takeIf { it.isNotBlank() }?.let { " (Android $it)" }.orEmpty()
            return "$base$os [$shortFingerprint]"
        }
}

/** Appwrite profile. username is the login email; no password or verifier is stored here. */
data class CloudUserAccount(
    val username: String,
    val displayName: String,
    val isAdmin: Boolean = false,
    val isActive: Boolean = true,
    val permissions: UserPermissions = UserPermissions.NONE,
    val boundDevice: BoundDeviceInfo? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val userId: String = ""
) {
    val effectivePermissions: UserPermissions
        get() = if (!isActive) UserPermissions.NONE else if (isAdmin) UserPermissions.FULL else permissions
}

/** In-memory/profile-cache view of Users responses. Never uploaded as a shared authentication registry. */
data class CloudAuthRegistry(
    val schemaVersion: Int = 4,
    val updatedAt: Long = 0L,
    val users: List<CloudUserAccount> = emptyList()
)

sealed interface AuthLoginOutcome {
    data class Authenticated(val user: CloudUserAccount, val registry: CloudAuthRegistry, val registryChanged: Boolean) : AuthLoginOutcome
}

sealed interface AuthSessionState {
    data object Initializing : AuthSessionState
    data class Unauthenticated(val message: String? = null) : AuthSessionState
    data class Authenticated(val user: CloudUserAccount, val currentDevice: BoundDeviceInfo) : AuthSessionState
}
