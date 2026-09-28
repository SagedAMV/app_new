package com.unihub.app.feature.galaxy

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.local.model.FolderWithFileCount
import com.unihub.app.ui.theme.toComposeColor
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
 * 3) المسافات (إعادة بناء هذه الجلسة — طلب تعليمات.md): التخطيط أصبح واعياً
 *    بأبعاد الشاشة وبععدد العناقيد فعلياً: عدد الأعمدة/الصفوف وحجم الخلية
 *    يُشتقان من المساحة المتاحة، فتكبر العناقيد عندما يقلّ عددها وتصغر عندما
 *    يكثر — وكل عنقود يُحجَّم بمعامل موحّد يحفظ النسب بين بنية الأم والأبناء.
 *
 * 4) التنقل داخل المجرّة (جديد هذه الجلسة): قرص للتكبير/التصغير وسحب للتحرك،
 *    بتكبير مثبّت على مركز القرصة نفسه (لا قفزات لأماكن غريبة)، ونقرة مزدوجة
 *    لإعادة الملاءمة. أدنى تكبير مسموح = ملاءمة المحتوى كله للشاشة، فلا يضيع
 *    أي مجلد مهما كثرت البيانات. وإن فاق المحتوى الشاشة (بيانات كثيرة) تبدأ
 *    المجرة بملاءمة تلقائية مصغَّرة تظهر فيها كل العناقيد، ثم يقرّب المستخدم
 *    بإصبعيه ما يشاء — صغرٌ تلقائي يتناسب مع العدد كما طلبت تعليمات.md.
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
            text = "اضغط أي كوكب لفتح مجلده — قرّب بالقرص واسحب للتحرك، ونقرة مزدوجة لإعادة الملاءمة",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFA9B4AD),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp)
        )
    }
}

// ثوابت التخطيط — مقاسة بوحدات dp مستقلة ثم تُحوَّل للبكسل وقت الحساب
private const val GALAXY_GAP_DP = 14f
private const val GALAXY_CONTENT_MARGIN_DP = 12f
private const val GALAXY_MIN_CELL_DP = 104f
private const val GALAXY_MAX_CELL_DP = 300f
private const val GALAXY_MAX_CLUSTER_SCALE = 1.9f
private const val GALAXY_ABSOLUTE_MIN_SCALE = 0.2f
private const val GALAXY_KEEP_VISIBLE_DP = 56f
private const val GALAXY_MAX_ZOOM = 5f

/** نتيجة حساب تخطيط المجرة كاملاً — دالة نقية قابلة للاختبار مباشرة. */
@Suppress("ArrayInDataClass")
internal data class GalaxyLayout(
    val cols: Int,
    val rows: Int,
    val cellPx: Float,
    val gapPx: Float,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
    /** معامل تحجيم موحّد لكل العناقيد — يحفظ النسب بين عنقود كبير وصغير */
    val clusterScale: Float,
    /** أدنى تكبير مسموح = ملاءمة المحتوى كله للشاشة (فلا يضيع أي مجلد) */
    val fitAllScale: Float,
    /** بصمة كل عنقود بالبكسل — أساس التحجيم النسبي */
    val footprintsPx: FloatArray
)

/**
 * حساب تخطيط المجرة (طلب تعليمات.md — «التطبيق يتعرف على حجم شاشتي ثم يبني
 * المجلدات داخل هذه الشاشة بحيث يصغر أو يكبر بما يتناسب مع العدد»):
 *
 * 1) عدد الأعمدة يُقدَّر من عدد العناقيد ونسبة أبعاد الشاشة (شبكة شبه مربعة)،
 *    ثم تُجرَّب الأعمدة المجاورة ويُلْتَقَط التقسيم الذي يمنح أكبر خلية ممكنة —
 *    منطق رياضي صريح بدل حجم ثابت مفروض كما في النسخة السابقة.
 * 2) حجم الخلية يشتق من المساحة المتاحة فعلياً محصوراً بين حد أدنى (يبقى
 *    الكوكب قابلاً للقرص) وحد أعلى (لا تتضخم العناقيد القليلة بلا طعم).
 * 3) إن فاق المحتوى الشاشة (بيانات كثيرة) يُترك الأمر للتكبير والسحب — أدنى
 *    تكبير مسموح به هو ملاءمة المحتوى كله، فيستطيع المستخدم دائماً رؤية الكل.
 */
