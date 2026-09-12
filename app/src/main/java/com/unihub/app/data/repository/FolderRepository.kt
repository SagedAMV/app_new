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

    /**
     * حذف مجلد مع كل فرعه: يحذف الملفات الفيزيائية لكل المجلدات في الشجرة
     * الفرعية قبل حذف الصفوف (قاعدة البيانات تحذف الصفوف عبر CASCADE لكن الملفات
     * على القرص تحتاج تنظيفاً يدوياً).
     */
    suspend fun deleteDeep(folder: FolderEntity) {
        val all = folderDao.getAllOnce()
        val subtreeIds = mutableSetOf(folder.id)
        var grew = true
        while (grew) {
            grew = false
            for (candidate in all) {
                if (candidate.parentId in subtreeIds && subtreeIds.add(candidate.id)) {
                    grew = true
                }
            }
        }
        fileDao.getInFoldersOnce(subtreeIds.toList())
            .forEach { fileStorage.delete(it.filePath) }
        folderDao.delete(folder)
    }
}
