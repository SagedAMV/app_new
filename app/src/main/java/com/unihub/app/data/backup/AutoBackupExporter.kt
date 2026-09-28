package com.unihub.app.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
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
 * فشل «فقد الوصول» إلى مجلد النسخ التلقائي: سببه إذن الوصول الدائم الذي
 * ضاع أو لم يُثبَّت أصلاً — إعادة المحاولة عبث حتى يعيد المستخدم اختيار
 * المجلد. يميّزها [AutoBackupWorker] عن أخطاء الإدخال/الإخراج العابرة
 * (التي تستحق إعادة المحاولة التلقائية).
 *
 * هذا التقسيم هو إصلاح جلسة اليوم: سابقاً كان كل فشل يُعامل كنهاية طريق
 * برسالة مبهمة، فمات النسخ التلقائي بصمت إلى الأبد بينما التصدير اليدوي
 * (الذي يأخذ إذناً جديداً من منتقي الملفات في كل مرة) يظل يعمل.
 */
class BackupAccessException(message: String) : IOException(message)

/**
 * التنفيذ الفعلي للنسخ الاحتياطي التلقائي إلى المجلد الذي اختاره المستخدم عبر
 * SAF (DocumentFile)، بعيداً عن العامل نفسه ليسهل استدعاؤه من أي سياق.
 *
 * يُكتب سطر الشفرة ([BackupSignature]) في رأس كل نسخة، فتتعرّف عليها أي عملية
 * مشاركة لاحقة وتعرض «هل تريد استيراد نسخة؟».
 *
 * تحصين هذه الجلسة (جذر مشكلة «النسخ التلقائي لا يعمل» في تعليمات.md):
 * 1) أخطاء الوصول تُلتقط وتُصنَّف [BackupAccessException] برسالة إرشادية واضحة
 *    («أعد اختيار المجلد») بدل الرسائل المبهمة التي كانت تظهر للمستخدم.
 * 2) فشل إنشاء الملف يُعيد المحاولة مرة بعد تنظيف الاسم، ويُلتقط سببه الحقيقي
 *    بدل ابتلاع مزوّد الوثائق له بصمت (كان createFile يعيد null بلا تفسير).
 * 3) كل فشل يُسجَّل بالاستثناء الكامل في السجل (وسم [TAG]) للتشخيص الفعلي.
 * 4) فحص إقلاع [validateConfiguredFolder] يفضح المجلد الذي ضاع الوصول إليه
 *    فور فتح التطبيق بدل انتظار أول محاولة نسخ لتفشل.
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

            val tree = try {
                DocumentFile.fromTreeUri(context, treeUri)
            } catch (security: SecurityException) {
                Log.w(TAG, "محاولة فتح مجلد النسخ قوبلت برفض الإذن", security)
                throw BackupAccessException(
                    "فقد التطبيق إذن الوصول إلى مجلد النسخ — أعد اختيار المجلد من الإعدادات"
                )
            } ?: throw BackupAccessException(
                "تعذّر فتح مجلد نسخ الاحتياطية — أعد اختيار المجلد من الإعدادات"
            )

            // فحص الوصول: وجود المجلد + إذن الكتابة. بعض المزودين يرمي
            // SecurityException من الاستعلام نفسه بدل إرجاع false — نلتقطها
            // كلها في سلة «فقد الوصول» نفسها لأنها تُعالج بنفس الطريقة.
            val accessible = runCatching { tree.exists() && tree.canWrite() }
                .getOrElse { error ->
                    Log.w(TAG, "تعذّر التحقق من مجلد النسخ — يُعامل كفقد وصول", error)
                    false
                }
            if (!accessible) {
                throw BackupAccessException(
                    "فقد التطبيق الوصول إلى مجلد النسخ التلقائي — أعد اختيار المجلد من الإعدادات"
                )
            }

            val fileName = if (latest) LATEST_FILE_NAME
            else "unihub_backup_" + TIMESTAMP_FORMAT.format(LocalDateTime.now())

            // حذف نسخة سابقة بنفس الاسم قبل الإنشاء (يفشل الإنشاء إن وُجدت)
            runCatching { tree.findFile(fileName)?.delete() }

            // إنشاء الملف مع التقاط السبب الحقيقي للفشل: بعض مزودي الوثائق
            // يرمي استثناءً بدل إرجاع null، والأخرى يعيد null ابتلاعاً لخطأ
            // داخلي. في الحالتين نجرب مرة ثانية بعد تنظيف الاسم، فإن بقي
            // الفشل فالمشكلة في الوصول نفسه (والرسالة ترشد للحل).
            val document = try {
                tree.createFile(MIME_ZIP, fileName)
            } catch (error: Exception) {
                Log.w(TAG, "إنشاء ملف النسخة رمى استثناءً — محاولة ثانية بعد تنظيف الاسم", error)
                runCatching { tree.findFile(fileName)?.delete() }
                runCatching { tree.createFile(MIME_ZIP, fileName) }.getOrNull()
            } ?: run {
                // محاولة أخيرة: قد يكون الاسم القديم باقياً رغم الحذف الأول
                runCatching { tree.findFile(fileName)?.delete() }
                runCatching { tree.createFile(MIME_ZIP, fileName) }.getOrNull()
            } ?: throw BackupAccessException(
                "تعذّر إنشاء ملف النسخة داخل المجلد المحدد — أعد اختيار المجلد من الإعدادات"
            )

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
            // التسجيل الكامل في السجل — التشخيص الحقيقي يبدأ من هنا
            Log.w(TAG, "فشل النسخ الاحتياطي التلقائي", error)
            runCatching {
                preferences.recordResult(
                    success = false,
                    message = error.message ?: "خطأ غير متوقع أثناء النسخ التلقائي"
                )
            }
        }
    }

    /**
     * فحص إقلاع صريح: إن كان المجلد محدداً لكن الوصول إليه ضائعاً، تُسجَّل
     * رسالة واضحة فوراً تظهر في شاشة الإعدادات — بدل أن ينتظر المستخدم أول
     * تعديل ليكتشف الفشل. لا تفعل شيئاً إن لم يكن أي مجلد محدداً بعد.
     */
    suspend fun validateConfiguredFolder() {
        val uri = preferences.snapshot().treeUri ?: return
        if (!verifyFolderAccess(uri)) {
            Log.w(TAG, "مجلد النسخ التلقائي المحدد لم يعد قابلاً للوصول: $uri")
            runCatching {
                preferences.recordResult(
                    success = false,
                    message = "فقد التطبيق الوصول إلى مجلد النسخ التلقائي — أعد اختياره من الإعدادات"
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
        private const val TAG = "AutoBackupExporter"
        const val MIME_ZIP = "application/zip"
        // إصلاح (جلسة التدقيق): الامتداد جزء ثابت من الاسم. بعض مزودي SAF يلحقون
        // «.zip» تلقائياً حسب MIME عند الإنشاء بلا امتداد، فلا يعود findFile() يجد
        // النسخة السابقة باسمها المجرد وتتراكم نسخ مكررة بدل الكتابة فوقها. باسم
        // صريح الامتداد يبقى findFile/حذف/إعادة الإنشاء متسقاً لدى كل المزودين.
        const val LATEST_FILE_NAME = "unihub_backup_latest.zip"
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
    }
}
