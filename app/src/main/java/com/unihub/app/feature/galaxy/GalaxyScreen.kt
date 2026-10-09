package com.unihub.app.feature.galaxy

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.local.model.FolderWithFileCount
import com.unihub.app.ui.theme.toComposeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private val SUN_GOLD = Color(0xFFFFD54F)
private const val GALAXY_KEEP_VISIBLE_DP = 56f

/**
 * مجرة UniHub: «جامعتي» هي الشمس الوحيدة، وجميع المجلدات — الجذرية والفرعية —
 * كواكب مستقلة تدور حولها على حلقات متحدة المركز بسرعات مختلفة. لا تدور أي
 * مجلدات حول مجلد آخر. الضغط على أي كوكب يفتح ذلك المجلد مباشرة.
 */
@Composable
fun GalaxyScreen(
    onOpenFolder: (Long) -> Unit,
    viewModel: GalaxyViewModel = hiltViewModel()
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF05070C), Color(0xFF0B101A), Color(0xFF05070C))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        GoldenStardustBackground(Modifier.matchParentSize())

        if (folders.isEmpty()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SolarCore()
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "لا توجد مجلدات بعد",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE0E5E1),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "أنشئ مجلدات لموادك من شاشة الملفات، وستظهر جميعها ككواكب تدور حول جامعتي.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA9B4AD),
                    textAlign = TextAlign.Center
                )
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = Color(0xFFA9B4AD),
                    modifier = Modifier.padding(top = 16.dp).size(24.dp)
                )
            }
        } else {
            GalaxyUniverse(folders = folders, onOpenFolder = onOpenFolder)
            Text(
                text = "كل المجلدات تدور حول «جامعتي» · اسحب لاستكشاف المدارات، وقرّب بإصبعين للتحديد",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFA9B4AD),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }
    }
}

/** حدود حركة المحتوى محسوبة بالبكسل؛ تسمح باستكشاف اللوحة مع إبقاء جزء منها مرئياً. */
private fun clampGalaxyTranslation(
    translation: Offset,
    scaledWidthPx: Float,
    scaledHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    keepVisiblePx: Float
): Offset {
    val minX = keepVisiblePx - scaledWidthPx
    val maxX = viewportWidthPx - keepVisiblePx
    val minY = keepVisiblePx - scaledHeightPx
    val maxY = viewportHeightPx - keepVisiblePx
    return Offset(
        x = translation.x.coerceIn(minOf(minX, maxX), maxOf(minX, maxX)),
        y = translation.y.coerceIn(minOf(minY, maxY), maxOf(minY, maxY))
    )
}

