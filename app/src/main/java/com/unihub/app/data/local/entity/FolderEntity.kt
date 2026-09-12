package com.unihub.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * مجلد مادة/تصنيف. يدعم تداخلاً واحداً (مجلد أب → مجلدات فرعية).
 * حذف مجلد يحذف أبناءه وملفاته عبر CASCADE على مستوى قاعدة البيانات،
 * بينما يتولى المستودع حذف الملفات الفيزيائية من التخزين.
 */
@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentId"), Index("createdAt")]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    /** لون سداسي للتمييز البصري في المجرة والبطاقات */
    val color: String = "#4E7D6E",
    val parentId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** ترتيب يدوي اختياري داخل نفس المستوى */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
)
