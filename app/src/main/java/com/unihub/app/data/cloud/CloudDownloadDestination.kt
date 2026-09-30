package com.unihub.app.data.cloud

/** اختيار الوجهة لا يغيّر أسماء أو تنظيم مجلدات المستخدم الحالية. */
enum class CloudDownloadLocation { ORIGINAL_CLOUD_TREE, LOCAL_FOLDER, FOLDER_INSIDE_LOCAL }
data class CloudDownloadDestination(
    val location: CloudDownloadLocation = CloudDownloadLocation.ORIGINAL_CLOUD_TREE,
    val localFolderId: Long? = null,
    val localFolderCreatedAt: Long? = null,
    /** اختيار مجلد كامل: اسم الجذر داخل وجهة المستخدم قابل للتغيير. */
    val rootFolderName: String? = null
)

object CloudDownloadPlacement {
    fun relativeFolders(folders: List<RemoteCloudFolder>, cloudKey: String?, anchorKey: String?): List<RemoteCloudFolder> {
        val all = CloudFolderTree.ancestors(folders, cloudKey)
        if (anchorKey == null) return all
        val anchor = all.indexOfFirst { it.key == anchorKey }
        return if (anchor < 0) emptyList() else all.drop(anchor)
    }
    fun shouldCreateCloudFolders(destination: CloudDownloadDestination): Boolean = destination.location != CloudDownloadLocation.LOCAL_FOLDER
    fun localRootName(destination: CloudDownloadDestination, cloudRoot: RemoteCloudFolder): String =
        destination.rootFolderName?.trim()?.takeIf { it.isNotEmpty() } ?: cloudRoot.name
}
