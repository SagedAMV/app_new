package com.unihub.app.feature.galaxy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unihub.app.data.local.model.FolderWithFileCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalaxyScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sunDiskIsAtTheActualViewportCenterInLtr() {
        setUniverse(roots(2))
        assertSunCentered()
        assertFolderDiskPosition(roots(2), 1L)
    }

    @Test
    fun rtlAndLargeFontDoNotMoveSunOrConnectionAnchors() {
        setUniverse(roots(2), LayoutDirection.Rtl, fontScale = 2f)
        assertSunCentered()
        assertFolderDiskPosition(roots(2), 1L)
        assertFolderDiskPosition(roots(2), 2L)
    }

    @Test
    fun tappingTheActualDiskOpensTheCorrectFolder() {
        var opened: Long? = null
        setUniverse(roots(2), onOpen = { opened = it })
        compose.onNodeWithTag("galaxy-disk-1", useUnmergedTree = true).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1L, opened) }
    }

    @Test
    fun fiveThousandFoldersKeepCanvasScreenSizedAndAvoidThousandsOfTextNodes() {
        setUniverse(roots(5_000), LayoutDirection.Rtl)
        val canvas = compose.onNodeWithTag("galaxy-canvas").fetchSemanticsNode().boundsInRoot
        assertTrue(canvas.width <= with(compose.density) { 320.dp.toPx() } + 1f)
        assertTrue(canvas.height <= with(compose.density) { 520.dp.toPx() } + 1f)
        compose.onNodeWithTag("galaxy-folder-1").assertDoesNotExist()
        compose.onNodeWithTag("galaxy-sun", useUnmergedTree = true).assertIsDisplayed()
        assertSunCentered()
    }

    @Test
    fun deepTreeDoesNotTryToMeasureAMillionsOfPixelsLayout() {
        val folders = (1L..2_500L).map { folder(it, if (it == 1L) null else it - 1L) }
        setUniverse(folders)
        compose.onNodeWithTag("galaxy-canvas").assertIsDisplayed()
        assertSunCentered()
    }

    @Test
    fun overviewTapFocusesThePlanetBeforeOpeningIt() {
        val folders = roots(100)
        var opened: Long? = null
        setUniverse(folders, onOpen = { opened = it })
        val canvas = compose.onNodeWithTag("galaxy-canvas").fetchSemanticsNode().boundsInRoot
        val point = projectedFolder(folders, 1L, canvas.width, canvas.height)
        compose.onNodeWithTag("galaxy-canvas").performTouchInput { click(Offset(point.x.toFloat(), point.y.toFloat())) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("galaxy-folder-1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, opened) }
        compose.onNodeWithTag("galaxy-disk-1", useUnmergedTree = true).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1L, opened) }
    }

    @Test
    fun searchableFolderListCanOpenAnExactFolderFromAThousandItemOverview() {
        var opened: Long? = null
        setUniverse(roots(5_000), LayoutDirection.Rtl, onOpen = { opened = it })
        compose.onNodeWithTag("galaxy-folder-list").performClick()
        compose.onNodeWithTag("galaxy-folder-search").performTextInput("مجلد 4999")
        compose.onNodeWithText("مجلد 4999").performClick()
        compose.runOnIdle { assertEquals(4_999L, opened) }
    }

    @Test
    fun resizeAfterPanningAndOverviewResetUseTheNewViewport() {
        var width by mutableStateOf(320.dp)
        var height by mutableStateOf(520.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width, height)) { GalaxyUniverse(roots(2), {}, motionEnabled = false) }
            }
        }
        compose.onNodeWithTag("galaxy-canvas").performTouchInput {
            swipe(center, center + Offset(-70f, 20f), durationMillis = 200)
        }
        compose.runOnIdle { width = 280.dp; height = 420.dp }
        compose.onNodeWithTag("galaxy-overview").performClick()
        assertSunCentered()
    }

    @Test
    fun loadingDoesNotClaimThatTheDatabaseHasNoFolders() {
        compose.setContent { MaterialTheme { GalaxyContent(GalaxyUiState.Loading, {}, {}, motionEnabled = false) } }
        compose.onNodeWithTag("galaxy-loading").assertIsDisplayed()
        compose.onNodeWithText("لا توجد مجلدات بعد").assertDoesNotExist()
    }

    @Test
    fun errorStateOffersAWorkingRetryInsteadOfAnEmptyGalaxy() {
        var retries = 0
        compose.setContent { MaterialTheme { GalaxyContent(GalaxyUiState.Error, {}, { retries++ }, motionEnabled = false) } }
        compose.onNodeWithText("لا توجد مجلدات بعد").assertDoesNotExist()
        compose.onNodeWithText("إعادة المحاولة").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun completedEmptyQueryShowsTheRealEmptyState() {
        compose.setContent { MaterialTheme { GalaxyContent(GalaxyUiState.Ready(emptyList()), {}, {}, motionEnabled = false) } }
        compose.onNodeWithText("لا توجد مجلدات بعد").assertIsDisplayed()
        compose.onNodeWithTag("galaxy-loading").assertDoesNotExist()
    }

    private fun setUniverse(
        folders: List<FolderWithFileCount>,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        onOpen: (Long) -> Unit = {}
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme { Box(Modifier.size(320.dp, 520.dp)) { GalaxyUniverse(folders, onOpen, motionEnabled = false) } }
            }
        }
    }

    private fun assertSunCentered() {
        val canvas = compose.onNodeWithTag("galaxy-canvas").fetchSemanticsNode().boundsInRoot
        val sun = compose.onNodeWithTag("galaxy-sun", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(canvas.center.x, sun.center.x, 1f)
        assertEquals(canvas.center.y, sun.center.y, 1f)
    }

    private fun assertFolderDiskPosition(folders: List<FolderWithFileCount>, id: Long) {
        val canvas = compose.onNodeWithTag("galaxy-canvas").fetchSemanticsNode().boundsInRoot
        val disk = compose.onNodeWithTag("galaxy-disk-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val expected = projectedFolder(folders, id, canvas.width, canvas.height)
        assertEquals(canvas.left + expected.x.toFloat(), disk.center.x, 1f)
        assertEquals(canvas.top + expected.y.toFloat(), disk.center.y, 1f)
    }

    private fun projectedFolder(folders: List<FolderWithFileCount>, id: Long, width: Float, height: Float): GalaxyPoint {
        val density = compose.density.density
        val scene = GalaxyScene(computeGalaxyOrbitLayout(folders, width, height, density))
        val viewport = GalaxyViewport(width, height, density, scene.layout.contentSizeDp, GalaxyCamera(scene.layout.fitAllScale))
        return viewport.projection(0.0).project(scene.position(id))
    }

    private fun roots(count: Int) = (1L..count.toLong()).map { folder(it) }
    private fun folder(id: Long, parent: Long? = null) = FolderWithFileCount(id, "مجلد $id", "#4E7D6E", (id % 50).toInt(), parent)
}
