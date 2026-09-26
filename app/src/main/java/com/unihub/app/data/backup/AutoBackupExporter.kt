package com.unihub.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.unihub.app.core.prefs.AutoBackupPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * التنفيذ الفعلي للنسخ الاحتياطي التلقائي إلى المجلد الذي اختاره المستخدم عبر
 * SAF (DocumentFile)، بعيداً عن العامل نفسه ليسهل استدعاؤه من أي سياق.
 *
 * يُكتب سطر الشفرة ([BackupSignature]) في رأس كل نسخة، فتتعرّف عليها أي عملية
 * مشاركة لاحقة وتعرض «هل تريد استيراد نسخة؟».
 */
@Singleton
class AutoBackupExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val preferences: AutoBackupPreferences
) {

    /**
     * تنفيذ نسخة واحدة. [latest] = true تكتب فوق ملف ثابت واحد (للتجربة/النسخ
     * الفوري)، وfalse تنشئ ملفاً جديداً بطابع زمني (للجدولة الدورية).
     */
    suspend fun runBackup(latest: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = preferences.snapshot()
            val treeUri = settings.treeUri
                ?: throw IOException("لم يتم تحديد مجلد نسخ الاحتياطية")

            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("تعذّر فتح مجلد نسخ الاحتياطية")
            if (!tree.exists()) {
                throw IOException("مجلد نسخ الاحتياطية لم يعد موجوداً على الجهاز")
            }
            if (!tree.canWrite()) {
                throw IOException("لا يوجد إذن كتابة على مجلد نسخ الاحتياطية")
            }

            val fileName = if (latest) LATEST_FILE_NAME
            else "unihub_backup_" + TIMESTAMP_FORMAT.format(LocalDateTime.now())

            // حذف نسخة سابقة بنفس الاسم قبل الإنشاء (يفشل الإنشاء إن وُجدت)
            runCatching { tree.findFile(fileName)?.delete() }
            val document = tree.createFile(MIME_ZIP, fileName)
                ?: throw IOException("تعذّر إنشاء ملف النسخة داخل المجلد المحدد")

            val count = context.contentResolver.openOutputStream(document.uri)?.use { stream ->
                backupRepository.exportToStream(stream)
            } ?: throw IOException("تعذّر فتح ملف النسخة للكتابة")

            preferences.recordResult(
                success = true,
                message = if (latest) {
                    "تم تحديث آخر نسخة احتياطية تلقائياً ($count عنصراً)"
                } else {
                    "تم إنشاء نسخة احتياطية تلقائية ($count عنصراً)"
                }
            )
            count
        }.onFailure { error ->
            runCatching {
                preferences.recordResult(
                    success = false,
                    message = error.message ?: "خطأ غير متوقع أثناء النسخ التلقائي"
                )
            }
        }
    }

    /** فحص «حقيقي» لقدرة التطبيق على الوصول للمجلد المحدد — للتغذية الراجعة */
    suspend fun verifyFolderAccess(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, uri)
            tree != null && tree.exists() && tree.canRead() && tree.canWrite()
        }.getOrDefault(false)
    }

    companion object {
        const val MIME_ZIP = "application/zip"
        const val LATEST_FILE_NAME = "unihub_backup_latest"
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
    }
}
