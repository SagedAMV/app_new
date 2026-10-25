package com.unihub.app.data.supabase

import com.unihub.app.data.auth.CloudAuthRules
import com.unihub.app.data.auth.CloudUserAccount
import com.unihub.app.data.auth.UserPermissions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** جلسة غير صالحة ⇒ يجب مسح الجلسة المحلية وطلب دخول جديد. */
class SupabaseSessionInvalidException(message: String) : IOException(message)

/**
 * بديل `AppwriteAuthRepository` بسطح الدوال نفسه، فلم يتغيّر `CloudAuthManager`
 * إلا في الأنواع. الاختلافات الجوهرية مقارنةً بـ Appwrite:
 *
 *  1) **لا جسر ولا مفتاح إداري**: إدارة المستخدمين دوال `SECURITY DEFINER`
 *     داخل القاعدة، والعميل يمرّر **رمز المشرف** فقط.
 *  2) **رمز واحد لكل شيء**: في Appwrite كان رمز الحساب لا يصل إلى مشروع
 *     الملفات (لأن مشروعين منفصلين) ⇒ جسر + رمز تخزين + هوية ظل.
 *     في Supabase رمز المستخدم نفسه هو التفويض، وRLS هي التنفيذ.
 *  3) **الصلاحيات أعمدة** في `public.profiles` بدل labels على حساب Appwrite.
 */
