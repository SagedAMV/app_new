package com.unihub.app.data.repository

import com.unihub.app.data.local.dao.FileDao
import com.unihub.app.data.local.dao.FolderDao
import com.unihub.app.data.local.entity.FolderEntity
import com.unihub.app.data.local.model.FolderWithFileCount
import com.unihub.app.data.storage.FileStorage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مستودع المجلدات. التحسين عن المرجع: حذف المجلد يحسب الشجرة الفرعية ويحذف
 * الملفات الفيزيائية في نفس المكان بدل منطق متكرر داخل الـ ViewModel.
 */
@Singleton
class FolderRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val fileDao: FileDao,
    private val fileStorage: FileStorage
) {

    fun observeChildren(parentId: Long?): Flow<List<FolderEntity>> =
        if (parentId == null) folderDao.observeRootFolders()
        else folderDao.observeChildFolders(parentId)

    fun observeFoldersWithFileCount(): Flow<List<FolderWithFileCount>> =
        folderDao.observeFoldersWithFileCount()

    suspend fun getById(id: Long): FolderEntity? = folderDao.getById(id)

    suspend fun create(folder: FolderEntity): Long = folderDao.insert(folder)

    suspend fun update(folder: FolderEntity) = folderDao.update(folder)

    /** لقطة كل المجلدات — لمنتقي النقل والحسابات الشجرية */
    suspend fun allOnce(): List<FolderEntity> = folderDao.getAllOnce()

    /** نتيجة محاولة نقل مجلد إلى أب جديد */
    enum class MoveResult { MOVED, SAME_PLACE, CYCLE, MISSING_PARENT }

    /**
     * نقل مجلد إلى أب جديد (أو إلى الجذر عند NULL) مع حماية كاملة من الحلقات:
     * يُرفض النقل إن كان الهدف هو المجلد نفسه أو أحد أحفاده (إذ سيصبح أباً
     * لنفسه دورياً ويختفي من الشجرة)، وإن كان الأب الهدف غير موجود.
     */
    suspend fun move(folder: FolderEntity, newParentId: Long?): MoveResult {
        if (newParentId == folder.parentId) return MoveResult.SAME_PLACE
        if (newParentId != null && folderDao.getById(newParentId) == null) {
            return MoveResult.MISSING_PARENT
        }
        val all = folderDao.getAllOnce()
        if (FolderTree.wouldCreateCycle(all, folder.id, newParentId)) {
            return MoveResult.CYCLE
        }
        folderDao.update(folder.copy(parentId = newParentId))
        return MoveResult.MOVED
    }

    /**
     * حذف مجلد مع كل فرعه: يحذف الملفات الفيزيائية لكل المجلدات في الشجرة
     * الفرعية قبل حذف الصفوف (قاعدة البيانات تحذف الصفوف عبر CASCADE لكن الملفات
     * على القرص تحتاج تنظيفاً يدوياً). حساب الشجرة الفرعية موحّد في [FolderTree].
     */
    suspend fun deleteDeep(folder: FolderEntity) {
        val subtreeIds = FolderTree.subtreeIds(folderDao.getAllOnce(), folder.id)
        fileDao.getInFoldersOnce(subtreeIds.toList())
            .forEach { fileStorage.delete(it.filePath) }
        folderDao.delete(folder)
    }
}

/**
 * منطق شجرة المجلدات النقي (بلا Room ولا Coroutines) — قابل للاختبار مباشرة،
 * وموحّد لكل الحسابات الشجرية (الحذف العميق، كشف حلقات النقل، منتقي النقل)
 * بدل تكرار خوارزمية الانتشار في كل موضع.
 */
object FolderTree {

    /**
     * كل معرفات الشجرة الفرعية: الجذر المعطى + كل أحفاده بأي عمق.
     * حلقة الانتشار تعمل مستوى بمستوى حتى لا يضاف أي حفيد جديد.
     */
    fun subtreeIds(all: List<FolderEntity>, rootId: Long): Set<Long> {
        val ids = mutableSetOf(rootId)
        var grew = true
        while (grew) {
            grew = false
            for (candidate in all) {
                if (candidate.parentId in ids && ids.add(candidate.id)) {
                    grew = true
                }
            }
        }
        return ids
    }

    /**
     * هل نقل المجلد [folderId] تحت الأب [newParentId] ينشئ حلقة؟
     * نتسلق سلسلة الآباء من الهدف: إن مررنا بالمجلد المنقول فالهدف حفيده
     * أي حلقة. مجموعة [seen] حماية دفاعية من بيانات فاسدة بدورة موجودة أصلاً.
     */
    fun wouldCreateCycle(all: List<FolderEntity>, folderId: Long, newParentId: Long?): Boolean {
        if (newParentId == null) return false
        if (newParentId == folderId) return true
        val byId = all.associateBy { it.id }
        val seen = mutableSetOf<Long>()
        var current: Long? = newParentId
        while (current != null) {
            if (current == folderId) return true
            if (!seen.add(current)) return false
            current = byId[current]?.parentId
        }
        return false
    }
}
