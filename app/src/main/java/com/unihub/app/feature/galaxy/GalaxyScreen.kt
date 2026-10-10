package com.unihub.app.feature.galaxy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.unihub.app.data.local.model.FolderWithFileCount
import com.unihub.app.ui.theme.toComposeColor
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private val SUN_GOLD = Color(0xFFFFD54F)
private const val GALAXY_LABEL_SCALE = .6f

@Composable
fun GalaxyScreen(onOpenFolder: (Long) -> Unit, viewModel: GalaxyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GalaxyContent(state, onOpenFolder, viewModel::retry)
}

/** Kept independent of Hilt so loading, empty and failure states can be tested on-device. */
@Composable
internal fun GalaxyContent(
    state: GalaxyUiState,
    onOpenFolder: (Long) -> Unit,
    onRetry: () -> Unit,
    motionEnabled: Boolean = true
) {
    val backgroundClock = rememberGalaxyClock(motionEnabled)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(Color(0xFF05070C), Color(0xFF0B101A), Color(0xFF05070C)))), contentAlignment = Alignment.Center) {
        GoldenStardustBackground(backgroundClock, Modifier.matchParentSize())
        when (state) {
            GalaxyUiState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = SUN_GOLD, modifier = Modifier.testTag("galaxy-loading"))
                Spacer(Modifier.height(12.dp))
                Text("جارٍ تحميل المجلدات…", color = Color(0xFFE0E5E1))
            }
            GalaxyUiState.Error -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تعذّر تحميل مجلدات المجرة", color = Color(0xFFE0E5E1), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("إعادة المحاولة") }
            }
            is GalaxyUiState.Ready -> if (state.folders.isEmpty()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SolarCore(pulse = { sunPulse(backgroundClock.value) })
                    Spacer(Modifier.height(20.dp))
                    Text("لا توجد مجلدات بعد", color = Color(0xFFE0E5E1), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("أنشئ مجلدات لموادك من شاشة الملفات، وستظهر ككواكب حول جامعتي.",
                        color = Color(0xFFA9B4AD), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Icon(Icons.Outlined.Public, contentDescription = null, tint = Color(0xFFA9B4AD),
                        modifier = Modifier.padding(top = 16.dp).size(24.dp))
                }
            } else GalaxyUniverse(state.folders, onOpenFolder, motionEnabled = motionEnabled)
        }
    }
}

