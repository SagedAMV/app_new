package com.unihub.app.data.cloud

import java.io.File

/**
 * استكمال التنزيل بعد انقطاع: اسم الملف الجزئي وقرار «من أين نكمل».
 *
 * الطبقة هنا عمدًا: قرار تقني بلا Android وبلا شبكة، فيُختبر محليًا (طبيعة تطبيق.md §11
 * «تحقق من الطبقة الصحيحة التي يجب أن يعيش فيها المنطق»). قبل هذا الملف كان الجزء المنزَّل
 * يُمحى عند كل انقطاع — لأن [CloudflareR2Client.downloadFile] يكتب `FileOutputStream` جديدًا
 * ويطبّق `if (!completed) targetFile.delete()`، ولأن المدير يحذف المؤقت في `finally` حتى عند
 * الإلغاء — فصار المستخدم يرى «ينزّل الملف من جديد» كل مرة.
 */
object CloudDownloadPart {

    const val SUFFIX = ".part"

    /** لا نعيدها حيّة إلى الأبد: جزء قديم بعد يوم لم يعد له معنى (الملف تغيّر غالبًا) */
    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /**
     * اسم ثابت لنفس المفتاح السحابي — الاسم العشوائي (`createTempFile`) كان يستحيل معه
     * العثور على الجزء بعد إعادة التشغيل. والتهريب ممنوع: `remoteKey` نص بعيد وقد يحوي
     * `../` أو `/`، فلا يُستخدم حرفًا في مسار نظام الملفات.
     */
    fun fileName(remoteKey: String): String {
        val safe = remoteKey.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('.', '_')
            .take(80)
            .ifBlank { "download" }
        // بصمة قصيرة تفصل بين مفتاحين يتشابهان بعد الاستبدال (a/b و a_b لا يتصادمان)
        return "dl_${safe}_${remoteKey.hashCode().toULong().toString(16)}$SUFFIX"
    }

    /** هل يُستكمل هذا الجزء أم يُستأنف من الصفر؟ */
    fun plan(partLength: Long, expectedSize: Long?): Resume {
        if (partLength <= 0L) return Resume.Restart("لا يوجد جزء منزّل")
        if (expectedSize == null || expectedSize <= 0L) return Resume.Restart("حجم الملف غير معروف فلا نجازف بجزء")
        if (partLength >= expectedSize) return Resume.Restart("الجزء أطول من الملف المعلن؛ سيُعاد")
        return Resume.Continue(partLength)
    }

    /** هل يُحفظ ملف جزئي بعد هذه الإخفاقات؟ */
    fun isStale(now: Long, lastModified: Long): Boolean = now - lastModified > MAX_AGE_MS

    sealed interface Resume {
        /** إعادة من الصفر مع تصفير الجزء الحالي */
        data class Restart(val reason: String) : Resume
        /** نكمل من [from] بطلب Range (والخادم يردّ 206 وإلا أعدنا من الصفر) */
        data class Continue(val from: Long) : Resume
    }

    /** مسار الجزء في مخزن التخزين المؤقت؛ لا يُنشئ شيئًا */
    fun fileFor(staging: File, remoteKey: String): File = File(staging, fileName(remoteKey))
}
