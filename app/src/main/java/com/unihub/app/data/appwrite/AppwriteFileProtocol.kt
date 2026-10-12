package com.unihub.app.data.appwrite

import com.unihub.app.data.cloud.CloudConflictException
import com.unihub.app.data.cloud.CloudObjectSummary
import com.unihub.app.data.cloud.CloudStorageConfig
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** Pure protocol rules, shared by the transport and its JVM tests. */
object AppwriteFileProtocol {
    const val CHUNK_BYTES = 5 * 1024 * 1024
    // A manifest is one atomic upload, never a partly uploaded multi-chunk commit.
    const val MAX_MANIFEST_BYTES = CHUNK_BYTES - 1
    const val PAGE_SIZE = 100
    private const val NAME_PREFIX = "uh1_"
    private const val EXTERNAL_PREFIX = "external:"
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,35}")
    private val revisionPattern = Regex("m[0-9]{16}_[0-9a-f]{17}")

    fun validId(value: String): Boolean = idPattern.matches(value)

    fun isSafeObjectKey(key: String): Boolean = key.isNotBlank() &&
        key.toByteArray(Charsets.UTF_8).size <= 180 && !key.startsWith('/') &&
        key.none { it.isISOControl() || it == '\\' } &&
        key.split('/').none { it == "." || it == ".." || it.isEmpty() }

    fun fileId(key: String): String {
        require(isSafeObjectKey(key)) { "مفتاح الملف غير صالح" }
        if (key.startsWith(EXTERNAL_PREFIX)) {
            return key.removePrefix(EXTERNAL_PREFIX).also { require(validId(it)) }
        }
        return "f" + sha256(key).take(35)
    }

    fun uploadName(key: String): String {
        require(isSafeObjectKey(key) && !key.startsWith(EXTERNAL_PREFIX))
        return NAME_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.toByteArray(Charsets.UTF_8)) + ".bin"
    }

    fun logicalKey(id: String, name: String): String {
        require(validId(id))
        if (name.startsWith(NAME_PREFIX) && name.endsWith(".bin")) {
            val decoded = runCatching {
                String(Base64.getUrlDecoder().decode(name.removePrefix(NAME_PREFIX).removeSuffix(".bin")), Charsets.UTF_8)
            }.getOrNull()
            if (decoded != null && isSafeObjectKey(decoded) && !decoded.startsWith(EXTERNAL_PREFIX) && fileId(decoded) == id) {
                return decoded
            }
        }
        return EXTERNAL_PREFIX + id
    }

    fun isManifest(id: String, name: String): Boolean = revisionPattern.matches(id) &&
        name == CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY

    fun manifestGeneration(id: String): Long {
        require(revisionPattern.matches(id)) { "معرّف إصدار الفهرس غير صالح" }
        return id.substring(1, 17).toLong()
    }

    /**
     * All writers advancing the same base choose the SAME next ID. Appwrite's unique-file
     * constraint makes a losing concurrent create return 409 instead of silently overwriting.
     * Keep committed revisions: deleting them would weaken this compare-and-create protocol.
     */
    fun nextManifestId(previous: String?): String {
        val generation = previous?.let { manifestGeneration(it) + 1 } ?: 0L
        require(generation in 0..9_999_999_999_999_999L)
        return "m${generation.toString().padStart(16, '0')}_${sha256(previous ?: "unihub-manifest-root-v1").take(17)}"
    }

    fun latestManifest(files: List<AppwriteStoredFile>): AppwriteStoredFile? {
        val revisions = files.filter { it.complete && isManifest(it.id, it.name) }
        val latest = revisions.maxByOrNull { manifestGeneration(it.id) } ?: return null
        if (revisions.any { it.id != latest.id && manifestGeneration(it.id) == manifestGeneration(latest.id) }) {
            throw CloudConflictException()
        }
        return latest
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun chunkLength(size: Long, offset: Long): Int {
        require(size >= 0 && offset in 0..size)
        return minOf(CHUNK_BYTES.toLong(), size - offset).toInt()
    }
}

data class AppwriteStoredFile(
    val id: String,
    val name: String,
    val size: Long,
    val signature: String,
    val createdAt: String,
    val updatedAt: String,
    val chunksTotal: Long,
    val chunksUploaded: Long
) {
    val complete: Boolean get() = chunksTotal > 0 && chunksUploaded == chunksTotal
    val version: String get() = AppwriteFileProtocol.sha256("$id:$createdAt:$signature:$size")
    fun summary(): CloudObjectSummary {
        val manifest = AppwriteFileProtocol.isManifest(id, name)
        val key = if (manifest) CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY else AppwriteFileProtocol.logicalKey(id, name)
        return CloudObjectSummary(key, size, if (manifest) id else version,
            runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L),
            if (key.startsWith("external:")) name else key.substringAfterLast('/'))
    }

    companion object {
        fun fromJson(json: JSONObject): AppwriteStoredFile = AppwriteStoredFile(
            id = json.getString("\$id").also { require(AppwriteFileProtocol.validId(it)) },
            name = json.getString("name"),
            size = json.getLong("sizeOriginal").also { require(it >= 0) },
            signature = json.optString("signature"),
            createdAt = json.getString("\$createdAt"),
            updatedAt = json.getString("\$updatedAt"),
            chunksTotal = json.getLong("chunksTotal"),
            chunksUploaded = json.getLong("chunksUploaded")
        )
    }
}
