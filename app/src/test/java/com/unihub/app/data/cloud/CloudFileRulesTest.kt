package com.unihub.app.data.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudFileRulesTest {
    private fun remote(key: String = "files/a.pdf", etag: String = "v1", size: Long = 50) =
        RemoteCloudFile(key, 99, "محاضرة", "pdf", size, etag = etag)
    private fun link(key: String = "files/a.pdf", version: String = "v1:50", localId: Long = 7, size: Long = 50) =
        CloudFileLink(localId, key, version, "f".repeat(64), size, 0)

    @Test fun exactIdentityAndVersionAreDownloaded() {
        assertTrue(CloudFileRules.isDownloaded(remote(), link(), 7, 50))
    }
    @Test fun sameNameAndSizeNeverEstablishIdentity() {
        assertFalse(CloudFileRules.isDownloaded(remote("other/a.pdf"), link(), 7, 50))
    }
    @Test fun sameSizeChangedEtagIsAvailableAgain() {
        assertFalse(CloudFileRules.isDownloaded(remote(etag = "v2"), link(), 7, 50))
    }
    @Test fun remoteRoomIdIsNotLocalIdentity() {
        assertFalse(CloudFileRules.isDownloaded(remote(), link(), 99, 50))
    }
    @Test fun missingDiskFileIsNotDownloaded() {
        assertFalse(CloudFileRules.isDownloaded(remote(), link(), 7, null))
    }
    @Test fun truncatedDiskFileIsNotDownloaded() {
        assertFalse(CloudFileRules.isDownloaded(remote(), link(), 7, 10))
    }
    @Test fun emptyFilesAreValid() {
        assertTrue(CloudFileRules.isDownloaded(remote(size = 0), link(version = "v1:0", size = 0), 7, 0))
    }
    @Test fun unmappedFileIsNotAssumedDownloaded() {
        assertFalse(CloudFileRules.isDownloaded(remote(), null, 7, 50))
    }
    @Test fun sameVersionDoesNotNotifyTwice() {
        val file = remote()
        assertTrue(CloudFileRules.newNotifications(listOf(file), setOf(file.notificationToken)).isEmpty())
    }
    @Test fun updatedVersionNotifiesOnceAgain() {
        assertEquals(1, CloudFileRules.newNotifications(listOf(remote(etag = "v2")), setOf(remote().notificationToken)).size)
    }
    @Test fun contentKeysDoNotUseAutoIncrementIds() {
        val hash = "a".repeat(64)
        assertEquals("files/$hash.pdf", CloudFileRules.contentObjectKey(hash, ".PDF"))
    }
    @Test fun equalContentsCanKeepDifferentNamesAndFolders() {
        val hash = "c".repeat(64)
        val keyA = CloudFileRules.contentObjectKey(hash, "pdf", "record-a")
        val keyB = CloudFileRules.contentObjectKey(hash, "pdf", "record-b")
        assertNotEquals(keyA, keyB)
        val records = CloudManifestTools.mergeFiles(null, listOf(
            remote(keyA).copy(name = "رياضيات", folderId = 1),
            remote(keyB).copy(name = "فيزياء", folderId = 2)))
        assertEquals(2, records.length())
        assertEquals("رياضيات", records.getJSONObject(0).getString("name"))
        assertEquals(2L, records.getJSONObject(1).getLong("folderId"))
    }
    @Test fun sameLogicalIdentityIsSafeToRetry() {
        assertEquals(CloudFileRules.contentObjectKey("c".repeat(64), "pdf", "stable-id"),
            CloudFileRules.contentObjectKey("c".repeat(64), "pdf", "stable-id"))
    }
    @Test fun extensionCannotEscapePrefix() {
        assertEquals("files/${"b".repeat(64)}.pdf", CloudFileRules.contentObjectKey("b".repeat(64), "../../pdf"))
    }
    @Test(expected = IllegalArgumentException::class) fun badHashIsRejected() {
        CloudFileRules.contentObjectKey("not-a-hash", "pdf")
    }
    @Test fun remoteDescriptorJsonRoundTrips() {
        val file = remote().copy(folderName = "الفصل الأول", folderId = 12, sha256 = "f".repeat(64), lastModifiedAt = 1234)
        assertEquals(file, remoteFileFromJson(remoteFileToJson(file)))
    }

    private fun root(title: String? = null): JSONObject = JSONObject().apply {
        put("app", "unihub"); put("schemaVersion", 3)
        for (table in CloudManifestTools.metadataTables) put(table, JSONArray())
        if (title != null) put("notes", JSONArray().put(JSONObject().put("id", 1).put("title", title)))
    }
    @Test fun updatingPartialLibraryPreservesUnselectedCloudFiles() {
        val old = JSONArray().put(remoteFileToJson(remote("files/huge.mp4", size = 2_000_000_000)))
        val merged = CloudManifestTools.mergeFiles(old, listOf(remote("files/small.pdf")))
        assertEquals(2, merged.length())
        assertEquals(2_000_000_000L, merged.getJSONObject(0).getLong("size"))
    }
    @Test fun metadataUploadDoesNotLeakLocalAbsolutePaths() {
        val old = JSONArray().put(remoteFileToJson(remote()).put("filePath", "/data/user/0/private/file"))
        assertFalse(CloudManifestTools.mergeFiles(old, emptyList()).getJSONObject(0).has("filePath"))
    }
    @Test fun fileMetadataUpdatesRatherThanDuplicatingKey() {
        val old = JSONArray().put(remoteFileToJson(remote()))
        val merged = CloudManifestTools.mergeFiles(old, listOf(remote(etag = "v2")))
        assertEquals(1, merged.length()); assertEquals("v2", merged.getJSONObject(0).getString("etag"))
    }
    @Test fun firstImportAddsRemoteMetadata() {
        val merged = CloudManifestTools.mergeMetadata(root(), root("remote"), null)
        assertEquals("remote", merged.getJSONArray("notes").getJSONObject(0).getString("title"))
    }
    @Test fun remoteEditUpdatesUnchangedLocalMetadata() {
        val merged = CloudManifestTools.mergeMetadata(root("old"), root("new"), root("old"))
        assertEquals("new", merged.getJSONArray("notes").getJSONObject(0).getString("title"))
    }
    @Test fun remoteEditCannotOverwriteUnsentLocalEdit() {
        val merged = CloudManifestTools.mergeMetadata(root("mine"), root("theirs"), root("old"))
        assertEquals("mine", merged.getJSONArray("notes").getJSONObject(0).getString("title"))
    }
    @Test fun localDeletionIsNotUndoneByUnchangedRemote() {
        val merged = CloudManifestTools.mergeMetadata(root(), root("old"), root("old"))
        assertEquals(0, merged.getJSONArray("notes").length())
    }
    @Test fun remoteDeletionAppliesWhenLocalUnchanged() {
        val merged = CloudManifestTools.mergeMetadata(root("old"), root(), root("old"))
        assertEquals(0, merged.getJSONArray("notes").length())
    }
    @Test fun concurrentTextEditsAreDetectedRatherThanOverwritten() {
        assertEquals(listOf("notes:1"), CloudManifestTools.conflicts(root("mine"), root("theirs"), root("old")))
    }
    @Test fun independentPhoneIdCollisionIsDetected() {
        assertEquals(listOf("notes:1"), CloudManifestTools.conflicts(root("mine"), root("other phone"), null))
    }
    @Test fun ordinaryRemoteEditIsNotAConflict() {
        assertTrue(CloudManifestTools.conflicts(root("old"), root("new"), root("old")).isEmpty())
    }
    @Test(expected = java.io.IOException::class) fun localEditDuringScanAbortsMetadataReplacement() {
        CloudManifestTools.requireUnchangedLocalMetadata(root("snapshot"), root("user edited while scanning"))
    }
    @Test fun snapshotTimestampsDoNotCauseFalseRaceDetection() {
        CloudManifestTools.requireUnchangedLocalMetadata(root("same").put("exportedAt", 1), root("same").put("exportedAt", 2))
    }
    @Test fun folderDeletionNeverCascadesToLocalFiles() {
        val baseline = root().put("folders", JSONArray().put(JSONObject().put("id", 1).put("name", "محاضرات")))
        val merged = CloudManifestTools.mergeMetadata(JSONObject(baseline.toString()), root(), baseline)
        assertEquals(1, merged.getJSONArray("folders").length())
    }
    @Test fun objectFieldOrderingDoesNotCreateFakeChanges() {
        assertEquals(CloudManifestTools.canonical(JSONObject("{\"id\":1,\"name\":\"a\"}")),
            CloudManifestTools.canonical(JSONObject("{\"name\":\"a\",\"id\":1}")))
    }
}
