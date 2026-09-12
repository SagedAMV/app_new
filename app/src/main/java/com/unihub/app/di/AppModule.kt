package com.unihub.app.di

import android.content.Context
import androidx.room.Room
import com.unihub.app.data.local.UniHubDatabase
import com.unihub.app.data.local.dao.ExamDao
import com.unihub.app.data.local.dao.FileDao
import com.unihub.app.data.local.dao.FolderDao
import com.unihub.app.data.local.dao.LectureDao
import com.unihub.app.data.local.dao.NoteDao
import com.unihub.app.data.local.dao.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * وحدة الحقن الرئيسية. كل المستودعات ومدير التفضيلات ومجدول التذكيرات
 * تُبنى بمنشئات @Inject ولا تحتاج تعريفاً هنا — فقط قاعدة البيانات وواجهاتها.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): UniHubDatabase =
        Room.databaseBuilder(context, UniHubDatabase::class.java, UniHubDatabase.NAME)
            // تطبيق شخصي غير منشور: عند أي تعارض مخطط مستقبلي تُبنى القاعدة من جديد
            // بدل الدخول في حلقة كراش. في تطبيق إنتاجي يجب استبدال هذا بهجرات صريحة.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideFolderDao(db: UniHubDatabase): FolderDao = db.folderDao()
    @Provides fun provideFileDao(db: UniHubDatabase): FileDao = db.fileDao()
    @Provides fun provideLectureDao(db: UniHubDatabase): LectureDao = db.lectureDao()
    @Provides fun provideTaskDao(db: UniHubDatabase): TaskDao = db.taskDao()
    @Provides fun provideNoteDao(db: UniHubDatabase): NoteDao = db.noteDao()
    @Provides fun provideExamDao(db: UniHubDatabase): ExamDao = db.examDao()
}
