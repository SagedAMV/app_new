package com.unihub.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ملاحظة. التحسين عن المرجع: إضافة [isPinned] لتثبيت الملاحظات المهمة في الأعلى —
 * ميزة عملية تفتقدها الشاشة الأصلية.
 */
@Entity(
    tableName = "notes",
    indices = [Index("updatedAt"), Index("isPinned")]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
