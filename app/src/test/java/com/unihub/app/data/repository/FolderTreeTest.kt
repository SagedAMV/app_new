package com.unihub.app.data.repository

import com.unihub.app.data.local.entity.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات منطق شجرة المجلدات النقي [FolderTree] — يحمي ميزتي النقل والحذف
 * العميق من الانكسار بصمت (حلقات الأبوة هي أخطر حالة فشل ممكنة هنا).
 */
class FolderTreeTest {

    private fun folder(id: Long, parentId: Long?, name: String = "f$id") =
        FolderEntity(id = id, name = name, parentId = parentId)

    /**
     * شجرة الاختبار:
     * 1 (جذر) ── 2 ── 4
     *        └─ 3
     * 5 (جذر مستقل)
     */
    private val tree = listOf(
        folder(1, null),
        folder(2, 1),
        folder(3, 1),
        folder(4, 2),
        folder(5, null)
    )

    // ─── subtreeIds ────────────────────────────────────────────────────────

    @Test
    fun subtreeOfRootContainsWholeBranch() {
        assertEquals(setOf(1L, 2L, 3L, 4L), FolderTree.subtreeIds(tree, 1))
    }

    @Test
    fun subtreeOfLeafIsItself() {
        assertEquals(setOf(4L), FolderTree.subtreeIds(tree, 4))
        assertEquals(setOf(5L), FolderTree.subtreeIds(tree, 5))
    }

    @Test
    fun subtreeOfMiddleNodeExcludesSiblingsAndParents() {
        assertEquals(setOf(2L, 4L), FolderTree.subtreeIds(tree, 2))
    }

    // ─── wouldCreateCycle ──────────────────────────────────────────────────

    @Test
    fun movingToRootNeverCreatesCycle() {
        assertFalse(FolderTree.wouldCreateCycle(tree, 1, null))
        assertFalse(FolderTree.wouldCreateCycle(tree, 2, null))
    }

    @Test
    fun movingFolderIntoItselfIsCycle() {
        assertTrue(FolderTree.wouldCreateCycle(tree, 2, 2))
    }

    @Test
    fun movingFolderIntoOwnDescendantIsCycle() {
        assertTrue(FolderTree.wouldCreateCycle(tree, 1, 4))
        assertTrue(FolderTree.wouldCreateCycle(tree, 2, 4))
    }

    @Test
    fun movingToSiblingOrUnrelatedFolderIsAllowed() {
        assertFalse(FolderTree.wouldCreateCycle(tree, 2, 3))
        assertFalse(FolderTree.wouldCreateCycle(tree, 3, 5))
        assertFalse(FolderTree.wouldCreateCycle(tree, 4, 5))
    }

    @Test
    fun movingUnderOwnParentChainIsAllowed() {
        assertFalse(FolderTree.wouldCreateCycle(tree, 4, 3))
    }

    @Test
    fun corruptedCycleInDataDoesNotLoopForever() {
        // بيانات فاسدة افتراضياً: 6 أبوه 7 و7 أبوه 6 — الدالة يجب أن تنتهي بأمان
        val corrupted = tree + listOf(folder(6, 7), folder(7, 6))
        assertFalse(FolderTree.wouldCreateCycle(corrupted, 5, 7))
    }
}
