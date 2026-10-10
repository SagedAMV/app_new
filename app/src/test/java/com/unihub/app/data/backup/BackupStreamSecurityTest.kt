package com.unihub.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupStreamSecurityTest {

    @Test
    fun legacyJsonReaderRejectsInputAboveConfiguredLimit() {
        try {
            BackupStream.readLegacyTextBounded(ByteArrayInputStream("123456".toByteArray()), 5L)
            throw AssertionError("Expected the input bound to be enforced")
        } catch (_: IOException) {
            // Expected: the input is larger than five bytes.
        }
    }

    @Test
    fun spoolPreservesManifestAndStreamsFileContentToDisk() {
        val bytes = zipOf("{}", "files/12.pdf", ByteArray(128) { (it % 251).toByte() })
        val folder = Files.createTempDirectory("unihub-backup-test-").toFile()
        try {
            val result = BackupStream.spool(
                input = ByteArrayInputStream(bytes),
                manifestEntry = "manifest.json",
                filesPrefix = "files/",
                spoolDir = folder,
                maxEntryBytes = 1_024,
                maxTotalBytes = 2_048,
                maxEntries = 10
            )
            assertEquals("{}", result.manifestText)
            val file = result.filesByEntry.getValue("files/12.pdf")
            assertTrue(file.isFile)
            assertEquals(128L, file.length())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun spoolRejectsArchiveWhoseExpandedContentExceedsGlobalBudget() {
        val bytes = zipOf("{}", "files/12.pdf", ByteArray(128) { 7 })
        val folder = Files.createTempDirectory("unihub-backup-limit-").toFile()
        try {
            try {
                BackupStream.spool(
                    input = ByteArrayInputStream(bytes),
                    manifestEntry = "manifest.json",
                    filesPrefix = "files/",
                    spoolDir = folder,
                    maxEntryBytes = 1_024,
                    maxTotalBytes = 32,
                    maxEntries = 10
                )
                throw AssertionError("Expected the expanded archive budget to be enforced")
            } catch (_: IOException) {
                // Expected: expanded content exceeds 32 bytes.
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun spoolRejectsArchivesWithTooManyEntries() {
        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                repeat(3) { index ->
                    zip.putNextEntry(ZipEntry("unknown-$index"))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
        val folder = Files.createTempDirectory("unihub-backup-count-").toFile()
        try {
            try {
                BackupStream.spool(
                    input = ByteArrayInputStream(bytes),
                    manifestEntry = "manifest.json",
                    filesPrefix = "files/",
                    spoolDir = folder,
                    maxEntryBytes = 1_024,
                    maxTotalBytes = 1_024,
                    maxEntries = 2
                )
                throw AssertionError("Expected the entry-count limit to be enforced")
            } catch (_: IOException) {
                // Expected: three entries exceed a limit of two.
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun zipOf(manifest: String, fileName: String, content: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content)
                zip.closeEntry()
            }
            out.toByteArray()
        }
}
