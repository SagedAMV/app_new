package com.unihub.app.data.repository

import com.unihub.app.data.local.dao.NoteDao
import com.unihub.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** مستودع الملاحظات مع تحديث وقت التعديل تلقائياً */
@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao
) {

    fun observeAll(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeCount(): Flow<Int> = noteDao.observeCount()

    suspend fun getById(id: Long): NoteEntity? = noteDao.getById(id)

    suspend fun create(note: NoteEntity): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(note.copy(createdAt = now, updatedAt = now))
    }

    suspend fun update(note: NoteEntity) {
        noteDao.update(note.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun togglePinned(note: NoteEntity) {
        noteDao.setPinned(note.id, !note.isPinned)
    }

    suspend fun delete(note: NoteEntity) = noteDao.delete(note)
}
