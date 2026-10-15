package com.unihub.app.data.cloud

/**
 * وجهة الرفع داخل السحابة: قرار صافٍ بلا Android وبلا شبكة.
 *
 * لماذا طبقة مستقلة بدل `if` داخل [CloudSyncManager]؟ لأن الاختيار يصل من وجهتين (بطاقة تأكيد
 * الرفع في شاشة الملفات، والفتح المباشر من شاشة السحابة)، وله فترة انتظار طويلة: الدفعة تبقى
 * في الطابور قد يقطعها انقطاع شبكة ثم تُستأنف بعد دقائق. لو تحوّل المفتاح إلى قرار ضمني داخل
 * طبقة الشبكة لأصبح سؤال «إلى أين ذهب ملفي؟» بلا جواب، ولتحركت الملفات إلى مكان آخر عند
 * الاستئناف دون أن يقرأ المستخدم ذلك.
 */
object CloudUploadTarget {

    /** لا وجهة = السلوك القديم تمامًا: شجرة المجلدات المحلية كما هي إلى جذر السحابة */
    fun normalize(key: String?): String? = key?.trim()?.takeIf { it.isNotEmpty() }

    /** مقبول: مجلد بيان أو مجلد مسار. `legacy:` مرفوض لأنه هوية مكتبة محلية لا مكان له في السحابة */
    fun isSelectable(key: String?): Boolean {
        val target = normalize(key) ?: return true
        return isSelectableKey(target)
    }

    /** البادئة وحدها لا تكفي: «folder:» بلا هوية مفتاح مشوَّه، ومثله يُرفض لا يُطابَق عليه */
    private fun isSelectableKey(key: String): Boolean =
        (key.startsWith("folder:") || key.startsWith("path:")) && key.substringAfter(':').isNotBlank()

    /**
     * سبب الرفض بكلام مستخدم، أو null عند القبول. وجود الوجهة في القائمة مفحوص هنا لا في
     * الشاشة: بين الضغط والبدء قد يحذف مستخدم آخر المجلد، فالسؤال عن القائمة الحالية وقت
     * التنفيذ هو الوحيد الأمين (نفس أسلوب moveRemoteFiles).
     */
    fun validate(key: String?, availableFolderKeys: Set<String>): String? {
        val target = normalize(key) ?: return null
        if (!isSelectableKey(target)) return "لا يمكن الرفع إلى هذا المجلد؛ اختر مجلدًا من شجرة السحابة"
        if (target !in availableFolderKeys) return "مجلد الوجهة لم يعد في قائمة السحابة؛ أعد الفحص ثم اختر مجددًا"
        return null
    }

    /**
     * مجموعة ملفات بحتة تُفرَد داخل الوجهة مباشرة — هذا معنى «ارفعهم إلى هذا المجلد» عند
     * المستخدم. أما مع مجلدات مختارة فتحية الشجرة تُحفظ تحت الوجهة، وإلا هُرِس تنظيم بناه
     * المستخدم لمجرد أنه اختار وجهة.
     */
    fun flattens(key: String?, selectedFolderIds: Set<Long>): Boolean = normalize(key) != null && selectedFolderIds.isEmpty()

    /** أب الشجرة المُرفَعة يصير الوجهة بدل أبيه المحلي؛ بقية المستويات تُبنى تحته كالمعتاد */
    fun parentFor(treeRoot: Boolean, target: String?, derivedParent: String?): String? =
        if (target != null && treeRoot) target else derivedParent

    /**
     * سطر واحد تقرأه البطاقة والإشعار معًا: إلى أين يذهب هذا الطلب. مُشتق من الحالة لا من
     * نص مكتوب في مكانين، فلو عُرض في الشاشة بغير صيغة الطابور لقرأ المستخدم وجهتين مختلفتين.
     */
    fun labelFor(targetFolderKey: String?, targetFolderName: String?, flatten: Boolean): String = when {
        targetFolderKey == null -> "إلى جذر السحابة مع بنية المجلدات"
        targetFolderName.isNullOrBlank() -> "إلى مجلد لم يعد في القائمة؛ أعد الفحص قبل الرفع"
        flatten -> "إلى «$targetFolderName» مباشرة — تُرفع الملفات بلا شجرة فرعية"
        else -> "إلى «$targetFolderName» مع بنية المجلدات تحته"
    }

    /** اسم العرض للوجهة في منتقي المجلد: الجذر خيار صريح، لا «إلغاء التحديد» المبهم */
    const val ROOT_LABEL = "جذر السحابة"
}
