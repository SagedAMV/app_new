package com.unihub.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.unihub.app.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /**
     * ترتيب عرض المهام: غير المنجزة أولاً، ثم حسب الأولوية، ثم الاستحقاق
     * (المواعيد الفارغة تُدفع لآخر القائمة بدل أن تتصدرها كما يحدث عند
     * الترتيب النصي البسيط).
     */
    @Query(
        "SELECT * FROM tasks ORDER BY " +
            "isDone ASC, " +
            "CASE priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END ASC, " +
            "CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END ASC, " +
            "dueDate ASC, " +
            "createdAt DESC"
    )
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<TaskEntity>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    /** تبديل حالة الإنجاز مع تسجيل وقت الإنجاز في عملية واحدة */
    @Query("UPDATE tasks SET isDone = :done, completedAt = :completedAt WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean, completedAt: Long?)

    @Query("SELECT COUNT(*) FROM tasks WHERE isDone = 0")
    fun observePendingCount(): Flow<Int>

    /** المهام المستحقة اليوم أو متأخرة (للرئيسية) */
    @Query(
        "SELECT * FROM tasks WHERE isDone = 0 AND dueDate IS NOT NULL " +
            "AND dueDate <= :todayIso ORDER BY dueDate ASC LIMIT :limit"
    )
    fun observeDueSoon(todayIso: String, limit: Int): Flow<List<TaskEntity>>

    /**
     * أقرب مهمة مطلوبة: غير منجزة ولها تاريخ استحقاق (ماضٍ أو مستقبلي)، مرتبة
     * تصاعدياً حسب الاستحقاق فيكون الصف الأول هو الأقرب موعداً. تستعملها
     * الشاشة الرئيسية لعرض «أقرب مهمة مطلوبة» حتى لو لم يكن استحقاقها اليوم —
     * بخلاف [observeDueSoon] المحصورة في اليوم وما قبله.
     */
    @Query(
        "SELECT * FROM tasks WHERE isDone = 0 AND dueDate IS NOT NULL " +
            "AND dueDate != '' ORDER BY dueDate ASC LIMIT 1"
    )
    fun observeNearestPending(): Flow<TaskEntity?>
}
