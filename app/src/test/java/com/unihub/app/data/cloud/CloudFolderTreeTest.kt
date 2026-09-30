package com.unihub.app.data.cloud

import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FolderEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudFolderTreeTest {
    private val folders = listOf(RemoteCloudFolder("photos", "صور"), RemoteCloudFolder("summer", "الصيف", "photos"), RemoteCloudFolder("other", "أخرى"))
    private fun remote(key: String, parent: String?) = RemoteCloudFile(key, 0, key, "jpg", 100, cloudFolderKey = parent)
    private fun local(id: Long, folder: Long?, size: Long = 100) = FileEntity(id = id, name = "ملف$id", extension = "jpg",
        mimeType = "image/jpeg", size = size, folderId = folder, filePath = "/local/$id.jpg")

    @Test fun folderSelectionIncludesAllNestedFilesButNotOtherFolders() {
        val files = listOf(remote("a", "photos"), remote("b", "summer"), remote("c", "other"), remote("d", null))
        assertEquals(setOf("a", "b"), CloudFolderTree.filesWithin(files, folders, "photos").map { it.remoteKey }.toSet())
    }
    @Test fun sameFolderNamesDoNotMergeDistinctIdentities() {
        val tree = listOf(RemoteCloudFolder("a", "صور"), RemoteCloudFolder("b", "صور"))
        assertEquals(2, CloudFolderTree.normalise(tree).size)
        assertEquals(setOf("a"), CloudFolderTree.descendants(tree, "a"))
    }
    @Test fun folderCyclesAreBrokenBeforeBrowsing() {
        val tree = CloudFolderTree.normalise(listOf(RemoteCloudFolder("a", "A", "b"), RemoteCloudFolder("b", "B", "a")))
        assertTrue(tree.all { it.parentKey == null })
    }
    @Test fun missingParentsRemainVisibleAtRoot() {
        val tree = CloudFolderTree.normalise(listOf(RemoteCloudFolder("a", "A", "missing")))
        assertNull(tree.single().parentKey)
    }
    @Test fun ancestorsAreOrderedForLocalFolderCreation() {
        assertEquals(listOf("photos", "summer"), CloudFolderTree.ancestors(folders, "summer").map { it.key })
    }
    @Test fun selectedFileDoesNotUploadFilesInItsAncestors() {
        val localFolders = listOf(FolderEntity(1, "أب"), FolderEntity(2, "ابن", parentId = 1), FolderEntity(3, "آخر"))
        val plan = CloudFolderTree.plan("selected", listOf(local(1, 1), local(2, 2), local(3, 3)), localFolders, setOf(2), emptySet())
        assertEquals(setOf(2L), plan.fileIds)
        assertEquals(setOf(1L, 2L), plan.folderIds)
        assertEquals(100L, plan.totalBytes)
    }
    @Test fun wholeFolderPlanIncludesPhotosAndNestedFilesOnly() {
        val localFolders = listOf(FolderEntity(1, "صور"), FolderEntity(2, "فرعي", parentId = 1), FolderEntity(3, "خاص"))
        val plan = CloudFolderTree.plan("folder", listOf(local(1, 1), local(2, 2), local(3, 3), local(4, null)), localFolders, emptySet(), setOf(1))
        assertEquals(setOf(1L, 2L), plan.fileIds)
        assertEquals(200L, plan.totalBytes)
    }
    @Test fun emptyFolderCanBeUploadedWithoutInventingAFile() {
        val plan = CloudFolderTree.plan("empty", emptyList(), listOf(FolderEntity(1, "فارغ")), emptySet(), setOf(1))
        assertTrue(plan.fileIds.isEmpty()); assertEquals(setOf(1L), plan.folderIds)
    }
    @Test fun folderMetadataRoundTripsAndKeepsHierarchy() {
        val json = folderToJson(folders[1])
        assertEquals(folders[1], folderFromJson(json))
        val merged = CloudFolderTree.mergeFolders(JSONArray().put(folderToJson(folders[0])), listOf(folders[1]))
        assertEquals(2, merged.length())
    }
    @Test fun updateDoesNotDeleteUnselectedCloudFolder() {
        val merged = CloudFolderTree.mergeFolders(JSONArray().put(folderToJson(folders[2])), listOf(folders[0]))
        assertEquals(2, merged.length())
    }
    @Test fun rawR2PathsProduceBrowsableNestedFolders() {
        val files = listOf(remote("2026/صور/a.jpg", "path:2026/صور"))
        val tree = CloudFolderTree.foldersFromManifest(null, files, emptyList())
        assertEquals("path:2026", tree.first { it.key == "path:2026/صور" }.parentKey)
    }
    @Test fun oldManifestFolderIdsMigrateWithoutLosingParents() {
        val manifest = JSONObject().put("folders", JSONArray()
            .put(JSONObject().put("id", 1).put("name", "أب"))
            .put(JSONObject().put("id", 2).put("name", "ابن").put("parentId", 1)))
        val files = listOf(remote("a", "legacy:2").copy(folderId = 2))
        val tree = CloudFolderTree.foldersFromManifest(manifest, files, emptyList())
        assertEquals(listOf("legacy:1", "legacy:2"), CloudFolderTree.ancestors(tree, "legacy:2").map { it.key })
    }
    @Test fun emptyS3FolderMarkersRemainVisible() {
        val tree = CloudFolderTree.foldersFromManifest(null, emptyList(), listOf(R2ObjectSummary("صور/فارغ/", 0, "v")))
        assertEquals(2, tree.size)
    }
    @Test fun remoteFileFolderKeySurvivesCachedJson() {
        val file = remote("a", "summer")
        assertEquals(file, remoteFileFromJson(remoteFileToJson(file)))
    }
}