/** The viewport stays screen-sized; no native layout/layer is ever sized to the virtual world. */
@Composable
internal fun GalaxyUniverse(
    folders: List<FolderWithFileCount>,
    onOpenFolder: (Long) -> Unit,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        if (widthPx <= 0f || heightPx <= 0f || !widthPx.isFinite() || !heightPx.isFinite()) return@BoxWithConstraints
        val scene = remember(folders, widthPx, heightPx, density.density) {
            GalaxyScene(computeGalaxyOrbitLayout(folders, widthPx, heightPx, density.density))
        }
        var camera by remember { mutableStateOf(GalaxyCamera(scene.layout.fitAllScale)) }
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        var userAdjusted by remember { mutableStateOf(false) }
        var paused by remember { mutableStateOf(false) }
        var showFolderList by remember { mutableStateOf(false) }
        val clock = rememberGalaxyClock(motionEnabled && !paused)
        val viewport = GalaxyViewport(
            viewportSize.width.takeIf { it > 0 }?.toFloat() ?: widthPx,
            viewportSize.height.takeIf { it > 0 }?.toFloat() ?: heightPx,
            density.density, scene.layout.contentSizeDp, camera
        )
        val currentViewport by rememberUpdatedState(viewport)
        val labelHeightDp = with(density) { (12.sp.toPx() + 11.sp.toPx()) / density.density + 6f }
        val showLabels = camera.scale >= GALAXY_LABEL_SCALE
        val colors = remember(scene) { scene.nodes.associate { node ->
            node.placement.folder.folderId to node.placement.folder.color.toComposeColor(Color(0xFF4E7D6E))
        } }
        val visibleFolders by remember(scene, viewport, showLabels, labelHeightDp, clock) {
            derivedStateOf(policy = structuralEqualityPolicy()) {
                if (showLabels) scene.visibleFolders(viewport.projection(orbitRadians(clock.value)), labelHeightDp)
                else emptyList()
            }
        }
        LaunchedEffect(scene, viewportSize, widthPx, heightPx, density.density) {
            val current = currentViewport
            camera = if (!userAdjusted) current.overviewCamera() else current.clampedCamera(
                camera.copy(scale = camera.scale.coerceIn(current.overviewCamera().scale, GALAXY_MAX_ZOOM)))
        }
        fun overview() {
            userAdjusted = false
            camera = currentViewport.overviewCamera()
        }
        fun zoom(factor: Float) {
            userAdjusted = true
            val current = currentViewport
            camera = current.transformed(GalaxyPoint(current.widthPx / 2.0, current.heightPx / 2.0),
                GalaxyPoint.ZERO, factor, current.overviewCamera().scale)
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportSize = it }.clipToBounds()
                .pointerInput(scene, density.density) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        userAdjusted = true
                        val current = currentViewport
                        camera = current.transformed(centroid.point(), pan.point(), zoom, current.overviewCamera().scale)
                    }
                }
                .pointerInput(scene, density.density) {
                    detectTapGestures(
                        onDoubleTap = { overview() },
                        onTap = { tap ->
                            val current = currentViewport
                            if (current.camera.scale < GALAXY_LABEL_SCALE) {
                                val projection = current.projection(orbitRadians(clock.value))
                                scene.nearestFolder(projection, tap.point(), 20.0 * density.density)?.let { node ->
                                    userAdjusted = true
                                    camera = current.focusedCamera(projection.rotated(node.position))
                                    // A focused folder stays still until the user resumes rotation.
                                    paused = true
                                }
                            }
                        }
                    )
                }, contentAlignment = AbsoluteAlignment.TopLeft) {
                Canvas(Modifier.matchParentSize().testTag("galaxy-canvas").semantics {
                    contentDescription = "خريطة المجلدات، ${scene.nodes.size} مجلد"
                }) {
                    val projection = viewport.projection(orbitRadians(clock.value))
                    val sun = projection.project(GalaxyPoint.ZERO).offset()
                    val rings = scene.visibleRingIndices(projection)
                    var lastDrawnRadius = Double.NEGATIVE_INFINITY
                    rings.forEach { index ->
                        val radius = scene.layout.ringRadiiDp[index] * projection.factor
                        // Sub-pixel concentric rings add work and obscure the overview.
                        if (radius - lastDrawnRadius >= 6.0 * density.density || index == rings.last) {
                            drawCircle(SUN_GOLD.copy(alpha = if (index % 3 == 0) .28f else .16f),
                                radius.toFloat(), sun, style = Stroke(max(.5f, projection.factor.toFloat())))
                            lastDrawnRadius = radius
                        }
                    }
                    scene.visibleEdges(projection).forEach { edge ->
                        val start = projection.project(edge.start)
                        val end = projection.project(edge.end)
                        if (hypot(end.x - start.x, end.y - start.y) >= 1.0) {
                            drawLine(Color(0xFFB7C7D4).copy(alpha = .62f), start.offset(), end.offset(),
                                max(.5f, (1.15 * projection.factor).toFloat()))
                        }
                    }
                    if (!showLabels) {
                        scene.visibleFolders(projection, labelHeightDp).forEach { node ->
                            val radius = max(1.25f * density.density,
                                (folderPlanetSizeDp(node.placement.folder.fileCount) / 2.0 * projection.factor).toFloat())
                            drawCircle(colors.getValue(node.placement.folder.folderId), radius, projection.project(node.position).offset())
                        }
                    }
                }
                visibleFolders.forEach { node ->
                    key(node.placement.folder.folderId) {
                        val diameter = folderPlanetSizeDp(node.placement.folder.fileCount)
                        OrbitingFolderNode(node.placement.folder, onOpenFolder,
                            Modifier.width(GALAXY_ORBITER_WIDTH_DP.dp).diskAnchor(
                                center = { viewport.projection(orbitRadians(clock.value)).project(node.position) },
                                diameterPx = with(density) { diameter.dp.roundToPx().toFloat() }, scale = camera.scale))
                    }
                }
                SolarCore(
                    modifier = Modifier.width(132.dp).diskAnchor(
                        center = { viewport.projection(0.0).project(GalaxyPoint.ZERO) },
                        diameterPx = with(density) { 76.dp.roundToPx().toFloat() },
                        scale = max(camera.scale, GALAXY_INITIAL_COMFORT_SCALE)),
                    pulse = { sunPulse(clock.value) }
                )
            }
            Row(Modifier.fillMaxWidth().background(Color(0xFF080C14).copy(alpha = .9f)).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { zoom(1.5f) }, modifier = Modifier.testTag("galaxy-zoom-in")) {
                    Icon(Icons.Outlined.ZoomIn, "تكبير", tint = SUN_GOLD)
                }
                IconButton(onClick = { zoom(1f / 1.5f) }) { Icon(Icons.Outlined.ZoomOut, "تصغير", tint = SUN_GOLD) }
                IconButton(onClick = ::overview, modifier = Modifier.testTag("galaxy-overview")) {
                    Icon(Icons.Outlined.ZoomOutMap, "عرض المجرة كاملة", tint = SUN_GOLD)
                }
                IconButton(onClick = { paused = !paused }, enabled = motionEnabled) {
                    Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        if (paused) "تشغيل الدوران" else "إيقاف الدوران", tint = SUN_GOLD)
                }
                IconButton(onClick = { showFolderList = true }, modifier = Modifier.testTag("galaxy-folder-list")) {
                    Icon(Icons.Outlined.FormatListBulleted, "قائمة المجلدات والبحث", tint = SUN_GOLD)
                }
            }
            Text(if (showLabels) "اسحب للتنقل · اضغط المجلد لفتحه · زر العرض الشامل يعيد التمركز"
                else "عرض شامل · اضغط كوكبًا لتقريبه أو اختر مجلدًا من القائمة",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFFA9B4AD),
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
        if (showFolderList) GalaxyFolderPicker(scene.nodes, onDismiss = { showFolderList = false }, onOpen = { id ->
            showFolderList = false
            onOpenFolder(id)
        })
    }
}

