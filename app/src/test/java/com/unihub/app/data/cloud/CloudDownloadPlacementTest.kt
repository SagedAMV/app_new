package com.unihub.app.data.cloud

import com.unihub.app.data.local.entity.FolderEntity
import org.junit.Assert.*
import org.junit.Test

class CloudDownloadPlacementTest {
    private val folders = listOf(RemoteCloudFolder("school", "اسم عند الآخر"),
        RemoteCloudFolder("photos", "صور", "school"), RemoteCloudFolder("trip", "رحلة", "photos"))
    @Test fun chosenLocalFolderDoesNotCreateCloudNames() {
        assertFalse(CloudDownloadPlacement.shouldCreateCloudFolders(CloudDownloadDestination(CloudDownloadLocation.LOCAL_FOLDER, 7, 999)))
    }
    @Test fun localRootIsAnExplicitDestinationNotACloudFolderFallback() {
        val target = CloudDownloadDestination(CloudDownloadLocation.LOCAL_FOLDER, null)
        assertNull(target.localFolderId); assertFalse(CloudDownloadPlacement.shouldCreateCloudFolders(target))
    }
    @Test fun cloudTreeRemainsAnAvailableChoice() {
        assertTrue(CloudDownloadPlacement.shouldCreateCloudFolders(CloudDownloadDestination()))
    }
    @Test fun nestedDownloadDoesNotCreateCloudAncestorsAboveTheChosenFolder() {
        assertEquals(listOf("photos", "trip"), CloudDownloadPlacement.relativeFolders(folders, "trip", "photos").map { it.key })
    }
    @Test fun downloadingRootOfSelectionCreatesOnlyThatRoot() {
        assertEquals(listOf("photos"), CloudDownloadPlacement.relativeFolders(folders, "photos", "photos").map { it.key })
    }
    @Test fun customFolderNameDoesNotRenameTheDestinationFolder() {
        val target = CloudDownloadDestination(CloudDownloadLocation.FOLDER_INSIDE_LOCAL, 7, 999, "مجلدي أنا")
        assertEquals("مجلدي أنا", CloudDownloadPlacement.localRootName(target, folders[1]))
        assertEquals(7L, target.localFolderId)
    }
    @Test fun unrelatedFolderCannotEscapeTheSelectionAnchor() {
        assertTrue(CloudDownloadPlacement.relativeFolders(folders, "school", "photos").isEmpty())
    }
    @Test fun defaultNameIsUsedOnlyForNewSubfolderNotUserParent() {
        val target = CloudDownloadDestination(CloudDownloadLocation.FOLDER_INSIDE_LOCAL, 7)
        assertEquals("صور", CloudDownloadPlacement.localRootName(target, folders[1]))
    }
    @Test fun existingLocalFolderIsMatchedOnlyAtTheSameParentIncludingRoot() {
        val localFolders = listOf(
            FolderEntity(id = 1, name = "علوم"),
            FolderEntity(id = 2, name = "علوم", parentId = 7),
            FolderEntity(id = 3, name = "علوم", parentId = 8)
        )

        assertEquals(2L, CloudDownloadPlacement.findExistingLocalFolder(localFolders, "علوم", 7)?.id)
        assertEquals(1L, CloudDownloadPlacement.findExistingLocalFolder(localFolders, "علوم", null)?.id)
        assertNull(CloudDownloadPlacement.findExistingLocalFolder(localFolders, "علوم", 9))
    }
    @Test fun localFolderMatchNormalizesNameAndPreservesTheOldestFolder() {
        val newest = FolderEntity(id = 9, name = "projects", parentId = 7)
        val original = FolderEntity(id = 4, name = "  Projects  ", parentId = 7)

        val matched = CloudDownloadPlacement.findExistingLocalFolder(
            listOf(newest, original), "  PROJECTS  ", 7
        )

        assertEquals(4L, matched?.id)
        assertEquals("  Projects  ", matched?.name)
    }
    @Test fun defaultLocationIsTheRecommendedCloudTreeFromSingleSource() {
        // جولة تعليمات.md: «ترتيب تلقائي .. موصى بة» مفعّل افتراضياً — المصدر الوحيد
        // CloudDownloadDefaults تقرأه الواجهة، وهذا الاختبار يثبته حتى لا ينحرف.
        assertEquals(CloudDownloadLocation.ORIGINAL_CLOUD_TREE, CloudDownloadDefaults.location)
        assertEquals(CloudDownloadDefaults.location, CloudDownloadDestination().location)
    }
}
