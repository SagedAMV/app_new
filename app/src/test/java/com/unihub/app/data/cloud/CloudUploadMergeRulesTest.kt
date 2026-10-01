package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات قواعد دمج الرفع (جولة تعليمات.md): المجلد الموجود بنفس الاسم يُدمج لا يُكرَّر،
 * والملف الموجود ببصمته واسمه في الوجهة لا يُرفع مرة أخرى. نقية على JVM بلا شبكة.
 */
class CloudUploadMergeRulesTest {
    private val folders = listOf(
        RemoteCloudFolder("folder:a", "محاضرات"),
        RemoteCloudFolder("folder:b", "ملخصات", "folder:a"),
        RemoteCloudFolder("path:docs", "Docs"),
        RemoteCloudFolder("folder:c", "محاضرات", "folder:a")
    )
    private val hash = "a".repeat(64)

    private fun remoteFile(key: String, name: String, ext: String = "pdf", sha: String = hash, size: Long = 100, folderKey: String? = null) =
        RemoteCloudFile(remoteKey = key, id = 0, name = name, extension = ext, size = size, sha256 = sha, cloudFolderKey = folderKey)

    @Test fun existingFolderWithSameNameAndParentIsMerged() {
        val found = CloudUploadMergeRules.findExistingFolder(folders, "محاضرات", null)
        assertEquals("folder:a", found?.key)
    }

    @Test fun folderMatchIgnoresCaseAndExtraWhitespace() {
        assertEquals("path:docs", CloudUploadMergeRules.findExistingFolder(folders, "  docs ", null)?.key)
        assertEquals("folder:a", CloudUploadMergeRules.findExistingFolder(folders, "محاضرات  ", null)?.key)
    }

    @Test fun sameNameUnderDifferentParentDoesNotMatch() {
        // «محاضرات» تحت folder:a موجودة لكن على مستوى آخر فلا تُلتبس بالجذر
        assertEquals("folder:c", CloudUploadMergeRules.findExistingFolder(folders, "محاضرات", "folder:a")?.key)
        assertNull(CloudUploadMergeRules.findExistingFolder(folders, "ملخصات", null))
    }

    @Test fun differentOrBlankFolderNameNeverMatches() {
        assertNull(CloudUploadMergeRules.findExistingFolder(folders, "غير موجود", null))
        assertNull(CloudUploadMergeRules.findExistingFolder(folders, "   ", null))
    }

    @Test fun existingFileWithSameFingerprintNameAndFolderSkipsUpload() {
        val files = listOf(remoteFile("files/x", "بحث", folderKey = "folder:a"))
        assertEquals("files/x", CloudUploadMergeRules.findExistingFile(files, "بحث", "pdf", hash, 100, "folder:a")?.remoteKey)
    }

    @Test fun sameFingerprintButDifferentNameIsADifferentFile() {
        val files = listOf(remoteFile("files/x", "بحث", folderKey = "folder:a"))
        // إعادة استخدام المفتاح هنا كانت ستعيد تسمية «بحث» صمتاً عبر mergeFiles
        assertNull(CloudUploadMergeRules.findExistingFile(files, "ورقة", "pdf", hash, 100, "folder:a"))
    }

    @Test fun sameFingerprintInAnotherFolderIsNotMerged() {
        val files = listOf(remoteFile("files/x", "بحث", folderKey = "folder:a"))
        assertNull(CloudUploadMergeRules.findExistingFile(files, "بحث", "pdf", hash, 100, "folder:b"))
        assertNull(CloudUploadMergeRules.findExistingFile(files, "بحث", "pdf", hash, 100, null))
    }

    @Test fun sizeOrExtensionMismatchNeverMerges() {
        val files = listOf(remoteFile("files/x", "بحث", folderKey = null))
        assertNull(CloudUploadMergeRules.findExistingFile(files, "بحث", "pdf", hash, 101, null))
        assertNull(CloudUploadMergeRules.findExistingFile(files, "بحث", "docx", hash, 100, null))
    }

    @Test fun blankFingerprintNeverMatches() {
        val files = listOf(remoteFile("files/x", "بحث", sha = "", folderKey = null))
        assertNull(CloudUploadMergeRules.findExistingFile(files, "بحث", "pdf", "", 100, null))
        assertNull(CloudUploadMergeRules.findExistingFile(files, "بحث", "pdf", hash, 100, null))
    }

    @Test fun hashAndNameComparisonAreCaseInsensitive() {
        val files = listOf(remoteFile("files/x", "Report", ext = "PDF", sha = hash.uppercase(), folderKey = null))
        assertEquals("files/x", CloudUploadMergeRules.findExistingFile(files, "report", "pdf", hash.lowercase(), 100, null)?.remoteKey)
        assertTrue(CloudUploadMergeRules.sameName("  File ", "file"))
    }
}
