package com.unihub.app.data.cloud

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unihub.app.data.local.entity.FileKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** الربط يستعمل هوية R2، وليس رقم Room البعيد أو تشابه الاسم والحجم. */
data class CloudFileLink(
    val localId: Long,
    val remoteKey: String,
    val remoteVersion: String,
    val sha256: String,
    val localSize: Long,
    val localModifiedAt: Long,
    val localCreatedAt: Long = 0L
)

data class CloudCatalogSnapshot(
    val connectionId: String,
    val files: List<RemoteCloudFile> = emptyList(),
    val links: List<CloudFileLink> = emptyList(),
    val notifiedVersions: Set<String> = emptySet(),
    val metadataBaseline: String? = null,
    val metadataEtag: String = "",
    val folders: List<RemoteCloudFolder> = emptyList(),
    val folderLinks: List<CloudFolderLink> = emptyList(),
    val localFingerprints: List<CloudLocalFingerprint> = emptyList()
)

private val Context.cloudCatalogDataStore by preferencesDataStore(
    name = "unihub_cloud_catalog",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** فهرس محلي صغير؛ لا يحتوي على محتوى الملفات أو مفاتيح الاتصال. الكتابات ذرية. */
@Singleton
class CloudCatalogStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val stateKey = stringPreferencesKey("catalog_json")
    private val data = context.cloudCatalogDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    suspend fun snapshot(connectionId: String): CloudCatalogSnapshot =
        decode(data.first()[stateKey], connectionId)

    suspend fun update(
        connectionId: String,
        transform: (CloudCatalogSnapshot) -> CloudCatalogSnapshot
    ) {
        context.cloudCatalogDataStore.edit { prefs ->
            prefs[stateKey] = encode(transform(decode(prefs[stateKey], connectionId)))
        }
    }

    private fun decode(text: String?, connectionId: String): CloudCatalogSnapshot {
        if (text.isNullOrBlank()) return CloudCatalogSnapshot(connectionId)
        val root = try { JSONObject(text) } catch (_: org.json.JSONException) {
            return CloudCatalogSnapshot(connectionId)
        }
        if (root.optString("connectionId") != connectionId) return CloudCatalogSnapshot(connectionId)
        val files = root.optJSONArray("files") ?: JSONArray()
        val links = root.optJSONArray("links") ?: JSONArray()
        val notices = root.optJSONArray("notified") ?: JSONArray()
        return CloudCatalogSnapshot(
            connectionId = connectionId,
            files = (0 until files.length()).mapNotNull { i ->
                files.optJSONObject(i)?.let(::remoteFileFromJson)
            },
            links = (0 until links.length()).mapNotNull { i ->
                val obj = links.optJSONObject(i) ?: return@mapNotNull null
                CloudFileLink(
                    localId = obj.optLong("localId"),
                    remoteKey = obj.optString("remoteKey"),
                    remoteVersion = obj.optString("remoteVersion"),
                    sha256 = obj.optString("sha256"),
                    localSize = obj.optLong("localSize"),
                    localModifiedAt = obj.optLong("localModifiedAt"),
                    localCreatedAt = obj.optLong("localCreatedAt")
                )
            },
            notifiedVersions = (0 until notices.length()).map { notices.getString(it) }.toSet(),
            metadataBaseline = root.optString("metadataBaseline").takeIf { it.isNotBlank() },
            metadataEtag = root.optString("metadataEtag"),
            folders = root.optJSONArray("folders")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::folderFromJson) } }.orEmpty(),
            folderLinks = root.optJSONArray("folderLinks")?.let { array -> (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { CloudFolderLink(it.optLong("localId"), it.optLong("localCreatedAt"), it.optString("remoteKey")) }
            } }.orEmpty(),
            localFingerprints = root.optJSONArray("localFingerprints")?.let { array -> (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { CloudLocalFingerprint(it.optLong("localId"), it.optLong("localCreatedAt"),
                    it.optString("path"), it.optLong("size"), it.optLong("modifiedAt"), it.optString("sha256")) }
            } }.orEmpty()
        )
    }

    private fun encode(state: CloudCatalogSnapshot): String = JSONObject().apply {
        put("connectionId", state.connectionId)
        put("files", JSONArray().also { array -> state.files.forEach { array.put(remoteFileToJson(it)) } })
        put("links", JSONArray().also { array ->
            state.links.forEach { link ->
                array.put(JSONObject().apply {
                    put("localId", link.localId)
                    put("remoteKey", link.remoteKey)
                    put("remoteVersion", link.remoteVersion)
                    put("sha256", link.sha256)
                    put("localSize", link.localSize)
                    put("localModifiedAt", link.localModifiedAt)
                    put("localCreatedAt", link.localCreatedAt)
                })
            }
        })
        put("notified", JSONArray(state.notifiedVersions.toList()))
        put("metadataBaseline", state.metadataBaseline)
        put("metadataEtag", state.metadataEtag)
        put("folders", JSONArray().also { array -> state.folders.forEach { array.put(folderToJson(it)) } })
        put("localFingerprints", JSONArray().also { array -> state.localFingerprints.forEach { fp -> array.put(JSONObject()
            .put("localId", fp.localId).put("localCreatedAt", fp.localCreatedAt).put("path", fp.path)
            .put("size", fp.size).put("modifiedAt", fp.modifiedAt).put("sha256", fp.sha256)) } })
        put("folderLinks", JSONArray().also { array -> state.folderLinks.forEach { link -> array.put(JSONObject()
            .put("localId", link.localId).put("localCreatedAt", link.localCreatedAt).put("remoteKey", link.remoteKey)) } })
    }.toString()
}

internal fun remoteFileToJson(file: RemoteCloudFile): JSONObject = JSONObject().apply {
    put("remoteKey", file.remoteKey)
    put("id", file.id)
    put("name", file.name)
    put("extension", file.extension)
    put("size", file.size)
    put("mimeType", file.mimeType)
    put("kind", file.kind.name)
    put("folderId", file.folderId ?: JSONObject.NULL)
    put("folderName", file.folderName ?: JSONObject.NULL)
    put("createdAt", file.createdAt)
    put("etag", file.etag)
    put("sha256", file.sha256)
    put("lastModifiedAt", file.lastModifiedAt)
    put("cloudFolderKey", file.cloudFolderKey ?: JSONObject.NULL)
}

internal fun remoteFileFromJson(obj: JSONObject): RemoteCloudFile? {
    val key = obj.optString("remoteKey")
    if (key.isBlank()) return null
    return RemoteCloudFile(
        remoteKey = key,
        id = obj.optLong("id"),
        name = obj.optString("name"),
        extension = obj.optString("extension"),
        size = obj.optLong("size"),
        mimeType = obj.optString("mimeType", "application/octet-stream"),
        kind = FileKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: FileKind.OTHER,
        folderId = if (obj.isNull("folderId")) null else obj.optLong("folderId"),
        folderName = if (obj.isNull("folderName")) null else obj.optString("folderName"),
        createdAt = obj.optLong("createdAt"),
        etag = obj.optString("etag"),
        sha256 = obj.optString("sha256"),
        lastModifiedAt = obj.optLong("lastModifiedAt"),
        cloudFolderKey = if (obj.isNull("cloudFolderKey")) null else obj.optString("cloudFolderKey").takeIf { it.isNotBlank() }
    )
}
