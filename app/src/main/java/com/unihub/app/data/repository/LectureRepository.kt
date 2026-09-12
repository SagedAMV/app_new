package com.unihub.app.data.repository

import com.unihub.app.core.common.Weekdays
import com.unihub.app.data.local.dao.LectureDao
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.Weekday
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** مستودع الجدول الأسبوعي */
@Singleton
class LectureRepository @Inject constructor(
    private val lectureDao: LectureDao
) {

    fun observeAll(): Flow<List<LectureEntity>> = lectureDao.observeAll()

    fun observeByDay(day: Weekday): Flow<List<LectureEntity>> =
        lectureDao.observeByDay(day)

    /** محاضرات اليوم — ما تحتاجه الشاشة الرئيسية */
    fun observeToday(): Flow<List<LectureEntity>> =
        lectureDao.observeByDay(Weekdays.today())

    fun observeCount(): Flow<Int> = lectureDao.observeCount()

    suspend fun create(lecture: LectureEntity): Long = lectureDao.insert(lecture)

    suspend fun update(lecture: LectureEntity) = lectureDao.update(lecture)

    suspend fun delete(lecture: LectureEntity) = lectureDao.delete(lecture)
}