@Singleton
class SupabaseAuthRepository @Inject constructor(
    private val http: SupabaseHttp
) {
    private val sessionLock = Any()

    @Volatile private var accessToken: String = ""
    @Volatile private var refreshToken: String = ""
    @Volatile var authenticatedUserId: String = ""
    @Volatile var sessionVersion: Long = 0

    fun currentAccessToken(): String = accessToken
    fun currentRefreshToken(): String = refreshToken

    /** يبطل الجلسة الحالية محليًا ويُرجع نسخة جديدة من رقمها. */
    fun invalidateSession(): Long = synchronized(sessionLock) {
        accessToken = ""
        refreshToken = ""
        authenticatedUserId = ""
        sessionVersion += 1
        sessionVersion
    }

    fun isCurrent(version: Long, userId: String): Boolean = synchronized(sessionLock) {
        version == sessionVersion && userId.isNotBlank() && userId == authenticatedUserId
    }

    /** استعادة هوية محلية بلا اتصال (لا تفوّض أي عملية سحابية). */
    fun restoreOfflineIdentity(user: CloudUserAccount) = synchronized(sessionLock) {
        authenticatedUserId = user.userId
    }

    /** استعادة الرموز المحفوظة في خزنة الجهاز بعد إعادة تشغيل التطبيق. */
    fun restoreTokens(access: String, refresh: String) = synchronized(sessionLock) {
        if (access.isNotBlank()) accessToken = access
        if (refresh.isNotBlank()) refreshToken = refresh
    }

    suspend fun login(email: String, password: String): CloudUserAccount {
        require(CloudAuthRules.isValidEmail(email)) { "أدخل بريد حسابك الإلكتروني" }
        require(password.isNotEmpty() && password.length <= CloudAuthRules.MAX_PASSWORD_LENGTH)
        val version = invalidateSession()
        val body = JSONObject()
            .put("email", CloudAuthRules.normalizeUsername(email))
            .put("password", password)
            .toString()
        val response = try {
            http.requestText("POST", "${SupabaseConfig.AUTH_PATH}/token?grant_type=password", body = body)
        } catch (error: SupabaseApiException) {
            throw SupabaseSessionInvalidException(authMessage(error))
        }
        val json = JSONObject(response)
        val token = json.optString("access_token")
        if (token.isBlank()) throw SupabaseSessionInvalidException("تعذّر توثيق الحساب؛ تحقق من البريد وكلمة المرور")
        synchronized(sessionLock) {
            accessToken = token
            refreshToken = json.optString("refresh_token")
        }
        val userId = json.optJSONObject("user")?.optString("id").orEmpty()
        if (userId.isBlank()) throw SupabaseSessionInvalidException("رد المصادقة لم يحتوِ معرّف حساب")
        val profile = fetchProfile(token, userId)
        return accept(profile, version)
    }

    /**
     * تمهيد أول حساب مشرف (مشروع جديد بلا مشرف بعد — راجع التعليق في
     * `SupabaseConfig.RPC_BOOTSTRAP_FIRST_ADMIN`). يُنفَّذ **بلا رمز مستخدم**
     * (مفتاح النشر وحده) لأنه يسبق وجود أي حساب أصلًا. القاعدة هي الحكم
     * الوحيد: ترفض الدالة نفسها بمجرد وجود مشرف نشِط واحد (`already_initialized`).
     * لا يُغيَّر أي شيء في حالة الجلسة هنا — المستدعي يُكمل بـ [login] العادية
     * بنفس البريد وكلمة المرور بعد نجاح هذه الدالة.
     */
    suspend fun bootstrapFirstAdmin(email: String, password: String) {
        require(CloudAuthRules.isValidEmail(email)) { "أدخل بريدًا إلكترونيًا صحيحًا لحسابك" }
        require(password.length in 10..CloudAuthRules.MAX_PASSWORD_LENGTH) { "كلمة المرور يجب أن تكون من 10 إلى 256 حرفًا" }
        val body = JSONObject()
            .put("target_email", CloudAuthRules.normalizeUsername(email))
            .put("target_password", password)
            .toString()
        try {
            http.requestText(
                "POST",
                "${SupabaseConfig.REST_PATH}/rpc/${SupabaseConfig.RPC_BOOTSTRAP_FIRST_ADMIN}",
                token = null,
                body = body
            )
        } catch (error: SupabaseApiException) {
            throw SupabaseAdminException(SupabaseAdminErrors.messageFor(error))
        }
    }

    suspend fun currentUser(expectedUserId: String? = authenticatedUserId): CloudUserAccount {
        val version = sessionVersion
        val token = validAccessToken()
        val userId = expectedUserId ?: authenticatedUserId
            ?: throw SupabaseSessionInvalidException("لا توجد جلسة موثقة")
        val profile = fetchProfile(token, userId)
        return accept(profile, version)
    }

    /** رمز وصول صالح (يجدّده تلقائيًا قبل انتهائه) — تستعمله طبقة التخزين. */
    suspend fun validAccessToken(): String {
        val current = accessToken
        if (current.isNotBlank() && !isExpiring(current)) return current
        val refresh = refreshToken
        if (refresh.isBlank()) throw SupabaseSessionInvalidException("انتهت الجلسة؛ سجّل الدخول من جديد")
        val body = JSONObject().put("refresh_token", refresh).toString()
        val response = try {
            http.requestText("POST", "${SupabaseConfig.AUTH_PATH}/token?grant_type=refresh_token", body = body)
        } catch (error: SupabaseApiException) {
            throw SupabaseSessionInvalidException(authMessage(error))
        }
        val json = JSONObject(response)
        val renewed = json.optString("access_token")
        if (renewed.isBlank()) throw SupabaseSessionInvalidException("انتهت الجلسة؛ سجّل الدخول من جديد")
        synchronized(sessionLock) {
            accessToken = renewed
            val rotated = json.optString("refresh_token")
            if (rotated.isNotBlank()) refreshToken = rotated
        }
        return renewed
    }

    /** ينتهي الرمز قبل دقيقة من الانتهاء — مهلة أمان لتفادي الانقطاع أثناء الطلب. */
    private fun isExpiring(token: String): Boolean {
        val expiry = expiryEpochSeconds(token) ?: return true
        return System.currentTimeMillis() / 1000L >= expiry - 60L
    }

    private fun expiryEpochSeconds(token: String): Long? = try {
        val payload = token.split('.').getOrNull(1) ?: return null
        val decoded = Base64.getUrlDecoder().decode(payload)
        JSONObject(String(decoded, Charsets.UTF_8)).optLong("exp").takeIf { it > 0L }
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchProfile(token: String, userId: String): CloudUserAccount {
        val body = http.requestText(
            "GET",
            "${SupabaseConfig.REST_PATH}/${SupabaseConfig.TABLE_PROFILES}" +
                "?select=id,email,display_name,is_admin,is_active,can_download,can_upload,can_modify,created_at,updated_at" +
                "&id=eq.$userId",
            token = token
        )
        val rows = JSONArray(body)
        if (rows.length() == 0) throw SupabaseSessionInvalidException("لا يوجد ملف حساب لهذا المستخدم")
        return CloudAuthRules.fromSupabaseProfile(rows.getJSONObject(0))
    }

    private fun accept(remote: CloudUserAccount, version: Long): CloudUserAccount {
        if (!remote.isActive) throw SupabaseSessionInvalidException("الحساب موقوف")
        synchronized(sessionLock) {
            if (version != sessionVersion) throw IOException("أُلغيت الجلسة أثناء العملية")
            authenticatedUserId = remote.userId
        }
        return remote
    }

    /** إنهاء الجلسة على الخادم — يُتجاهل الفشل حتى لا يمنع الخروج المحلي. */
    suspend fun deleteRemoteSession() {
        val token = accessToken
        if (token.isBlank()) return
        try {
            http.requestText("POST", "${SupabaseConfig.AUTH_PATH}/logout", token = token)
        } catch (_: SupabaseApiException) {
            // الجلسة المحلية تُمسح على أي حال.
        }
    }

    suspend fun listUsers(): List<CloudUserAccount> {
        val token = validAccessToken()
        val body = http.requestText(
            "POST",
            "${SupabaseConfig.REST_PATH}/rpc/${SupabaseConfig.RPC_LIST_USERS}",
            token = token,
            body = "{}"
        )
        val rows = JSONArray(body)
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { CloudAuthRules.fromSupabaseProfile(it) }
        }
    }

    suspend fun addUser(email: String, password: String, permissions: UserPermissions) {
        val body = JSONObject()
            .put("target_email", CloudAuthRules.normalizeUsername(email))
            .put("target_password", password)
            .put("download", permissions.canDownload)
            .put("upload", permissions.canUpload)
            .put("modify", permissions.canModify)
            .toString()
        call(SupabaseConfig.RPC_CREATE_USER, body)
    }

    suspend fun deleteUser(id: String) = call(SupabaseConfig.RPC_DELETE_USER, JSONObject().put("target", id).toString())

    suspend fun setUserActive(id: String, active: Boolean) =
        call(SupabaseConfig.RPC_SET_ACTIVE, JSONObject().put("target", id).put("active", active).toString())

    suspend fun updatePermissions(id: String, permissions: UserPermissions) {
        val body = JSONObject()
            .put("target", id)
            .put("download", permissions.canDownload)
            .put("upload", permissions.canUpload)
            .put("modify", permissions.canModify)
            .toString()
        call(SupabaseConfig.RPC_SET_PERMISSIONS, body)
    }

    suspend fun changeUserPassword(id: String, password: String) =
        call(SupabaseConfig.RPC_SET_PASSWORD, JSONObject().put("target", id).put("new_password", password).toString())

    suspend fun revokeUserSessions(id: String) =
        call(SupabaseConfig.RPC_REVOKE_SESSIONS, JSONObject().put("target", id).toString())

    /** تغيير كلمة مرور المستخدم نفسه — يتحقق الخادم من الكلمة الحالية. */
    suspend fun changeOwnPassword(currentPassword: String, newPassword: String) {
        require(newPassword.length in 10..CloudAuthRules.MAX_PASSWORD_LENGTH) { "كلمة المرور يجب أن تكون من 10 إلى 256 حرفًا" }
        val token = validAccessToken()
        val body = JSONObject().put("password", newPassword).put("current_password", currentPassword).toString()
        try {
            http.requestText("PUT", "${SupabaseConfig.AUTH_PATH}/user", token = token, body = body)
        } catch (error: SupabaseApiException) {
            val message = if (error.status == 422 || error.status == 400) {
                "كلمة المرور الحالية غير صحيحة أو الكلمة الجديدة غير مقبولة"
            } else authMessage(error)
            throw SupabaseAdminException(message)
        }
    }

    /**
     * نداء دالة إدارية داخل القاعدة.
     *
     * **لا يُرسل أي مفتاح إداري**: الطلب يحمل رمز المستخدم نفسه، ودالة
     * `SECURITY DEFINER` تتحقق داخل القاعدة أن `auth.uid()` مشرف.
     * أي أن الطلب بلا رمز مستخدم = دور anon = رفض 403.
     */
    private suspend fun call(function: String, body: String) {
        val token = validAccessToken()
        try {
            http.requestText("POST", "${SupabaseConfig.REST_PATH}/rpc/$function", token = token, body = body)
        } catch (error: SupabaseApiException) {
            throw SupabaseAdminException(SupabaseAdminErrors.messageFor(error))
        }
    }

    private fun authMessage(error: SupabaseApiException): String {
        val detail = error.detail.lowercase()
        return when {
            error.status == 400 && ("email not confirmed" in detail || "email_not_confirmed" in detail) ->
                "البريد الإلكتروني غير مؤكد؛ افتح رسالة التأكيد ثم أعد المحاولة"
            error.status == 400 && ("invalid login credentials" in detail || "invalid_credentials" in detail) ->
                "تعذّر توثيق الحساب؛ تحقق من البريد وكلمة المرور"
            error.status == 400 -> "تعذّر تسجيل الدخول؛ تحقق من بيانات الحساب ثم أعد المحاولة"
            error.status == 401 || error.status == 403 -> "انتهت الجلسة أو رُفض الدخول"
            error.status == 429 -> "محاولات كثيرة؛ انتظر قليلًا ثم أعد المحاولة"
            else -> SupabaseAdminErrors.messageFor(error)
        }
    }
}
