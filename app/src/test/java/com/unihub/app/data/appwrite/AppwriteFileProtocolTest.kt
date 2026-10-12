package com.unihub.app.data.appwrite

import com.unihub.app.data.cloud.CloudConflictException
import com.unihub.app.data.cloud.CloudDeleteRules
import com.unihub.app.data.cloud.CloudStorageConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppwriteFileProtocolTest {
    @Test fun suppliedProjectsAndBucketAreDistinctAndExact() {
        assertEquals("6acd036f0022a495a9df", AppwriteConfig.AUTH_PROJECT_ID)
        assertEquals("6acd0a6800189ccc164a", AppwriteConfig.STORAGE_PROJECT_ID)
        assertEquals("6acd11520015520ba016", AppwriteConfig.STORAGE_BUCKET_ID)
        assertNotEquals(AppwriteConfig.AUTH_PROJECT_ID, AppwriteConfig.STORAGE_PROJECT_ID)
    }
    @Test fun logicalKeysRoundTripThroughBoundedAppwriteIdentifiers() {
        for (key in listOf("files/" + "a".repeat(64) + ".pdf", "files/محاضرة.pdf", "folder/file with spaces.txt")) {
            val id = AppwriteFileProtocol.fileId(key)
            val name = AppwriteFileProtocol.uploadName(key)
            assertTrue(AppwriteFileProtocol.validId(id))
            assertTrue(id.length <= 36)
            assertTrue(name.toByteArray().size <= 255)
            assertEquals(key, AppwriteFileProtocol.logicalKey(id, name))
        }
    }
    @Test fun unsafeAndUnboundedKeysAreRejected() {
        for (key in listOf("", "/absolute", "../escape", "a/../b", "a//b", "a\\b", "bad\nheader", "x".repeat(181))) {
            assertFalse(key, AppwriteFileProtocol.isSafeObjectKey(key))
        }
    }
    @Test fun untrustedNamesCannotImpersonateApplicationObjects() {
        val encoded = AppwriteFileProtocol.uploadName("files/file.pdf")
        assertEquals("external:other-file", AppwriteFileProtocol.logicalKey("other-file", encoded))
        assertEquals("other-file", AppwriteFileProtocol.fileId("external:other-file"))
        assertEquals("external:plain", AppwriteFileProtocol.logicalKey("plain", "lecture.pdf"))
    }
    @Test fun concurrentWritersSelectSameNextIdAndCannotOverwriteTheirParent() {
        val root = AppwriteFileProtocol.nextManifestId(null)
        val next = AppwriteFileProtocol.nextManifestId(root)
        assertEquals(next, AppwriteFileProtocol.nextManifestId(root))
        assertNotEquals(root, next)
        assertTrue(AppwriteFileProtocol.validId(next))
        assertEquals(1L, AppwriteFileProtocol.manifestGeneration(next))
    }
    @Test fun latestManifestUsesGenerationNotLocalClockAndIgnoresIncompleteUploads() {
        val first = AppwriteFileProtocol.nextManifestId(null)
        val second = AppwriteFileProtocol.nextManifestId(first)
        val third = AppwriteFileProtocol.nextManifestId(second)
        val files = listOf(manifest(second), manifest(first), manifest(third).copy(chunksUploaded = 0))
        assertEquals(second, AppwriteFileProtocol.latestManifest(files)?.id)
    }
    @Test(expected = CloudConflictException::class)
    fun divergentHeadsAreNotSilentlySelected() {
        val first = "m0000000000000001_" + "a".repeat(17)
        val second = "m0000000000000001_" + "b".repeat(17)
        AppwriteFileProtocol.latestManifest(listOf(manifest(first), manifest(second)))
    }
    @Test fun metadataKeysAreProtectedFromDeletion() {
        for (key in listOf(CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY, CloudStorageConfig.REMOTE_META_OBJECT_KEY, CloudStorageConfig.REMOTE_BACKUP_OBJECT_KEY)) {
            assertFalse(CloudDeleteRules.isDeletableObjectKey(key))
        }
        assertTrue(CloudDeleteRules.isDeletableObjectKey("files/lecture.pdf"))
    }
    @Test fun lastUploadChunkContainsOnlyRemainingBytes() {
        val chunk = AppwriteFileProtocol.CHUNK_BYTES.toLong()
        assertEquals(chunk.toInt(), AppwriteFileProtocol.chunkLength(chunk + 17, 0))
        assertEquals(17, AppwriteFileProtocol.chunkLength(chunk + 17, chunk))
        assertEquals(0, AppwriteFileProtocol.chunkLength(0, 0))
        assertEquals(0, AppwriteFileProtocol.chunkLength(chunk, chunk))
    }
    @Test fun storageGrantIsBoundToUserProjectBucketAndBoundedLifetime() {
        val response = grant()
        val accepted = StorageGrant.fromResponse(response, "user-1", 7L, 10L)
        assertEquals(7L, accepted.sessionVersion)
        assertEquals(270_000_000_010L, accepted.refreshAtNanos)
        assertFalse(accepted.toString().contains("header.payload.signature"))
        for ((field, value) in listOf("projectId" to AppwriteConfig.AUTH_PROJECT_ID, "bucketId" to "wrong", "authUserId" to "other")) {
            assertTrue(runCatching { StorageGrant.fromResponse(grant().put(field, value), "user-1", 1L, 0L) }.isFailure)
        }
        for (ttl in listOf(0, 59, 301, 3600)) {
            assertTrue(runCatching { StorageGrant.fromResponse(grant().put("expiresIn", ttl), "user-1", 1L, 0L) }.isFailure)
        }
    }
    @Test fun resumedPartsAreBoundToConnectionAndVersion() {
        val staging = java.io.File("staging")
        fun part(version: String, connection: String) = com.unihub.app.data.cloud.CloudDownloadPart.fileFor(staging, "external:lecture", version, connection)
        assertEquals(part("v1", "user-A"), part("v1", "user-A"))
        assertNotEquals(part("v1", "user-A"), part("v2", "user-A"))
        assertNotEquals(part("v1", "user-A"), part("v1", "user-B"))
        assertTrue(part("v1", "user-A").name.matches(Regex("dl_[0-9a-f]{64}\\.part")))
    }
    private fun grant() = JSONObject().put("projectId", AppwriteConfig.STORAGE_PROJECT_ID)
        .put("bucketId", AppwriteConfig.STORAGE_BUCKET_ID).put("authUserId", "user-1")
        .put("jwt", "header.payload.signature").put("expiresIn", 300)
    private fun manifest(id: String) = AppwriteStoredFile(id, CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY, 2, "digest",
        "2026-10-12T00:00:00Z", "2026-10-12T00:00:00Z", 1, 1)
}
