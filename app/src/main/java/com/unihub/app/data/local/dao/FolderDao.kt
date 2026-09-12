package com.unihub.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.unihub.app.data.local.entity.FolderEntity
import com.unihub.app.data.local.model.FolderWithFileCount
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    /** المجلدات الجذرية مرتبة حسب الترتيب اليدوي ثم الأحدث */
    @Query(
        "SELECT * FROM folders WHERE parentId IS NULL " +
            "ORDER BY sortOrder ASC, createdAt DESC"
    )
    fun observeRootFolders(): Flow<List<FolderEntity>>

    /**
     * المجلدات الأبناء. ملاحظة: في SQLite لا يصح "parentId = :parentId" عندما يكون
     * المعامل NULL، لذا يُطابق الجذر صراحةً بفرع منفصل (خلل كان موجوداً في التطبيق
     * المرجعي وتمت معالجته هنا من الأصل).
     */
    @Query(
        "SELECT * FROM folders WHERE parentId = :parentId " +
            "ORDER BY sortOrder ASC, name COLLATE NOCASE ASC"
    )
    fun observeChildFolders(parentId: Long): Flow<List<FolderEntity>>

    /** كل المجلدات مع عدد ملفاتها — استعلام واحد بدل قائمة + استعلام لكل مجلد */
    @Query(
        "SELECT folders.id AS folderId, folders.name AS name, folders.color AS color, " +
            "COUNT(files.id) AS fileCount " +
            "FROM folders LEFT JOIN files ON files.folderId = folders.id " +
            "GROUP BY folders.id ORDER BY folders.sortOrder ASC, folders.name COLLATE NOCASE ASC"
    )
    fun observeFoldersWithFileCount(): Flow<List<FolderWithFileCount>>

    @Query("SELECT * FROM folders ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}
