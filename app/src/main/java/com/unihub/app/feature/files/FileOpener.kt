package com.unihub.app.feature.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.unihub.app.data.local.entity.FileEntity
import java.io.File

/**
 * نتيجة عملية فتح/مشاركة — بدل Boolean غامض، تعرف الشاشة سبب الفشل بالضبط
 * لتعرض رسالة ذكية (ملف اختفى من التخزين؟ لا تطبيق يفتحه؟ فشل عام؟).
 */
sealed interface FileOpResult {
    data object Success : FileOpResult
    /** النسخة الفيزيائية غير موجودة (حُذفت خارج التطبيق أو تلفت) */
    data object MissingFile : FileOpResult
    /** لا يوجد تطبيق على الجهاز يتعامل مع هذا النوع */
    data object NoApp : FileOpResult
    data object Failed : FileOpResult

    /** رسالة عربية جاهزة للعرض — null عند النجاح */
    fun errorMessage(): String? = when (this) {
        Success -> null
        MissingFile -> "الملف غير موجود في التخزين — ربما حُذف خارج التطبيق"
        NoApp -> "لا يوجد تطبيق على جهازك يفتح هذا النوع من الملفات"
        Failed -> "تعذّرت العملية، حاول مجدداً"
    }
}

/**
 * فتح الملفات ومشاركتها عبر تطبيقات النظام باستخدام FileProvider + ACTION_VIEW/SEND.
 * معزولة في كائن واحد بدل تكرار نفس المنطق داخل الواجهة كما في التطبيق المرجعي.
 *
 * التحسين: فحص وجود الملف الفيزيائي قبل محاولة الفتح، ونتيجة مفصلة [FileOpResult]
 * بدل `false` عامة، ودعم مشاركة/إرسال عدة ملفات دفعة واحدة (ACTION_SEND_MULTIPLE).
 */
object FileOpener {

    /** يفتح الملف بتطبيق خارجي */
    fun open(context: Context, file: FileEntity): FileOpResult {
        if (!physicalExists(file)) return FileOpResult.MissingFile
        return runCatching {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "فتح الملف").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            FileOpResult.Success as FileOpResult
        }.getOrElse { it.toResult() }
    }

    /** يشارك ملفاً واحداً مع تطبيقات أخرى */
    fun share(context: Context, file: FileEntity): FileOpResult {
        if (!physicalExists(file)) return FileOpResult.MissingFile
        return shareInternal(context, listOf(file))
    }

    /**
     * مشاركة/إرسال عدة ملفات دفعة واحدة — يتخطى بصمت أي ملف فيزيائي مفقود
     * ويعيد [FileOpResult.MissingFile] فقط عندما لا يوجد ملف قابل للإرسال إطلاقاً.
     */
    fun shareMultiple(context: Context, files: List<FileEntity>): FileOpResult {
        val available = files.filter { physicalExists(it) }
        if (available.isEmpty()) return FileOpResult.MissingFile
        return shareInternal(context, available)
    }

    /** هل النسخة الفيزيائية موجودة فعلاً داخل تخزين التطبيق؟ */
    fun physicalExists(file: FileEntity): Boolean =
        file.filePath.isNotBlank() && File(file.filePath).isFile

    private fun shareInternal(context: Context, files: List<FileEntity>): FileOpResult =
        runCatching {
            val uris = ArrayList(files.map { uriFor(context, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = files.first().mimeType.ifBlank { "application/octet-stream" }
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    putExtra(Intent.EXTRA_SUBJECT, files.first().name)
                }
            } else {
                // أنواع مختلطة ممكنة — */* يضمن قبول المستقبل لأي مزيج
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "مشاركة الملفات").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            FileOpResult.Success as FileOpResult
        }.getOrElse { it.toResult() }

    private fun uriFor(context: Context, file: FileEntity) =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(file.filePath)
        )

    private fun Throwable.toResult(): FileOpResult = when (this) {
        is ActivityNotFoundException -> FileOpResult.NoApp
        is IllegalArgumentException -> FileOpResult.MissingFile // خارج نطاق FileProvider
        else -> FileOpResult.Failed
    }
}
