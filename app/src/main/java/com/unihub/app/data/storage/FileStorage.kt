package com.unihub.app.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.core.validation.InputValidationException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** نتيجة استيراد ناجحة لملف من منتقي النظام */
data class ImportedFile(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val size: Long,
    val absolutePath: String
)

/**
 * إدارة الملفات الفيزيائية داخل تخزين التطبيق الداخلي.
 * التحسين الجوهري: في التطبيق المرجعي كان النسخ والفتح والحذف موزعاً داخل
 * ملفات الواجهة (FilesScreen/ViewModel)؛ هنا كل عمليات القرص معزولة في صف واحد
 * قابل للاختبار، مع إزالة تكرار الأسماء وحجم أقصى وتنظيف محارف.
 */
@Singleton
class FileStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** مجلد المكتبة داخل التخزين الداخلي الخاص بالتطبيق */
    private val libraryDir: File
        get() = File(context.filesDir, "library").apply { if (!exists()) mkdirs() }

    /**
     * استيراد ملف عبر نسخة فعلية إلى تخزين التطبيق.
     * يرمي [InputValidationException] برسالة عربية جاهزة عند رفض الامتداد أو الحجم.
     */
    suspend fun import(uri: Uri, fallbackMimeType: String): ImportedFile =
        withContext(Dispatchers.IO) {
            // 1) قراءة الاسم والحجم من مزود المحتوى
            var displayName = "ملف"
            var size = -1L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }

            // 2) استخراج الامتداد والتحقق منه
            val dot = displayName.lastIndexOf('.')
            val baseName = if (dot > 0) displayName.substring(0, dot) else displayName
            val extension = if (dot > 0) displayName.substring(dot + 1) else ""

            val validExt = InputValidator.validateExtension(extension)
                .getOrElse { throw it }
            if (size > InputValidator.MAX_UPLOAD_SIZE_BYTES) {
                throw InputValidationException(InputValidator.InputError.FileTooLarge)
            }

            // 3) اسم فريد آمن على القرص (بدون تكرار أو محارف مسارات)
            val safeBase = InputValidator.sanitizeName(baseName).ifBlank { "ملف" }
            var target = File(libraryDir, "$safeBase.$validExt")
            var counter = 1
            while (target.exists()) {
                target = File(libraryDir, "$safeBase ($counter).$validExt")
                counter++
            }

            // 4) نسخ التدفق
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) throw java.io.IOException("تعذّر قراءة الملف المحدد")

            val mimeType = context.contentResolver.getType(uri) ?: fallbackMimeType

            ImportedFile(
                displayName = target.nameWithoutExtension,
                extension = validExt,
                mimeType = mimeType,
                // بعض مزودي المحتوى لا يعيدون SIZE (أو يعيدون 0) — حجم النسخة هو الموثوق حينها
                size = if (size > 0) size else target.length(),
                absolutePath = target.absolutePath
            )
        }

    /**
     * حفظ صورة التُقطت بكاميرا التطبيق (ملف مؤقت في الكاش) داخل المكتبة.
     * نفس منطق الاستيراد: اسم آمن فريد بلا تكرار أو محارف مسارات.
     */
    suspend fun saveCapturedImage(source: File, baseName: String?): ImportedFile =
        withContext(Dispatchers.IO) {
            saveLocalFile(source, baseName ?: "صورة", "jpg", "image/jpeg")
        }

    /** حفظ تسجيل صوتي (ملف مؤقت في الكاش) داخل المكتبة بصيغة m4a/AAC */
    suspend fun saveAudioRecording(source: File, baseName: String?): ImportedFile =
        withContext(Dispatchers.IO) {
            saveLocalFile(source, baseName ?: "تسجيل صوتي", "m4a", "audio/mp4")
        }

    /**
     * نقل ملف محلي (من كاش التطبيق) إلى مجلد المكتبة باسم فريد آمن،
     * ثم حذف الأصل المؤقت. تُستخدم للملفات التي ينتجها التطبيق نفسه
     * (الكاميرا والمسجل) بدل منتقي النظام.
     */
    private fun saveLocalFile(
        source: File,
        rawBase: String,
        extension: String,
        mimeType: String
    ): ImportedFile {
        if (!source.isFile) throw java.io.IOException("الملف المؤقت لم يعد متاحاً")
        val safeBase = InputValidator.sanitizeName(rawBase).ifBlank { "ملف" }
        var target = File(libraryDir, "$safeBase.$extension")
        var counter = 1
        while (target.exists()) {
            target = File(libraryDir, "$safeBase ($counter).$extension")
            counter++
        }
        source.copyTo(target, overwrite = true)
        runCatching { source.delete() }
        return ImportedFile(
            displayName = target.nameWithoutExtension,
            extension = extension,
            mimeType = mimeType,
            size = target.length(),
            absolutePath = target.absolutePath
        )
    }

    /** حذف ملف فيزيائي بصمت — فشله لا يجب أن يُفشل حذف السجل */
    fun delete(absolutePath: String) {
        if (absolutePath.isBlank()) return
        runCatching {
            val file = File(absolutePath)
            // أمان: نحذف فقط داخل مجلد المكتبة الخاص بالتطبيق
            if (file.isFile && file.absolutePath.startsWith(libraryDir.absolutePath)) {
                file.delete()
            }
        }
    }

    /** حذف كل الملفات المستوردة (يُستخدم من الإعدادات) */
    fun clearAll() {
        runCatching {
            libraryDir.listFiles()?.forEach { it.delete() }
        }
    }
}
