package com.unihub.app.data.appwrite

import com.unihub.app.data.auth.AppwriteAuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Kept in memory only; never serialized to preferences, logs, QR codes, or crash messages. */
class StorageGrant(
    val jwt: String,
    val authUserId: String,
    val sessionVersion: Long,
    val refreshAtNanos: Long
) {
    override fun toString(): String = "StorageGrant(jwt=<redacted>)"

    companion object {
        fun fromResponse(json: JSONObject, expectedUserId: String, version: Long, nowNanos: Long): StorageGrant {
            require(json.getString("projectId") == AppwriteConfig.STORAGE_PROJECT_ID)
            require(json.getString("bucketId") == AppwriteConfig.STORAGE_BUCKET_ID)
            require(json.getString("authUserId") == expectedUserId)
            val ttl = json.getLong("expiresIn")
            require(ttl in 60..AppwriteConfig.STORAGE_TOKEN_TTL_SECONDS)
            val jwt = json.getString("jwt")
            require(jwt.length <= 8192 && Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matches(jwt))
            return StorageGrant(jwt, expectedUserId, version, nowNanos + (ttl - 30) * 1_000_000_000L)
        }
    }
}

@Singleton
class AppwriteStorageAuthorization @Inject constructor(
    private val auth: AppwriteAuthRepository,
    private val bridge: AppwriteAuthBridge
) : StorageAuthorizer {
    private val mutex = Mutex()
    @Volatile private var cached: StorageGrant? = null

    override fun clear() { cached = null }

    override fun requireCurrent(grant: StorageGrant) {
        if (!auth.isCurrent(grant.sessionVersion, grant.authUserId)) {
            throw IOException("انتهت جلسة المستخدم أثناء نقل الملفات")
        }
    }

    override suspend fun grant(): StorageGrant = mutex.withLock {
        val version = auth.sessionVersion
        val userId = auth.authenticatedUserId ?: throw IOException("سجّل الدخول قبل الوصول إلى ملفات Appwrite")
        cached?.takeIf { it.sessionVersion == version && it.authUserId == userId && System.nanoTime() < it.refreshAtNanos }
            ?.let { requireCurrent(it); return@withLock it }
        // The auth JWT/session is used only by Functions in the auth project. It is NEVER
        // forwarded to the storage endpoint; the function issues a distinct storage-project JWT.
        val response = bridge.execute("storageToken") {
            if (!auth.isCurrent(version, userId)) throw IOException("انتهت الجلسة قبل طلب تفويض التخزين")
        }
        val grant = try { StorageGrant.fromResponse(response, userId, version, System.nanoTime()) }
        catch (_: IllegalArgumentException) { throw IOException("خدمة التفويض أعادت اتصالًا غير مطابق لمشروع الملفات") }
        requireCurrent(grant)
        cached = grant
        grant
    }
}
