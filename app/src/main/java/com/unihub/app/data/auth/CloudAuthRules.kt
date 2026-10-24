package com.unihub.app.data.auth

import com.unihub.app.data.cloud.CloudFileProtocol
import com.unihub.app.data.supabase.SupabaseConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Locale

/**
 * قواعد الحساب والصلاحيات.
 *
 * المصدر الوحيد لاشتقاق `CloudUserAccount` من رد الخادم. كان الرد من Appwrite
 * (labels على الحساب)؛ صار من `public.profiles` في Supabase (أعمدة صلاحية).
 * **شكل `CloudUserAccount` لم يتغيّر** — فشاشات إدارة المستخدمين وواجهة
 * الصلاحيات لم تُمسّ.
 */
object CloudAuthRules {
    const val MAX_PASSWORD_LENGTH = 256
    const val MAX_USERNAME_LENGTH = 320
    const val MAX_OFFLINE_SESSION_AGE_MS = 24L * 60 * 60 * 1_000
    private const val CACHE_SCHEMA = 5

    fun normalizeUsername(raw: String): String = raw.trim().lowercase(Locale.ROOT)
    fun isValidEmail(raw: String): Boolean = raw.length <= MAX_USERNAME_LENGTH &&
        Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(raw.trim()) && raw.none(Char::isISOControl)

    fun computeDeviceFingerprint(androidId: String, manufacturer: String, model: String,
        brand: String = "", device: String = ""): String = CloudFileProtocol.sha256(
        "unihub-device-fp::" + listOf(androidId, manufacturer, model, brand, device)
            .joinToString("|") { it.trim().lowercase(Locale.ROOT) }
    )

    /**
     * يبني الحساب من صف `public.profiles`.
     *
     * المشرف يأخذ الصلاحيات كاملة كما كان في Appwrite (label «admin»).
     */
    fun fromSupabaseProfile(json: JSONObject): CloudUserAccount {
        val id = json.optString("id")
        val email = normalizeUsername(json.optString("email"))
        require(id.isNotBlank() && isValidEmail(email)) { "بيانات الحساب غير صالحة" }
        val admin = json.optBoolean("is_admin", false)
        // تسلسل الصلاحيات نفسه في القاعدة: تعديل⇒رفع⇒قراءة.
        // (public.effective_can_* في القاعدة، وadmin_set_permissions يمنع تخزين
        //  تركيبة غير متسلسلة — والتطبيق يطبّق التسلسل نفسه عند العرض.)
        val canDownload = admin || json.optBoolean("can_download", false)
        val canUpload = admin || (json.optBoolean("can_upload", false) && canDownload)
        val canModify = admin || (json.optBoolean("can_modify", false) && canUpload && canDownload)
        return CloudUserAccount(
            username = email,
            displayName = json.optString("display_name").ifBlank { email },
            userId = id,
            isAdmin = admin,
            isActive = json.optBoolean("is_active", true),
            permissions = if (admin) UserPermissions.FULL else UserPermissions(
                canDownload = canDownload,
                canUpload = canUpload,
                canModify = canModify
            ),
            createdAt = timestamp(json.optString("created_at")),
            updatedAt = timestamp(json.optString("updated_at"))
        )
    }

    fun checkPermission(user: CloudUserAccount?, permission: AuthPermission): Result<Unit> = when {
        user == null -> Result.failure(IOException("يجب تسجيل الدخول أولاً للوصول إلى السحابة"))
        !user.isActive -> Result.failure(IOException("تم إيقاف الحساب"))
        !user.effectivePermissions.allows(permission) -> Result.failure(IOException("ليس لديك إذن لتنفيذ هذه العملية السحابية؛ تواصل مع المشرف"))
        else -> Result.success(Unit)
    }

    fun isCachedSessionFresh(verifiedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        verifiedAt > 0 && now >= verifiedAt && now - verifiedAt <= MAX_OFFLINE_SESSION_AGE_MS

    fun redactedRegistry(registry: CloudAuthRegistry, username: String, includeAllUsers: Boolean = false): CloudAuthRegistry =
        registry.copy(users = registry.users.filter { includeAllUsers || normalizeUsername(it.username) == normalizeUsername(username) })

    fun toJson(registry: CloudAuthRegistry): String = JSONObject().apply {
        put("schemaVersion", CACHE_SCHEMA)
        put("projectId", SupabaseConfig.PROJECT_URL)
        put("updatedAt", registry.updatedAt)
        put("users", JSONArray().apply {
            registry.users.forEach { user ->
                // أسماء الأعمدة نفسها التي يقرأها fromSupabaseProfile — مصدر واحد
                // للقراءة والكتابة، فلا تفترق الذاكرة عن صف الخادم.
                put(JSONObject().apply {
                    put("id", user.userId); put("email", user.username)
                    put("display_name", user.displayName)
                    put("is_active", user.isActive)
                    put("is_admin", user.isAdmin)
                    put("can_download", user.permissions.canDownload)
                    put("can_upload", user.permissions.canUpload)
                    put("can_modify", user.permissions.canModify)
                    put("created_at", Instant.ofEpochMilli(user.createdAt).toString())
                    put("updated_at", Instant.ofEpochMilli(user.updatedAt).toString())
                })
            }
        })
    }.toString()

    /** ذاكرة_profile من مزوّد أقدم لا تُقبل كجلسة سحابية. */
    fun fromJson(raw: String?): CloudAuthRegistry = runCatching {
        if (raw.isNullOrBlank()) return@runCatching CloudAuthRegistry()
        val json = JSONObject(raw)
        if (json.optInt("schemaVersion") != CACHE_SCHEMA || json.optString("projectId") != SupabaseConfig.PROJECT_URL) {
            return@runCatching CloudAuthRegistry()
        }
        val users = json.optJSONArray("users") ?: JSONArray()
        require(users.length() <= 10_000)
        CloudAuthRegistry(schemaVersion = CACHE_SCHEMA, updatedAt = json.optLong("updatedAt"),
            users = (0 until users.length()).map { fromSupabaseProfile(users.getJSONObject(it)) })
    }.getOrDefault(CloudAuthRegistry())

    private fun timestamp(raw: String): Long = runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
}
