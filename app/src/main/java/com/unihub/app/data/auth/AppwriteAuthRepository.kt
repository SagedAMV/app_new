package com.unihub.app.data.auth

import com.unihub.app.data.appwrite.AppwriteSdkGate
import com.unihub.app.data.appwrite.AppwriteAuthBridge
import com.unihub.app.di.AuthAppwrite
import io.appwrite.exceptions.AppwriteException
import io.appwrite.models.User
import io.appwrite.services.Account
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class AppwriteSessionInvalidException(message: String) : IOException(message)

/** All account/user requests terminate in the Users & Auth project, never in the file bucket. */
@Singleton
class AppwriteAuthRepository @Inject constructor(
    @AuthAppwrite private val account: Account,
    private val bridge: AppwriteAuthBridge,
    private val sdkGate: AppwriteSdkGate
) {
    private val sessionLock = Any()
    @Volatile var sessionVersion: Long = 0L
        private set
    @Volatile var authenticatedUserId: String? = null
        private set

    fun invalidateSession(): Long = synchronized(sessionLock) {
        authenticatedUserId = null
        ++sessionVersion
    }

    fun isCurrent(version: Long, userId: String): Boolean = synchronized(sessionLock) {
        sessionVersion == version && authenticatedUserId == userId
    }

    fun restoreOfflineIdentity(user: CloudUserAccount) = synchronized(sessionLock) {
        authenticatedUserId = user.userId
    }

    suspend fun login(email: String, password: String): CloudUserAccount {
        require(CloudAuthRules.isValidEmail(email)) { "أدخل بريد حساب Appwrite الإلكتروني" }
        require(password.isNotEmpty() && password.length <= CloudAuthRules.MAX_PASSWORD_LENGTH)
        val version = invalidateSession()
        val remote = sdkGate.await {
            // Finish old SDK cookie writes before replacing the active session.
            try { account.deleteSession(sessionId = "current") }
            catch (error: AppwriteException) { if (error.code != 401 && error.code != 404) throw error }
            val session = account.createEmailPasswordSession(email = CloudAuthRules.normalizeUsername(email), password = password)
            account.get().also {
                if (it.id != session.userId || CloudAuthRules.normalizeUsername(it.email) != CloudAuthRules.normalizeUsername(email)) {
                    throw AppwriteSessionInvalidException("الحساب المعاد لا يطابق محاولة الدخول")
                }
            }
        }
        return accept(remote, version)
    }

    suspend fun currentUser(expectedUserId: String? = authenticatedUserId): CloudUserAccount {
        val version = sessionVersion
        val remote = sdkGate.await { account.get() }
        if (expectedUserId != null && expectedUserId != remote.id) throw AppwriteSessionInvalidException("تغيّر حساب الجلسة؛ سجّل الدخول مجددًا")
        return accept(remote, version)
    }

    private fun accept(remote: User<Map<String, Any>>, version: Long): CloudUserAccount {
        // Explicit allow-list: never call User.toMap(), which also contains sensitive server fields.
        val user = CloudAuthRules.fromAppwriteUser(JSONObject().apply {
            put("\$id", remote.id); put("email", remote.email); put("name", remote.name)
            put("status", remote.status); put("labels", JSONArray(remote.labels))
            put("\$createdAt", remote.createdAt); put("\$updatedAt", remote.updatedAt)
        })
        if (!user.isActive) throw AppwriteSessionInvalidException("الحساب موقوف في Appwrite")
        synchronized(sessionLock) {
            if (version != sessionVersion) throw IOException("أُلغيت الجلسة أثناء العملية")
            authenticatedUserId = user.userId
        }
        return user
    }

    /** Local invalidation is done before any potentially failing network operation. */
    suspend fun deleteRemoteSession() {
        sdkGate.await {
            try { account.deleteSession(sessionId = "current") }
            catch (error: AppwriteException) { if (error.code != 401 && error.code != 404) throw error }
        }
    }

    suspend fun listUsers(): List<CloudUserAccount> {
        val users = linkedMapOf<String, CloudUserAccount>()
        val seen = hashSetOf<String>()
        var cursor = ""
        do {
            val response = executeBridge("listUsers", JSONObject().put("cursor", cursor))
            val page = response.getJSONArray("users")
            for (index in 0 until page.length()) {
                val user = CloudAuthRules.fromAppwriteUser(page.getJSONObject(index))
                users[user.userId] = user
            }
            cursor = response.optString("cursor")
            if (cursor.isNotBlank() && !seen.add(cursor)) throw IOException("تكرر مؤشر صفحة المستخدمين")
            if (users.size > 10_000) throw IOException("قائمة المستخدمين تجاوزت حد الأمان")
        } while (cursor.isNotBlank())
        return users.values.toList()
    }

    suspend fun addUser(email: String, password: String, permissions: UserPermissions) {
        executeBridge("createUser", JSONObject().put("email", CloudAuthRules.normalizeUsername(email))
            .put("password", password).put("permissions", permissionsJson(permissions)))
    }

    suspend fun deleteUser(id: String) { executeBridge("deleteUser", JSONObject().put("userId", id)) }
    suspend fun setUserActive(id: String, active: Boolean) {
        executeBridge("setUserActive", JSONObject().put("userId", id).put("active", active))
    }
    suspend fun updatePermissions(id: String, permissions: UserPermissions) {
        executeBridge("setPermissions", JSONObject().put("userId", id).put("permissions", permissionsJson(permissions)))
    }
    suspend fun changeUserPassword(id: String, password: String) {
        executeBridge("setPassword", JSONObject().put("userId", id).put("password", password))
    }
    suspend fun revokeUserSessions(id: String) {
        executeBridge("revokeSessions", JSONObject().put("userId", id))
    }
    suspend fun changeOwnPassword(currentPassword: String, newPassword: String) {
        require(newPassword.length in 10..CloudAuthRules.MAX_PASSWORD_LENGTH) { "كلمة المرور يجب أن تكون من 10 إلى 256 حرفًا" }
        sdkGate.await { account.updatePassword(password = newPassword, oldPassword = currentPassword) }
    }

    private suspend fun executeBridge(operation: String, payload: JSONObject = JSONObject()): JSONObject {
        val version = sessionVersion
        val userId = authenticatedUserId ?: throw AppwriteSessionInvalidException("لا توجد جلسة موثقة")
        return bridge.execute(operation, payload) {
            if (!isCurrent(version, userId)) throw AppwriteSessionInvalidException("انتهت الجلسة قبل إرسال الطلب")
        }
    }

    private fun permissionsJson(value: UserPermissions) = JSONObject()
        .put("canDownload", value.canDownload).put("canUpload", value.canUpload).put("canModify", value.canModify)
}
