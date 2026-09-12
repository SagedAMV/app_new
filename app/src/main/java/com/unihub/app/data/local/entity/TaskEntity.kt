package com.unihub.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * مهمة. التحسينات عن المرجع:
 * - الأولوية enum بدل نص
 * - تاريخ الاستحقاق بصيغة ISO موحدة
 * - حقل [completedAt] لمعرفة متى أُنجزت المهمة (يفيد في الترتيب والإحصاء)
 */
@Entity(
    tableName = "tasks",
    indices = [Index("isDone"), Index("dueDate"), Index("createdAt")]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    /** yyyy-MM-dd أو null إن لم تكن مرتبطة بتاريخ */
    val dueDate: String? = null,
    val isDone: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** هل المهمة متأخرة؟ (لها تاريخ استحقاق ماضٍ وغير منجزة) */
    val isOverdue: Boolean
        get() = !isDone &&
            dueDate != null &&
            com.unihub.app.core.common.DateFormats.daysUntil(dueDate)?.let { it < 0 } == true
}