@Composable
private fun GalaxyUniverse(
    folders: List<FolderWithFileCount>,
    onOpenFolder: (Long) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val layout = remember(folders, viewportWidthPx, viewportHeightPx, density.density) {
            computeGalaxyOrbitLayout(folders, viewportWidthPx, viewportHeightPx, density.density)
        }
        val contentSizePx = layout.contentSizePx
        val contentSizeDp = layout.contentSizeDp.dp
        val fitAllScale = layout.fitAllScale

        var scale by remember { mutableFloatStateOf(1f) }
        var translation by remember { mutableStateOf(Offset.Zero) }
        var userAdjusted by remember { mutableStateOf(false) }
        val keepVisiblePx = GALAXY_KEEP_VISIBLE_DP * density.density

        // عرض ابتدائي قابل للقراءة: لا نصغّر آلاف أهداف اللمس إلى نقاط غير قابلة للنقر.
        // يمكن للمستخدم استكشاف بقية المدارات بالسحب، أو عرض المجرة كاملة بالنقر المزدوج.
        LaunchedEffect(folders, contentSizePx, viewportWidthPx, viewportHeightPx, fitAllScale) {
            if (!userAdjusted) {
                scale = min(1f, max(fitAllScale, GALAXY_INITIAL_COMFORT_SCALE))
                translation = Offset(
                    x = (viewportWidthPx - contentSizePx * scale) / 2f,
                    y = (viewportHeightPx - contentSizePx * scale) / 2f
                )
            } else {
                // عندما تُحذف مجلدات ويصغر المدار، لا نترك عامل تكبير أقل من الحد الجديد.
                scale = scale.coerceIn(fitAllScale, GALAXY_MAX_ZOOM)
                translation = clampGalaxyTranslation(
                    translation = translation,
                    scaledWidthPx = contentSizePx * scale,
                    scaledHeightPx = contentSizePx * scale,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    keepVisiblePx = keepVisiblePx
                )
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                .pointerInput(contentSizePx, viewportWidthPx, viewportHeightPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        userAdjusted = true
                        val newScale = (scale * zoom).coerceIn(fitAllScale, GALAXY_MAX_ZOOM)
                        val appliedScale = newScale / scale
                        translation = centroid - (centroid - translation) * appliedScale + pan
                        scale = newScale
                        translation = clampGalaxyTranslation(
                            translation = translation,
                            scaledWidthPx = contentSizePx * scale,
                            scaledHeightPx = contentSizePx * scale,
                            viewportWidthPx = viewportWidthPx,
                            viewportHeightPx = viewportHeightPx,
                            keepVisiblePx = keepVisiblePx
                        )
                    }
                }
                .pointerInput(contentSizePx, fitAllScale) {
                    detectTapGestures(
                        onDoubleTap = {
                            userAdjusted = true
                            scale = fitAllScale
                            translation = Offset(
                                x = (viewportWidthPx - contentSizePx * scale) / 2f,
                                y = (viewportHeightPx - contentSizePx * scale) / 2f
                            )
                        }
                    )
                },
            contentAlignment = AbsoluteAlignment.TopLeft
        ) {
            Box(
                modifier = Modifier
                    .size(contentSizeDp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.matchParentSize()) {
                    layout.ringRadiiDp.forEachIndexed { ringIndex, radiusDp ->
                        val alpha = if (ringIndex % 3 == 0) 0.28f else 0.16f
                        drawCircle(
                            color = SUN_GOLD.copy(alpha = alpha),
                            radius = radiusDp.dp.toPx(),
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = if (ringIndex % 3 == 0) {
                                    PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 9.dp.toPx()))
                                } else null
                            )
                        )
                    }
                    // نقاط صغيرة على الحلقات تمنح المشهد إحساساً مدارياً دون خطوط ربط بالعائلات.
                    drawCircle(
                        color = SUN_GOLD.copy(alpha = 0.45f),
                        radius = 2.dp.toPx(),
                        center = center
                    )
                }

                val orbitTransition = rememberInfiniteTransition(label = "SolarSystemOrbits")
                val placementsByRing = remember(layout) { layout.placements.groupBy { it.ringIndex } }
                val visibleRings = remember(
                    layout, scale, translation, viewportWidthPx, viewportHeightPx, density.density
                ) {
                    visibleOrbitRingIndices(
                        layout = layout,
                        contentSizePx = contentSizePx,
                        scale = scale,
                        translation = translation,
                        viewportWidthPx = viewportWidthPx,
                        viewportHeightPx = viewportHeightPx,
                        density = density.density
                    )
                }
                layout.ringRadiiDp.indices.forEach { ringIndex ->
                    GalaxyOrbitRingLayer(
                        transition = orbitTransition,
                        ringIndex = ringIndex,
                        placements = placementsByRing[ringIndex].orEmpty(),
                        visible = ringIndex in visibleRings,
                        onOpenFolder = onOpenFolder
                    )
                }
                SolarCore()
            }
        }
    }
}

/**
 * لا ننشئ عناصر Compose للمدارات البعيدة خارج مساحة الرؤية الحالية.
 * لأن عناصر الحلقة تدور على محيط ثابت، يكفي اختبار تقاطع دائرة المدار مع
 * مستطيل الرؤية؛ تبقى جميع كواكب الحلقة قابلة للظهور أثناء دورانها، من دون
 * بناء آلاف النصوص والعناصر غير المرئية في كل إطار.
 */
