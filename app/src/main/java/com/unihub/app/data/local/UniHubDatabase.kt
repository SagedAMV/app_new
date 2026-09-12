package com.unihub.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.unihub.app.data.local.dao.ExamDao
import com.unihub.app.data.local.dao.FileDao
import com.unihub.app.data.local.dao.FolderDao
import com.unihub.app.data.local.dao.LectureDao
import com.unihub.app.data.local.dao.NoteDao
import com.unihub.app.data.local.dao.TaskDao
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FolderEntity
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.NoteEntity
import com.unihub.app.data.local.entity.TaskEntity

/**
 * قاعدة بيانات التطبيق. تبدأ من الإصدار 1 بمخطط نظيف (لا هجرات موروثة من
 * التطبيق المرجعي الذي راكم 4 إصدارات وهجرات). أي تغيير مستقبلي يُضاف كترقية
 * صريحة أو يُرفع الإصدار مع استراتيجية إعادة بناء (تطبيق شخصي غير منشور).
 */
@Database(
    entities = [
        FolderEntity::class,
        FileEntity::class,
        LectureEntity::class,
        TaskEntity::class,
        NoteEntity::class,
        ExamEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class UniHubDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun fileDao(): FileDao
    abstract fun lectureDao(): LectureDao
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun examDao(): ExamDao

    companion object {
        const val NAME = "unihub_db"
    }
}
