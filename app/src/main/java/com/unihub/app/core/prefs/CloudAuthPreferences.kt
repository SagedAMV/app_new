package com.unihub.app.core.prefs

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unihub.app.data.auth.CloudAuthRegistry
import com.unihub.app.data.auth.CloudAuthRules
import com.unihub.app.data.auth.CloudUserAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalAuthSessionSnapshot(
    val loggedInUsername: String = "",
    val userId: String = "",
    val boundDeviceFingerprint: String = "",
    val cachedRegistryJson: String = "",
    val lastVerifiedAt: Long = 0L,
    /** رموز Supabase — تُحفظ داخل الخزنة المشفّرة، لا كنص صريح. */
    val accessToken: String = "",
    val refreshToken: String = ""
) {
    val hasAuthenticatedSession: Boolean get() = userId.isNotBlank() && loggedInUsername.isNotBlank() && boundDeviceFingerprint.isNotBlank()
}

private val profileMigrationKey = booleanPreferencesKey("cloud_profile_migrated_v2")
private val Context.cloudAuthDataStore by preferencesDataStore(
    name = "unihub_cloud_auth",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = {
        listOf(object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = currentData[profileMigrationKey] != true
            override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
                asMap().keys.filter { it.name != "auth_fallback_device_seed" }.forEach { remove(it) }
                this[profileMigrationKey] = true
            }
            override suspend fun cleanUp() = Unit
        })
    }
)

/** Encrypted profile cache for local/offline access. It is never used as a network credential. */
@Singleton
class CloudAuthPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val payloadKey = stringPreferencesKey("cloud_protected_profile_v2")
    private val fallbackSeedKey = stringPreferencesKey("auth_fallback_device_seed")
    private val vault = CredentialSecretVault("unihub_cloud_profile_v2")

    suspend fun snapshot(): LocalAuthSessionSnapshot = withContext(Dispatchers.IO) {
        val payload = context.cloudAuthDataStore.data.first()[payloadKey].orEmpty()
        if (payload.isBlank()) return@withContext LocalAuthSessionSnapshot()
        val json = JSONObject(vault.decodeProtected(payload, PURPOSE))
        LocalAuthSessionSnapshot(json.getString("email"), json.getString("userId"), json.getString("fingerprint"),
            json.getString("profile"), json.getLong("verifiedAt"),
            json.optString("accessToken"), json.optString("refreshToken"))
    }

    suspend fun saveAuthenticatedSession(
        user: CloudUserAccount,
        deviceFingerprint: String,
        accessToken: String = "",
        refreshToken: String = ""
    ) = withContext(Dispatchers.IO) {
        require(user.userId.isNotBlank() && user.isActive)
        val payload = JSONObject().put("email", user.username).put("userId", user.userId)
            .put("fingerprint", deviceFingerprint).put("verifiedAt", System.currentTimeMillis())
            .put("accessToken", accessToken).put("refreshToken", refreshToken)
            .put("profile", CloudAuthRules.toJson(CloudAuthRegistry(users = listOf(user))))
        val encoded = vault.encode(payload.toString(), PURPOSE)
        context.cloudAuthDataStore.edit { it[payloadKey] = encoded }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        context.cloudAuthDataStore.edit { it.remove(payloadKey) }
    }

    suspend fun getOrCreateFallbackSeed(): String = withContext(Dispatchers.IO) {
        val prefs = context.cloudAuthDataStore.edit {
            if (it[fallbackSeedKey].isNullOrBlank()) it[fallbackSeedKey] = UUID.randomUUID().toString()
        }
        prefs[fallbackSeedKey].orEmpty().ifBlank { throw IOException("تعذّر حفظ معرّف الجهاز") }
    }

    private companion object { const val PURPOSE = "cloud-profile" }
}
