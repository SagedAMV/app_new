package com.unihub.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * محاضرة في الجدول الأسبوعي.
 * التحسين: اليوم enum بدل نص عربي حر كان يُقارن حرفياً في الاستعلامات.
 */
@Entity(
    tableName = "lectures",
    indices = [Index("day"), Index("timeFrom")]
)
data class LectureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val doctor: String = "",
    val day: Weekday,
    /** HH:mm */
    val timeFrom: String,
    /** HH:mm — اختياري */
    val timeTo: String = "",
    val room: String = ""
)
