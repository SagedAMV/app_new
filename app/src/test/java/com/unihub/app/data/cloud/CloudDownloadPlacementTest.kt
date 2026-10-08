package com.unihub.app.data.cloud

import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.data.local.entity.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDownloadPlacementTest {
    private val folders = listOf(
        RemoteCloudFolder("school", "اسم عند الآخر"),
        RemoteCloudFolder("photos", "صور", "school"),
        RemoteCloudFolder("trip", "رحلة", "photos")
    )
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun localFile(
        id: Long,
        name: String,
        ext: String = "pdf",
        size: Long = 5000L,
        folderId: Long? = 10L
    ) = FileEntity(
        id = id,
        name = name,
        extension = ext,
        kind = FileKind.PDF,
        mimeType = "application/pdf",
        size = size,
        folderId = folderId,
        filePath = "/data/user/0/com.unihub.app/files/library/$id.$ext"
    )

    @Test fun chosenLocalFolderDoesNotCreateCloudNames() {
        assertFalse(CloudDownloadPlacement.shouldCreateCloudFolders(CloudDownloadDestination(CloudDownloadLocation.LOCAL_FOLDER, 7, 999)))
    }

    @Test fun localRootIsAnExplicitDestinationNotACloudFolderFallback() {
        val target = CloudDownloadDestination(CloudDownloadLocation.LOCAL_FOLDER, null)
        assertNull(target.localFolderId)
        assertFalse(CloudDownloadPlacement.shouldCreateCloudFolders(target))
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

    @Test fun resolveLocalFolderPrefersOldestSameLevelFolderOverStaleOrMovedLink() {
        // S6 & Inv1 & Inv3: رابط قديم يشير لمجلد مكرر أحدث (id=9) أو مجلد نُقل لمستوى آخر (id=12 تحت parent=50)
        val original = FolderEntity(id = 2, name = "مختبر", parentId = null, createdAt = 100L)
        val duplicate = FolderEntity(id = 9, name = "مختبر", parentId = null, createdAt = 200L)
        val moved = FolderEntity(id = 12, name = "مختبر", parentId = 50L, createdAt = 300L)
        val all = listOf(moved, duplicate, original)
        val remote = RemoteCloudFolder("folder:lab", "مختبر", parentKey = null, createdAt = 200L)

        val resolvedFromDuplicateLink = CloudDownloadPlacement.resolveLocalFolder(
            all, remote, parentId = null, link = CloudFolderLink(localId = 9, localCreatedAt = 200L, remoteKey = "folder:lab")
        )
        val resolvedFromMovedLink = CloudDownloadPlacement.resolveLocalFolder(
             listOf(moved), remote, parentId = null, link = CloudFolderLink(localId = 12, localCreatedAt = 300L, remoteKey = "folder:lab")
        )

        assertEquals(2L, resolvedFromDuplicateLink?.id)
        assertNull(resolvedFromMovedLink)
    }

    @Test fun shouldReuseChosenFolderAsRootPreventsNestedDuplicateFolderCreation() {
        // S3 & Inv6: اختيار مجلد محلي يحمل نفس اسم المجلد المسحوب لا ينشئ «ملخصات / ملخصات»
        val chosen = FolderEntity(id = 7, name = "  ملخصات  ", parentId = null)
        assertTrue(CloudDownloadPlacement.shouldReuseChosenFolderAsRoot(chosen, "ملخصات"))
        assertFalse(CloudDownloadPlacement.shouldReuseChosenFolderAsRoot(chosen, "محاضرات"))
        assertFalse(CloudDownloadPlacement.shouldReuseChosenFolderAsRoot(null, "ملخصات"))
    }

    @Test fun existingLocalFileInTargetFolderIsSkippedWhileNewFileIsDownloaded() {
        // S1 & S8 & Inv4 & MR2: تجاهل الملفات الموجودة سابقاً داخل المجلد المحلي الوجهة فقط
        val files = listOf(
            localFile(id = 1, name = "الفصل الأول", ext = "pdf", size = 5000L, folderId = 10L)
        )
        val hashes = mapOf(1L to hashA)

        val matchedExisting = CloudDownloadPlacement.findExistingLocalFile(
            localFiles = files,
            name = "الفصل الأول",
            extension = "pdf",
            size = 5000L,
            targetFolderId = 10L,
            remoteSha256 = hashA,
            localSha256ById = hashes
        )
        val unmatchedNew = CloudDownloadPlacement.findExistingLocalFile(
            localFiles = files,
            name = "الفصل الثاني",
            extension = "pdf",
            size = 7000L,
            targetFolderId = 10L,
            remoteSha256 = hashB,
            localSha256ById = hashes
        )

        assertNotNull(matchedExisting)
        assertEquals(1L, matchedExisting?.id)
        assertNull(unmatchedNew)
    }

    @Test fun sameFileInAnotherLocalFolderDoesNotPreventDownloadIntoTargetFolder() {
        // S5 & Inv5: وجود الملف في مجلد آخر (folderId=2) لا يمنع تنزيله في المجلد الوجهة (folderId=4)
        val files = listOf(
            localFile(id = 1, name = "واجب1", ext = "pdf", size = 3000L, folderId = 2L)
        )
        val hashes = mapOf(1L to hashA)

        assertNull(
            CloudDownloadPlacement.findExistingLocalFile(
                localFiles = files,
                name = "واجب1",
                extension = "pdf",
                size = 3000L,
                targetFolderId = 4L,
                remoteSha256 = hashA,
                localSha256ById = hashes
            )
        )
    }

    @Test fun modifiedLocalFileWithDifferentHashOrSizeIsNeverTreatedAsIdentical() {
        // S9: ملف عدّله المستخدم محلياً داخل المجلد الوجهة (بصمة مختلفة أو حجم مختلف) لا يُتجاهل ولا يُطابق
        val files = listOf(
            localFile(id = 5, name = "مشروع", ext = "docx", size = 12000L, folderId = 10L)
        )
        val localHashes = mapOf(5L to hashA)

        // بصمة مختلفة رغم تساوي الحجم والاسم
        assertNull(
            CloudDownloadPlacement.findExistingLocalFile(
                localFiles = files,
                name = "مشروع",
                extension = "docx",
                size = 12000L,
                targetFolderId = 10L,
                remoteSha256 = hashB,
                localSha256ById = localHashes
            )
        )
        // بصمة سحابية قوية مع غياب بصمة الملف المحلي لا تطابق عميانياً
        assertNull(
            CloudDownloadPlacement.findExistingLocalFile(
                localFiles = files,
                name = "مشروع",
                extension = "docx",
                size = 12000L,
                targetFolderId = 10L,
                remoteSha256 = hashA,
                localSha256ById = emptyMap()
            )
        )
        // حجم مختلف
        assertNull(
            CloudDownloadPlacement.findExistingLocalFile(
                localFiles = files,
                name = "مشروع",
                extension = "docx",
                size = 20000L,
                targetFolderId = 10L,
                remoteSha256 = hashA,
                localSha256ById = localHashes
            )
        )
    }

    @Test fun fileAndFolderMatchingAreCaseInsensitiveAndSanitizeUntrustedNames() {
        // S4 & S10 & MR3: مقارنة متسامحة مع المسافات وحالة الأحرف وتطهير محارف المسارات
        val files = listOf(
            localFile(id = 20, name = "  Report  ", ext = ".PDF", size = 1024L, folderId = 4L)
        )
        val matched = CloudDownloadPlacement.findExistingLocalFile(
            localFiles = files,
            name = "report",
            extension = "pdf",
            size = 1024L,
            targetFolderId = 4L,
            remoteSha256 = hashA.uppercase(),
            localSha256ById = mapOf(20L to hashA.lowercase())
        )
        assertEquals(20L, matched?.id)
        assertNull(CloudDownloadPlacement.findExistingLocalFolder(listOf(FolderEntity(id = 1, name = "علوم")), "   /..  ", null))
        assertNull(CloudDownloadPlacement.findExistingLocalFile(files, "   ", "pdf", 1024L, 4L))
    }

    @Test fun rawCloudObjectWithoutManifestHashMatchesByFolderNameExtensionAndSize() {
        // Hole #1: كائن سحابي خام بلا بصمة في البيان يطابق الملف الموجود في نفس المجلد بالاسم والامتداد والحجم
        val files = listOf(localFile(id = 11, name = "مرجع", ext = "pdf", size = 4096L, folderId = 3L))
        val matched = CloudDownloadPlacement.findExistingLocalFile(
            localFiles = files,
            name = "مرجع",
            extension = "pdf",
            size = 4096L,
            targetFolderId = 3L,
            remoteSha256 = ""
        )
        assertEquals(11L, matched?.id)
    }

    @Test fun duplicateLocalFilesInSameFolderDeterministicallySelectOldestId() {
        // Hole #2 & MR3: حتمية اختيار السجل الأقدم بغض النظر عن ترتيب القائمة
        val older = localFile(id = 3, name = "ملخص", ext = "pdf", size = 2048L, folderId = 5L)
        val newer = localFile(id = 8, name = "ملخص", ext = "pdf", size = 2048L, folderId = 5L)
        val hashes = mapOf(3L to hashA, 8L to hashA)

        val firstOrder = CloudDownloadPlacement.findExistingLocalFile(
            listOf(newer, older), "ملخص", "pdf", 2048L, 5L, hashA, hashes
        )
        val secondOrder = CloudDownloadPlacement.findExistingLocalFile(
            listOf(older, newer), "ملخص", "pdf", 2048L, 5L, hashA, hashes
        )

        assertEquals(3L, firstOrder?.id)
        assertEquals(3L, secondOrder?.id)
    }

    @Test fun resolveLocalFolderSupportsLegacyIdAndBlankNameFallback() {
        // Hole #3: مطابقة legacyId والاسم الاحتياطي «مجلد سحابي» عند فراغ الاسم السحابي
        val fallbackFolder = FolderEntity(id = 6, name = "مجلد سحابي", parentId = null, createdAt = 500L)
        val remoteBlank = RemoteCloudFolder("legacy:6", "   ", parentKey = null, createdAt = 500L, legacyId = 6L)

        val resolved = CloudDownloadPlacement.resolveLocalFolder(
            listOf(fallbackFolder), remoteBlank, parentId = null, link = null
        )
        assertEquals(6L, resolved?.id)
    }

    @Test fun defaultLocationIsTheRecommendedCloudTreeFromSingleSource() {
        // جولة تعليمات.md: «ترتيب تلقائي — موصى به» مفعّل افتراضياً — المصدر الوحيد
        // CloudDownloadDefaults تقرأه الواجهة، وهذا الاختبار يثبته حتى لا ينحرف.
        assertEquals(CloudDownloadLocation.ORIGINAL_CLOUD_TREE, CloudDownloadDefaults.location)
        assertEquals(CloudDownloadDefaults.location, CloudDownloadDestination().location)
    }
}
