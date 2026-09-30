package com.unihub.app.data.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class CloudPresenceMatcherTest {
    private val hash = "a".repeat(64)
    private fun remote(key: String = "cloud/a", sha: String = hash, folder: String? = "photos") =
        RemoteCloudFile(key, 99, "السحابة", "pdf", 10, sha256 = sha, etag = "v1", cloudFolderKey = folder)
    private fun stat(id: Long = 7, path: String = "/restored/اسم محلي.pdf", modified: Long = 200) = CloudLocalFileStat(id, 123, path, 10, modified)
    private fun fp(local: CloudLocalFileStat = stat(), sha: String = hash) = CloudLocalFingerprint(local.id, local.createdAt, local.path, local.size, local.modifiedAt, sha)

    @Test fun restoredContentIsPresentWithoutCloudLinks() {
        val result = CloudPresenceMatcher.compare(listOf(remote()), listOf(stat()), listOf(fp()), emptyList())
        assertEquals(setOf("cloud/a"), result.presentKeys); assertTrue(result.missingKeys.isEmpty())
    }
    @Test fun changedRoomIdAndFilenameDoNotForceRedownload() {
        val local = stat(888, "/my-folders/renamed.txt")
        val result = CloudPresenceMatcher.compare(listOf(remote()), listOf(local), listOf(fp(local)), emptyList())
        assertTrue(result.missingKeys.isEmpty())
    }
    @Test fun matchingNameAndSizeWithDifferentBytesIsMissing() {
        val result = CloudPresenceMatcher.compare(listOf(remote()), listOf(stat()), listOf(fp(sha = "b".repeat(64))), emptyList())
        assertEquals(setOf("cloud/a"), result.missingKeys)
    }
    @Test fun unhashedBackupIsVerifyingNotFalselyNew() {
        val result = CloudPresenceMatcher.compare(listOf(remote()), listOf(stat()), emptyList(), emptyList())
        assertEquals(setOf("cloud/a"), result.verifyingKeys); assertTrue(result.missingKeys.isEmpty())
    }
    @Test fun localFileDeletionInvalidatesFingerprint() {
        val result = CloudPresenceMatcher.compare(listOf(remote()), emptyList(), listOf(fp()), emptyList())
        assertEquals(setOf("cloud/a"), result.missingKeys)
    }
    @Test fun sameSizedLocalEditInvalidatesCachedFingerprint() {
        val changed = stat(modified = 300)
        val result = CloudPresenceMatcher.compare(listOf(remote()), listOf(changed), listOf(fp()), emptyList())
        assertEquals(setOf("cloud/a"), result.verifyingKeys)
    }
    @Test fun idReuseCannotReuseAnotherRecordsFingerprint() {
        val changed = stat().copy(createdAt = 900)
        assertFalse(CloudPresenceMatcher.validFingerprint(fp(), changed))
    }
    @Test fun changedPathInvalidatesFingerprint() {
        assertFalse(CloudPresenceMatcher.validFingerprint(fp(), stat(path = "/other/path.pdf")))
    }
    @Test fun noChecksumNeverMatchesBySizeAlone() {
        val result = CloudPresenceMatcher.compare(listOf(remote(sha = "")), listOf(stat()), listOf(fp()), emptyList())
        assertEquals(setOf("cloud/a"), result.missingKeys)
    }
    @Test fun identicalPayloadAtAnotherLocalFolderIsNotDownloadedTwice() {
        val files = listOf(remote("a", folder = "photos"), remote("b", folder = "other"))
        val result = CloudPresenceMatcher.compare(files, listOf(stat()), listOf(fp()), emptyList())
        assertEquals(setOf("a", "b"), result.presentKeys)
    }
    @Test fun completedFoldersDoNotAppearInMissingOnlyTree() {
        val folders = listOf(RemoteCloudFolder("photos", "صور"), RemoteCloudFolder("other", "آخر"))
        assertTrue(CloudPresenceMatcher.foldersForMissing(folders, listOf(remote()), emptySet()).isEmpty())
    }
    @Test fun partialRestoreShowsOnlyFoldersWithMissingDescendants() {
        val folders = listOf(RemoteCloudFolder("photos", "صور"), RemoteCloudFolder("child", "فرعي", "photos"), RemoteCloudFolder("other", "آخر"))
        val files = listOf(remote("a", folder = "photos"), remote("b", folder = "child"), remote("c", folder = "other"))
        assertEquals(setOf("photos", "child"), CloudPresenceMatcher.foldersForMissing(folders, files, setOf("b")))
    }
    @Test fun newRemoteVersionDoesNotUseAnOldIdentityLink() {
        val file = remote().copy(etag = "v2", sha256 = "b".repeat(64))
        val link = CloudFileLink(7, file.remoteKey, "v1:10", hash, 10, 200, 123)
        val result = CloudPresenceMatcher.compare(listOf(file), listOf(stat()), listOf(fp()), listOf(link))
        assertEquals(setOf(file.remoteKey), result.missingKeys)
    }
    @Test fun exactExistingLinkStillWorksWithoutAFullHashPass() {
        val file = remote(); val link = CloudFileLink(7, file.remoteKey, file.versionToken, hash, 10, 200, 123)
        assertEquals(setOf(file.remoteKey), CloudPresenceMatcher.compare(listOf(file), listOf(stat()), emptyList(), listOf(link)).presentKeys)
    }
    @Test fun checksumComparisonIsCaseInsensitive() {
        assertTrue(CloudPresenceMatcher.compare(listOf(remote(sha = hash.uppercase())), listOf(stat()), listOf(fp()), emptyList()).missingKeys.isEmpty())
    }
    @Test fun backupByteRoundTripThenCloudComparisonDoesNotNeedOldIdsOrPaths() {
        val old = File.createTempFile("backup_source_", ".pdf")
        val restored = File.createTempFile("backup_restored_", ".pdf")
        try {
            old.writeText("data-restored-after-app-reset")
            restored.writeBytes(old.readBytes())
            val digest = MessageDigest.getInstance("SHA-256").digest(restored.readBytes()).joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }
            val local = CloudLocalFileStat(88, 900, restored.absolutePath, restored.length(), restored.lastModified())
            val file = remote().copy(size = old.length(), sha256 = digest)
            val fingerprint = CloudLocalFingerprint(88, 900, local.path, local.size, local.modifiedAt, digest)
            assertTrue(CloudPresenceMatcher.compare(listOf(file), listOf(local), listOf(fingerprint), emptyList()).missingKeys.isEmpty())
        } finally { old.delete(); restored.delete() }
    }
}