private fun Modifier.diskAnchor(center: () -> GalaxyPoint, diameterPx: Float, scale: Float): Modifier = graphicsLayer {
    if (size.width > 0f && size.height > 0f) {
        val anchor = galaxyDiskAnchor(center(), size.width, size.height, diameterPx, scale)
        translationX = anchor.translation.x.toFloat()
        translationY = anchor.translation.y.toFloat()
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(anchor.pivotFractionX, anchor.pivotFractionY)
    }
}

@Composable
private fun OrbitingFolderNode(folder: FolderWithFileCount, onOpenFolder: (Long) -> Unit, modifier: Modifier) {
    val color = folder.color.toComposeColor(Color(0xFF4E7D6E))
    Column(modifier.testTag("galaxy-folder-${folder.folderId}").clickable { onOpenFolder(folder.folderId) },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(folderPlanetSizeDp(folder.fileCount).dp).testTag("galaxy-disk-${folder.folderId}")
            .drawBehind { drawCircle(color.copy(alpha = .27f), size.minDimension / 2f + 6.dp.toPx()) }
            .clip(CircleShape).background(Brush.radialGradient(listOf(color.copy(alpha = .98f), color.copy(alpha = .62f))))) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = .25f), size.minDimension * .12f, Offset(size.width * .32f, size.height * .28f))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(folder.name, Modifier.fillMaxWidth(), color = Color(0xFFE9EEE9), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 12.sp,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
        Text("${folder.fileCount} ملف", Modifier.fillMaxWidth(), color = Color(0xFFAFBBB3), maxLines = 1,
            overflow = TextOverflow.Clip, textAlign = TextAlign.Center, lineHeight = 11.sp,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable
private fun SolarCore(modifier: Modifier = Modifier.width(132.dp), pulse: () -> Float = { 1f }) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).testTag("galaxy-sun").graphicsLayer { scaleX = pulse(); scaleY = scaleX }
            .drawBehind {
                drawCircle(SUN_GOLD.copy(alpha = .13f), size.minDimension * .92f)
                drawCircle(SUN_GOLD.copy(alpha = .25f), size.minDimension * .68f)
            }.clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFF8C9), SUN_GOLD,
                Color(0xFFEF8F24), Color(0xFF9F4E11))))) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = .5f), size.minDimension * .12f, Offset(size.width * .33f, size.height * .26f))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text("جامعتي", color = Color(0xFFFFE9A1), maxLines = 1, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp))
        Text("مركز المجلدات", color = Color(0xFFB9B5A1), maxLines = 1, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
    }
}

