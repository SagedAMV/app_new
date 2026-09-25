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
        return insertFromImport(imported, folderId)
    }

    /**
     * حفظ صورة التُقطت من كاميرا التطبيق داخل مجلد معيّن.
     * [source] ملف مؤقت في الكاش — يُنقل للمكتبة ويُحذف الأصل.
     */
    suspend fun saveCapturedImage(source: java.io.File, name: String?, folderId: Long?): FileEntity {
        val imported = fileStorage.saveCapturedImage(source, name)
        return insertFromImport(imported, folderId)
    }

    /** حفظ تسجيل صوتي من مسجل التطبيق داخل مجلد معيّن */
    suspend fun saveAudioRecording(source: java.io.File, name: String?, folderId: Long?): FileEntity {
        val imported = fileStorage.saveAudioRecording(source, name)
        return insertFromImport(imported, folderId)
    }

    /** بناء سجل FileEntity من نتيجة حفظ/استيراد ناجحة وإدراجه في القاعدة */
    private suspend fun insertFromImport(imported: ImportedFile, folderId: Long?): FileEntity {
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

    /** حذف سجل فقط (عندما تكون النسخة الفيزيائية مفقودة أصلاً) */
    suspend fun deleteRecord(file: FileEntity) {
        fileDao.delete(file)
    }

    /**
     * إعادة تسمية ملف: تُعيد تسمية النسخة الفعلية على القرص أولاً لتطابق
     * الاسم الجديد (يُصلح فشل مشاركة/فتح الملف باسمه القديم عبر FileProvider)،
     * ثم تُحدَّث قاعدة البيانات بالاسم الجديد ومساره الفعلي الجديد معاً.
     *
     * إن تعذّرت إعادة التسمية الفعلية (مثلاً حُذفت النسخة الفعلية خارج
     * التطبيق) نتدهور بلطف: يُحدَّث الاسم الظاهر فقط في قاعدة البيانات بدل
     * فشل العملية كاملة على المستخدم.
     */
    suspend fun rename(file: FileEntity, newName: String) {
        val newPath = runCatching {
            fileStorage.rename(file.filePath, newName, file.extension)
        }.getOrElse { file.filePath }
        fileDao.update(file.copy(name = newName, filePath = newPath))
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = fileDao.setFavorite(id, favorite)

    /**
     * نقل ملفات إلى مجلد آخر (أو إلى الجذر عند NULL). التخزين الفيزيائي مسطح
     * في مجلد مكتبة واحد، فالنقل تحديث منطقي لقاعدة البيانات فقط — لا حركة
     * ملفات على القرص ولا خطر فقدان نسخ فعلية.
     */
    suspend fun moveToFolder(fileIds: List<Long>, targetFolderId: Long?) {
        if (fileIds.isEmpty()) return
        fileDao.setFolder(fileIds, targetFolderId)
    }
}
