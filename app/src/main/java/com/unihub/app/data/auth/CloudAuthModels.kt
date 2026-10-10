package com.unihub.app.data.auth

/**
 * أنواع الصلاحيات السحابية التي يتحكم بها المشرف لكل مستخدم.
 */
enum class AuthPermission {
    /** صلاحية السحب والتنزيل من السحابة إلى الجهاز */
    DOWNLOAD,
    /** صلاحية رفع الملفات والمجلدات إلى السحابة */
    UPLOAD,
    /** صلاحية التعديل أو الحذف أو النقل أو إعادة التسمية في الحساب السحابي */
    MODIFY
}

/**
 * صلاحيات المستخدم الفردية كما يحددها المشرف.
 */
data class UserPermissions(
    val canDownload: Boolean = true,
    val canUpload: Boolean = true,
    val canModify: Boolean = true
) {
    fun allows(permission: AuthPermission): Boolean = when (permission) {
        AuthPermission.DOWNLOAD -> canDownload
        AuthPermission.UPLOAD -> canUpload
        AuthPermission.MODIFY -> canModify
    }

    companion object {
        val FULL = UserPermissions(canDownload = true, canUpload = true, canModify = true)
    }
}

/**
 * بصمة ومعلومات الجهاز المرتبط بحساب المستخدم في السحابة (جهاز واحد لكل حساب).
 */
data class BoundDeviceInfo(
    val fingerprint: String,
    val deviceName: String,
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val boundAt: Long = 0L
) {
    val shortFingerprint: String
        get() = fingerprint.take(12).uppercase()

    val summaryLabel: String
        get() {
            val parts = listOf(manufacturer.trim(), model.trim())
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
            val base = parts.ifBlank { deviceName.ifBlank { "جهاز أندرويد" } }
            val os = androidVersion.trim().takeIf { it.isNotBlank() }?.let { " (Android $it)" }.orEmpty()
            return "$base$os [$shortFingerprint]"
        }
}

/**
 * حالة طلب تسجيل الدخول من جهاز جديد (غريب).
 */
enum class DeviceApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}

/**
 * طلب موافقة يرسل للمشرف عندما يحاول مستخدم الدخول من جهاز مختلف عن جهازه المسجل في السحابة.
 */
data class DeviceChangeRequest(
    val requestId: String,
    val username: String,
    val currentBoundDevice: BoundDeviceInfo?,
    val requestedDevice: BoundDeviceInfo,
    val requestedAt: Long,
    val status: DeviceApprovalStatus = DeviceApprovalStatus.PENDING,
    val decidedAt: Long = 0L
)

/**
 * بيانات حساب مستخدم مخزنة في سجل المصادقة السحابي.
 * passwordHash هو مُتحقّق PBKDF2 مملّح، ولا توجد نسخة قابلة لفك التشفير من كلمة المرور.
 */
data class CloudUserAccount(
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val isAdmin: Boolean = false,
    val isActive: Boolean = true,
    val permissions: UserPermissions = UserPermissions.FULL,
    val boundDevice: BoundDeviceInfo? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val effectivePermissions: UserPermissions
        get() = if (isAdmin) UserPermissions.FULL else permissions
}

/**
 * وثيقة سجل المصادقة الكاملة المخزنة في السحابة (unihub_auth_registry.json).
 */
data class CloudAuthRegistry(
    val schemaVersion: Int = 1,
    val updatedAt: Long = 0L,
    val users: List<CloudUserAccount> = emptyList(),
    val deviceRequests: List<DeviceChangeRequest> = emptyList()
) {
    val pendingDeviceRequests: List<DeviceChangeRequest>
        get() = deviceRequests.filter { it.status == DeviceApprovalStatus.PENDING }
}

/**
 * نتيجة تقييم عملية تسجيل الدخول عبر السحابة.
 */
sealed interface AuthLoginOutcome {
    data class Authenticated(
        val user: CloudUserAccount,
        val registry: CloudAuthRegistry,
        val registryChanged: Boolean
    ) : AuthLoginOutcome

    data class PendingAdminApproval(
        val username: String,
        val message: String,
        val request: DeviceChangeRequest,
        val registry: CloudAuthRegistry
    ) : AuthLoginOutcome

    data class Rejected(
        val reason: String
    ) : AuthLoginOutcome
}

/**
 * نتيجة فحص حالة طلب الجهاز المعلق عند انتظار رد المشرف.
 */
sealed interface PendingApprovalCheckOutcome {
    data class Approved(val user: CloudUserAccount) : PendingApprovalCheckOutcome
    data class StillPending(val message: String, val request: DeviceChangeRequest) : PendingApprovalCheckOutcome
    data class Rejected(val reason: String) : PendingApprovalCheckOutcome
}

/**
 * الحالة الحية لجلسة المصادقة في التطبيق.
 */
sealed interface AuthSessionState {
    data object Initializing : AuthSessionState

    data class Unauthenticated(
        val message: String? = null
    ) : AuthSessionState

    data class WaitingAdminApproval(
        val username: String,
        val requestedDevice: BoundDeviceInfo,
        val message: String = "سيرد لك مشرف",
        val statusNote: String? = null
    ) : AuthSessionState

    data class Authenticated(
        val user: CloudUserAccount,
        val currentDevice: BoundDeviceInfo,
        val pendingAdminRequests: List<DeviceChangeRequest> = emptyList()
    ) : AuthSessionState
}
