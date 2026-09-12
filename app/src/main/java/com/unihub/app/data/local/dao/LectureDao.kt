package com.unihub.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.Weekday
import kotlinx.coroutines.flow.Flow

@Dao
interface LectureDao {

    @Query("SELECT * FROM lectures ORDER BY day ASC, timeFrom ASC")
    fun observeAll(): Flow<List<LectureEntity>>

    @Query("SELECT * FROM lectures WHERE day = :day ORDER BY timeFrom ASC")
    fun observeByDay(day: Weekday): Flow<List<LectureEntity>>

    @Query("SELECT * FROM lectures ORDER BY day ASC, timeFrom ASC")
    suspend fun getAllOnce(): List<LectureEntity>

    @Insert
    suspend fun insert(lecture: LectureEntity): Long

    @Update
    suspend fun update(lecture: LectureEntity)

    @Delete
    suspend fun delete(lecture: LectureEntity)

    @Query("DELETE FROM lectures")
    suspend fun deleteAll()
}
