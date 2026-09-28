package com.unihub.app.feature.galaxy

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.local.model.FolderWithFileCount
import com.unihub.app.ui.theme.toComposeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * مجرّة «زخات الشهب» — التصميم المختار رقم 17 من استوديو التصاميم
 * (ملف اختيارات.MD) مع تحسينات الجلسة:
 *
 * 1) السماء: شهب ذهبية بطيئة ومتباعدة تعبر الخلفية (طبقة Canvas ثابتة)،
 *    مع نجوم تومض بهدوء — مشهد حي دون تشتيت وبلا استهلاك عالٍ للبطارية.
 *
 * 2) العناقيد: كل مجلد جذر «كوكب أم» في مركز عنقوده:
 *    - بلا مجلدات فرعية: تحرسه حلقة واقية (كما في مقترح زخات الشهب).
 *    - بمجلدات فرعية: أبناؤه كواكب صغيرة موزّعة على مدار حوله بزوايا متساوية
 *      حسابياً (مسافة مضمونة بين الكواكب بلا تداخل مهما كثر عددها)،
 *      خيوط رفيعة تربط كل ابن بأمه، ودائرة حاضنة تحصر العائلة كلها بلون الأم.
 *
 * 3) المسافات: نصف قطر المدار يتسع مع عدد الأبناء بحد أعلى، والتوزيع شبكي
 *    بفجوة ثابتة بسيطة بين العناقيد — لا تداخل مزعج ولا تباعد مبالغ فيه.
 *
 * كل الألوان من لوحة الشاشة/السمة الحالية — لا لون جديداً واحداً.
 */
@Composable
fun GalaxyScreen(
    onOpenFolder: (Long) -> Unit,
    viewModel: GalaxyViewModel = hiltViewModel()
) {
    val clusters by viewModel.clusters.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF101614),
                        Color(0xFF182220),
                        Color(0xFF101614)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // سماء الشهب تُرى دائماً — حتى والمجرة فارغة تبقى السماء حيّة
        MeteorBackground(Modifier.matchParentSize())

        if (clusters.isEmpty()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "المجرّة",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFE0E5E1)
                )
                Spacer(Modifier.height(24.dp))
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = Color(0xFFA9B4AD),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "مجرتك فارغة",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE0E5E1),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "أنشئ مجلدات لموادك في شاشة الملفات وستظهر هنا ككواكب تحرسها حلقات تحت سماء الشهب، وتتجمع مجلداتك الفرعية حول أمها داخل دائرة واحدة",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA9B4AD),
                    textAlign = TextAlign.Center
                )
            }
            return@Box
        }

        GalaxyClusters(clusters = clusters, onOpenFolder = onOpenFolder)

        Text(
            text = "اضغط أي كوكب لفتح مجلده",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFA9B4AD),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

/**
 * طبقة العناقيد: توزيع شبكي منتظم بفجوة ثابتة بسيطة تضمن مسافة بين العناقيد
 * بلا تداخل، مع تمرير عمودي عندما تفوق العناقيد مساحة السماء.
 */
@Composable
private fun GalaxyClusters(
    clusters: List<GalaxyCluster>,
    onOpenFolder: (Long) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 14.dp
        // حجم الخلية موحد = أكبر بصمة عنقود (بحدين أدنى وأعلى) حتى تبقى المسافات منتظمة
        val cell = remember(clusters) { computeCellSize(clusters) }
        val cols = maxOf(1, ((maxWidth.value - 24f) / (cell.value + gap.value)).toInt())
        val rowWidth = cell * cols + gap * (cols - 1)
        val startX = (maxWidth - rowWidth) / 2

        Box(
            modifier = Modifier
                .matchParentSize()
                .verticalScroll(rememberScrollState())
        ) {
            clusters.forEachIndexed { index, cluster ->
                val col = index % cols
                val row = index / cols
                FolderClusterView(
                    cluster = cluster,
                    cellSize = cell,
                    onOpenFolder = onOpenFolder,
                    modifier = Modifier
                        .offset(
                            x = startX + (cell + gap) * col,
                            y = 10.dp + (cell + gap) * row
                        )
                        .size(cell)
                )
            }
        }
    }
}

/** بصمة عنقود واحد بالبكسل المستقل: قطر الحلقات + هامش التسمية. */
private fun clusterFootprint(cluster: GalaxyCluster): Float {
    return if (cluster.children.isEmpty()) {
        val planetSize = 30f + min(cluster.parent.fileCount * 2f, 18f)
        planetSize + 28f + 26f // قطر الكوكب + الحلقة الواقية + هامش التسمية
    } else {
        val orbit = 50f + min(cluster.children.size, 8) * 6f
        val maxChildSize = cluster.children.maxOf { 20f + min(it.fileCount * 2f, 10f) }
        (orbit + maxChildSize / 2f + 8f) * 2f + 26f
    }
}

