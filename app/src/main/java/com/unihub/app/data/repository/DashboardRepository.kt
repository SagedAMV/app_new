package com.unihub.app.data.repository

import com.unihub.app.core.common.NextLecture
import com.unihub.app.core.common.NextLectureResolver
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.TaskEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ملخص الشاشة الرئيسية في مستودع واحد بدل 8 تدفقات متفرقة في ViewModel عام.
 * الشاشة الرئيسية تهمها "لقطة اليوم": محاضرات اليوم، مهام مستحقة، امتحانات قريبة،
 * وأقرب مهمة مطلوبة للبطاقة العلوية.
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

    /**
     * المحاضرة التالية من كامل الجدول الأسبوعي — تتحدث تلقائياً كل 30 ثانية
     * ليبقى العد التنازلي حياً، وتتحدث فورياً عند أي تعديل على الجدول.
     */
    fun observeNextLecture(): Flow<NextLecture?> =
        combine(lectureRepository.observeAll(), minuteTicker()) { all, now ->
            NextLectureResolver.resolve(all, now)
        }

    /** نبض زمني: يعيد اللحظة الحالية كل 30 ثانية */
    private fun minuteTicker(): Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }

    fun observeDueSoonTasks(limit: Int = 3): Flow<List<TaskEntity>> =
        taskRepository.observeDueSoon(limit)

    /** أقرب مهمة مطلوبة أياً كان موعد استحقاقها — لنصف الاستحقاقات في البطاقة العلوية */
    fun observeNearestTask(): Flow<TaskEntity?> =
        taskRepository.observeNearestPending()

    fun observeUpcomingExams(limit: Int = 3): Flow<List<ExamEntity>> =
        examRepository.observeUpcomingLimited(limit)

    fun observePendingTaskCount(): Flow<Int> = taskRepository.observePendingCount()

    fun observeUpcomingExamCount(): Flow<Int> = examRepository.observeUpcomingCount()

    fun observeNoteCount(): Flow<Int> = noteRepository.observeCount()

    fun observeFileCount(): Flow<Int> = fileRepository.observeFileCount()

    suspend fun toggleTask(task: TaskEntity) = taskRepository.toggleDone(task)
}
