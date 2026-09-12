package com.unihub.app.data.repository

import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.NoteEntity
import com.unihub.app.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ملخص الشاشة الرئيسية في مستودع واحد بدل 8 تدفقات متفرقة في ViewModel عام.
 * الشاشة الرئيسية تهمها "لقطة اليوم": محاضرات اليوم، مهام مستحقة، امتحانات قريبة.
 */
@Singleton
class DashboardRepository @Inject constructor(
    private val lectureRepository: LectureRepository,
    private val taskRepository: TaskRepository,
    private val examRepository: ExamRepository,
    private val noteRepository: NoteRepository,
    private val fileRepository: FileRepository
) {

    fun observeTodayLectures(): Flow<List<LectureEntity>> =
        lectureRepository.observeToday()

    fun observeDueSoonTasks(limit: Int = 3): Flow<List<TaskEntity>> =
        taskRepository.observeDueSoon(limit)

    fun observeUpcomingExams(limit: Int = 3): Flow<List<ExamEntity>> =
        examRepository.observeUpcomingLimited(limit)

    fun observePendingTaskCount(): Flow<Int> = taskRepository.observePendingCount()

    fun observeUpcomingExamCount(): Flow<Int> = examRepository.observeUpcomingCount()

    fun observeNoteCount(): Flow<Int> = noteRepository.observeCount()

    fun observeFileCount(): Flow<Int> = fileRepository.observeFileCount()

    fun observeTotalFileSize(): Flow<Long> = fileRepository.observeTotalSize()

    suspend fun toggleTask(task: TaskEntity) = taskRepository.toggleDone(task)
}
