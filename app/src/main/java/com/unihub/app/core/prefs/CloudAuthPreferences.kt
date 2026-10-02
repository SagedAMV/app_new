package com.unihub.app.core.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalAuthSessionSnapshot(
    val loggedInUsername: String,
    val boundDeviceFingerprint: String,
    val pendingUsername: String,
    val cachedRegistryJson: String,
    val lastVerifiedAt: Long,
    val fallbackDeviceSeed: String
) {
    val hasAuthenticatedSession: Boolean
        get() = loggedInUsername.isNotBlank() && boundDeviceFingerprint.isNotBlank()
}

private val Context.cloudAuthDataStore by preferencesDataStore(
    name = "unihub_cloud_auth",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * مخزن الجلسة المحلية المعتمدة بعد أول تسجيل دخول عبر السحابة.
 * يسمح للتطبيق بالتعرف على المستخدم والجهاز تلقائياً في المرات اللاحقة دون طلب تسجيل جديد،
 * ما لم يتغير الجهاز أو يوقف المشرف الحساب في السحابة.
 */
@Singleton
class CloudAuthPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usernameKey = stringPreferencesKey("auth_logged_in_username")
    private val fingerprintKey = stringPreferencesKey("auth_bound_fingerprint")
    private val pendingUserKey = stringPreferencesKey("auth_pending_username")
    private val registryJsonKey = stringPreferencesKey("auth_cached_registry_json")
    private val verifiedAtKey = longPreferencesKey("auth_last_verified_at")
    private val fallbackSeedKey = stringPreferencesKey("auth_fallback_device_seed")

    private val data: Flow<Preferences> = context.cloudAuthDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val sessionFlow: Flow<LocalAuthSessionSnapshot> = data.map(::toSnapshot)

    suspend fun snapshot(): LocalAuthSessionSnapshot = sessionFlow.first()

    private fun toSnapshot(prefs: Preferences) = LocalAuthSessionSnapshot(
        loggedInUsername = prefs[usernameKey].orEmpty(),
        boundDeviceFingerprint = prefs[fingerprintKey].orEmpty(),
        pendingUsername = prefs[pendingUserKey].orEmpty(),
        cachedRegistryJson = prefs[registryJsonKey].orEmpty(),
        lastVerifiedAt = prefs[verifiedAtKey] ?: 0L,
        fallbackDeviceSeed = prefs[fallbackSeedKey].orEmpty()
    )

    suspend fun getOrCreateFallbackSeed(): String {
        val current = snapshot().fallbackDeviceSeed
        if (current.isNotBlank()) return current
        val created = UUID.randomUUID().toString()
        context.cloudAuthDataStore.edit { prefs ->
            val existing = prefs[fallbackSeedKey].orEmpty()
            if (existing.isBlank()) {
                prefs[fallbackSeedKey] = created
            }
        }
        return snapshot().fallbackDeviceSeed.ifBlank { created }
    }

    suspend fun saveAuthenticatedSession(
        username: String,
        deviceFingerprint: String,
        registryJson: String
    ) {
        context.cloudAuthDataStore.edit { prefs ->
            prefs[usernameKey] = username.trim()
            prefs[fingerprintKey] = deviceFingerprint.trim()
            prefs[pendingUserKey] = ""
            if (registryJson.isNotBlank()) {
                prefs[registryJsonKey] = registryJson
            }
            prefs[verifiedAtKey] = System.currentTimeMillis()
        }
    }

    suspend fun savePendingApprovalState(
        username: String,
        registryJson: String
    ) {
        context.cloudAuthDataStore.edit { prefs ->
            prefs[usernameKey] = ""
            prefs[fingerprintKey] = ""
            prefs[pendingUserKey] = username.trim()
            if (registryJson.isNotBlank()) {
                prefs[registryJsonKey] = registryJson
            }
        }
    }

    suspend fun updateCachedRegistry(registryJson: String) {
        context.cloudAuthDataStore.edit { prefs ->
            prefs[registryJsonKey] = registryJson
            prefs[verifiedAtKey] = System.currentTimeMillis()
        }
    }

    suspend fun clearSession() {
        context.cloudAuthDataStore.edit { prefs ->
            prefs[usernameKey] = ""
            prefs[fingerprintKey] = ""
            prefs[pendingUserKey] = ""
        }
    }
}
