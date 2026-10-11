package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/** Tests the geometry actually used by the screen, not the removed cluster renderer. */
class GalaxyGeometryTest {
    @Test
    fun planetDiameterIsFiniteBoundedAndMonotonicEvenForBadCounts() {
        val counts = listOf(Int.MIN_VALUE, -1, 0, 1, 4, 500, Int.MAX_VALUE)
        val diameters = counts.map(::folderPlanetSizeDp)
        assertTrue(diameters.all { it.isFinite() && it in 28f..40f })
        assertTrue(diameters.zipWithNext().all { (first, second) -> second >= first })
        assertEquals(28f, folderPlanetSizeDp(0), .001f)
        assertEquals(40f, folderPlanetSizeDp(Int.MAX_VALUE), .001f)
    }

    @Test
    fun rotatingTheActiveScenePreservesOrbitRadiusAndInvertsAtHalfATurn() {
        val scene = scene()
        val viewport = GalaxyViewport(360f, 780f, 1f, scene.layout.contentSizeDp, GalaxyCamera())
        scene.nodes.forEach { node ->
            val initial = viewport.projection(0.0).project(node.position)
            val opposite = viewport.projection(PI).project(node.position)
            assertEquals(360.0, initial.x + opposite.x, .001)
            assertEquals(780.0, initial.y + opposite.y, .001)
            assertEquals(node.placement.radiusDp.toDouble(), hypot(initial.x - 180.0, initial.y - 390.0), .001)
        }
    }

    @Test
    fun evenlyDistributedRootsRotateAroundTheActualUniversityDiskCenter() {
        val scene = scene()
        val viewport = GalaxyViewport(360f, 780f, 1f, scene.layout.contentSizeDp, GalaxyCamera(.72f))
        listOf(0.0, .8, 2.4, PI).forEach { rotation ->
            val points = scene.nodes.map { viewport.projection(rotation).project(it.position) }
            val sun = viewport.projection(rotation).project(GalaxyPoint.ZERO)
            assertEquals(sun.x, points.sumOf { it.x } / points.size, .001)
            assertEquals(sun.y, points.sumOf { it.y } / points.size, .001)
        }
    }

    @Test
    fun worldPaddingContainsPlanetDisksAndNormalFontLabelsAtEveryAngle() {
        val scene = scene()
        val size = scene.layout.contentSizeDp
        val viewport = GalaxyViewport(size, size, 1f, size, GalaxyCamera())
        listOf(0.0, .4, 1.2, 2.8).forEach { rotation ->
            val projection = viewport.projection(rotation)
            scene.nodes.forEach { node ->
                val bounds = projection.nodeBounds(node.position, folderPlanetSizeDp(node.placement.folder.fileCount), 29f)
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= size && bounds.bottom <= size)
            }
        }
    }

    private fun scene() = GalaxyScene(computeGalaxyOrbitLayout((1L..6L).map { id ->
        FolderWithFileCount(id, "مجلد $id", "#4E7D6E", (id * 3).toInt())
    }, 360f, 780f, 1f))
}
