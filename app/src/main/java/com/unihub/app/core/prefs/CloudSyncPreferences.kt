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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
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
    val lastSyncSuccess: Boolean,
    val credentialError: String? = null
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
    private val pairingKey = stringPreferencesKey("r2_provisioning_pair_v1")
    private val autoKey = booleanPreferencesKey("auto_sync_enabled")
    private val pendingKey = booleanPreferencesKey("pending_upload")
    private val changeKey = longPreferencesKey("last_local_change_at")
    private val remoteAtKey = longPreferencesKey("last_synced_remote_timestamp")
    private val syncAtKey = longPreferencesKey("last_sync_at")
    private val messageKey = stringPreferencesKey("last_sync_message")
    private val successKey = booleanPreferencesKey("last_sync_success")
    private val secretVault = CredentialSecretVault()
    private val data = context.cloudSyncDataStore.data
    val settings: Flow<CloudSyncSettings> = data.map { prefs ->
        try {
            migrateLegacyCredentials(prefs)
            toSettings(data.first())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            toSettings(prefs).copy(
                credentials = R2Credentials(accessKeyId = "", secretAccessKey = ""),
                credentialError = "تعذّر فتح الاتصال المحمي؛ أعد استيراده من الخدمة أو الباركود"
            )
        }
    }.catch { error ->
        if (error is IOException) emit(toSettings(emptyPreferences()).copy(
            credentialError = "تعذّر قراءة إعدادات الاتصال المحمي؛ أعد المحاولة أو استورد الاتصال"
        )) else throw error
    }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): CloudSyncSettings = withContext(Dispatchers.IO) {
        migrateLegacyCredentials(data.first())
        toSettings(data.first())
    }

    private suspend fun migrateLegacyCredentials(prefs: Preferences) {
        for ((key, purpose) in listOf(kotlin.Pair(accessKey, "r2-access"), kotlin.Pair(secretKey, "r2-secret"))) {
            val old = prefs[key].orEmpty()
            if (!secretVault.needsMigration(old)) continue
            val encoded = secretVault.encode(secretVault.decodeOrLegacy(old, purpose), purpose)
            context.cloudSyncDataStore.edit { mutable ->
                if (mutable[key] == old) mutable[key] = encoded
            }
        }
    }

    private fun toSettings(prefs: Preferences): CloudSyncSettings {
        var credentialError: String? = null
        fun decode(value: String, purpose: String): String = try {
            secretVault.decodeProtected(value, purpose)
        } catch (_: Exception) {
            credentialError = "تعذّر فتح الاتصال المحمي؛ أعد استيراده من الخدمة أو الباركود"
            ""
        }
        val access = decode(prefs[accessKey].orEmpty(), "r2-access")
        val secret = decode(prefs[secretKey].orEmpty(), "r2-secret")
        return CloudSyncSettings(
        credentials = R2Credentials(
            accountId = prefs[accountIdKey] ?: CloudflareR2Config.DEFAULT_ACCOUNT_ID,
            endpointUrl = prefs[endpointKey] ?: CloudflareR2Config.DEFAULT_ENDPOINT_URL,
            bucketName = prefs[bucketKey] ?: CloudflareR2Config.DEFAULT_BUCKET_NAME,
            accessKeyId = access,
            secretAccessKey = secret
        ),
        autoSyncEnabled = prefs[autoKey] ?: true,
        pendingUpload = prefs[pendingKey] ?: false,
        lastLocalChangeAt = prefs[changeKey] ?: 0L,
        lastSyncedRemoteTimestamp = prefs[remoteAtKey] ?: 0L,
        lastSyncAt = prefs[syncAtKey] ?: 0L,
        lastSyncMessage = prefs[messageKey].orEmpty(),
        lastSyncSuccess = prefs[successKey] ?: true,
        credentialError = credentialError
        )
    }

    suspend fun saveCredentials(credentials: R2Credentials) = withContext(Dispatchers.IO) {
        require(credentials.isConfigured) { "بيانات اتصال R2 غير صالحة" }
        val access = secretVault.encode(credentials.accessKeyId.trim(), "r2-access")
        val secret = secretVault.encode(credentials.secretAccessKey.trim(), "r2-secret")
        context.cloudSyncDataStore.edit { prefs ->
            writeCredentials(prefs, credentials, access, secret)
            prefs.remove(pairingKey)
        }
    }

    /** Pairing private material is encrypted too; it never enters SavedState, logs or a QR. */
    suspend fun pairingState(): String = withContext(Dispatchers.IO) {
        secretVault.decodeProtected(data.first()[pairingKey].orEmpty(), "provisioning-pair")
    }

    suspend fun savePairingState(state: String) = withContext(Dispatchers.IO) {
        val encrypted = secretVault.encode(state, "provisioning-pair")
        context.cloudSyncDataStore.edit { it[pairingKey] = encrypted }
    }

    suspend fun consumePairingAndSave(credentials: R2Credentials, expectedState: String) = withContext(Dispatchers.IO) {
        require(credentials.isConfigured) { "حزمة اتصال R2 غير صالحة" }
        val access = secretVault.encode(credentials.accessKeyId.trim(), "r2-access")
        val secret = secretVault.encode(credentials.secretAccessKey.trim(), "r2-secret")
        context.cloudSyncDataStore.edit { prefs ->
            check(expectedState.isNotBlank() && secretVault.decodeProtected(
                prefs[pairingKey].orEmpty(), "provisioning-pair"
            ) == expectedState) { "طلب الاستقبال تغير أو استُهلك؛ أنشئ طلبًا جديدًا" }
            writeCredentials(prefs, credentials, access, secret)
            prefs.remove(pairingKey)
        }
    }

    private fun writeCredentials(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        credentials: R2Credentials,
        access: String,
        secret: String
    ) {
        prefs[accountIdKey] = credentials.accountId.trim()
        prefs[endpointKey] = credentials.endpointUrl.trim()
        prefs[bucketKey] = credentials.bucketName.trim()
        prefs[accessKey] = access
        prefs[secretKey] = secret
    }

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
