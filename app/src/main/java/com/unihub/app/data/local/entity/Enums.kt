package com.unihub.app.data.local.entity

/**
 * تحسين جوهري عن التطبيق المرجعي: الأولوية ونوع الامتحان ويوم الأسبوع كانت
 * نصوصاً سحرية ("high"/"متوسط"...) منتشرة في الـ DAO والواجهة، وأي خطأ إملائي
 * يمرّ بدون اكتشاف. هنا أصبحت أنواعاً محددة يضمنها المترجم.
 */

/** أولوية المهمة */
enum class TaskPriority(val label: String, val weight: Int) {
    HIGH("عالية", 0),
    MEDIUM("متوسطة", 1),
    LOW("منخفضة", 2);

    companion object {
        fun fromStorage(value: String?): TaskPriority =
            entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

/** نوع الامتحان */
enum class ExamType(val label: String) {
    MIDTERM("نصفي"),
    FINAL("نهائي"),
    QUIZ("فجائي"),
    LAB("عملي");

    companion object {
        fun fromStorage(value: String?): ExamType =
            entries.firstOrNull { it.name == value } ?: FINAL

        fun fromLabel(label: String?): ExamType =
            entries.firstOrNull { it.label == label } ?: FINAL
    }
}

/**
 * أيام الأسبوع — الترتيب يبدأ بالسبت (بداية الأسبوع الدراسي في أغلب الجامعات العربية).
 * [javaDayValue] يطابق ترقيم java.time.DayOfWeek (الاثنين=1 .. الأحد=7).
 */
enum class Weekday(val label: String, val shortLabel: String, val javaDayValue: Int) {
    SATURDAY("السبت", "سبت", 6),
    SUNDAY("الأحد", "أحد", 7),
    MONDAY("الاثنين", "اثنين", 1),
    TUESDAY("الثلاثاء", "ثلاثاء", 2),
    WEDNESDAY("الأربعاء", "أربعاء", 3),
    THURSDAY("الخميس", "خميس", 4),
    FRIDAY("الجمعة", "جمعة", 5);

    companion object {
        fun fromStorage(value: String?): Weekday =
            entries.firstOrNull { it.name == value } ?: SUNDAY
    }
}

/** تصنيف الملف لتحديد الأيقونة واللون — بدل نص حر في التطبيق المرجعي */
enum class FileKind(val label: String) {
    PDF("مستند PDF"),
    DOCUMENT("مستند"),
    SPREADSHEET("جدول بيانات"),
    PRESENTATION("عرض تقديمي"),
    IMAGE("صورة"),
    VIDEO("فيديو"),
    AUDIO("صوت"),
    ARCHIVE("أرشيف"),
    TEXT("نص"),
    OTHER("ملف");

    companion object {
        fun fromExtension(ext: String): FileKind = when (ext.lowercase()) {
            "pdf" -> PDF
            "doc", "docx", "rtf" -> DOCUMENT
            "xls", "xlsx", "csv" -> SPREADSHEET
            "ppt", "pptx" -> PRESENTATION
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> IMAGE
            "mp4", "avi", "mkv", "mov", "wmv", "webm" -> VIDEO
            "mp3", "wav", "m4a", "ogg", "flac", "aac" -> AUDIO
            "zip", "rar", "7z", "tar", "gz" -> ARCHIVE
            "txt", "md", "json", "xml", "html" -> TEXT
            else -> OTHER
        }
    }
}