/** حجم الخلية الموحد: أكبر بصمة، محصورة بين حدّين حتى لا تضيق ولا تتباعد بلا داعٍ. */
private fun computeCellSize(clusters: List<GalaxyCluster>): Dp {
    val maxFootprint = clusters.maxOfOrNull { clusterFootprint(it) } ?: 150f
    return maxFootprint.coerceIn(150f, 264f).dp
}

/**
 * عنقود واحد: كوكب الأم في المركز، وحوله مجلداته الفرعية على مدار بزوايا
 * متساوية (المسافة بين الأبناء مضمونة هندسياً)، خيوط تربطهم بالأم،
 * ودائرة حاضنة بلون الأم تحصر العائلة. وإن لم يكن له أبناء فحلقة حارسة فقط.
 */
@Composable
private fun FolderClusterView(
    cluster: GalaxyCluster,
    cellSize: Dp,
    onOpenFolder: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val parentColor = cluster.parent.color.toComposeColor(fallback = Color(0xFF4E7D6E))
    val parentSize = (30 + min(cluster.parent.fileCount * 2, 18)).dp

    Box(modifier = modifier) {
        if (cluster.children.isEmpty()) {
            // مجلد بلا أبناء: كوكب تحرسه حلقة واقية — جوهر مقترح «زخات الشهب»
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    color = parentColor.copy(alpha = 0.32f),
                    radius = parentSize.toPx() / 2f + 14.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
            PlanetColumn(
                folder = cluster.parent,
                planetSize = parentSize,
                color = parentColor,
                onOpenFolder = onOpenFolder,
                modifier = Modifier
                    .offset(x = cellSize / 2 - 56.dp, y = cellSize / 2 - parentSize / 2)
                    .width(112.dp)
            )
        } else {
            val count = cluster.children.size
            // نصف قطر المدار يتسع مع عدد الأبناء بحد أعلى: مسافة بسيطة لا بعيدة
            val orbitRadius = (50 + min(count, 8) * 6).dp
            val maxChildSize = cluster.children.maxOf { (20 + min(it.fileCount * 2, 10)).dp }
            val confinementRadius = orbitRadius + maxChildSize / 2 + 8.dp

            Canvas(Modifier.matchParentSize()) {
                val c = center
                // الدائرة الحاضنة تحصر كل كواكب العائلة
                drawCircle(
                    color = parentColor.copy(alpha = 0.30f),
                    radius = confinementRadius.toPx(),
                    center = c,
                    style = Stroke(width = 1.3.dp.toPx())
                )
                // مدار الأبناء — دليل بصري خفيف
                drawCircle(
                    color = parentColor.copy(alpha = 0.12f),
                    radius = orbitRadius.toPx(),
                    center = c,
                    style = Stroke(width = 1.dp.toPx())
                )
                // خيوط تربط كل كوكب ابن بكوكب أمه
                for (i in cluster.children.indices) {
                    val angle = -PI / 2 + i * (2.0 * PI / count)
                    val pos = Offset(
                        x = c.x + orbitRadius.toPx() * cos(angle).toFloat(),
                        y = c.y + orbitRadius.toPx() * sin(angle).toFloat()
                    )
                    drawLine(
                        color = parentColor.copy(alpha = 0.22f),
                        start = c,
                        end = pos,
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // كوكب الأم في مركز العنقود
            PlanetColumn(
                folder = cluster.parent,
                planetSize = parentSize,
                color = parentColor,
                onOpenFolder = onOpenFolder,
                modifier = Modifier
                    .offset(x = cellSize / 2 - 56.dp, y = cellSize / 2 - parentSize / 2)
                    .width(112.dp)
            )

            // كواكب الأبناء: زوايا متساوية تماماً — لا تداخل مهما كثر العدد
            cluster.children.forEachIndexed { index, child ->
                val angle = -PI / 2 + index * (2.0 * PI / count)
                val childSize = (20 + min(child.fileCount * 2, 10)).dp
                val posX = cellSize / 2 + orbitRadius * cos(angle).toFloat()
                val posY = cellSize / 2 + orbitRadius * sin(angle).toFloat()
                PlanetColumn(
                    folder = child,
                    planetSize = childSize,
                    color = child.color.toComposeColor(fallback = Color(0xFF4E7D6E)),
                    onOpenFolder = onOpenFolder,
                    modifier = Modifier
                        .offset(x = posX - 39.dp, y = posY - childSize / 2)
                        .width(78.dp)
                )
            }
        }
    }
}

/**
 * كوكب واحد (مجلد) مع اسمه وعدد ملفاته — العنصر قابل للنقر لفتح مجلده.
 * التوهج والتدرج الشعاعي نفس روح النسخ السابقة، بحجمين: أم وابن.
 */
@Composable
private fun PlanetColumn(
    folder: FolderWithFileCount,
    planetSize: Dp,
    color: Color,
    onOpenFolder: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable { onOpenFolder(folder.folderId) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(planetSize)
                .drawBehind {
                    // توهج خفيف حول الكوكب
                    drawCircle(
                        color = color.copy(alpha = 0.25f),
                        radius = size.minDimension / 2f + 6f
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.6f))
                    )
                )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFE0E5E1),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${folder.fileCount} ملف",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFA9B4AD),
            textAlign = TextAlign.Center
        )
    }
}

