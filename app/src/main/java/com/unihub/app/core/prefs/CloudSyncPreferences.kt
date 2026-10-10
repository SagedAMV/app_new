package com.unihub.app.core.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unihub.app.data.cloud.CloudflareR2Config
import com.unihub.app.data.cloud.R2Credentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class CloudSyncSettings(
    val credentials: R2Credentials,
    val autoSyncEnabled: Boolean,
    val pendingUpload: Boolean,
    val lastLocalChangeAt: Long,
    val lastSyncedRemoteTimestamp: Long,
    val lastSyncAt: Long,
    val lastSyncMessage: String,
    val lastSyncSuccess: Boolean
) {
    val isConfigured: Boolean get() = credentials.isConfigured
}

private val Context.cloudSyncDataStore by preferencesDataStore(
    name = "unihub_cloud_sync",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class CloudSyncPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val accountIdKey = stringPreferencesKey("r2_account_id")
    private val endpointKey = stringPreferencesKey("r2_endpoint_url")
    private val bucketKey = stringPreferencesKey("r2_bucket_name")
    private val accessKey = stringPreferencesKey("r2_access_key_id")
    private val secretKey = stringPreferencesKey("r2_secret_access_key")
    private val autoKey = booleanPreferencesKey("auto_sync_enabled")
    private val pendingKey = booleanPreferencesKey("pending_upload")
    private val changeKey = longPreferencesKey("last_local_change_at")
    private val remoteAtKey = longPreferencesKey("last_synced_remote_timestamp")
    private val syncAtKey = longPreferencesKey("last_sync_at")
    private val messageKey = stringPreferencesKey("last_sync_message")
    private val successKey = booleanPreferencesKey("last_sync_success")
    private val secretVault = CredentialSecretVault()
    private val data = context.cloudSyncDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
    val settings: Flow<CloudSyncSettings> = data.map(::toSettings)

    /** Migrate legacy plaintext credentials to Android Keystore encryption before use. */
    suspend fun snapshot(): CloudSyncSettings {
        val prefs = data.first()
        val oldAccess = prefs[accessKey].orEmpty()
        val oldSecret = prefs[secretKey].orEmpty()
        if ((oldAccess.isNotBlank() && !secretVault.isEncoded(oldAccess)) ||
            (oldSecret.isNotBlank() && !secretVault.isEncoded(oldSecret))) {
            context.cloudSyncDataStore.edit { mutable ->
                val currentAccess = mutable[accessKey].orEmpty()
                val currentSecret = mutable[secretKey].orEmpty()
                if (currentAccess.isNotBlank() && !secretVault.isEncoded(currentAccess)) {
                    mutable[accessKey] = secretVault.encode(currentAccess)
                }
                if (currentSecret.isNotBlank() && !secretVault.isEncoded(currentSecret)) {
                    mutable[secretKey] = secretVault.encode(currentSecret)
                }
            }
        }
        return toSettings(prefs)
    }

    private fun toSettings(prefs: Preferences) = CloudSyncSettings(
        credentials = R2Credentials(
            accountId = prefs[accountIdKey] ?: CloudflareR2Config.DEFAULT_ACCOUNT_ID,
            endpointUrl = prefs[endpointKey] ?: CloudflareR2Config.DEFAULT_ENDPOINT_URL,
            bucketName = prefs[bucketKey] ?: CloudflareR2Config.DEFAULT_BUCKET_NAME,
            accessKeyId = decodeSecretSafely(prefs[accessKey].orEmpty()),
            secretAccessKey = decodeSecretSafely(prefs[secretKey].orEmpty())
        ),
        autoSyncEnabled = prefs[autoKey] ?: true,
        pendingUpload = prefs[pendingKey] ?: false,
        lastLocalChangeAt = prefs[changeKey] ?: 0L,
        lastSyncedRemoteTimestamp = prefs[remoteAtKey] ?: 0L,
        lastSyncAt = prefs[syncAtKey] ?: 0L,
        lastSyncMessage = prefs[messageKey].orEmpty(),
        lastSyncSuccess = prefs[successKey] ?: true
    )

    suspend fun saveCredentials(credentials: R2Credentials) {
        val encodedAccessKey = secretVault.encode(credentials.accessKeyId.trim())
        val encodedSecretKey = secretVault.encode(credentials.secretAccessKey.trim())
        context.cloudSyncDataStore.edit { prefs ->
            prefs[accountIdKey] = credentials.accountId.trim()
            prefs[endpointKey] = credentials.endpointUrl.trim()
            prefs[bucketKey] = credentials.bucketName.trim()
            prefs[accessKey] = encodedAccessKey
            prefs[secretKey] = encodedSecretKey
        }
    }

    private fun decodeSecretSafely(value: String): String =
        runCatching { secretVault.decodeOrLegacy(value) }.getOrDefault("")

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.cloudSyncDataStore.edit { it[autoKey] = enabled }
    }

    suspend fun markLocalChange(timestamp: Long = System.currentTimeMillis()) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[pendingKey] = true
            // رقم متزايد حتى عندما تقع تعديلات متعددة في نفس المللي ثانية.
            prefs[changeKey] = maxOf(timestamp, (prefs[changeKey] ?: 0L) + 1L)
        }
    }

    /** نجاح فحص/تنزيل لا يمسح التعديلات المحلية المعلقة. */
    suspend fun recordSyncSuccess(remoteTimestamp: Long, message: String) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[remoteAtKey] = remoteTimestamp
            prefs[syncAtKey] = System.currentTimeMillis()
            prefs[messageKey] = message
            prefs[successKey] = true
        }
    }

    /** نمسح الانتظار فقط إذا لم يحصل تعديل محلي أثناء الرفع. */
    suspend fun recordUploadSuccess(changeAtStart: Long, remoteTimestamp: Long, message: String) {
        context.cloudSyncDataStore.edit { prefs ->
            if ((prefs[changeKey] ?: 0L) == changeAtStart) prefs[pendingKey] = false
            prefs[remoteAtKey] = remoteTimestamp
            prefs[syncAtKey] = System.currentTimeMillis()
            prefs[messageKey] = message
            prefs[successKey] = true
        }
    }

    suspend fun recordSyncFailure(message: String) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[syncAtKey] = System.currentTimeMillis()
            prefs[messageKey] = message
            prefs[successKey] = false
        }
    }
}
