package com.unihub.app.core.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unihub.app.data.auth.CloudAuthRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalAuthSessionSnapshot(
    val loggedInUsername: String = "",
    val boundDeviceFingerprint: String = "",
    val pendingUsername: String = "",
    val cachedRegistryJson: String = "",
    val lastVerifiedAt: Long = 0L,
    val fallbackDeviceSeed: String = "",
    val storageError: String? = null
) {
    val hasAuthenticatedSession: Boolean
        get() = loggedInUsername.isNotBlank() && boundDeviceFingerprint.isNotBlank()
}

private val Context.cloudAuthDataStore by preferencesDataStore(
    name = "unihub_cloud_auth",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** A minimal encrypted local cache, not a substitute for server-side user authorization. */
@Singleton
class CloudAuthPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val payloadKey = stringPreferencesKey("auth_protected_session_v2")
    private val usernameKey = stringPreferencesKey("auth_logged_in_username")
    private val fingerprintKey = stringPreferencesKey("auth_bound_fingerprint")
    private val pendingUserKey = stringPreferencesKey("auth_pending_username")
    private val registryJsonKey = stringPreferencesKey("auth_cached_registry_json")
    private val verifiedAtKey = longPreferencesKey("auth_last_verified_at")
    private val fallbackSeedKey = stringPreferencesKey("auth_fallback_device_seed")
    private val vault = CredentialSecretVault("unihub_auth_session_v2")
    private val data: Flow<Preferences> = context.cloudAuthDataStore.data

    val sessionFlow: Flow<LocalAuthSessionSnapshot> = data.map { prefs ->
        try {
            migrateLegacy(prefs)
            toSnapshot(data.first())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LocalAuthSessionSnapshot(storageError = STORAGE_ERROR)
        }
    }.catch { error ->
        if (error is IOException) emit(LocalAuthSessionSnapshot(storageError = STORAGE_ERROR)) else throw error
    }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): LocalAuthSessionSnapshot = withContext(Dispatchers.IO) {
        migrateLegacy(data.first())
        toSnapshot(data.first())
    }

    private fun toSnapshot(prefs: Preferences): LocalAuthSessionSnapshot {
        val stored = prefs[payloadKey].orEmpty()
        if (stored.isBlank()) return LocalAuthSessionSnapshot(fallbackDeviceSeed = prefs[fallbackSeedKey].orEmpty())
        val root = JSONObject(vault.decodeProtected(stored, PURPOSE))
        return LocalAuthSessionSnapshot(
            loggedInUsername = root.optString("username"),
            boundDeviceFingerprint = root.optString("fingerprint"),
            pendingUsername = root.optString("pending"),
            cachedRegistryJson = root.optString("profile"),
            lastVerifiedAt = root.optLong("verifiedAt", 0L),
            fallbackDeviceSeed = prefs[fallbackSeedKey].orEmpty()
        )
    }

    private suspend fun migrateLegacy(prefs: Preferences) {
        val hasLegacy = prefs[usernameKey] != null || prefs[pendingUserKey] != null || prefs[registryJsonKey] != null
        if (!hasLegacy) return
        if (prefs[payloadKey].isNullOrBlank()) {
            val username = prefs[usernameKey].orEmpty()
            val pending = prefs[pendingUserKey].orEmpty()
            val profile = minimalProfile(prefs[registryJsonKey].orEmpty(), username.ifBlank { pending })
            val encoded = encode(LocalAuthSessionSnapshot(
                loggedInUsername = username,
                boundDeviceFingerprint = prefs[fingerprintKey].orEmpty(),
                pendingUsername = pending,
                cachedRegistryJson = profile,
                lastVerifiedAt = prefs[verifiedAtKey] ?: 0L
            ))
            context.cloudAuthDataStore.edit {
                if (it[payloadKey].isNullOrBlank()) it[payloadKey] = encoded
                removeLegacy(it)
            }
        } else {
            context.cloudAuthDataStore.edit { removeLegacy(it) }
        }
    }

    private fun minimalProfile(json: String, username: String): String {
        if (username.isBlank() || json.isBlank()) return ""
        return CloudAuthRules.toJson(CloudAuthRules.redactedRegistry(CloudAuthRules.fromJson(json), username))
    }

    private fun encode(snap: LocalAuthSessionSnapshot): String = vault.encode(JSONObject().apply {
        put("username", snap.loggedInUsername)
        put("fingerprint", snap.boundDeviceFingerprint)
        put("pending", snap.pendingUsername)
        put("profile", snap.cachedRegistryJson)
        put("verifiedAt", snap.lastVerifiedAt)
    }.toString(), PURPOSE)

    private fun removeLegacy(prefs: MutablePreferences) {
        prefs.remove(usernameKey)
        prefs.remove(fingerprintKey)
        prefs.remove(pendingUserKey)
        prefs.remove(registryJsonKey)
        prefs.remove(verifiedAtKey)
    }

    /** The fallback seed is an identifier, not an authentication secret. */
    suspend fun getOrCreateFallbackSeed(): String = withContext(Dispatchers.IO) {
        context.cloudAuthDataStore.edit { prefs ->
            if (prefs[fallbackSeedKey].isNullOrBlank()) prefs[fallbackSeedKey] = UUID.randomUUID().toString()
        }
        data.first()[fallbackSeedKey].orEmpty().ifBlank { throw IOException("تعذّر حفظ معرّف الجهاز") }
    }

    suspend fun saveAuthenticatedSession(username: String, deviceFingerprint: String, registryJson: String) =
        withContext(Dispatchers.IO) {
            val encoded = encode(LocalAuthSessionSnapshot(
                loggedInUsername = username.trim(),
                boundDeviceFingerprint = deviceFingerprint.trim(),
                cachedRegistryJson = minimalProfile(registryJson, username),
                lastVerifiedAt = System.currentTimeMillis()
            ))
            context.cloudAuthDataStore.edit { prefs ->
                prefs[payloadKey] = encoded
                removeLegacy(prefs)
            }
        }

    suspend fun savePendingApprovalState(username: String, registryJson: String) = withContext(Dispatchers.IO) {
        val encoded = encode(LocalAuthSessionSnapshot(
            pendingUsername = username.trim(), cachedRegistryJson = minimalProfile(registryJson, username)
        ))
        context.cloudAuthDataStore.edit { prefs ->
            prefs[payloadKey] = encoded
            removeLegacy(prefs)
        }
    }

    suspend fun updateCachedRegistry(registryJson: String) = withContext(Dispatchers.IO) {
        context.cloudAuthDataStore.edit { prefs ->
            val current = toSnapshot(prefs)
            val username = current.loggedInUsername.ifBlank { current.pendingUsername }
            if (username.isNotBlank()) prefs[payloadKey] = encode(current.copy(
                cachedRegistryJson = minimalProfile(registryJson, username),
                lastVerifiedAt = if (current.hasAuthenticatedSession) System.currentTimeMillis() else 0L
            ))
            removeLegacy(prefs)
        }
    }

    suspend fun markVerified() = withContext(Dispatchers.IO) {
        context.cloudAuthDataStore.edit { prefs ->
            val current = toSnapshot(prefs)
            if (current.hasAuthenticatedSession) prefs[payloadKey] = encode(current.copy(lastVerifiedAt = System.currentTimeMillis()))
        }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        context.cloudAuthDataStore.edit { prefs ->
            prefs.remove(payloadKey)
            removeLegacy(prefs)
        }
    }

    private companion object {
        const val PURPOSE = "auth-session"
        const val STORAGE_ERROR = "تعذّر قراءة الجلسة المحمية؛ أعد تسجيل الدخول أو حاول إعادة التهيئة"
    }
}
