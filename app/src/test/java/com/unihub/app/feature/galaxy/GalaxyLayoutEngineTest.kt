package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI

/** اختبارات هندسة المجرة الكونية على JVM، دون Compose أو Android. */
class GalaxyLayoutEngineTest {

    @Test
    fun rootAndChildFoldersAreAllIndependentOrbitersAroundOneSun() {
        val folders = listOf(
            folder(1L, parentId = null),
            folder(2L, parentId = 1L),
            folder(3L, parentId = 2L),
            folder(4L, parentId = null)
        )

        val layout = layout(folders)

        assertEquals(folders.size, layout.placements.size)
        assertEquals(folders.map { it.folderId }.toSet(), layout.placements.map { it.folder.folderId }.toSet())
        assertTrue(layout.placements.all { it.ringIndex >= 0 && it.radiusDp >= GALAXY_FIRST_ORBIT_RADIUS_DP })
        assertTrue(layout.placements.any { it.folder.folderId == 2L && it.folder.parentId == 1L })
    }

    @Test
    fun changingParentRelationshipsDoesNotChangeAnyOrbitalPlacement() {
        val ungrouped = (1L..40L).map { folder(it, parentId = null) }
        val hierarchical = (1L..40L).map { id -> folder(id, parentId = if (id == 1L) null else 1L) }

        val first = layout(ungrouped).placements
        val second = layout(hierarchical).placements

        assertEquals(first.map { it.folder.folderId }, second.map { it.folder.folderId })
        assertEquals(first.map { it.ringIndex to it.indexInRing }, second.map { it.ringIndex to it.indexInRing })
        assertEquals(first.map { it.radiusDp }, second.map { it.radiusDp })
        assertEquals(first.map { it.startAngleRadians }, second.map { it.startAngleRadians })
    }

    @Test
    fun adjacentFoldersOnSameOrbitKeepSafeMinimumChordDistance() {
        val layout = layout((1L..500L).map { folder(it) })

        layout.placements.groupBy { it.ringIndex }.values.forEach { ring ->
            if (ring.size > 1) {
                ring.forEachIndexed { index, item ->
                    val next = ring[(index + 1) % ring.size]
                    val distance = 2f * item.radiusDp *
                        sin(PI * absAngle(item.startAngleRadians - next.startAngleRadians) / (2.0 * PI)).toFloat()
                    assertTrue(
                        "ring ${item.ringIndex} has too-small adjacent spacing: $distance dp",
                        distance + 0.02f >= GALAXY_MIN_ORBITER_SPACING_DP
                    )
                }
            }
        }
    }

    @Test
    fun ringsAreFarEnoughApartToPreventOrbiterBoxOverlapAtAnyRotationPhase() {
        val layout = layout((1L..1_000L).map { folder(it) })
        assertTrue(layout.ringRadiiDp.size > 1)
        layout.ringRadiiDp.zipWithNext().forEach { (inner, outer) ->
            assertTrue(outer - inner >= GALAXY_ORBIT_RADIAL_STEP_DP)
            assertTrue(outer - inner > hypot(GALAXY_ORBITER_WIDTH_DP.toDouble(), GALAXY_ORBITER_HEIGHT_DP.toDouble()))
        }
    }

    @Test
    fun thousandsOfFoldersHaveUniquePlacementsAndFitScaleRemainsPositive() {
        val layout = layout((1L..5_000L).map { id ->
            folder(id, parentId = if (id % 2L == 0L) id - 1L else null)
        }, width = 360f, height = 780f, density = 1f)

        assertEquals(5_000, layout.placements.size)
        assertEquals(5_000, layout.placements.map { it.folder.folderId }.toSet().size)
        assertTrue(layout.ringRadiiDp.zipWithNext().all { (a, b) -> b > a })
        assertTrue(layout.fitAllScale > 0f)
        assertTrue(layout.contentSizePx * layout.fitAllScale <= 360.01f)
        assertTrue(layout.contentSizePx * layout.fitAllScale <= 780.01f)
    }

    @Test
    fun orbitSpeedsDifferByRingAndAlternateDirection() {
        assertTrue(orbitDurationMillis(0) < orbitDurationMillis(1))
        assertTrue(orbitDurationMillis(1) < orbitDurationMillis(2))
        assertEquals(120_000, orbitDurationMillis(20))
        assertEquals(360f, orbitDirectionDegrees(0))
        assertEquals(-360f, orbitDirectionDegrees(1))
    }

    @Test
    fun largerViewportProducesAtLeastAsLargeFitScale() {
        val folders = (1L..250L).map { folder(it) }
        val small = layout(folders, width = 360f, height = 780f, density = 1f)
        val large = layout(folders, width = 720f, height = 1560f, density = 1f)
        assertTrue(large.fitAllScale >= small.fitAllScale)
    }

    @Test
    fun emptyGalaxyStillHasAValidBoardForTheCentralUniversitySun() {
        val empty = layout(emptyList())
        assertTrue(empty.placements.isEmpty())
        assertTrue(empty.ringRadiiDp.isEmpty())
        assertTrue(empty.contentSizeDp > 0f)
        assertTrue(empty.fitAllScale > 0f && empty.fitAllScale <= 1f)
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

    private fun absAngle(angle: Double): Double {
        val normalized = angle % (2.0 * PI)
        return if (normalized < 0) normalized + 2.0 * PI else normalized
    }
}
