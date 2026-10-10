package com.unihub.app.data.backup

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * أدوات قراءة النسخ الاحتياطية كـ«تدفق» بدل تحميل الملف كاملاً في الذاكرة.
 *
 * لماذا وُجدت؟ مسار الاستيراد القديم كان يفعل: `readBytes()` للملف كله ← ثم
 * `copyOfRange` لإسقاط سطر الشفرة ← ثم يجمع محتوى **كل** ملف مضمّن في
 * `HashMap<String, ByteArray>` قبل أي كتابة على القرص. نسخة فيها محاضرة مرئية
 * أو PDF كبيرة كانت ترفع استهلاك الكومة فوق حدها فتفشل الاستعادة برسالة
 * «Failed to allocate a … byte allocation … until OOM».
 *
 * هنا تُقرأ القطع بحجم ثابت (8KB)، ويُنزل محتوى كل ملف إلى قرص مؤقت، فتبقى
 * الذاكرة بعيدة عن حجم النسخة. الكائن بلا Context وبلا أصناف Android ليُختبر
 * محليًا على JVM مباشرة.
 */
internal object BackupStream {

    /** ناتج فك أرشيف: البيانات الوصفية في الذاكرة (نص صغير)، والملفات على قرص مؤقت */
    internal class Spooled(
        val manifestText: String?,
        val filesByEntry: Map<String, File>
    )

    /** توقيع ZIP القياسي — يميّز الأرشيف الجديد عن نسخة JSON القديمة */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private const val CHUNK_BYTES = 8 * 1024
    // JSON manifest is metadata, not a payload: keep its peak heap use bounded.
    private const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024

    /**
     * يتخطى سطر شفرة [BackupSignature] إن كان في رأس التدفق، ويعيد هل وُجد.
     * عند عدم التطابق تُعاد البايتات للتدفق عبر mark/reset، فلا يضيع شيء من نسخة قديمة.
     */
    fun skipSignatureLine(source: BufferedInputStream, header: ByteArray): Boolean {
        source.mark(header.size)
        val head = ByteArray(header.size)
        var read = 0
        while (read < header.size) {
            val n = source.read(head, read, header.size - read)
            if (n < 0) break
            read += n
        }
        if (read == header.size && head.contentEquals(header)) return true
        source.reset()
        return false
    }

    /** هل هذا التدفق أرشيف ZIP؟ يقرأ أربع بايتات ثم يعيدها فلا يمسها قارئ الأرشيف */
    fun looksLikeZip(source: BufferedInputStream): Boolean {
        source.mark(ZIP_MAGIC.size)
        val head = ByteArray(ZIP_MAGIC.size)
        var read = 0
        while (read < ZIP_MAGIC.size) {
            val n = source.read(head, read, ZIP_MAGIC.size - read)
            if (n < 0) break
            read += n
        }
        source.reset()
        return read == ZIP_MAGIC.size && head.contentEquals(ZIP_MAGIC)
    }

    /**
     * ينسخ من [input] إلى [output] بذاكرة ثابتة وبحجم أقصى [maxBytes].
     * الحد يمنع «قنبلة الضغط»: أرشيف صغير يفكّ نفسه إلى غيغابايتات.
     */
    private class UncompressedBudget(private val maxTotalBytes: Long) {
        var consumed: Long = 0L
            private set

        fun add(count: Int) {
            if (count <= 0) return
            if (consumed > maxTotalBytes - count.toLong()) {
                throw IOException("حجم محتوى النسخة الاحتياطية بعد فك الضغط تجاوز الحد الآمن")
            }
            consumed += count
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream?,
        maxEntryBytes: Long,
        budget: UncompressedBudget
    ) {
        val chunk = ByteArray(CHUNK_BYTES)
        var entryBytes = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (entryBytes > maxEntryBytes - n.toLong()) {
                throw IOException("عنصر داخل النسخة الاحتياطية أكبر من الحد المسموح")
            }
            budget.add(n)
            entryBytes += n
            output?.write(chunk, 0, n)
        }
    }

    /** Bounded reader for pre-ZIP JSON backups. */
    fun readLegacyTextBounded(input: InputStream, maxBytes: Long): String {
        val buffer = ByteArrayOutputStream()
        copyBounded(input, buffer, maxBytes, UncompressedBudget(maxBytes))
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    /** نص عنصر صغير (البيانات الوصفية) بحد أقصى — وحده ما يبقى في الذاكرة */
    private fun readTextBounded(input: InputStream, maxEntryBytes: Long, budget: UncompressedBudget): String {
        val buffer = ByteArrayOutputStream()
        copyBounded(input, buffer, maxEntryBytes, budget)
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    /**
     * يفك أرشيف نسخة احتياطية من [input] إلى [spoolDir]:
     - العنصر [manifestEntry] يُقرأ كنص.
     - كل عنصر تحت [filesPrefix] يُنزل ملفاً مستقلاً على القرص (بلا قراءة في الذاكرة).
     - أي عنصر آخر يُتخطى بلا قراءة، والمجلدات لا تُنشئ ملفات.
     *
     * لا يلمس أي تخزين دائم: المتَّصل يُستدعى منه فقط بعد أن يكتمل الأرشيف ويُتحقق منه،
     * وهو الترتيب الذي يمنع مسح مكتبة المستخدم بسبب أرشيف ناقص.
     */
    fun spool(
        input: InputStream,
        manifestEntry: String,
        filesPrefix: String,
        spoolDir: File,
        maxEntryBytes: Long,
        maxTotalBytes: Long = 1_500L * 1024 * 1024,
        maxEntries: Int = 20_000
    ): Spooled {
        if (!spoolDir.isDirectory && !spoolDir.mkdirs()) {
            throw IOException("تعذّر تجهيز مساحة مؤقتة لفك النسخة الاحتياطية")
        }
        var manifestText: String? = null
        val files = LinkedHashMap<String, File>()
        val budget = UncompressedBudget(maxTotalBytes)
        var written = 0
        var entryCount = 0
        val seenNames = HashSet<String>()

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > maxEntries) throw IOException("النسخة الاحتياطية تحتوي على عناصر أكثر من الحد المسموح")
                val name = entry.name
                if (!seenNames.add(name)) throw IOException("النسخة الاحتياطية تحتوي على أسماء عناصر مكررة")
                when {
                    name == manifestEntry && !entry.isDirectory -> {
                        if (manifestText != null) throw IOException("النسخة الاحتياطية تحتوي على أكثر من ملف بيانات")
                        manifestText = readTextBounded(zip, minOf(maxEntryBytes, MAX_MANIFEST_BYTES), budget)
                    }
                    name.startsWith(filesPrefix) && !entry.isDirectory -> {
                        // اسم رقمي ثابت: لا يعتمد على اسم المدخل، فلا مفاتيح غير صالحة للقرص
                        val target = File(spoolDir, written.toString())
                        try {
                            FileOutputStream(target).use { out -> copyBounded(zip, out, maxEntryBytes, budget) }
                            files[name] = target
                            written++
                        } catch (error: Exception) {
                            target.delete()
                            throw error
                        }
                    }
                    else -> copyBounded(zip, null, maxEntryBytes, budget)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return Spooled(manifestText, files)
    }
}
