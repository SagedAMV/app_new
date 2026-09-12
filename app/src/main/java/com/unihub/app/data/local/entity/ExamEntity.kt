package com.unihub.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * امتحان. التحسين: النوع enum والتاريخ ISO موحّد، مع فهرس على التاريخ
 * لأن أغلب الاستعلامات ترتّب بحسبه.
 */
@Entity(
    tableName = "exams",
    indices = [Index("date"), Index("type")]
)
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val type: ExamType,
    /** yyyy-MM-dd */
    val date: String,
    /** HH:mm — اختياري، يُفترض 9:00 صباحاً عند الغياب */
    val time: String = "",
    val room: String = "",
    val notes: String = ""
) {
    /** عدد الأيام المتبقية (سالب إن انقضى) */
    val daysRemaining: Long?
        get() = com.unihub.app.core.common.DateFormats.daysUntil(date)

    val isPast: Boolean
        get() = daysRemaining?.let { it < 0 } == true
}
