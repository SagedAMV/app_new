package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GalaxySceneTest {
    @Test
    fun hugeWorldStillProjectsItsSunToTheViewportCenter() {
        val viewport = viewport(contentSizeDp = 40_000_000f, scale = 0.72f)
        assertPoint(GalaxyPoint(180.0, 390.0), viewport.projection(1.234).project(GalaxyPoint.ZERO))
        assertEquals(360f, viewport.widthPx)
        assertEquals(780f, viewport.heightPx)
    }

    @Test
    fun projectionAndInverseAgreeThroughPanZoomAndRotation() {
        val viewport = viewport(scale = 3.25f, focus = GalaxyPoint(12_000.0, -2_300.0), density = 3f)
        val projection = viewport.projection(1.234)
        listOf(GalaxyPoint.ZERO, GalaxyPoint(13_000.0, -2_000.0), GalaxyPoint(-100.0, 35.0)).forEach { point ->
            assertPoint(point, projection.unproject(projection.project(point)))
        }
    }

    @Test
    fun zoomPreservesTheWorldPointUnderTheFingers() {
        val original = viewport(scale = 0.72f, focus = GalaxyPoint(300.0, -20.0), density = 2f)
        val fingers = GalaxyPoint(125.0, 210.0)
        val before = original.projection(0.0).unproject(fingers)
        val camera = original.transformed(fingers, GalaxyPoint.ZERO, zoom = 2f, minimumScale = .1f)
        assertPoint(fingers, original.copy(camera = camera).projection(0.0).project(before))
    }

    @Test
    fun panMovesContentInTheFingerDirection() {
        val original = viewport(scale = 2f, density = 3f)
        val camera = original.transformed(GalaxyPoint(180.0, 390.0), GalaxyPoint(30.0, -42.0), 1f, .1f)
        assertPoint(GalaxyPoint(210.0, 348.0), original.copy(camera = camera).projection(0.0).project(GalaxyPoint.ZERO))
    }

    @Test
    fun cameraClampsAfterZoomOrWorldShrinkWithoutLosingFiniteCoordinates() {
        val original = viewport(contentSizeDp = 500f, scale = 1f, focus = GalaxyPoint(1e12, -1e12))
        val camera = original.clampedCamera(original.camera)
        assertTrue(camera.focus.x < 1_000.0 && camera.focus.y > -1_000.0)
        assertTrue(camera.scale.isFinite())
        assertTrue(camera.focus.x.isFinite() && camera.focus.y.isFinite())
    }

    @Test
    fun diskAnchorIsExactForDifferentPlanetSizesColumnHeightsAndZooms() {
        val center = GalaxyPoint(120.0, 230.0)
        listOf(28f, 40f, 76f).forEach { diameter ->
            listOf(76f, 130f, 220f).forEach { height ->
                listOf(.25f, .72f, 1f, 3f).forEach { scale ->
                    val anchor = galaxyDiskAnchor(center, 132f, height, diameter, scale)
                    val diskLocal = GalaxyPoint(66.0, diameter / 2.0)
                    assertPoint(center, anchor.transform(diskLocal))
                }
            }
        }
    }

    @Test
    fun columnBoundsIncludeLabelsBelowTheDiskEvenWithLargeFonts() {
        val projection = viewport(scale = 2f).projection(0.0)
        val bounds = projection.nodeBounds(GalaxyPoint.ZERO, planetDiameterDp = 28f, labelHeightDp = 70f)
        assertEquals(100.0, bounds.right - 180.0, .001)
        assertEquals(28.0, 390.0 - bounds.top, .001)
        assertEquals(168.0, bounds.bottom - 390.0, .001)
    }

    @Test
    fun cullingIsPerFolderNotPerRingAndUsesTheRealProjectedPosition() {
        val scene = rootsScene(5_000)
        val placement = scene.layout.placements.first()
        val position = scene.position(placement.folder.folderId)
        val viewport = viewport(scene.layout.contentSizeDp, .72f, position)
        val visible = scene.visibleFolders(viewport.projection(0.0), labelHeightDp = 26f)
        assertTrue(visible.any { it.placement.folder.folderId == placement.folder.folderId })
        assertTrue("Only a small arc is visible, not all 5,000 labels", visible.size < 20)
        visible.forEach { node ->
            assertTrue(viewport.projection(0.0).nodeBounds(node.position, folderPlanetSizeDp(node.placement.folder.fileCount), 26f)
                .intersects(viewport.screenBounds))
        }
    }

    @Test
    fun rotationCullingAgreesWithBruteForceVisibility() {
        val scene = rootsScene(300)
        val center = scene.position(scene.layout.placements.first().folder.folderId)
        val viewport = viewport(scene.layout.contentSizeDp, .5f, center)
        listOf(0.0, PI / 4.0, PI / 2.0, PI, 2 * PI - .01).forEach { angle ->
            val projection = viewport.projection(angle)
            val expected = scene.nodes.filter { node ->
                projection.nodeBounds(node.position, folderPlanetSizeDp(node.placement.folder.fileCount), 26f)
                    .intersects(viewport.screenBounds)
            }.map { it.placement.folder.folderId }.toSet()
            val actual = scene.visibleFolders(projection, 26f).map { it.placement.folder.folderId }.toSet()
            assertEquals(expected, actual)
        }
    }

    @Test
    fun connectionEndpointsAreTheSameDiskCentersUsedForNodes() {
        val folders = listOf(folder(1L), folder(2L, 1L), folder(3L, 2L))
        val scene = GalaxyScene(computeGalaxyOrbitLayout(folders, 360f, 780f, 1f))
        val projection = viewport(scene.layout.contentSizeDp, .2f).projection(.9)
        scene.edges.forEach { edge ->
            assertPoint(projection.project(scene.position(edge.connection.parentFolderId)), projection.project(edge.start))
            assertPoint(projection.project(scene.position(edge.connection.childFolderId)), projection.project(edge.end))
        }
    }

    @Test
    fun lineCrossingViewportRemainsVisibleWhenBothEndpointsAreOutside() {
        val rect = GalaxyRect(0.0, 0.0, 100.0, 100.0)
        assertTrue(rect.intersectsSegment(GalaxyPoint(-100.0, 50.0), GalaxyPoint(200.0, 50.0)))
        assertTrue(rect.intersectsSegment(GalaxyPoint(50.0, -100.0), GalaxyPoint(50.0, 200.0)))
        assertFalse(rect.intersectsSegment(GalaxyPoint(-100.0, -30.0), GalaxyPoint(200.0, -30.0)))
        assertFalse(rect.intersectsSegment(GalaxyPoint(-10.0, 1.0), GalaxyPoint(1.0, -10.0)))
    }

    @Test
    fun spatialIndexHandlesThousandsOfDeepFoldersWithoutRecursiveTreeTraversal() {
        val folders = (1L..2_500L).map { folder(it, if (it == 1L) null else it - 1L) }
        val scene = GalaxyScene(computeGalaxyOrbitLayout(folders, 360f, 780f, 3f))
        val viewport = viewport(scene.layout.contentSizeDp, .72f, scene.nodes.last().position, 3f)
        assertTrue(scene.visibleFolders(viewport.projection(0.0), 26f).isNotEmpty())
        assertTrue(scene.visibleFolders(viewport.projection(0.0), 26f).size < 10)
        assertTrue(scene.visibleEdges(viewport.projection(0.0)).size < 10)
    }

    @Test
    fun overviewAndComfortFocusAreCenteredAndBounded() {
        val viewport = viewport(5_000f)
        val overview = viewport.overviewCamera()
        assertEquals(GalaxyPoint.ZERO, overview.focus)
        assertTrue(5_000 * overview.scale <= viewport.widthPx + .01f)
        val target = GalaxyPoint(800.0, -200.0)
        val focused = viewport.focusedCamera(target)
        assertPoint(GalaxyPoint(180.0, 390.0), viewport.copy(camera = focused).projection(0.0).project(target))
        assertTrue(focused.scale >= GALAXY_INITIAL_COMFORT_SCALE)
    }

    @Test
    fun dustHasNoResetAtFiftySecondsForDifferentSpeeds() {
        listOf(.4f, .63f, .99f).forEach { speed ->
            val before = galaxyDustProgress(49.999, speed, .13f)
            val after = galaxyDustProgress(50.001, speed, .13f)
            val circularDifference = kotlin.math.abs(after - before).let { minOf(it, 1.0 - it) }
            assertTrue("A particle teleported at the old 50-second reset", circularDifference < .0001)
        }
    }

    @Test
    fun dustProgressStaysBoundedEvenAfterManyHours() {
        listOf(0.0, 50.0, 3_600.0, 86_400.0, 31_536_000.0).forEach { elapsed ->
            val value = galaxyDustProgress(elapsed, .7f, .2f)
            assertTrue(value >= 0.0 && value < 1.0)
        }
    }

    private fun rootsScene(count: Int): GalaxyScene = GalaxyScene(
        computeGalaxyOrbitLayout((1L..count.toLong()).map { folder(it) }, 360f, 780f, 1f)
    )

    private fun viewport(
        contentSizeDp: Float = 10_000f,
        scale: Float = 1f,
        focus: GalaxyPoint = GalaxyPoint.ZERO,
        density: Float = 1f
    ) = GalaxyViewport(360f, 780f, density, contentSizeDp, GalaxyCamera(scale, focus))

    private fun folder(id: Long, parent: Long? = null) =
        FolderWithFileCount(id, "مجلد $id", "#4E7D6E", (id % 50).toInt(), parent)

    private fun assertPoint(expected: GalaxyPoint, actual: GalaxyPoint) {
        assertEquals(expected.x, actual.x, .001)
        assertEquals(expected.y, actual.y, .001)
    }
}