internal fun computeGalaxyLayout(
    clusters: List<GalaxyCluster>,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    density: Float
): GalaxyLayout {
    val n = clusters.size
    val gap = GALAXY_GAP_DP * density
    val margin = GALAXY_CONTENT_MARGIN_DP * density
    val availWidth = (viewportWidthPx - margin * 2f).coerceAtLeast(GALAXY_MIN_CELL_DP * density)
    val availHeight = (viewportHeightPx - margin * 2f).coerceAtLeast(GALAXY_MIN_CELL_DP * density)

    val footprints = FloatArray(n) { clusterFootprint(clusters[it]) * density }
    val maxFootprint = footprints.max()

    // تقدير أولي: شبكة شبه مربعة تناسب نسبة أبعاد الشاشة
    val estimate = sqrt(n.toFloat() * availWidth / availHeight)
        .roundToInt()
        .coerceIn(1, n)

    // تجربة التقسيمات المجاورة واختيار أوسعها خلية
    var bestCols = 1
    var bestRows = n
    var bestCell = 0f
    for (candidate in max(1, estimate - 1)..min(n, estimate + 1)) {
        val rowsForCandidate = ceil(n.toFloat() / candidate).toInt()
        val cellByWidth = (availWidth - gap * (candidate - 1)) / candidate
        val cellByHeight = (availHeight - gap * (rowsForCandidate - 1)) / rowsForCandidate
        val candidateCell = min(cellByWidth, cellByHeight)
        if (candidateCell > bestCell) {
            bestCell = candidateCell
            bestCols = candidate
            bestRows = rowsForCandidate
        }
    }

    // حجم الخلية النهائي: يتسع قدر ما تسمح الشاشة، بحد أعلى مرتبط بأكبر
    // عنقود (حتى يبقى التحجيم نسبياً معقولاً) وحد أدنى يحفظ قابلية القراءة
    val maxCellByFootprint = maxFootprint * GALAXY_MAX_CLUSTER_SCALE
    val maxCellAbsolute = GALAXY_MAX_CELL_DP * density
    val minCell = GALAXY_MIN_CELL_DP * density
    val cell = min(min(bestCell, maxCellByFootprint), maxCellAbsolute).coerceAtLeast(minCell)

    // التحجيم الموحّد: كل عنقود يُرسم ببصمته الطبيعية مضروبة في معامل واحد —
    // العنقود ذو الأبناء الكثيرة يبقى أكبر من جاره، لكن الكل يتناسب مع الشاشة
    val clusterScale = (cell / maxFootprint).coerceAtMost(GALAXY_MAX_CLUSTER_SCALE)

    val contentWidth = bestCols * cell + (bestCols - 1) * gap
    val contentHeight = bestRows * cell + (bestRows - 1) * gap

    val fitAll = min(
        viewportWidthPx / contentWidth,
        viewportHeightPx / contentHeight
    ).coerceAtMost(1f).coerceAtLeast(GALAXY_ABSOLUTE_MIN_SCALE)

    return GalaxyLayout(
        cols = bestCols,
        rows = bestRows,
        cellPx = cell,
        gapPx = gap,
        contentWidthPx = contentWidth,
        contentHeightPx = contentHeight,
        clusterScale = clusterScale,
        fitAllScale = fitAll,
        footprintsPx = footprints
    )
}

/**
 * حصر إزاحة المحتوى بحيث يبقى شريط منه ظاهراً دائماً على الشاشة — فلا
 * يستطيع المستخدم أن «يضيع» المجرّة خارج حدود النظر مهما سحب.
 */
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

