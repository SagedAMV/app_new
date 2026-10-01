package com.unihub.app.data.cloud

/**
 * قواعد دمج الرفع — جولة تعليمات.md (حل مشكلة تكرار المجلدات والملفات في السحابة):
 * قبل إنشاء هوية جديدة في السحابة يتحقق التطبيق أولاً:
 *  - هل يوجد مجلد بنفس الاسم في نفس المستوى؟ نعم → يدمج الملفات بداخله بدل إنشاء مجلد ثانٍ.
 *  - هل يوجد ملف بنفس البصمة والحجم والاسم داخل المجلد الوجهة؟ نعم → لا يُرفع مرة أخرى.
 * القواعد نقية (بلا شبكة وبلا أندرويد) ويمكن اختبارها على JVM مباشرة.
 *
 * ثابتان يحكمان التصميم:
 *  - لا ننقل أو نعيد تسمية مجلد سحابي موجود بسبب الرفع (الوصف المنشور يحفظ اسم الأب ومساره).
 *  - لا نعيد استخدام مفتاح ملف موجود إلا بتطابق الاسم أيضاً؛ لأن [CloudManifestTools.mergeFiles]
 *    يستبدل السجل المقصود بمفتاحه، فإعادة الاستخدام مع اسم مختلف تعني إعادة تسمية صامتة.
 */
object CloudUploadMergeRules {

    /** مقارنة أسماء متسامحة: تجاهل الفراغات الزائدة وحالة الأحرف — «محاضرات » مثل «محاضرات». */
    fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * مجلد سحابي موجود بنفس الاسم ونفس الأب (null = المستوى الرئيسي) أو لا شيء.
     * يشمل كل أنواع المجلدات (folder:/path:/legacy:) لأن المستخدم يرى الاسم والبنية،
     * والهدف ألا يظهر مجلدان بنفس الاسم في الشجرة مهما كان مصدرهما.
     */
    fun findExistingFolder(folders: List<RemoteCloudFolder>, name: String, parentKey: String?): RemoteCloudFolder? {
        if (name.isBlank()) return null
        return folders.firstOrNull { it.parentKey == parentKey && sameName(it.name, name) }
    }

    /**
     * ملف موجود داخل المجلد الوجهة نفسه ببصمة وحجم واسم مطابقة، أو لا شيء.
     * بصمة مطابقة مع اسم مختلف = ملف آخر للمستخدم: يُرفع ككائن جديد ولا يُعاد استخدام المفتاح
     * حتى لا تستبدل [CloudManifestTools.mergeFiles] سجل الملف الموجود فتغيّر اسمه صمتاً.
     * بصمة فارغة = لا مطابقة أبداً (لا نخمّن وجود محتوى لم تُحسب بصمته).
     */
    fun findExistingFile(
        files: List<RemoteCloudFile>,
        name: String,
        extension: String,
        sha256: String,
        size: Long,
        folderKey: String?
    ): RemoteCloudFile? {
        if (sha256.isBlank()) return null
        return files.firstOrNull {
            it.cloudFolderKey == folderKey &&
                it.size == size &&
                it.sha256.equals(sha256, ignoreCase = true) &&
                sameName(it.name, name) &&
                it.extension.equals(extension, ignoreCase = true)
        }
    }
}
