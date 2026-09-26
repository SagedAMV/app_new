package com.unihub.app.data.backup

/**
 * شفرة التعريف في بداية ملفات النسخ الاحتياطي (طلب تعليمات.md — «شفرة في بداية
 * ملف النسخ الاحتياطية»).
 *
 * الفكرة: يُكتب سطر نصي مميز قبل تدفق ZIP نفسه، فيتعرّف التطبيق على الملف فور
 * مشاركته إليه ويعرض «هل تريد استيراد نسخة؟» بدل مسار المشاركة العادي.
 *
 * لماذا لا تُكسر صيغة ZIP؟ لأن [java.util.zip.ZipInputStream] يقرأ من موضع
 * التدفق الحالي — تخطي السطر الأول يجعل بقية البايتات أرشيف ZIP سليماً تماماً.
 * النسخ القديمة (بلا شفرة، تبدأ بـ PK مباشرة) تبقى قابلة للاستيراد كما كانت.
 */
object BackupSignature {

    /** العلامة الفريدة التي يتعرّف عليها التطبيق — نصية وقصيرة وآمنة بايتياً */
    const val MARKER = "::UNIHUB_BACKUP::"

    /** السطر الكامل الذي يُصدَّر في رأس كل نسخة */
    const val HEADER_LINE = "$MARKER unihub-backup v${BackupRepository.SCHEMA_VERSION}\n"

    val HEADER_BYTES: ByteArray = HEADER_LINE.toByteArray(Charsets.UTF_8)

    /** هل تبدأ البايتات بشفرة النسخة الاحتياطية؟ (فحص أول بايتات فقط — رخيص) */
    fun startsWithSignature(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES.size) return false
        for (i in HEADER_BYTES.indices) {
            if (bytes[i] != HEADER_BYTES[i]) return false
        }
        return true
    }

    /** إسقاط سطر الشفرة إن وُجد — ما بعده أرشيف ZIP (أو JSON قديم) صالح */
    fun stripIfPresent(bytes: ByteArray): ByteArray =
        if (startsWithSignature(bytes)) bytes.copyOfRange(HEADER_BYTES.size, bytes.size)
        else bytes
}
