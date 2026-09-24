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
 *
 * جولة الإصلاح الحالية (راجع تعليمات.md):
 * 1) توحيد منطق "اسم فريد بلا تكرار" في [uniqueTarget] بدل تكراره في كل دالة
 *    (كان مكرراً حرفياً في import() و saveLocalFile()) — إزالة تكرار كود حقيقي.
 * 2) إضافة [rename] الذي يُعيد تسمية الملف الفعلي على القرص ليطابق دائماً الاسم
 *    الظاهر في التطبيق، بدل بقاء الاسم الأصلي وقت الاستيراد إلى الأبد. هذا هو
 *    السبب الجذري لعطلين مُبلَّغ عنهما معاً: تعذّر "تعديل الاسم الأصلي" فعلياً،
 *    وإرسال/مشاركة الملف باسمه القديم بدل اسمه الحالي — لأن FileProvider يقرأ
 *    اسم الملف الحقيقي من القرص وليس عمود "name" في قاعدة البيانات.
 * 3) إضافة [importBytes] لاستعادة نسخة فعلية من محتوى مضمّن داخل أرشيف نسخة
 *    احتياطية (انظر BackupRepository) بدل الاكتفاء بمسار نصّي لم يعد موجوداً.
 */
@Singleton
class FileStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** مجلد المكتبة داخل التخزين الداخلي الخاص بالتطبيق */
    private val libraryDir: File
        get() = File(context.filesDir, "library").apply { if (!exists()) mkdirs() }

    /**
     * يبني ملف هدف بامتداد ثابت داخل مجلد المكتبة بلا تصادم اسم مع ملف موجود
     * فعلاً — "الاسم (1)"، "الاسم (2)"... بالترتيب. مستخدمة من الاستيراد،
     * الحفظ المحلي (كاميرا/تسجيل)، إعادة التسمية، واستعادة النسخ الاحتياطية.
     */
    private fun uniqueTarget(safeBase: String, extension: String): File {
        var target = File(libraryDir, "$safeBase.$extension")
        var counter = 1
        while (target.exists()) {
            target = File(libraryDir, "$safeBase ($counter).$extension")
            counter++
        }
        return target
    }

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
            val target = uniqueTarget(safeBase, validExt)

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
        val target = uniqueTarget(safeBase, extension)
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

    /**
     * إعادة تسمية النسخة الفعلية على القرص لتطابق دائماً الاسم الظاهر في
     * التطبيق. تُستدعى من [com.unihub.app.data.repository.FileRepository.rename]
     * بعد كل إعادة تسمية ناجحة في قاعدة البيانات.
     *
     * لماذا فعليًا وليس فقط في القاعدة؟ لأن FileProvider (المستخدَم في الفتح
     * والمشاركة) يقرأ اسم العرض (DISPLAY_NAME) من اسم الملف الحقيقي على القرص،
     * وليس من عمود "name" في Room. بدون هذه الخطوة يبقى أي تطبيق يستقبل الملف
     * (مشاركة) يرى الاسم القديم دائماً — وهذا هو العطل المُبلَّغ عنه بالضبط.
     *
     * إن لم تعد النسخة الفعلية موجودة (حُذفت خارج التطبيق)، نرمي استثناءً
     * ليتعامل معه المستدعي بإبقاء السجل مع الاسم الجديد فقط (تدهور رشيق) بدل
     * فشل العملية كلها.
     */
    suspend fun rename(oldAbsolutePath: String, newDisplayName: String, extension: String): String =
        withContext(Dispatchers.IO) {
            val source = File(oldAbsolutePath)
            if (!source.isFile) {
                throw java.io.IOException("النسخة الفعلية للملف لم تعد موجودة على القرص")
            }

            val safeBase = InputValidator.sanitizeName(newDisplayName).ifBlank { "ملف" }
            val safeExt = extension.trim('.').ifBlank { source.extension }

            // إن كان الاسم الفعلي مطابقاً أصلاً (نفس القاعدة والامتداد) لا داعي لأي عملية قرص
            if (source.nameWithoutExtension == safeBase && source.extension.equals(safeExt, ignoreCase = true)) {
                return@withContext source.absolutePath
            }

            val target = uniqueTarget(safeBase, safeExt)
            val moved = source.renameTo(target)
            if (!moved) {
                // renameTo() قد يفشل نادراً (مثلاً عبر أنظمة ملفات مختلفة) — خطة بديلة: نسخ ثم حذف الأصل
                source.copyTo(target, overwrite = true)
                if (!source.delete()) {
                    // فشل حذف الأصل بعد النسخ لا يجب أن يُفشل إعادة التسمية —
                    // النسخة الجديدة صحيحة، والأصل يُنظَّف لاحقاً بأمان (ملف يتيم لا يشير له أي سجل)
                    android.util.Log.w(TAG, "تعذّر حذف الملف الأصلي بعد النسخ أثناء إعادة التسمية: ${source.absolutePath}")
                }
            }
            target.absolutePath
        }

    /**
     * استعادة نسخة فعلية من بايتات مضمّنة داخل أرشيف نسخة احتياطية (ZIP)
     * إلى مجلد المكتبة الخاص بهذا الجهاز، وإرجاع مسارها الجديد الصالح محلياً.
     */
    suspend fun importBytes(bytes: ByteArray, displayName: String, extension: String): String =
        withContext(Dispatchers.IO) {
            val safeBase = InputValidator.sanitizeName(displayName).ifBlank { "ملف" }
            // نتساهل هنا عمداً في التحقق من الامتداد مقارنة بـ import(): بيانات النسخة
            // الاحتياطية سبق التحقق منها عند تصديرها، ورفض الاستعادة بسبب تغيّر قائمة
            // الامتدادات المسموحة بين إصدارين يفقد المستخدم بياناته بلا داعٍ.
            val safeExt = extension.trim('.').ifBlank { "bin" }.filter { it.isLetterOrDigit() }.ifBlank { "bin" }
            val target = uniqueTarget(safeBase, safeExt)
            target.writeBytes(bytes)
            target.absolutePath
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

    private companion object {
        const val TAG = "FileStorage"
    }
}