/** مواصفات شهاب واحد: مسار مُعيَّن (إحداثيات نسبية) ودورة وزمن بدء. */
private class MeteorSpec(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val period: Float,
    val offset: Float
)

/**
 * ثلاثة شهب فقط، متباعدة الدورات (11/13/17 ثانية) حتى يبقى المشهد هادئاً
 * ولا يتحول إلى ازدحام ضوئي. المسارات مائلة وقصيرة نسبياً — تعبر وتختفي.
 */
private val METEORS = listOf(
    MeteorSpec(0.08f, -0.04f, 0.52f, 0.50f, period = 13f, offset = 0f),
    MeteorSpec(0.95f, -0.05f, 0.60f, 0.52f, period = 17f, offset = 5f),
    MeteorSpec(0.38f, -0.06f, 0.06f, 0.44f, period = 11f, offset = 8f)
)

/**
 * سماء الشهب: طبقة Canvas واحدة للنجوم الثابتة (بذرة ثابتة حتى لا تتغير
 * مع كل إعادة تركيب) مع وميض بطيء، وللشهب الثلاثة بذيل ضوئي متدرج
 * وغلاف ظهور/اختفاء ناعم. الحركة بطيئة ومريحة — لا ومضات أسرع من ثانيتين.
 */
@Composable
private fun MeteorBackground(modifier: Modifier = Modifier) {
    // نجوم ثابتة (بذرة عشوائية ثابتة حتى لا تتغير مع كل إعادة تركيب)
    val stars = remember {
        val random = Random(42)
        List(70) {
            Triple(random.nextFloat(), random.nextFloat(), random.nextFloat())
        }
    }

    val transition = rememberInfiniteTransition(label = "galaxyMeteors")
    // ساعة بطيئة جداً (دقيقة كاملة للدورة) تُشتق منها أطوار الشهب — قناة واحدة تكفي
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
        label = "meteorClock"
    )
    val twinkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "twinkle"
    )

    Canvas(modifier) {
        // النجوم — نفس أسلوب النسخ السابقة
        stars.forEach { star ->
            val (fx, fy, phase) = star
            val alpha = 0.25f + 0.55f * kotlin.math.abs(
                sin((twinkle + phase) * 2f * PI.toFloat())
            )
            drawCircle(
                color = Color.White.copy(alpha = alpha * 0.8f),
                radius = 1.2f + phase * 1.6f,
                center = Offset(fx * size.width, fy * size.height)
            )
        }

        // الشهب — موضع كل شهاب دالة في الزمن، بلا حالة محفوظة
        val seconds = clock * 60f
        METEORS.forEach { meteor ->
            val progress = ((seconds + meteor.offset) % meteor.period) / meteor.period
            // غلاف ظهور/اختفاء ناعم: تلاشٍ تدريجي بلا قفزات
            val fade = minOf(1f, progress / 0.12f, (1f - progress) / 0.22f).coerceAtLeast(0f)

            val headX = (meteor.startX + (meteor.endX - meteor.startX) * progress) * size.width
            val headY = (meteor.startY + (meteor.endY - meteor.startY) * progress) * size.height
            val dirX = (meteor.endX - meteor.startX) * size.width
            val dirY = (meteor.endY - meteor.startY) * size.height
            val length = sqrt(dirX * dirX + dirY * dirY)
            if (length <= 0f || fade <= 0f) return@forEach
            val unitX = dirX / length
            val unitY = dirY / length
            val tailLength = 44.dp.toPx()
            val head = Offset(headX, headY)
            val tail = Offset(headX - unitX * tailLength, headY - unitY * tailLength)

            // الذيل الضوئي المتدرج
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFE8D9A0).copy(alpha = 0f),
                        Color(0xFFE8D9A0).copy(alpha = 0.55f * fade)
                    ),
                    start = tail,
                    end = head
                ),
                start = tail,
                end = head,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            // توهج الرأس ثم الرأس نفسه
            drawCircle(
                color = Color(0xFFC79A4B).copy(alpha = 0.18f * fade),
                radius = 8.dp.toPx(),
                center = head
            )
            drawCircle(
                color = Color(0xFFE8D9A0).copy(alpha = 0.85f * fade),
                radius = 2.4.dp.toPx(),
                center = head
            )
        }
    }
}
