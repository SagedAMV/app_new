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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.local.model.FolderWithFileCount
import com.unihub.app.ui.theme.toComposeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * مجرّة الثقب الأسود: كل مجلد كوكب يدور حول ثقب أسود مركزي بقرص تراكم ذهبي
 * وحلقة فوتونات لامعة، وخلفه ذيل ضوئي من لون المجلد.
 *
 * التصميم محفوظ من نسخة «مجرّة المواد» السابقة: رسم واحد على Canvas للنجوم
 * والمدارات والذيل الضوئي، والكواكب عناصر Compose قابلة للنقر فوقها (بلا
 * حسابات لمس يدوية معقدة)، وحركة بطيئة مريحة لا تصرف البطارية، مع حماية
 * الشاشات الضيقة/المقسومة.
 *
 * العمق: الكواكب في النصف السفلي (أمام الثقب) تُرسم فوقه عبر [zIndex]،
 * وفي النصف العلوي خلفه؛ والثقب يحجب جزء الذيل الضوئي المار خلفه كما
 * يحدث واقعياً (حجب ضوئي)، لذا يبقى الرسم على Canvas واحد بلا طبقة ثانية.
 * كل الألوان من لوحة الشاشة/السمة الحالية — لا لون جديداً واحداً.
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
                    colors = listOf(
                        Color(0xFF101614),
                        Color(0xFF182220),
                        Color(0xFF101614)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (folders.isEmpty()) {
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
                    text = "أنشئ مجلدات لموادك في شاشة الملفات وستظهر هنا ككواكب تدور حول الثقب الأسود في مركز مجرتك",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA9B4AD),
                    textAlign = TextAlign.Center
                )
            }
            return@Box
        }

        GalaxySystem(folders = folders, onOpenFolder = onOpenFolder)

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

@Composable
private fun GalaxySystem(folders: List<FolderWithFileCount>, onOpenFolder: (Long) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val centerX = maxWidth / 2
        val centerY = maxHeight / 2 - 12.dp
        // حماية من نصف قطر سالب على الشاشات الضيقة/المقسومة — الرسم يبقى سليماً دائماً
        val maxRadius = (min(maxWidth.value, maxHeight.value) / 2 - 52f).coerceAtLeast(36f).dp

        // نجوم ثابتة (بذرة عشوائية ثابتة حتى لا تتغير مع كل إعادة تركيب)
        val stars = remember {
            val random = Random(42)
            List(70) {
                Triple(random.nextFloat(), random.nextFloat(), random.nextFloat())
            }
        }

        // حركة المدارات البطيئة + وميض النجوم
        val transition = rememberInfiniteTransition(label = "galaxy")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(150_000, easing = LinearEasing)),
            label = "rotation"
        )
        val twinkle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
            label = "twinkle"
        )

        // حلقات المدارات الثلاثة (بنسب من نصف القطر الأقصى)
        val ringFractions = listOf(0.34f, 0.66f, 1f)
        val ellipseSquash = 0.72f

        // حالة مدار كل كوكب لكل إطار: الموضع والزاوية وطبقة العمق (أمام/خلف الثقب).
        // «أمام» = النصف السفلي من المدار حيث يزداد y نحو الأسفل (جيب الزاوية موجب).
        val orbits = folders.mapIndexed { index, folder ->
            val ringIndex = index % ringFractions.size
            val radiusDp: Dp = maxRadius * ringFractions[ringIndex]
            // توزيع الزوايا بزاوية ذهبية + سرعة تختلف حسب الحلقة
            val baseAngle = index * 137.5f
            val speed = 1f / (ringIndex + 1)
            val angleDeg = baseAngle + rotation * speed
            val angleRad = angleDeg * PI.toFloat() / 180f
            PlanetOrbit(
                folder = folder,
                radiusDp = radiusDp,
                angleDeg = angleDeg,
                x = centerX + radiusDp * cos(angleRad),
                y = centerY + radiusDp * ellipseSquash * sin(angleRad),
                isFront = sin(angleRad) > 0f
            )
        }

        Canvas(Modifier.matchParentSize()) {
            // النجوم
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

            // المدارات (بيضاوية)
            ringFractions.forEach { fraction ->
                val rx = maxRadius.toPx() * fraction
                val ry = rx * ellipseSquash
                drawOval2(
                    center = Offset(centerX.toPx(), centerY.toPx()),
                    rx = rx,
                    ry = ry,
                    color = Color.White.copy(alpha = 0.10f)
                )
            }

            // الذيول الضوئية: قوس قصير من مدار الكوكب خلف اتجاه حركته مباشرة.
            // الزاوية هنا بنفس اصطلاح رسم الموضع (0° عند اليمين، تزداد باتجاه
            // الأسفل)، والقوس يمتد من الزاوية الحالية ناقصاً عرض الذيل إليها.
            orbits.forEach { orbit ->
                val rx = orbit.radiusDp.toPx()
                val ry = rx * ellipseSquash
                drawArc(
                    color = orbit.folder.color.toComposeColor(fallback = Color(0xFF4E7D6E))
                        .copy(alpha = 0.35f),
                    topLeft = Offset(centerX.toPx() - rx, centerY.toPx() - ry),
                    size = Size(rx * 2f, ry * 2f),
                    startAngle = orbit.angleDeg - 32f,
                    sweepAngle = 32f,
                    useCenter = false,
                    style = Stroke(width = 2f, cap = StrokeCap.Round)
                )
            }
        }

        // الكواكب جميعها بترتيبها الأصلي — العمق يتحكم به zIndex وحده، فتبقى
        // هوية كل عنصر ثابتة ولا يعاد تدوير العناصر عند تبديل الطبقات.
        orbits.forEach { orbit ->
            PlanetItem(
                orbit = orbit,
                onOpenFolder = onOpenFolder,
                modifier = Modifier.zIndex(if (orbit.isFront) 2f else 0f)
            )
        }

        // الثقب الأسود المركزي بين الطبقتين
        BlackHole(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-12).dp)
                .zIndex(1f)
        )
    }
}

