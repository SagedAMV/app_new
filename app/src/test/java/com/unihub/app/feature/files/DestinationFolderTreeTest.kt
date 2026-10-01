package com.unihub.app.feature.files

import com.unihub.app.data.local.entity.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات محلية لشجرة وجهة التنزيل القابلة للتوسيع (جولة تعليمات.md — المشكلة
 * الثالثة): بديل القائمة المسطحة «فيزياء / ملخصات».
 * التشغيل: ./gradlew :app:testDebugUnitTest --tests "com.unihub.app.feature.files.DestinationFolderTreeTest"
 */
class DestinationFolderTreeTest {

    private fun folder(id: Long, name: String, parentId: Long? = null) =
        FolderEntity(id = id, name = name, parentId = parentId)

    private val physics = folder(1, "فيزياء")
    private val summaries = folder(2, "ملخصات", parentId = 1)
    private val handouts = folder(3, "الملازم", parentId = 1)
    private val mechanics = folder(4, "ميكانيكا", parentId = 2)
    private val folders = listOf(physics, summaries, handouts, mechanics)

    @Test fun onlyRootsVisibleBeforeExpansion() {
        val rows = DestinationFolderTree.rows(folders, expandedIds = emptySet(), selectedId = null)
        assertEquals(listOf("فيزياء"), rows.map { it.folder.name })
        assertTrue(rows.single().hasChildren)
        assertFalse(rows.single().expanded)
    }

    @Test fun clickingToExpandRevealsChildrenIndentedOneLevel() {
        val rows = DestinationFolderTree.rows(folders, expandedIds = setOf(1L), selectedId = null)
        assertEquals(listOf("فيزياء", "ملخصات", "الملازم"), rows.map { it.folder.name })
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
    }

    @Test fun nestedExpansionReachesGrandchildrenAndCollapseHidesThem() {
        val deep = DestinationFolderTree.rows(folders, expandedIds = setOf(1L, 2L), selectedId = null)
        assertEquals(listOf("فيزياء", "ملخصات", "ميكانيكا", "الملازم"), deep.map { it.folder.name })
        assertEquals(listOf(0, 1, 2, 1), deep.map { it.depth })
        val collapsed = DestinationFolderTree.rows(folders, expandedIds = emptySet(), selectedId = null)
        assertEquals(1, collapsed.size)
    }

    @Test fun selectionFlagFollowsTheSelectedFolderAtAnyDepth() {
        val rows = DestinationFolderTree.rows(folders, expandedIds = setOf(1L, 2L), selectedId = 2L)
        assertEquals(listOf(false, true, false, false), rows.map { it.isSelected })
    }

    @Test fun emptyFolderShowsAsLeafWithoutExpandArrow() {
        val rows = DestinationFolderTree.rows(folders, expandedIds = setOf(1L), selectedId = null)
        val leaf = rows.first { it.folder.name == "الملازم" }
        assertFalse(leaf.hasChildren)
    }

    @Test fun parentIdCyclesTerminateAndAppearAtTopLevelOnce() {
        // بيانات فاسدة: د ↔ هـ يشير كل منهما للآخر — لا حلقة لانهائية ولا اختفاء
        val d = folder(5, "د", parentId = 6)
        val e = folder(6, "هـ", parentId = 5)
        val rows = DestinationFolderTree.rows(folders + d + e, expandedIds = setOf(1L, 5L, 6L), selectedId = null)
        assertEquals(rows.size, rows.map { it.folder.id }.toSet().size) // لا تكرار
        assertTrue(rows.any { it.folder.id == 5L })
        assertTrue(rows.any { it.folder.id == 6L })
    }

    @Test fun selfParentFolderDoesNotHangAndAppearsOnce() {
        val selfRef = FolderEntity(id = 9, name = "ذاتي", parentId = 9)
        val rows = DestinationFolderTree.rows(listOf(selfRef), expandedIds = setOf(9L), selectedId = null)
        assertEquals(1, rows.size)
        assertEquals(9L, rows.single().folder.id)
    }

}