@Composable
private fun GalaxyFolderPicker(nodes: List<GalaxySceneNode>, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(nodes, query) { nodes.filter { it.placement.folder.name.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("مجلدات المجرة") }, text = {
        Column {
            OutlinedTextField(query, onValueChange = { query = it }, label = { Text("بحث عن مجلد") },
                modifier = Modifier.fillMaxWidth().testTag("galaxy-folder-search"), singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(matches, key = { it.placement.folder.folderId }) { node ->
                    ListItem(headlineContent = { Text(node.placement.folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${node.placement.folder.fileCount} ملف") },
                        modifier = Modifier.clickable { onOpen(node.placement.folder.folderId) })
                }
            }
            if (matches.isEmpty()) Text("لا توجد مجلدات مطابقة", Modifier.padding(top = 12.dp))
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } })
}

/** Pauses with the lifecycle; the elapsed clock does not restart at an animation boundary. */
@Composable
private fun rememberGalaxyClock(enabled: Boolean): State<Double> {
    val elapsed = remember { mutableStateOf(0.0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, lifecycle) {
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous: Long? = null
            while (isActive) withFrameNanos { now ->
                previous?.let { elapsed.value += ((now - it) / 1_000_000_000.0).coerceIn(0.0, .1) }
                previous = now
            }
        }
    }
    return elapsed
}

private fun orbitRadians(seconds: Double): Double =
    (seconds / (orbitDurationMillis(0) / 1_000.0) % 1.0) * orbitDirectionDegrees(0) * PI / 180.0
private fun sunPulse(seconds: Double): Float = 1f + .04f * sin(seconds * 2.0 * PI / 4.5).toFloat()
private fun Offset.point() = GalaxyPoint(x.toDouble(), y.toDouble())
private fun GalaxyPoint.offset() = Offset(x.toFloat(), y.toFloat())

private data class StardustParticle(val x: Float, val y: Float, val radiusPx: Float, val speed: Float, val phase: Float, val color: Color)
private val STARDUST_COLORS = listOf(SUN_GOLD, Color(0xFFFFE082), Color(0xFFE6C280), Color(0xFFFFF8E1))

@Composable
private fun GoldenStardustBackground(clock: State<Double>, modifier: Modifier = Modifier) {
    val stars = remember { Random(42).let { random -> List(60) { Triple(random.nextFloat(), random.nextFloat(), random.nextFloat()) } } }
    val particles = remember { Random(101).let { random -> List(45) {
        StardustParticle(random.nextFloat(), random.nextFloat(), 1.2f + random.nextFloat() * 2.2f,
            .4f + random.nextFloat() * .6f, random.nextFloat(), STARDUST_COLORS[random.nextInt(STARDUST_COLORS.size)])
    } } }
    Canvas(modifier) {
        val seconds = clock.value
        val twinkle = seconds / 4.5
        stars.forEach { (x, y, phase) ->
            val alpha = .2f + .45f * abs(sin((twinkle + phase) * 2.0 * PI)).toFloat()
            drawCircle(Color.White.copy(alpha = alpha * .7f), 1f + phase * 1.4f, Offset(x * size.width, y * size.height))
        }
        particles.forEach { particle ->
            val progress = galaxyDustProgress(seconds, particle.speed, particle.phase)
            val position = Offset(((particle.x + sin((progress + particle.phase) * 2.0 * PI) * .025) * size.width).toFloat(),
                (((particle.y + progress) % 1.0) * size.height).toFloat())
            val alpha = .25f + .5f * abs(sin((twinkle + particle.phase) * 2.0 * PI)).toFloat()
            drawCircle(particle.color.copy(alpha = alpha * .2f), particle.radiusPx * 2.5f, position)
            drawCircle(particle.color.copy(alpha = alpha * .85f), particle.radiusPx, position)
        }
    }
}