/** كوكب واحد (مجلد) على مداره — نفس بناء النسخة السابقة مع إحداثيات محسوبة مسبقاً. */
@Composable
private fun PlanetItem(
    orbit: PlanetOrbit,
    onOpenFolder: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val folder = orbit.folder
    val planetSize = (30 + min(folder.fileCount * 2, 18)).dp
    val color = folder.color.toComposeColor(fallback = Color(0xFF4E7D6E))

    Column(
        modifier = modifier
            // محاذاة دقيقة: عرض العمود 112dp، فنزيحه بنصف عرضه ليصبح مركز
            // الكوكب على نقطة المدار تماماً مهما اختلف حجمه.
            .offset(x = orbit.x - 56.dp, y = orbit.y - planetSize / 2 - 8.dp)
            .width(112.dp)
            .clickable { onOpenFolder(folder.folderId) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(planetSize)
                .drawBehind {
                    // توهج خفيف حول الكوكب
                    drawCircle(color = color.copy(alpha = 0.25f), radius = size.minDimension / 2 + 6f)
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

/**
 * الثقب الأسود المركزي: نواة داكنة بلون خلفية الشاشة نفسه ([Color] 0xFF101614)
 * تحيط بها حلقة فوتونات لامعة وقرص تراكم ذهبي مائل — وهي ألوان الشاشة
 * والسمة القائمة (الذهبيان كانا لونا «الشمس» السابقة)، فلا يُضاف لون جديد.
 * العنصر بلا أي مُدخَل لمس، لذا تمر نقرات الكواكب الخلفية من خلاله.
 */
@Composable
private fun BlackHole(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(150.dp)) {
        val c = center

        // توهج قرص التراكم
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFC79A4B).copy(alpha = 0.30f),
                    Color(0xFFC79A4B).copy(alpha = 0f)
                ),
                center = c,
                radius = 72.dp.toPx()
            ),
            radius = 72.dp.toPx(),
            center = c
        )

        // قرص التراكم: بيضاوي مائل بتدرج ذهبي يمر خلف النواة.
        // ملاحظة: تُستخدم withTransform بدل rotate المجردة لأن هذه الأخيرة امتداد
        // على CanvasDrawScope بينما مستقبل Canvas الساكن DrawScope فلا تُحل —
        // و withTransform عضو في DrawScope نفسه وتدور حول مركز مساحة الرسم (c هنا).
        withTransform({ rotate(-18f) }) {
            drawOval(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF7A5B2E).copy(alpha = 0f),
                        Color(0xFFE8D9A0),
                        Color(0xFFC79A4B),
                        Color(0xFF7A5B2E).copy(alpha = 0f)
                    ),
                    start = Offset(c.x - 52.dp.toPx(), c.y),
                    end = Offset(c.x + 52.dp.toPx(), c.y)
                ),
                topLeft = Offset(c.x - 52.dp.toPx(), c.y - 16.dp.toPx()),
                size = Size(104.dp.toPx(), 32.dp.toPx()),
                style = Stroke(width = 4.dp.toPx())
            )
        }

        // النواة الداكنة — بلون خلفية الشاشة الداكن نفسه (لا لون جديداً)
        drawCircle(color = Color(0xFF101614), radius = 22.dp.toPx(), center = c)

        // حلقة الفوتونات
        drawCircle(
            color = Color(0xFFE8D9A0),
            radius = 23.dp.toPx(),
            center = c,
            style = Stroke(width = 1.6.dp.toPx())
        )
    }
}

/** حالة مدار كوكب واحدة محسوبة للإطار الحالي. */
private class PlanetOrbit(
    val folder: FolderWithFileCount,
    val radiusDp: Dp,
    val angleDeg: Float,
    val x: Dp,
    val y: Dp,
    val isFront: Boolean
)

/** رسم بيضاوي مداري */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOval2(
    center: Offset,
    rx: Float,
    ry: Float,
    color: Color
) {
    drawOval(
        color = color,
        topLeft = Offset(center.x - rx, center.y - ry),
        size = Size(rx * 2, ry * 2),
        style = Stroke(width = 1.2f)
    )
}
