package com.unihub.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.unihub.app.data.local.entity.ExamEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    @Query("SELECT * FROM exams ORDER BY date ASC, time ASC")
    fun observeAll(): Flow<List<ExamEntity>>

    /** الامتحانات القادمة (من اليوم فصاعداً) */
    @Query("SELECT * FROM exams WHERE date >= :todayIso ORDER BY date ASC, time ASC")
    fun observeUpcoming(todayIso: String): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams WHERE date >= :todayIso ORDER BY date ASC, time ASC LIMIT :limit")
    fun observeUpcomingLimited(todayIso: String, limit: Int): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams ORDER BY date ASC")
    suspend fun getAllOnce(): List<ExamEntity>

    @Query("SELECT * FROM exams WHERE id = :id")
    suspend fun getById(id: Long): ExamEntity?

    @Insert
    suspend fun insert(exam: ExamEntity): Long

    @Update
    suspend fun update(exam: ExamEntity)

    @Delete
    suspend fun delete(exam: ExamEntity)

    @Query("DELETE FROM exams")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM exams WHERE date >= :todayIso")
    fun observeUpcomingCount(todayIso: String): Flow<Int>
}
