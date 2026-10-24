package com.unihub.app.data.cloud

import com.unihub.app.data.local.entity.FileKind
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Provider-independent logical keys used in local backups and the library manifest. */
object CloudStorageConfig {
    const val REMOTE_BACKUP_OBJECT_KEY = "unihub_cloud_backup.zip"
    const val REMOTE_MANIFEST_OBJECT_KEY = "unihub_manifest.json"
    const val REMOTE_META_OBJECT_KEY = "unihub_cloud_meta.json"
    const val REMOTE_FILES_PREFIX = "files/"

    fun remoteFileObjectKey(id: Long, extension: String): String {
        val cleanExt = extension.trim().trim('.').filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        return "${REMOTE_FILES_PREFIX}file_${id}.$cleanExt"
    }
}

/** Public storage coordinates, never a credential container. */
data class CloudStorageConnection(
    val endpointUrl: String = "",
    val projectId: String = "",
    val bucketId: String = ""
) {
    val isConfigured: Boolean get() = endpointUrl.isNotBlank() && projectId.isNotBlank() && bucketId.isNotBlank()
}

data class RemoteCloudFile(
    val remoteKey: String,
    val id: Long,
    val name: String,
    val extension: String,
    val size: Long,
    val mimeType: String = "application/octet-stream",
    val kind: FileKind = FileKind.OTHER,
    val folderId: Long? = null,
    val folderName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Opaque version, not an assumed content checksum. */
    val etag: String = "",
    val sha256: String = "",
    val lastModifiedAt: Long = 0L,
    val cloudFolderKey: String? = null
) {
    val versionToken: String
        get() = "${etag.ifBlank { sha256.ifBlank { lastModifiedAt.toString() } }}:$size"
    val notificationToken: String get() = "$remoteKey\u0000$versionToken"
    val fullDisplayName: String
        get() = if (extension.isNotBlank() && !name.endsWith(".$extension", ignoreCase = true)) "$name.$extension" else name
}

data class CloudObjectSummary(
    val key: String,
    val size: Long,
    val etag: String,
    val lastModifiedAt: Long = 0L,
    val displayName: String = ""
)

data class CloudUploadedObject(val etag: String, val size: Long)
data class CloudTextObject(val text: String, val etag: String, val notModified: Boolean = false)
class CloudConflictException : IOException("تغيّرت النسخة")

internal suspend fun <T> cloudAttempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
