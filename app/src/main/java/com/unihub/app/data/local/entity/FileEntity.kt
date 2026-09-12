package com.unihub.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * سجل ملف مستورد عبر منتقي النظام (SAF) ومنسوخ داخل تخزين التطبيق.
 * التحسين: [kind] نوع قوي (enum) بدل نص حر، وفهرس على المفضلة لعلامات التبويب السريعة.
 */
@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("folderId"),
        Index("createdAt"),
        Index("isFavorite"),
        Index("name")
    ]
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val extension: String,
    val kind: FileKind = FileKind.OTHER,
    val mimeType: String,
    val size: Long,
    val folderId: Long?,
    /** المسار الفعلي للنسخة داخل تخزين التطبيق الداخلي */
    val filePath: String,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
