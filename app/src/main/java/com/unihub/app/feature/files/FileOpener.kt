package com.unihub.app.feature.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.unihub.app.data.local.entity.FileEntity
import java.io.File

/**
 * فتح الملفات ومشاركتها عبر تطبيقات النظام باستخدام FileProvider + ACTION_VIEW/SEND.
 * معزولة في كائن واحد بدل تكرار نفس المنطق داخل الواجهة كما في التطبيق المرجعي.
 */
object FileOpener {

    /** يفتح الملف بتطبيق خارجي. يعيد false إن لا يوجد تطبيق مناسب */
    fun open(context: Context, file: FileEntity): Boolean =
        runCatching {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "فتح الملف").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.recover { it is ActivityNotFoundException }.getOrElse { false }

    /** يشارك الملف مع تطبيقات أخرى */
    fun share(context: Context, file: FileEntity): Boolean =
        runCatching {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = file.mimeType.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "مشاركة الملف").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.recover { it is ActivityNotFoundException }.getOrElse { false }

    private fun uriFor(context: Context, file: FileEntity) =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(file.filePath)
        )
}
