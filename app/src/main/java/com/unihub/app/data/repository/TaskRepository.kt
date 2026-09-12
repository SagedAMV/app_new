package com.unihub.app.data.repository

import com.unihub.app.data.local.dao.TaskDao
import com.unihub.app.data.local.entity.TaskEntity
import com.unihub.app.notifications.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** مستودع المهام مع جدولة/إلغاء التذكيرات تلقائياً ضمن نفس العملية */
@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val reminderScheduler: ReminderScheduler
) {

    fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()
    fun observePending(): Flow<List<TaskEntity>> = taskDao.observePending()
    fun observePendingCount(): Flow<Int> = taskDao.observePendingCount()

    fun observeDueSoon(limit: Int = 3): Flow<List<TaskEntity>> =
        taskDao.observeDueSoon(com.unihub.app.core.common.DateFormats.todayIso(), limit)

    suspend fun create(task: TaskEntity): Long {
        val id = taskDao.insert(task)
        if (!task.dueDate.isNullOrBlank()) {
            reminderScheduler.scheduleTaskReminder(task.copy(id = id))
        }
        return id
    }

    suspend fun update(task: TaskEntity) {
        taskDao.update(task)
        reminderScheduler.cancelTaskReminder(task.id)
        if (!task.isDone && !task.dueDate.isNullOrBlank()) {
            reminderScheduler.scheduleTaskReminder(task)
        }
    }

    suspend fun toggleDone(task: TaskEntity) {
        val done = !task.isDone
        taskDao.setDone(task.id, done, if (done) System.currentTimeMillis() else null)
        if (done) reminderScheduler.cancelTaskReminder(task.id)
        else if (!task.dueDate.isNullOrBlank()) reminderScheduler.scheduleTaskReminder(task)
    }

    suspend fun delete(task: TaskEntity) {
        reminderScheduler.cancelTaskReminder(task.id)
        taskDao.delete(task)
    }
}
