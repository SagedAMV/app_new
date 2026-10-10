package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** اختبارات هندسة المجرة الهرمية على JVM، دون Compose أو Android. */
class GalaxyLayoutEngineTest {

    @Test
    fun everyFolderIsPlacedOnceAndEveryChildIsConnectedToItsParent() {
        val folders = listOf(
            folder(1L),
            folder(2L, parentId = 1L),
            folder(3L, parentId = 2L),
            folder(4L),
            folder(5L, parentId = 4L),
            folder(6L, parentId = 5L)
        )

        val layout = layout(folders)

        assertEquals(folders.map { it.folderId }.toSet(), layout.placements.map { it.folder.folderId }.toSet())
        assertEquals(folders.size, layout.placements.size)
        assertEquals(folders.size - 2, layout.connections.size)
        val byId = layout.placements.associateBy { it.folder.folderId }
        layout.connections.forEach { connection ->
            val parent = byId.getValue(connection.parentFolderId)
            val child = byId.getValue(connection.childFolderId)
            assertEquals(parent.ringIndex + 1, child.ringIndex)
            assertTrue("child should be farther than its parent", child.radiusDp > parent.radiusDp)
        }
    }

    @Test
    fun rootFoldersStayNearTheSunAndEachNestedLevelMovesOutward() {
        val layout = layout(
            listOf(
                folder(1L),
                folder(2L, parentId = 1L),
                folder(3L, parentId = 2L),
                folder(4L, parentId = 3L)
            )
        )
        assertEquals(GALAXY_FIRST_ORBIT_RADIUS_DP, layout.placements.first { it.folder.folderId == 1L }.radiusDp)
        assertTrue(layout.placements.first { it.folder.folderId == 2L }.radiusDp >= GALAXY_FIRST_ORBIT_RADIUS_DP + GALAXY_ORBIT_RADIAL_STEP_DP)
        assertTrue(layout.placements.first { it.folder.folderId == 3L }.radiusDp > layout.placements.first { it.folder.folderId == 2L }.radiusDp)
        assertTrue(layout.placements.first { it.folder.folderId == 4L }.radiusDp > layout.placements.first { it.folder.folderId == 3L }.radiusDp)
    }

    @Test
    fun childrenOccupyTheirParentSectorRatherThanBeingScatteredAcrossTheWholeGalaxy() {
        val layout = layout(
            listOf(
                folder(1L),
                folder(2L, parentId = 1L),
                folder(3L, parentId = 1L),
                folder(4L),
                folder(5L, parentId = 4L)
            )
        )
        val roots = layout.placements.filter { it.parentFolderId == null }
        val firstRoot = roots.first { it.folder.folderId == 1L }
        val children = layout.placements.filter { it.parentFolderId == 1L }
        assertEquals(2, children.size)
        // مع جذرين، يشغل كل جذر نصف الدائرة، لذا يبقى أبناؤه ضمن نصفه الزاوي.
        assertTrue(children.all { angularDistance(it.startAngleRadians, firstRoot.startAngleRadians) < PI / 2.0 })
    }

    @Test
    fun adjacentFoldersAtTheSameDepthHaveSafeTouchSpacing() {
        val folders = buildList {
            repeat(25) { rootIndex ->
                val rootId = rootIndex + 1L
                add(folder(rootId))
                repeat((rootIndex % 7) + 1) { childIndex ->
                    val childId = 10_000L + rootId * 100L + childIndex
                    add(folder(childId, parentId = rootId))
                    if (childIndex % 2 == 0) add(folder(childId + 50_000L, parentId = childId))
                }
            }
        }
        val result = layout(folders)
        result.placements.groupBy { it.ringIndex }.values.forEach { level ->
            if (level.size > 1) {
                val sortedAngles = level.map { normalize(it.startAngleRadians) }.sorted()
                val smallestGap = sortedAngles.indices.minOf { index ->
                    val next = if (index == sortedAngles.lastIndex) sortedAngles[0] + 2.0 * PI else sortedAngles[index + 1]
                    next - sortedAngles[index]
                }
                val chord = 2f * level.first().radiusDp * sin(smallestGap / 2.0).toFloat()
                assertTrue("level has a crowded pair: $chord dp", chord + 0.05f >= GALAXY_MIN_ORBITER_SPACING_DP)
            }
        }
    }

