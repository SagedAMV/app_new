package com.unihub.app.data.local.model

import androidx.room.ColumnInfo

/**
 * إسقاط خفيف لشاشة المجرة: المجلد + عدد ملفاته من استضمام واحد،
 * بدل جلب كل الملفات وحساب العدّادات في الذاكرة كما في التطبيق المرجعي.
 */
data class FolderWithFileCount(
    @ColumnInfo(name = "folderId") val folderId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color") val color: String,
    @ColumnInfo(name = "fileCount") val fileCount: Int
)
