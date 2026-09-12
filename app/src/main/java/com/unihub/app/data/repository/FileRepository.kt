package com.unihub.app.data.repository

import com.unihub.app.data.local.dao.FileDao
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.data.storage.FileStorage
import com.unihub.app.data.storage.ImportedFile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** مستودع الملفات: السجلات + النسخ الفيزيائية معاً لضمان عدم تسرّب أي منهما */
@Singleton
class FileRepository @Inject constructor(
    private val fileDao: FileDao,
    private val fileStorage: FileStorage
) {

    fun observeInFolder(folderId: Long?): Flow<List<FileEntity>> =
        fileDao.observeFilesInFolder(folderId)

    fun observeFileCount(): Flow<Int> = fileDao.observeFileCount()

    /** استيراد ملف من المنتقي وتسجيله في مجلد معيّن */
    suspend fun import(uri: android.net.Uri, mimeTypeFallback: String, folderId: Long?): FileEntity {
        val imported: ImportedFile = fileStorage.import(uri, mimeTypeFallback)
        val entity = FileEntity(
            name = imported.displayName,
            extension = imported.extension,
            kind = FileKind.fromExtension(imported.extension),
            mimeType = imported.mimeType,
            size = imported.size,
            folderId = folderId,
            filePath = imported.absolutePath
        )
        val id = fileDao.insert(entity)
        return entity.copy(id = id)
    }

    /** حذف سجل الملف ونسخته الفيزيائية معاً */
    suspend fun delete(file: FileEntity) {
        fileDao.delete(file)
        fileStorage.delete(file.filePath)
    }

    suspend fun rename(file: FileEntity, newName: String) {
        fileDao.update(file.copy(name = newName))
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) =
        fileDao.setFavorite(id, favorite)
}
