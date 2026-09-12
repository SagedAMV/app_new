package com.unihub.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.unihub.app.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    /** ملفات مجلد معيّن (الجذر عندما يكون المعامل NULL — يُطابق صراحةً) */
    @Query(
        "SELECT * FROM files " +
            "WHERE (:folderId IS NULL AND folderId IS NULL) OR folderId = :folderId " +
            "ORDER BY name COLLATE NOCASE ASC"
    )
    fun observeFilesInFolder(folderId: Long?): Flow<List<FileEntity>>

    @Query("SELECT * FROM files ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<FileEntity>

    @Query("SELECT * FROM files WHERE folderId IN (:folderIds)")
    suspend fun getInFoldersOnce(folderIds: List<Long>): List<FileEntity>

    @Insert
    suspend fun insert(file: FileEntity): Long

    @Update
    suspend fun update(file: FileEntity)

    @Delete
    suspend fun delete(file: FileEntity)

    @Query("DELETE FROM files")
    suspend fun deleteAll()

    @Query("UPDATE files SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM files")
    fun observeFileCount(): Flow<Int>
}
