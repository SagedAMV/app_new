package com.unihub.app.data.repository

import com.unihub.app.core.common.DateFormats
import com.unihub.app.data.local.dao.ExamDao
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.notifications.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** مستودع الامتحانات: جدولة التذكيرات (قبل يوم/ساعة/عند البدء) مرتبطة بدورة حياة السجل */
@Singleton
class ExamRepository @Inject constructor(
    private val examDao: ExamDao,
    private val reminderScheduler: ReminderScheduler
) {

    fun observeAll(): Flow<List<ExamEntity>> = examDao.observeAll()

    fun observeUpcomingLimited(limit: Int = 3): Flow<List<ExamEntity>> =
        examDao.observeUpcomingLimited(DateFormats.todayIso(), limit)

    fun observeUpcomingCount(): Flow<Int> =
        examDao.observeUpcomingCount(DateFormats.todayIso())

    suspend fun create(exam: ExamEntity): Long {
        val id = examDao.insert(exam)
        reminderScheduler.scheduleExamReminders(exam.copy(id = id))
        return id
    }

    suspend fun update(exam: ExamEntity) {
        examDao.update(exam)
        reminderScheduler.cancelExamReminders(exam.id)
        reminderScheduler.scheduleExamReminders(exam)
    }

    suspend fun delete(exam: ExamEntity) {
        reminderScheduler.cancelExamReminders(exam.id)
        examDao.delete(exam)
    }
}
