package com.unihub.app.core.prefs

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unihub.app.data.cloud.CloudStorageConnection
import com.unihub.app.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class CloudSyncSettings(
    val connection: CloudStorageConnection,
    val autoSyncEnabled: Boolean,
    val pendingUpload: Boolean,
    val lastLocalChangeAt: Long,
    val lastSyncedRemoteTimestamp: Long,
    val lastSyncAt: Long,
    val lastSyncMessage: String,
    val lastSyncSuccess: Boolean
) {
    val isConfigured: Boolean get() = connection.isConfigured
}

private val migratedKey = booleanPreferencesKey("cloud_provider_migrated_v2")
private val Context.cloudSyncDataStore by preferencesDataStore(
    name = "unihub_cloud_sync",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = {
        listOf(object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = currentData[migratedKey] != true
            override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
                // Allow-list removes all former provider credentials/pairing material without ever decrypting them.
                val retained = setOf("auto_sync_enabled", "pending_upload", "last_local_change_at")
                asMap().keys.filter { it.name !in retained }.forEach { remove(it) }
                this[migratedKey] = true
            }
            override suspend fun cleanUp() = Unit
        })
    }
)

@Singleton
class CloudSyncPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val autoKey = booleanPreferencesKey("auto_sync_enabled")
    private val pendingKey = booleanPreferencesKey("pending_upload")
    private val changeKey = longPreferencesKey("last_local_change_at")
    private val remoteAtKey = longPreferencesKey("last_synced_remote_timestamp")
    private val syncAtKey = longPreferencesKey("last_sync_at")
    private val messageKey = stringPreferencesKey("last_sync_message")
    private val successKey = booleanPreferencesKey("last_sync_success")
    private val data = context.cloudSyncDataStore.data

    val settings: Flow<CloudSyncSettings> = data.map(::toSettings).catch { error ->
        if (error is IOException) emit(toSettings(emptyPreferences()).copy(
            lastSyncSuccess = false, lastSyncMessage = "تعذّر قراءة تفضيلات المزامنة"
        )) else throw error
    }
    suspend fun snapshot(): CloudSyncSettings = toSettings(data.first())
    private fun toSettings(prefs: Preferences) = CloudSyncSettings(
        connection = CloudStorageConnection(SupabaseConfig.PROJECT_URL, SupabaseConfig.PROJECT_REF, SupabaseConfig.STORAGE_BUCKET_ID),
        autoSyncEnabled = prefs[autoKey] ?: true,
        pendingUpload = prefs[pendingKey] ?: false,
        lastLocalChangeAt = prefs[changeKey] ?: 0L,
        lastSyncedRemoteTimestamp = prefs[remoteAtKey] ?: 0L,
        lastSyncAt = prefs[syncAtKey] ?: 0L,
        lastSyncMessage = prefs[messageKey].orEmpty(),
        lastSyncSuccess = prefs[successKey] ?: true
    )

    suspend fun setAutoSyncEnabled(enabled: Boolean) { context.cloudSyncDataStore.edit { it[autoKey] = enabled } }
    suspend fun markLocalChange(timestamp: Long = System.currentTimeMillis()) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[pendingKey] = true
            prefs[changeKey] = maxOf(timestamp, (prefs[changeKey] ?: 0L) + 1L)
        }
    }
    suspend fun recordSyncSuccess(remoteTimestamp: Long, message: String) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[remoteAtKey] = remoteTimestamp; prefs[syncAtKey] = System.currentTimeMillis()
            prefs[messageKey] = message; prefs[successKey] = true
        }
    }
    suspend fun recordUploadSuccess(changeAtStart: Long, remoteTimestamp: Long, message: String) {
        context.cloudSyncDataStore.edit { prefs ->
            if ((prefs[changeKey] ?: 0L) == changeAtStart) prefs[pendingKey] = false
            prefs[remoteAtKey] = remoteTimestamp; prefs[syncAtKey] = System.currentTimeMillis()
            prefs[messageKey] = message; prefs[successKey] = true
        }
    }
    suspend fun recordSyncFailure(message: String) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[syncAtKey] = System.currentTimeMillis(); prefs[messageKey] = message; prefs[successKey] = false
        }
    }
}