    @Test
    fun malformedOrCyclicParentRelationshipsDoNotDropFoldersOrLoopForever() {
        val malformed = listOf(
            folder(1L, parentId = 2L),
            folder(2L, parentId = 1L),
            folder(3L, parentId = 999L),
            folder(4L, parentId = 4L),
            folder(5L, parentId = 3L)
        )
        val layout = layout(malformed)
        assertEquals(malformed.map { it.folderId }.toSet(), layout.placements.map { it.folder.folderId }.toSet())
        assertEquals(malformed.size, layout.placements.size)
        assertTrue(layout.placements.map { it.folder.folderId }.toSet().containsAll(malformed.map { it.folderId }))
    }

    @Test
    fun thousandsOfFoldersHaveUniquePlacementsAndPositiveScale() {
        val folders = (1L..5_000L).map { id ->
            folder(id, parentId = if (id % 2L == 0L) id - 1L else null)
        }
        val result = layout(folders, width = 360f, height = 780f, density = 1f)
        assertEquals(5_000, result.placements.size)
        assertEquals(5_000, result.placements.map { it.folder.folderId }.toSet().size)
        assertTrue(result.ringRadiiDp.zipWithNext().all { (a, b) -> b > a })
        assertTrue(result.fitAllScale > 0f && result.fitAllScale <= 1f)
        assertTrue(result.contentSizePx * result.fitAllScale <= 360.01f)
        assertTrue(result.contentSizePx * result.fitAllScale <= 780.01f)
    }

    @Test
    fun everyLevelUsesTheSameAngularDirectionAndSpeed() {
        assertEquals(orbitDurationMillis(0), orbitDurationMillis(1))
        assertEquals(orbitDurationMillis(1), orbitDurationMillis(20))
        assertEquals(GALAXY_SHARED_ORBIT_DURATION_MILLIS, orbitDurationMillis(0))
        assertEquals(360f, orbitDirectionDegrees(0))
        assertEquals(360f, orbitDirectionDegrees(1))
        assertEquals(360f, orbitDirectionDegrees(20))
    }

    @Test
    fun emptyGalaxyStillHasAValidBoardForTheCentralUniversitySun() {
        val empty = layout(emptyList())
        assertTrue(empty.placements.isEmpty())
        assertTrue(empty.ringRadiiDp.isEmpty())
        assertTrue(empty.connections.isEmpty())
        assertTrue(empty.contentSizeDp > 0f)
        assertTrue(empty.fitAllScale > 0f && empty.fitAllScale <= 1f)
    }

    @Test
    fun deepChainsAreLaidOutWithoutRecursiveTraversal() {
        val folders = (1L..2_500L).map { id -> folder(id, parentId = if (id == 1L) null else id - 1L) }
        val result = layout(folders, width = 360f, height = 780f, density = 1f)
        assertEquals(2_500, result.placements.size)
        assertEquals(2_499, result.connections.size)
        assertTrue(result.placements.last().radiusDp > result.placements.first().radiusDp)
    }

    private fun layout(
        folders: List<FolderWithFileCount>,
        width: Float = 1080f,
        height: Float = 2400f,
        density: Float = 3f
    ) = computeGalaxyOrbitLayout(folders, width, height, density)

    private fun folder(id: Long, parentId: Long? = null) = FolderWithFileCount(
        folderId = id,
        name = "مجلد $id",
        color = "#4E7D6E",
        fileCount = (id % 50).toInt(),
        parentId = parentId
    )

    private fun normalize(angle: Double): Double = ((angle % (2.0 * PI)) + 2.0 * PI) % (2.0 * PI)

    private fun angularDistance(first: Double, second: Double): Double {
        val difference = kotlin.math.abs(normalize(first) - normalize(second))
        return minOf(difference, 2.0 * PI - difference)
    }
}
