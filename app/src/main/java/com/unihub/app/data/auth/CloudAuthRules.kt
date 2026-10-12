package com.unihub.app.data.auth

import com.unihub.app.data.appwrite.AppwriteConfig
import com.unihub.app.data.appwrite.AppwriteFileProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Locale

/** Profile/display rules only. Password verification and session creation belong to Appwrite Account. */
object CloudAuthRules {
    const val MAX_PASSWORD_LENGTH = 256
    const val MAX_USERNAME_LENGTH = 320
    const val MAX_OFFLINE_SESSION_AGE_MS = 24L * 60 * 60 * 1_000
    private const val CACHE_SCHEMA = 4

    fun normalizeUsername(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    fun isValidEmail(raw: String): Boolean = raw.length <= MAX_USERNAME_LENGTH &&
        Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(raw.trim()) && raw.none(Char::isISOControl)

    fun computeDeviceFingerprint(androidId: String, manufacturer: String, model: String,
        brand: String = "", device: String = ""): String = AppwriteFileProtocol.sha256(
        "unihub-device-fp::" + listOf(androidId, manufacturer, model, brand, device)
            .joinToString("|") { it.trim().lowercase(Locale.ROOT) }
    )

    /** Only call with Account.get or the authenticated, server-side Users bridge response. */
    fun fromAppwriteUser(json: JSONObject): CloudUserAccount {
        val id = json.getString("\$id")
        val email = normalizeUsername(json.getString("email"))
        require(AppwriteFileProtocol.validId(id) && isValidEmail(email)) { "بيانات حساب Appwrite غير صالحة" }
        val labels = json.optJSONArray("labels") ?: JSONArray()
        val assigned = (0 until labels.length()).mapNotNull { labels.opt(it) as? String }.toSet()
        val admin = AppwriteConfig.ADMIN_LABEL in assigned
        return CloudUserAccount(
            username = email,
            displayName = json.optString("name").ifBlank { email },
            userId = id,
            isAdmin = admin,
            isActive = json.opt("status") == true,
            permissions = if (admin) UserPermissions.FULL else UserPermissions(
                AppwriteConfig.DOWNLOAD_LABEL in assigned,
                AppwriteConfig.UPLOAD_LABEL in assigned,
                AppwriteConfig.MODIFY_LABEL in assigned
            ),
            createdAt = timestamp(json.optString("\$createdAt")),
            updatedAt = timestamp(json.optString("\$updatedAt"))
        )
    }

    fun checkPermission(user: CloudUserAccount?, permission: AuthPermission): Result<Unit> = when {
        user == null -> Result.failure(IOException("يجب تسجيل الدخول أولاً للوصول إلى السحابة"))
        !user.isActive -> Result.failure(IOException("تم إيقاف الحساب في Appwrite"))
        !user.effectivePermissions.allows(permission) -> Result.failure(IOException("ليس لديك إذن لتنفيذ هذه العملية السحابية؛ تواصل مع المشرف"))
        else -> Result.success(Unit)
    }

    fun isCachedSessionFresh(verifiedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        verifiedAt > 0 && now >= verifiedAt && now - verifiedAt <= MAX_OFFLINE_SESSION_AGE_MS

    fun redactedRegistry(registry: CloudAuthRegistry, username: String, includeAllUsers: Boolean = false): CloudAuthRegistry =
        registry.copy(users = registry.users.filter { includeAllUsers || normalizeUsername(it.username) == normalizeUsername(username) })

    fun toJson(registry: CloudAuthRegistry): String = JSONObject().apply {
        put("schemaVersion", CACHE_SCHEMA)
        put("projectId", AppwriteConfig.AUTH_PROJECT_ID)
        put("updatedAt", registry.updatedAt)
        put("users", JSONArray().apply {
            registry.users.forEach { user ->
                put(JSONObject().apply {
                    put("\$id", user.userId); put("email", user.username); put("name", user.displayName)
                    put("status", user.isActive)
                    put("labels", JSONArray().apply {
                        if (user.isAdmin) put(AppwriteConfig.ADMIN_LABEL)
                        if (user.permissions.canDownload) put(AppwriteConfig.DOWNLOAD_LABEL)
                        if (user.permissions.canUpload) put(AppwriteConfig.UPLOAD_LABEL)
                        if (user.permissions.canModify) put(AppwriteConfig.MODIFY_LABEL)
                    })
                    put("\$createdAt", Instant.ofEpochMilli(user.createdAt).toString())
                    put("\$updatedAt", Instant.ofEpochMilli(user.updatedAt).toString())
                })
            }
        })
    }.toString()

    /** Old registry files are deliberately not accepted as Appwrite sessions. */
    fun fromJson(raw: String?): CloudAuthRegistry = runCatching {
        if (raw.isNullOrBlank()) return@runCatching CloudAuthRegistry()
        val json = JSONObject(raw)
        if (json.optInt("schemaVersion") != CACHE_SCHEMA || json.optString("projectId") != AppwriteConfig.AUTH_PROJECT_ID) {
            return@runCatching CloudAuthRegistry()
        }
        val users = json.optJSONArray("users") ?: JSONArray()
        require(users.length() <= 10_000)
        CloudAuthRegistry(schemaVersion = CACHE_SCHEMA, updatedAt = json.optLong("updatedAt"),
            users = (0 until users.length()).map { fromAppwriteUser(users.getJSONObject(it)) })
    }.getOrDefault(CloudAuthRegistry())

    private fun timestamp(raw: String): Long = runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
}