private fun visibleOrbitRingIndices(
    layout: GalaxyOrbitLayout,
    contentSizePx: Float,
    scale: Float,
    translation: Offset,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    density: Float
): Set<Int> {
    if (scale <= 0f) return layout.ringRadiiDp.indices.toSet()

    val left = -translation.x / scale
    val top = -translation.y / scale
    val right = (viewportWidthPx - translation.x) / scale
    val bottom = (viewportHeightPx - translation.y) / scale
    val center = contentSizePx / 2f

    val dx = when {
        center < left -> left - center
        center > right -> center - right
        else -> 0f
    }
    val dy = when {
        center < top -> top - center
        center > bottom -> center - bottom
        else -> 0f
    }
    val nearestDistance = sqrt(dx * dx + dy * dy)
    val farthestDistance = listOf(
        Offset(left, top), Offset(right, top),
        Offset(left, bottom), Offset(right, bottom)
    ).maxOf { corner ->
        val x = corner.x - center
        val y = corner.y - center
        sqrt(x * x + y * y)
    }
    val nodeHalfDiagonal = sqrt(
        (GALAXY_ORBITER_WIDTH_DP * density / 2f).let { it * it } +
            (GALAXY_ORBITER_HEIGHT_DP * density / 2f).let { it * it }
    ) + 4f * density

    return layout.ringRadiiDp.indices.filterTo(mutableSetOf()) { ringIndex ->
        val radiusPx = layout.ringRadiiDp[ringIndex] * density
        radiusPx + nodeHalfDiagonal >= nearestDistance &&
            radiusPx - nodeHalfDiagonal <= farthestDistance
    }
}

/** تُحدّث زوايا الكواكب داخل الحلقة مع إبقاء الأسماء أفقية دائماً. */
@Composable
private fun GalaxyOrbitRingLayer(
    transition: androidx.compose.animation.core.InfiniteTransition,
    ringIndex: Int,
    placements: List<OrbitingFolder>,
    visible: Boolean,
    onOpenFolder: (Long) -> Unit
) {
    val rotationDegrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = orbitDirectionDegrees(ringIndex),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = orbitDurationMillis(ringIndex), easing = LinearEasing)
        ),
        label = "orbitRing$ringIndex"
    )

    // الحساب الإحداثي يحرك الكواكب من دون إنشاء طبقة رسومية بحجم المجرة كاملة
    // لكل مدار؛ وهذا مهم عندما تكبر اللوحة إلى آلاف dp أو تحتوي آلاف المجلدات.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (visible) {
            val rotationRadians = rotationDegrees * (PI / 180.0)
            placements.forEach { placement ->
                val angle = placement.startAngleRadians + rotationRadians
                val x = placement.radiusDp * cos(angle).toFloat()
                val y = placement.radiusDp * sin(angle).toFloat()
                OrbitingFolderNode(
                    folder = placement.folder,
                    onOpenFolder = onOpenFolder,
                    modifier = Modifier
                        .absoluteOffset(x = x.dp, y = y.dp)
                        .size(GALAXY_ORBITER_WIDTH_DP.dp, GALAXY_ORBITER_HEIGHT_DP.dp)
                )
            }
        }
    }
}