/**
 * طبقة العناقيد — إعادة بناء هذه الجلسة (إصلاح جذري لطلب تعليمات.md).
 *
 * المشكلة الجذرية في النسخة السابقة: العناقيد كانت تُصفّ عبر offset() داخل
 * صندوق بحجم الشاشة نفسه مع تمرير عمودي — لكن offset لا يمدّ ارتفاع المحتوى
 * المقاس، فكان مدى التمرير صفراً وتُقصّ كل الصفوف بعد ما تسعه الشاشة (تظهر
 * 4 مجلدات من 8 ولا سبيل للبقية). كما أن حجم الخلية كان ثابتاً لا يعرف عدد
 * العناقيد ولا أبعاد الشاشة إطلاقاً.
 *
 * الحل هنا: محتوى بحجم محسوب صراحةً (يُمدّ القياس الحقيقي)، تخطيط واعٍ
 * بالشاشة والعدد (انظر [computeGalaxyLayout])، وطبقة تحويل للقرص والسحب
 * بتكبير مثبّت على مركز القرصة.
 */
@Composable
private fun GalaxyClusters(
    clusters: List<GalaxyCluster>,
    onOpenFolder: (Long) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        val layout = remember(clusters, viewportWidthPx, viewportHeightPx) {
            computeGalaxyLayout(clusters, viewportWidthPx, viewportHeightPx, density.density)
        }
        val contentWidthPx = layout.contentWidthPx
        val contentHeightPx = layout.contentHeightPx

        // حالة العرض: معامل التكبير وإزاحة المحتوى. الحسابات كلها على نقطة
        // الأصل (0،0) — لذلك يوضع في graphicsLayer أدناه
        // [TransformOrigin(0,0)]. هذا هو سر التكبير بلا قفزات: مع الأصل
        // المركزي الافتراضي تتحرك الصورة «تحت الأصابع» فيظن المستخدم أن
        // القرص نقله لمكان غريب (الشكوى في تعليمات.md).
        var scale by remember { mutableFloatStateOf(1f) }
        var translation by remember { mutableStateOf(Offset.Zero) }
        var userAdjusted by remember { mutableStateOf(false) }

        val minScale = layout.fitAllScale
        val keepVisiblePx = GALAXY_KEEP_VISIBLE_DP * density.density

        // تمركز أولي (وإعادة تمركز عند تغيّر بنية المحتوى) ما لم يكن المستخدم
        // قد حرّك المجرة بنفسه — حتى لا يقفز العرض تحت يده بعد كل تعديل.
        // الملاءمة التلقائية الأولية (طلب تعليمات.md — «اذا بيانات اصبحت كثيرة
        // التطبيق سيقوم بتصغيرها تلقائيا حتى تتناسب مع الشاشة»): إن فاق المحتوى
        // الشاشة يُبدَأ بمعامل ملاءمة الكل فيظهر كل مجلد مصغّراً، ومن هناك
        // يقرّب المستخدم بالقرص ما يشاء. وإن كان المحتوى يسع الشاشة فمقياس 1.
        LaunchedEffect(contentWidthPx, contentHeightPx) {
            if (!userAdjusted) {
                val fits = contentWidthPx <= viewportWidthPx &&
                    contentHeightPx <= viewportHeightPx
                scale = if (fits) 1f else layout.fitAllScale
                translation = Offset(
                    x = ((viewportWidthPx - contentWidthPx * scale) / 2f).coerceAtLeast(0f),
                    y = ((viewportHeightPx - contentHeightPx * scale) / 2f).coerceAtLeast(0f)
                )
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                // قرص للتكبير + إصبع/إصبعان للسحب. معادلة التثبيت على مركز
                // القرصة: النقطة التي تحت الأصابع تبقى تحت الأصابع بعد تغيير
                // المقياس، ثم تُضاف إزاحة السحب، ثم الحصر الآمن.
                .pointerInput(contentWidthPx, contentHeightPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        userAdjusted = true
                        val newScale = (scale * zoom).coerceIn(minScale, GALAXY_MAX_ZOOM)
                        val applied = newScale / scale
                        translation = centroid - (centroid - translation) * applied + pan
                        scale = newScale
                        translation = clampGalaxyTranslation(
                            translation = translation,
                            scaledWidthPx = contentWidthPx * scale,
                            scaledHeightPx = contentHeightPx * scale,
                            viewportWidthPx = viewportWidthPx,
                            viewportHeightPx = viewportHeightPx,
                            keepVisiblePx = keepVisiblePx
                        )
                    }
                }
                // نقرة مزدوجة: إعادة الملاءمة — إن كان المحتوى أكبر من الشاشة
                // يُصغَّر حتى يظهر كله، وإلا عاد لمقياس 1 متمركزاً.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            userAdjusted = true
                            scale = layout.fitAllScale
                            translation = Offset(
                                x = ((viewportWidthPx - contentWidthPx * scale) / 2f)
                                    .coerceAtLeast(0f),
                                y = ((viewportHeightPx - contentHeightPx * scale) / 2f)
                                    .coerceAtLeast(0f)
                            )
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { contentWidthPx.toDp() },
                        height = with(density) { contentHeightPx.toDp() }
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                val cellDp = with(density) { layout.cellPx.toDp() }
                val gapDp = with(density) { layout.gapPx.toDp() }
                clusters.forEachIndexed { index, cluster ->
                    val col = index % layout.cols
                    val row = index / layout.cols
                    Box(
                        modifier = Modifier
                            .offset(x = (cellDp + gapDp) * col, y = (cellDp + gapDp) * row)
                            .size(cellDp),
                        contentAlignment = Alignment.Center
                    ) {
                        // كل عنقود يُرسم ببصمته الطبيعية ثم يُحجَّم بالمعامل
                        // الموحّد حول مركزه — النسب بين الكواكب تبقى سليمة
                        FolderClusterView(
                            cluster = cluster,
                            sizeDp = with(density) { layout.footprintsPx[index].toDp() },
                            onOpenFolder = onOpenFolder,
                            modifier = Modifier.graphicsLayer {
                                scaleX = layout.clusterScale
                                scaleY = layout.clusterScale
                                transformOrigin = TransformOrigin.Center
                            }
                        )
                    }
                }
            }
        }
    }
}

