package com.unihub.app.data.repository

import androidx.room.withTransaction
import com.unihub.app.data.local.UniHubDatabase
import com.unihub.app.data.storage.FileStorage
import com.unihub.app.notifications.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * عمليات صيانة شاملة (مسح كل البيانات) — كانت مبعثرة في شاشة الإعدادات
 * في التطبيق المرجعي؛ هنا في مستودع واحد يضمن تنظيف: الجداول، الملفات
 * الفيزيائية، وكل التذكيرات المجدولة.
 */
@Singleton
class DataMaintenanceRepository @Inject constructor(
    private val database: UniHubDatabase,
    private val fileStorage: FileStorage,
    private val reminderScheduler: ReminderScheduler
) {

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        withTransaction(database) {
            database.folderDao().deleteAll()
            database.fileDao().deleteAll()
            database.taskDao().deleteAll()
            database.noteDao().deleteAll()
            database.examDao().deleteAll()
            database.lectureDao().deleteAll()
        }
        fileStorage.clearAll()
        reminderScheduler.cancelAll()
    }
}
