package com.unihub.app.data.backup

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * اختبارات طبقة التدفق الخاصة بالاستيراد — وُجدت بعد عطل مرصود على جهاز المستخدم:
 * `Failed to allocate a 35258384 byte allocation ... until OOM` أثناء استعادة نسخة
 * احتياطية قديمة. السبب الجذري كان قراءة الأرشيف كله في الذاكرة ثم تجميع محتوى
 * كل ملف مضمّن فيها قبل الكتابة على القرص.
 *
 * التغطية هنا على JVM خالص (بلا Context ولا Android): قرارات رأس الملف، وفك الأرشيف
 * إلى قرص مؤقت، وحدود العناصر — وهي المواضع التي لو خطئت لانكسر استيراد كل النسخ.
 */
class BackupStreamTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private fun buffered(bytes: ByteArray): BufferedInputStream =
        BufferedInputStream(ByteArrayInputStream(bytes), 32 * 1024)

    // ========================================================================
    // S1: نسخة جديدة — سطر الشفرة يسبق PK. يجب أن يُستهلك السطر ويبقى الأرشيف كما هو
    // ========================================================================
    @Test
    fun skipSignatureLine_consumesHeaderOnlyWhenItMatches() {
        val header = BackupSignature.HEADER_BYTES
        val payload = "payload-bytes".toByteArray()

        val stream = buffered(header + payload)
        assertTrue("لازم يتعرف على الشفرة", BackupStream.skipSignatureLine(stream, header))
        assertArrayEquals("البايتات بعد الشفرة هي بقية الأرشيف", payload, stream.readBytes())
    }

    @Test
    fun skipSignatureLine_leavesStreamUntouchedForLegacyBackup() {
        val legacy = "{\"app\":\"unihub\",\"schemaVersion\":1}".toByteArray()
        val stream = buffered(legacy)

        assertFalse(BackupStream.skipSignatureLine(stream, BackupSignature.HEADER_BYTES))
        assertArrayEquals("نسخة JSON القديمة لا تفقد أي بايت", legacy, stream.readBytes())
    }

    // ========================================================================
    // S2: التمييز بين أرشيف ZIP ونص JSON — قرار خاطئ = «الملف ليس نسخة صالحة»
    // ========================================================================
    @Test
    fun looksLikeZip_detectsArchiveAndDoesNotConsumeMagicBytes() {
        val zip = zipOf("manifest.json" to "{}")
        val stream = buffered(zip)

        assertTrue(BackupStream.looksLikeZip(stream))
        assertArrayEquals("أربع بايتات التوقيع يجب أن تبقى مقروءة لقارئ الأرشيف", zip, stream.readBytes())
    }

    @Test
    fun looksLikeZip_returnsFalseForJsonAndForTinyFiles() {
        assertFalse(BackupStream.looksLikeZip(buffered("{\"app\":\"unihub\"}".toByteArray())))
        assertFalse(BackupStream.looksLikeZip(buffered(byteArrayOf(0x50, 0x4B))))
        assertFalse(BackupStream.looksLikeZip(buffered(ByteArray(0))))
    }

    // ========================================================================
    // S3: التدفق الكامل — النسخة الجديدة تمر بالمسار الصحيح لا بمسار JSON
    // ========================================================================
    @Test
    fun headerAndMagicTogetherRouteARealExportToTheArchivePath() {
        val manifest = """{"app":"unihub","schemaVersion":3,"files":[]}"""
        val archive = zipOf("manifest.json" to manifest)
        val stream = buffered(BackupSignature.HEADER_BYTES + archive)

        assertTrue(BackupStream.skipSignatureLine(stream, BackupSignature.HEADER_BYTES))
        assertTrue("أرشيف موقّع يجب أن يُقرأ كأرشيف", BackupStream.looksLikeZip(stream))

        // من نفس موضع التدفق — كما يفعل المسار الحقيقي: manifest يُستخرج من الأرشيف لا من رأس الملف
        val result = BackupStream.spool(
            input = stream,
            manifestEntry = "manifest.json",
            filesPrefix = "files/",
            spoolDir = File(temp.root, "routed"),
            maxEntryBytes = 1L * 1024 * 1024
        )
        assertEquals("unihub", JSONObject(result.manifestText!!).getString("app"))
    }

    // ========================================================================
    // S4 (حدّية): نسخ بذاكرة ثابتة مع حماية من قنبلة الضغط
    // ========================================================================
    @Test
    fun copyBounded_copiesEverythingUnderTheLimit() {
        val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()

        BackupStream.copyBounded(ByteArrayInputStream(data), out, 2L * 1024 * 1024)

        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun copyBounded_rejectsPayloadBiggerThanLimitWithUserReadableMessage() {
        val data = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()

        val error = runCatching {
            BackupStream.copyBounded(ByteArrayInputStream(data), out, 8L * 1024)
        }.exceptionOrNull()

        assertTrue("يجب أن يكون IOException", error is IOException)
        assertEquals("عنصر داخل النسخة الاحتياطية أكبر من الحد المسموح", error?.message)
    }

    // ========================================================================
    // S5 (الأهم للإصلاح): محتوى الملفات يذهب إلى قرص مؤقت لا إلى كومة الذاكرة
    // Oracle: كل عنصر ملفات = ملف موجود على القرص بحجمه الأصلي، والبايتات لا تُخزَّن
    // ========================================================================
    @Test
    fun spool_writesFileEntriesToDiskAndKeepsOnlyManifestInMemory() {
        val bigContent = ByteArray(12 * 1024 * 1024) { (it % 197).toByte() }
        val manifest = """{"app":"unihub","schemaVersion":3,"files":[]}"""
        val archive = zipOf(
            "manifest.json" to manifest,
            "files/7.pdf" to bigContent,
            "notes/ignored.txt" to "هذا العنصر ليس جزء الاستعادة".toByteArray()
        )
        val spoolDir = File(temp.root, "spool")

        val result = BackupStream.spool(
            input = ByteArrayInputStream(archive),
            manifestEntry = "manifest.json",
            filesPrefix = "files/",
            spoolDir = spoolDir,
            maxEntryBytes = 200L * 1024 * 1024
        )

        assertEquals(manifest, result.manifestText)
        assertEquals("عنصر خارج البادئة لا يُستعاد", 1, result.filesByEntry.size)
        val spooled = result.filesByEntry["files/7.pdf"]!!
        assertTrue("الملف يجب أن يكون على القرص", spooled.isFile)
        assertEquals("الحجم على القرص = حجم العنصر", bigContent.size.toLong(), spooled.length())
        assertArrayEquals(bigContent, spooled.readBytes())
    }

    @Test
    fun spool_createsSpoolDirAndSkipsDirectoryEntries() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("files/"))          // مجلد داخل الأرشيف
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{\"app\":\"unihub\"}".toByteArray())
            zip.closeEntry()
        }
        val spoolDir = File(temp.root, "nested/spool")

        val result = BackupStream.spool(
            input = ByteArrayInputStream(out.toByteArray()),
            manifestEntry = "manifest.json",
            filesPrefix = "files/",
            spoolDir = spoolDir,
            maxEntryBytes = 1024L
        )

        assertNotNull(result.manifestText)
        assertTrue("مجلد الأرشيف لا يُنشئ ملفاً مؤقتاً", result.filesByEntry.isEmpty())
    }

    @Test
    fun spool_reportsMissingManifestForNonBackupArchive() {
        val archive = zipOf("readme.txt" to "لا يوجد بيان نسخة هنا".toByteArray())

        val result = BackupStream.spool(
            input = ByteArrayInputStream(archive),
            manifestEntry = "manifest.json",
            filesPrefix = "files/",
            spoolDir = File(temp.root, "spool2"),
            maxEntryBytes = 1024L * 1024
        )

        assertNull("بدون manifest لا توجد بيانات صالحة — والمكتبة لم تُمَس", result.manifestText)
        assertTrue(result.filesByEntry.isEmpty())
    }

    @Test
    fun spool_stopsAtFirstEntryOverTheLimit() {
        val archive = zipOf("files/1.bin" to ByteArray(40 * 1024) { (it % 7).toByte() })

        val error = runCatching {
            BackupStream.spool(
                input = ByteArrayInputStream(archive),
                manifestEntry = "manifest.json",
                filesPrefix = "files/",
                spoolDir = File(temp.root, "spool3"),
                maxEntryBytes = 16L * 1024
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("عنصر داخل النسخة الاحتياطية أكبر من الحد المسموح", error?.message)
    }

    /** يبني أرشيف ZIP في الذاكرة من أزواج (اسم → نص أو بايتات) */
    private fun zipOf(vararg entries: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (value is ByteArray) value else value.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