/** بصمة عنقود واحد بوحدات dp المستقلة: قطر الحلقات + هامش التسمية. */
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

/**
 * عنقود واحد: كوكب الأم في مركز عنقوده، وحوله مجلداته الفرعية على مدار
 * بزوايا متساوية (المسافة بين الأبناء مضمونة هندسياً)، خيوط تربطهم بالأم،
 * ودائرة حاضنة بلون الأم تحصر العائلة. وإن لم يكن له أبناء فحلقة حارسة فقط.
 *
 * [sizeDp] هي بصمة العنقود الطبيعية — كل الحسابات الداخلية نسبة إليها،
 * والتحجيم الفعلي على الشاشة يجري في طبقة التحويل الخارجية بمعامل موحّد.
 */
@Composable
private fun FolderClusterView(
    cluster: GalaxyCluster,
    sizeDp: Dp,
    onOpenFolder: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val parentColor = cluster.parent.color.toComposeColor(fallback = Color(0xFF4E7D6E))
    val parentSize = (30 + min(cluster.parent.fileCount * 2, 18)).dp

    Box(modifier = modifier.size(sizeDp)) {
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
                    .offset(x = sizeDp / 2 - 56.dp, y = sizeDp / 2 - parentSize / 2)
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
                    .offset(x = sizeDp / 2 - 56.dp, y = sizeDp / 2 - parentSize / 2)
                    .width(112.dp)
            )

            // كواكب الأبناء: زوايا متساوية تماماً — لا تداخل مهما كثر العدد
            cluster.children.forEachIndexed { index, child ->
                val angle = -PI / 2 + index * (2.0 * PI / count)
                val childSize = (20 + min(child.fileCount * 2, 10)).dp
                val posX = sizeDp / 2 + orbitRadius * cos(angle).toFloat()
                val posY = sizeDp / 2 + orbitRadius * sin(angle).toFloat()
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
                    )
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
