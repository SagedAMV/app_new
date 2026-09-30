package com.unihub.app.data.cloud

/** بصمة محتوى محلي موثقة؛ يبطُل الكاش إذا تغيّر السجل أو المسار أو الحجم أو وقت الكتابة. */
data class CloudLocalFingerprint(
    val localId: Long, val localCreatedAt: Long, val path: String,
    val size: Long, val modifiedAt: Long, val sha256: String
)
data class CloudLocalFileStat(val id: Long, val createdAt: Long, val path: String, val size: Long, val modifiedAt: Long)
data class CloudPresenceResult(val presentKeys: Set<String>, val verifyingKeys: Set<String>, val missingKeys: Set<String>)
data class CloudLocalVerification(val active: Boolean = false, val fileName: String = "", val completed: Int = 0, val total: Int = 0, val error: String? = null)

/** الاسم والمجلد وأرقام الهاتف لا تثبت وجود المحتوى. استعادة ZIP تعاد مطابقتها ببصمة قوية. */
object CloudPresenceMatcher {
    fun validFingerprint(fp: CloudLocalFingerprint, stat: CloudLocalFileStat): Boolean =
        fp.localId == stat.id && fp.localCreatedAt == stat.createdAt && fp.path == stat.path &&
            fp.size == stat.size && fp.modifiedAt == stat.modifiedAt && strongHash(fp.sha256)

    fun strongHash(value: String): Boolean = value.matches(Regex("[a-fA-F0-9]{64}"))

    fun compare(files: List<RemoteCloudFile>, stats: List<CloudLocalFileStat>, fingerprints: List<CloudLocalFingerprint>, links: List<CloudFileLink>): CloudPresenceResult {
        val byId = stats.associateBy { it.id }
        val valid = fingerprints.filter { fp -> byId[fp.localId]?.let { validFingerprint(fp, it) } == true }
        val hashedIds = valid.mapTo(mutableSetOf()) { it.localId }
        val hashes = valid.mapTo(mutableSetOf()) { it.sha256.lowercase() to it.size }
        val unhashedSizes = stats.filter { it.id !in hashedIds }.mapTo(mutableSetOf()) { it.size }
        val linksByKey = links.groupBy { it.remoteKey }
        val present = linkedSetOf<String>(); val verifying = linkedSetOf<String>(); val missing = linkedSetOf<String>()
        for (remote in files) {
            val linked = linksByKey[remote.remoteKey].orEmpty().any { link ->
                val stat = byId[link.localId]
                stat != null && stat.createdAt == link.localCreatedAt && stat.size == link.localSize &&
                    stat.modifiedAt == link.localModifiedAt && link.remoteVersion == remote.versionToken &&
                    (remote.sha256.isBlank() || remote.sha256.equals(link.sha256, ignoreCase = true))
            }
            val hasHash = strongHash(remote.sha256)
            when {
                linked || (hasHash && (remote.sha256.lowercase() to remote.size) in hashes) -> present += remote.remoteKey
                hasHash && remote.size in unhashedSizes -> verifying += remote.remoteKey
                else -> missing += remote.remoteKey
            }
        }
        return CloudPresenceResult(present, verifying, missing)
    }

    /** المجلدات اللازمة لعرض المفقود فقط؛ لا نعرض مجلداً مكتملاً كأنه يحتاج تنزيله. */
    fun foldersForMissing(folders: List<RemoteCloudFolder>, files: List<RemoteCloudFile>, pendingKeys: Set<String>): Set<String> {
        val keys = linkedSetOf<String>()
        files.filter { it.remoteKey in pendingKeys }.forEach { file ->
            CloudFolderTree.ancestors(folders, file.cloudFolderKey).forEach { keys += it.key }
        }
        return keys
    }
}
