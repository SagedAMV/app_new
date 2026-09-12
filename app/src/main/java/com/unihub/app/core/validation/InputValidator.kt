package com.unihub.app.core.validation

/**
 * التحقق من المدخلات بشكل موحّد.
 * تحسّن عن التطبيق المرجعي: إرجاع رسائل خطأ عربية واضحة عبر [InputError] بدل
 * استثناءات عامة، والسماح بأي حرف لغوي (وليس فقط اللاتينية والعربية) مع مسافات
 * وشرطات ونقاط وأرقام.
 */
object InputValidator {

    const val MAX_NAME_LENGTH = 120
    const val MAX_TITLE_LENGTH = 200
    const val MAX_TEXT_LENGTH = 20_000

    val MAX_UPLOAD_SIZE_BYTES: Long = 200L * 1024 * 1024 // 200 م.ب كحد أقصى للملف الواحد

    /** امتدادات مدعومة للاستيراد */
    val ALLOWED_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic",
        "mp4", "avi", "mkv", "mov", "wmv", "webm",
        "mp3", "wav", "m4a", "ogg", "flac", "aac",
        "zip", "rar", "7z", "tar", "gz",
        "apk", "json", "xml", "html"
    )

    sealed interface InputError {
        val message: String
        data object Empty : InputError { override val message = "هذا الحقل مطلوب" }
        data class TooLong(val max: Int) : InputError {
            override val message = "النص أطول من المسموح ($max حرفاً)"
        }
        data object InvalidChars : InputError {
            override val message = "الاسم يحتوي على رموز غير مسموحة (/ \\ : * ? \" < > |)"
        }
        data object UnsupportedExtension : InputError {
            override val message = "نوع الملف غير مدعوم"
        }
        data object FileTooLarge : InputError {
            override val message = "الملف أكبر من الحد المسموح (200 م.ب)"
        }
        data object InvalidDate : InputError { override val message = "تاريخ غير صالح" }
        data object InvalidTimeRange : InputError {
            override val message = "وقت النهاية يجب أن يكون بعد وقت البداية"
        }
    }

    /** التحقق من اسم (مجلد/ملف/مادة): لا يقبل رموز المسارات */
    fun validateName(raw: String, maxLength: Int = MAX_NAME_LENGTH): Result<String> {
        val name = sanitizeName(raw)
        return when {
            name.isBlank() -> failure(InputError.Empty)
            name.length > maxLength -> failure(InputError.TooLong(maxLength))
            name.any { it in "/\\:*?\"<>|" } -> failure(InputError.InvalidChars)
            else -> Result.success(name)
        }
    }

    /** التحقق من عنوان قصير (مهمة/ملاحظة) */
    fun validateTitle(raw: String): Result<String> {
        val title = raw.trim().replace(Regex("[\\x00-\\x1F\\x7F]"), "")
        return when {
            title.isBlank() -> failure(InputError.Empty)
            title.length > MAX_TITLE_LENGTH -> failure(InputError.TooLong(MAX_TITLE_LENGTH))
            else -> Result.success(title)
        }
    }

    /** التحقق من امتداد ملف */
    fun validateExtension(ext: String): Result<String> {
        val normalized = ext.trimStart('.').lowercase().trim()
        return when {
            normalized.isBlank() -> failure(InputError.UnsupportedExtension)
            normalized !in ALLOWED_EXTENSIONS -> failure(InputError.UnsupportedExtension)
            else -> Result.success(normalized)
        }
    }

    /** التحقق من تاريخ ISO */
    fun validateDate(dateIso: String): Result<String> {
        return if (com.unihub.app.core.common.DateFormats.parseDateOrNull(dateIso) == null) {
            failure(InputError.InvalidDate)
        } else {
            Result.success(dateIso)
        }
    }

    /** التحقق من أن وقت النهاية بعد البداية (كلاهما اختياري — الفحص فقط عند توفرهما) */
    fun validateTimeRange(from: String?, to: String?): Result<Unit> {
        val f = com.unihub.app.core.common.DateFormats.parseTimeOrNull(from)
        val t = com.unihub.app.core.common.DateFormats.parseTimeOrNull(to)
        return if (f != null && t != null && !t.isAfter(f)) {
            failure(InputError.InvalidTimeRange)
        } else {
            Result.success(Unit)
        }
    }

    /** تنظيف اسم من محارف التحكم وتبييض المسافات الزائدة */
    fun sanitizeName(raw: String): String =
        raw.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** تنظيف نص طويل (محتوى ملاحظة/وصف) مع الحفاظ على الأسطر */
    fun sanitizeText(raw: String): String =
        raw.trimEnd()
            .take(MAX_TEXT_LENGTH)
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            .trimStart()

    private fun <T> failure(error: InputError): Result<T> =
        Result.failure(InputValidationException(error))
}

/** استثناء يحمل رسالة عربية جاهزة للعرض */
class InputValidationException(val error: InputValidator.InputError) :
    Exception(error.message)