@Composable
private fun OrbitingFolderNode(
    folder: FolderWithFileCount,
    onOpenFolder: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val folderColor = folder.color.toComposeColor(fallback = Color(0xFF4E7D6E))
    Column(
        modifier = modifier.clickable { onOpenFolder(folder.folderId) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(folderPlanetSizeDp(folder.fileCount).dp)
                .drawBehind {
                    drawCircle(
                        color = folderColor.copy(alpha = 0.27f),
                        radius = size.minDimension / 2f + 6.dp.toPx()
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(folderColor.copy(alpha = 0.98f), folderColor.copy(alpha = 0.62f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = size.minDimension * 0.12f,
                    center = Offset(size.width * 0.32f, size.height * 0.28f)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            lineHeight = 12.sp,
            color = Color(0xFFE9EEE9),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${folder.fileCount} ملف",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            lineHeight = 11.sp,
            color = Color(0xFFAFBBB3),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** شمس الجامعة ثابتة في المركز؛ ليست مجلداً ولا تستبدل أي مجلد في البيانات. */
@Composable
private fun SolarCore() {
    val pulseTransition = rememberInfiniteTransition(label = "UniversitySunPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sunPulse"
    )
    Column(
        modifier = Modifier.width(132.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .drawBehind {
                    drawCircle(
                        color = SUN_GOLD.copy(alpha = 0.13f),
                        radius = size.minDimension * 0.92f
                    )
                    drawCircle(
                        color = SUN_GOLD.copy(alpha = 0.25f),
                        radius = size.minDimension * 0.68f
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFF8C9),
                            Color(0xFFFFD54F),
                            Color(0xFFEF8F24),
                            Color(0xFF9F4E11)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.50f),
                    radius = size.minDimension * 0.12f,
                    center = Offset(size.width * 0.33f, size.height * 0.26f)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "جامعتي",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
            color = Color(0xFFFFE9A1),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = "مركز المجلدات",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color(0xFFB9B5A1),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * مواصفات ذرة غبار ذهبي نيزكي (المقترح #10 المعتمد):
 * إحداثيات نسبية وسرعة انجراف متناهية البطء وحجم ناعم ونوع اللون.
 */
private class StardustParticle(
    val x: Float,
    val y: Float,
    val radiusPx: Float,
    val speed: Float,
    val phase: Float,
    val colorIndex: Int
)

/**
 * لوحة ألوان غبار الذهب النيزكي الداكن:
 * درجات ذهبية وكهرمانية ونحاسية دافئة خالية من الوهج المشتت.
 */
private val STARDUST_COLORS = listOf(
    Color(0xFFFFD54F), // ذهب مشرق ناعم
    Color(0xFFFFE082), // ذهب دافئ فاتح
    Color(0xFFE6C280), // نحاس كوني خافت
    Color(0xFFFFF8E1)  // أبيض عاجي نجمي
)

/**
 * سماء غبار الذهب النيزكي الداكن (المقترح #10 المعتمد في اختيارات.MD):
 * طبقة Canvas خفيفة لسماء OLED عميقة يعبرها غبار ذهبي ينجرف بهدوء وبطء فائق،
 * مع نجوم خافتة تومض برقة — مشهد تأملي مريح للأعصاب ويوفر استهلاك البطارية.
 */
@Composable
private fun GoldenStardustBackground(modifier: Modifier = Modifier) {
    // نجوم ثابتة خافتة
    val stars = remember {
        val random = Random(42)
        List(60) {
            Triple(random.nextFloat(), random.nextFloat(), random.nextFloat())
        }
    }

    // ذرات غبار الذهب النيزكي (بذرة عشوائية ثابتة)
    val stardust = remember {
        val random = Random(101)
        List(45) {
            StardustParticle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radiusPx = 1.2f + random.nextFloat() * 2.2f,
                speed = 0.4f + random.nextFloat() * 0.6f,
                phase = random.nextFloat(),
                colorIndex = random.nextInt(STARDUST_COLORS.size)
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "goldenStardust")
    // دورة انجراف هادئة جداً (50 ثانية) تحاكي انعدام الجاذبية الكونية
    val driftClock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(50_000, easing = LinearEasing)),
        label = "driftClock"
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_500, easing = LinearEasing)),
        label = "twinkle"
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // 1) النجوم الخلفية الثابتة
        stars.forEach { star ->
            val (fx, fy, phase) = star
            val alpha = 0.20f + 0.45f * kotlin.math.abs(
                sin((twinkle + phase) * 2f * PI.toFloat())
            )
            drawCircle(
                color = Color.White.copy(alpha = alpha * 0.7f),
                radius = 1.0f + phase * 1.4f,
                center = Offset(fx * w, fy * h)
            )
        }

        // 2) ذرات غبار الذهب النيزكي البطيئة الانجراف
        stardust.forEach { p ->
            // حركة رأسية هادئة مع تموج أفقي متناهي الصغر
            val currentProgress = (driftClock * p.speed + p.phase) % 1f
            val py = (p.y + currentProgress) % 1f * h
            val px = (p.x + sin((currentProgress + p.phase) * 2f * PI.toFloat()) * 0.025f) * w

            val baseColor = STARDUST_COLORS[p.colorIndex]
            val pulseAlpha = 0.25f + 0.50f * kotlin.math.abs(
                sin((twinkle + p.phase) * 2f * PI.toFloat())
            )

            // توهج خفيف للذرة
            drawCircle(
                color = baseColor.copy(alpha = pulseAlpha * 0.20f),
                radius = p.radiusPx * 2.5f,
                center = Offset(px, py)
            )
            // مركز الذرة الذهبية
            drawCircle(
                color = baseColor.copy(alpha = pulseAlpha * 0.85f),
                radius = p.radiusPx,
                center = Offset(px, py)
            )
        }
    }
}
