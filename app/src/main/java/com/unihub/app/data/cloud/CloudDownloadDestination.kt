package com.unihub.app.data.cloud

import com.unihub.app.core.validation.InputValidator
import com.unihub.app.data.local.entity.FolderEntity

/** اختيار الوجهة لا يغيّر أسماء أو تنظيم مجلدات المستخدم الحالية. */
enum class CloudDownloadLocation { ORIGINAL_CLOUD_TREE, LOCAL_FOLDER, FOLDER_INSIDE_LOCAL }

/**
 * جولة تعليمات.md: «ترتيب تلقائي .. موصى بة» هو الخيار المفعّل افتراضياً عند فتح
 * حوار الوجهة، مع بقاء الاختيار كاملاً للمستخدم. مصدر وحيد تقرأه الواجهة ويثبته
 * اختبار محلي — حتى لا ينحرف الافتراضي في الواجهة عن طبقة البيانات.
 */
object CloudDownloadDefaults {
    val location: CloudDownloadLocation = CloudDownloadLocation.ORIGINAL_CLOUD_TREE
}
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

    /**
     * Find an existing local folder at the exact destination level. This mirrors
     * upload's name matching while preserving the existing local folder's ID/name.
     * If old duplicates already exist, the lowest ID is chosen deterministically.
     */
    fun findExistingLocalFolder(folders: List<FolderEntity>, name: String, parentId: Long?): FolderEntity? {
        val candidateName = InputValidator.sanitizeName(name)
        if (candidateName.isBlank()) return null
        return folders.asSequence()
            .filter { folder ->
                folder.parentId == parentId &&
                    CloudUploadMergeRules.sameName(InputValidator.sanitizeName(folder.name), candidateName)
            }
            .minByOrNull { it.id }
    }

    fun shouldCreateCloudFolders(destination: CloudDownloadDestination): Boolean =
        destination.location != CloudDownloadLocation.LOCAL_FOLDER

    fun localRootName(destination: CloudDownloadDestination, cloudRoot: RemoteCloudFolder): String =
        destination.rootFolderName?.trim()?.takeIf { it.isNotEmpty() } ?: cloudRoot.name
}
