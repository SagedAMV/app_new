package com.unihub.app.data.cloud

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
}
