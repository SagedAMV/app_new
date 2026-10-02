package com.unihub.app.data.cloud

import com.unihub.app.core.validation.InputValidator
import com.unihub.app.data.local.entity.FileEntity
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
     * إيجاد مجلد محلي موجود في نفس المستوى الأب تماماً (الجذر أو مجلد فرعي).
     * يطابق منطق دمج الرفع [CloudUploadMergeRules.findExistingFolder] مع الحفاظ على
     * معرّف واسم مجلد المستخدم الأصلي، واختيار المجلد الأقدم (الأقل id) حتمياً.
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

    /**
     * حلّ المجلد المحلي المقابل لمجلد سحابي عند التنزيل دون إنشاء مجلد مكرر بنفس الاسم:
     * 1) نتحقق من صلاحية الأب [parentId] ضمن المجلدات المحلية الموجودة.
     * 2) نبحث أولاً عن مجلد محلي بنفس الاسم في نفس المستوى الأب [validParent] (الأقدم id أولى).
     * 3) إن وُجد رابط سابق [link] أو معرّف قديم [RemoteCloudFolder.legacyId] في نفس المستوى
     *    وبنفس الاسم، وكان هو الأقدم أو الوحيد، يُعتمد؛ ولا يُسمح لرابط قديم في مستوى آخر
     *    أو باسم مختلف أن يتجاوز المجلد المطابق في المستوى الهدف.
     */
    fun resolveLocalFolder(
        folders: List<FolderEntity>,
        remote: RemoteCloudFolder,
        parentId: Long?,
        link: CloudFolderLink?
    ): FolderEntity? {
        val byId = folders.associateBy { it.id }
        val validParent = parentId?.takeIf { it in byId }
        val safeName = InputValidator.sanitizeName(remote.name).ifBlank { "مجلد سحابي" }
        val existingByName = findExistingLocalFolder(folders, safeName, validParent)
        if (existingByName != null) return existingByName

        val linked = link?.let { byId[it.localId] }?.takeIf {
            it.createdAt == link.localCreatedAt &&
                it.parentId == validParent &&
                CloudUploadMergeRules.sameName(InputValidator.sanitizeName(it.name), safeName)
        }
        if (linked != null) return linked

        return remote.legacyId?.let { byId[it] }?.takeIf {
            it.createdAt == remote.createdAt &&
                it.parentId == validParent &&
                CloudUploadMergeRules.sameName(InputValidator.sanitizeName(it.name), safeName)
        }
    }

    /**
     * في وضع [CloudDownloadLocation.FOLDER_INSIDE_LOCAL]، إذا اختار المستخدم كوجهة
     * مجلداً محلياً يحمل أصلاً نفس اسم الجذر المراد تنزيله، ندمج المحتويات داخل
     * هذا المجلد المختار مباشرة بدل إنشاء مجلد فرعي متداخل بنفس الاسم (مثل محاضرات/محاضرات).
     */
    fun shouldReuseChosenFolderAsRoot(chosenLocal: FolderEntity?, rootName: String): Boolean {
        if (chosenLocal == null) return false
        val safeChosen = InputValidator.sanitizeName(chosenLocal.name)
        val safeRoot = InputValidator.sanitizeName(rootName)
        return safeChosen.isNotBlank() &&
            safeRoot.isNotBlank() &&
            CloudUploadMergeRules.sameName(safeChosen, safeRoot)
    }

    /**
     * نظير [CloudUploadMergeRules.findExistingFile] عند السحب من السحابة (تعليمات.md):
     * يتحقق مما إذا كان الملف موجوداً مسبقاً داخل المجلد المحلي الوجهة [targetFolderId]
     * بنفس الاسم والامتداد والحجم (ومع تطابق البصمة [remoteSha256] إن توفرت البصمتان).
     *
     * - إذا وُجد الملف المطابق داخل المجلد الوجهة نفسه: يُتجاهل تنزيله ولا يُكرَّر.
     * - إذا كان الملف المحلي في مجلد آخر، أو مختلف الاسم/الامتداد/الحجم/البصمة: يعيد null.
     */
    fun findExistingLocalFile(
        localFiles: List<FileEntity>,
        name: String,
        extension: String,
        size: Long,
        targetFolderId: Long?,
        remoteSha256: String = "",
        localSha256ById: Map<Long, String> = emptyMap()
    ): FileEntity? {
        val candidateName = InputValidator.sanitizeName(name)
        if (candidateName.isBlank() || size < 0L) return null
        val candidateExt = normalizeExtension(extension)
        val normalizedRemoteSha = remoteSha256.trim()
        val hasRemoteSha = CloudPresenceMatcher.strongHash(normalizedRemoteSha)

        return localFiles.asSequence()
            .filter { file ->
                file.folderId == targetFolderId &&
                    file.size == size &&
                    normalizeExtension(file.extension).equals(candidateExt, ignoreCase = true) &&
                    CloudUploadMergeRules.sameName(InputValidator.sanitizeName(file.name), candidateName) &&
                    (!hasRemoteSha || localSha256ById[file.id]?.takeIf { CloudPresenceMatcher.strongHash(it) }
                        ?.equals(normalizedRemoteSha, ignoreCase = true) == true)
            }
            .minByOrNull { it.id }
    }

    private fun normalizeExtension(ext: String): String =
        ext.trim().trim('.').filter { it.isLetterOrDigit() }

    fun shouldCreateCloudFolders(destination: CloudDownloadDestination): Boolean =
        destination.location != CloudDownloadLocation.LOCAL_FOLDER

    fun localRootName(destination: CloudDownloadDestination, cloudRoot: RemoteCloudFolder): String =
        destination.rootFolderName?.trim()?.takeIf { it.isNotEmpty() } ?: cloudRoot.name
}
