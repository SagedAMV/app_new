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
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.local.model.FolderWithFileCount
import com.unihub.app.ui.theme.toComposeColor
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * مجرّة «النسيج الكوكبي وغبار الذهب النيزكي» — التصميمان المعتمدان (#1 و #10)
 * من وثيقة اختيارات.MD المرفوعة في المستودع:
 *
 * 1) سماء OLED وغبار الذهب النيزكي (المقترح #10):
 *    خلفية سوداء ناصعة عميقة توفر طاقة شاشات OLED وتحمي العين في الظلام،
 *    تعبرها ذرات غبار ذهبي ونحاسي ناعمة تنجرف بهدوء وبطء فائق مع نجوم تومض
 *    برقة — مشهد حي هادئ يزيل التوتر دون أي خطوط صاخبة أو استهلاك للبطارية.
 *
 * 2) النسيج الكوكبي الهادئ للعناقيد (المقترح #1):
 *    مجلدات ثابتة في إحداثياتها المكانية كنجوم قطبية مستقرة: الأب في المركز،
 *    والأبناء في مقاعد محددة على حلقات متعاقبة، مع روابط هادئة تصنع شبكة
 *    واضحة بين أفراد العائلة دون دوران مشتت أو ازدحام.
 *
 * 3) ثبات تام للنصوص: اتجاه نصوص التسميات يظل أفقياً بزاوية 0° دائماً
 *    (Orientation Locking) لضمان القراءة الفورية لأسماء المواد الأكاديمية.
 *
 * 4) التخطيط المتجاوب والتنقل السلس: حساب أبعاد الخلايا بما يناسب الشاشة
 *    [computeGalaxyLayout]، ودعم التكبير والتصغير المثبّت على مركز القرصة
 *    والسحب المرن والنقر لفتح المجلدات.
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
                        Color(0xFF07090E),
                        Color(0xFF0D121C),
                        Color(0xFF07090E)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // سماء غبار الذهب النيزكي الداكن (#10) — سماء حية هادئة دائمة
        GoldenStardustBackground(Modifier.matchParentSize())

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

// ثوابت طبقة العرض التفاعلية (قرص/سحب) — ثوابت الشبكة نفسها انتقلت إلى
// [GalaxyLayoutEngine] مع بقية الدوال النقية القابلة للاختبار على JVM.
private const val GALAXY_KEEP_VISIBLE_DP = 56f
private const val GALAXY_MAX_ZOOM = 5f

// نموذج [GalaxyLayout] ودالة [computeGalaxyLayout] انتقلا كما هما إلى
// [GalaxyLayoutEngine] — نفس الحزمة، فالاستدعاء أدناه بلا أي تغيير.

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
                },
            // حاوية الإيماءات تضع **لوح المحتوى** كاملاً. هنا لا يصلح التمركز:
            // حصر السحب (clampGalaxyTranslation) وأصل التحجيم
            // TransformOrigin(0,0) كلاهما مبني على أصل يساري علوي. ومحاذاة
            // TopStart الافتراضية تنقلب إلى اليمين على RTL فينزاح اللوح كله.
            // العلاج: محاذاة فيزيائية مطلقة لا تنقلب بأي اتجاه.
            // (ملاحظة: الاسم الصحيح AbsoluteAlignment.TopLeft — لا يوجد
            //  Alignment.TopLeft في واجهة Compose إطلاقاً.)
            contentAlignment = AbsoluteAlignment.TopLeft
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
                    },
                // نقطة أصل محايدة لاتجاه التخطيط: انحياز المركز صفر فلا
                // ينقلب في RTL، بعكس TopStart الافتراضية — انظر التعليل في
                // GalaxyGeometry ودالة gridCellOffsetFromCenterDp.
                contentAlignment = Alignment.Center
            ) {
                val cellDp = with(density) { layout.cellPx.toDp() }
                val gapDp = with(density) { layout.gapPx.toDp() }
                val stepDp = cellDp + gapDp
                clusters.forEachIndexed { index, cluster ->
                    val col = index % layout.cols
                    val row = index / layout.cols
                    Box(
                        modifier = Modifier
                            .absoluteOffset(
                                x = gridCellOffsetFromCenterDp(col, layout.cols, stepDp.value).dp,
                                y = gridCellOffsetFromCenterDp(row, layout.rows, stepDp.value).dp
                            )
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

// حساب البصمة [clusterFootprint] صار في [GalaxyLayoutEngine] — نفس الحزمة.

/**
 * عنقود واحد: كوكب الأم في مركز عنقوده، وحوله مجلداته الفرعية على مدار
 * بزوايا متساوية (المسافة بين الأبناء مضمونة هندسياً)، خيوط تربطهم بالأم،
 * ودائرة حاضنة بلون الأم تحصر العائلة كاملة — الكوكب وتسميته المتدلية معاً
 * مهما كثر عدد الأبناء (الحدّ الرياضي في [GalaxyGeometry.confinementRadiusDp]).
 * وإن لم يكن له أبناء فحلقة حارسة فقط.
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
    val parentSize = GalaxyGeometry.parentPlanetSizeDp(cluster.parent.fileCount).dp

    // عرض عمود الكوكب مشتق من ثابت الهندسة (مصدر حقيقة واحد للأم كما للابن)
    val parentColumnWidth = (GalaxyGeometry.PARENT_COLUMN_HALF_WIDTH_DP * 2f).dp
    val discY = GalaxyGeometry.columnOffsetYDp(0f).dp

    // حركة الدوران للمدارات (Orbit Rotation) — سرعات متفاوتة لكل حلقة
    // الحلقات الداخلية تدور أسرع من الخارجية (محاكاة بسيطة لقوانين كبلر)
    val orbitTransition = rememberInfiniteTransition(label = "OrbitRotation")
    
    // مدة الدوران للحلقة الأولى (أسرع) - 40 ثانية للدورة الكاملة
    val ring1Angle by orbitTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(40_000, easing = LinearEasing)
        ),
        label = "ring1Angle"
    )
    
    // مدة الدوران للحلقة الثانية (أبطأ) - 60 ثانية للدورة الكاملة
    val ring2Angle by orbitTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(60_000, easing = LinearEasing)
        ),
        label = "ring2Angle"
    )
    
    // مدة الدوران للحلقة الثالثة (الأبطأ) - 80 ثانية للدورة الكاملة
    val ring3Angle by orbitTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(80_000, easing = LinearEasing)
        ),
        label = "ring3Angle"
    )

    // نبض النسيج الكوكبي الهادئ (المقترح #1 المعتمد) — حركة نبض نورانية رقيقة في خيوط المسار
    val transition = rememberInfiniteTransition(label = "ConstellationPulse")
    val pulsePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing)
        ),
        label = "pulsePhase"
    )

    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        if (cluster.children.isEmpty()) {
            // مجلد بلا أبناء: كوكب تحرسه حلقة نسيج كوكبي منقطة ونابضة
            Canvas(Modifier.matchParentSize()) {
                val ringRadius = GalaxyGeometry
                    .guardianRingRadiusDp(cluster.parent.fileCount)
                    .dp.toPx()
                drawCircle(
                    color = parentColor.copy(alpha = 0.35f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(
                        width = 1.4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                            pulsePhase
                        )
                    )
                )
            }
            // الشمس (المجلد الأم) مع توهج أقوى
            SunColumn(
                folder = cluster.parent,
                planetSize = parentSize,
                color = parentColor,
                onOpenFolder = onOpenFolder,
                modifier = Modifier
                    .absoluteOffset(x = 0.dp, y = discY)
                    .width(parentColumnWidth)
            )
        } else {
            val count = cluster.children.size
            val maxChildFileCount = cluster.children.maxOf { it.fileCount }
            val confinementRadius = GalaxyGeometry
                .confinementRadiusDp(count, maxChildFileCount, cluster.parent.fileCount)
                .dp

            // حساب الزوايا الحالية لكل حلقة بناءً على Animation
            // دالة مساعدة للحصول على زاوية الدوران للحلقة المحددة
            fun getOrbitAngleForRing(ringIndex: Int): Double {
                return when (ringIndex) {
                    0 -> ring1Angle.toDouble()
                    1 -> ring2Angle.toDouble()
                    2 -> ring3Angle.toDouble()
                    else -> ring3Angle.toDouble() // الحلقات الأبعد تدور بنفس سرعة الحلقة الثالثة
                }
            }

            Canvas(Modifier.matchParentSize()) {
                val c = center
                // حدود العنقود: إطار هادئ يحدد المساحة المخصصة لهذه العائلة.
                drawCircle(
                    color = parentColor.copy(alpha = 0.28f),
                    radius = confinementRadius.toPx(),
                    center = c,
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(10.dp.toPx(), 6.dp.toPx()),
                            -pulsePhase * 0.5f
                        )
                    )
                )
                
                // رسم مدارات ثابتة خافتة (للتوضيح البصري للمسار)
                val lastRing = GalaxyGeometry.childRing(count - 1)
                for (ring in 0..lastRing) {
                    drawCircle(
                        color = parentColor.copy(alpha = if (ring == 0) 0.08f else 0.045f),
                        radius = (GalaxyGeometry.FIRST_RING_RADIUS_DP + ring * GalaxyGeometry.RING_STEP_DP).dp.toPx(),
                        center = c,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                val dashEffect = PathEffect.dashPathEffect(
                    floatArrayOf(7.dp.toPx(), 6.dp.toPx()),
                    pulsePhase
                )
                
                // حساب المواقع الحالية للكواكب مع دوران المدارات
                val positions = cluster.children.indices.map { i ->
                    val ringIndex = GalaxyGeometry.childRing(i)
                    val orbitAngle = getOrbitAngleForRing(ringIndex)
                    Offset(
                        x = c.x + GalaxyGeometry.childCenterDxDpWithOrbit(i, count, orbitAngle).dp.toPx(),
                        y = c.y + GalaxyGeometry.childCenterDyDpWithOrbit(i, count, orbitAngle).dp.toPx()
                    )
                }

                // الرابط الرئيسي: كل مجلد ابن مرتبط بالأب، مثل خريطة علاقات
                // واضحة، وليس كواكباً سائبة داخل دائرة.
                positions.forEach { pos ->
                    drawLine(
                        color = parentColor.copy(alpha = 0.34f),
                        start = c,
                        end = pos,
                        strokeWidth = 1.25.dp.toPx(),
                        pathEffect = dashEffect,
                        cap = StrokeCap.Round
                    )
                }

                // روابط جانبية خفيفة بين الإخوة في الحلقة نفسها. هذه الروابط
                // تعطي إحساس «شبكة» واحدة من دون أن تسرق الانتباه من المجلدات.
                cluster.children.indices
                    .groupBy { GalaxyGeometry.childRing(it) }
                    .values
                    .forEach { ringIndices ->
                        if (ringIndices.size > 1) {
                            ringIndices.forEachIndexed { index, fromIndex ->
                                val toIndex = ringIndices[(index + 1) % ringIndices.size]
                                drawLine(
                                    color = parentColor.copy(alpha = 0.13f),
                                    start = positions[fromIndex],
                                    end = positions[toIndex],
                                    strokeWidth = 0.9.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }

                positions.forEach { pos ->
                    drawCircle(
                        color = parentColor.copy(alpha = 0.58f),
                        radius = 2.2.dp.toPx(),
                        center = pos
                    )
                }
            }

            // الشمس (المجلد الأم) في مركز العنقود تماماً: إزاحة أفقية صفر عن نقطة أصل
            // متمركزة ⇒ مركز القرص ينطبق على مركز الدائرة الحاضنة المرسومة
            // على Canvas، في LTR وRTL على حد سواء.
            SunColumn(
                folder = cluster.parent,
                planetSize = parentSize,
                color = parentColor,
                onOpenFolder = onOpenFolder,
                modifier = Modifier
                    .absoluteOffset(x = 0.dp, y = discY)
                    .width(parentColumnWidth)
            )

            // كواكب الأبناء: كل واحد منها له مقعد ثابت داخل شبكة الحلقات؛
            // العدد لا يضغط العناصر فوق بعضها، بل يفتح حلقة جديدة عند الحاجة.
            // الآن الكواكب تدور مع مداراتها!
            cluster.children.forEachIndexed { index, child ->
                val childSize = GalaxyGeometry.childPlanetSizeDp(child.fileCount).dp
                val ringIndex = GalaxyGeometry.childRing(index)
                val orbitAngle = getOrbitAngleForRing(ringIndex)
                
                // الإحداثيات نسبةً لمركز العنقود — نفس الزوايا التي يرسم بها
                // Canvas المدار والخيوط (GalaxyGeometry.childAngleRadWithOrbit)، فلا
                // يمكن أن يفترق الكوكب عن خيطه مهما كان اتجاه الجهاز.
                val dx = GalaxyGeometry.childCenterDxDpWithOrbit(index, count, orbitAngle)
                val dy = GalaxyGeometry.childCenterDyDpWithOrbit(index, count, orbitAngle)
                PlanetColumn(
                    folder = child,
                    planetSize = childSize,
                    color = child.color.toComposeColor(fallback = Color(0xFF4E7D6E)),
                    onOpenFolder = onOpenFolder,
                    // عرض العمود مشتق من ثابت الهندسة نفسه (مصدر حقيقة واحد —
                    // نصف العرض 39 والعرض 78) حتى لا يظهر انحراف صامت إن تغيّر
                    // الثابت هناك يوماً
                    modifier = Modifier
                        .absoluteOffset(
                            x = dx.dp,
                            y = GalaxyGeometry.columnOffsetYDp(dy).dp
                        )
                        .width((GalaxyGeometry.CHILD_COLUMN_HALF_WIDTH_DP * 2f).dp)
                )
            }
        }
    }
}

/**
 * «الشمس» — المجلد الأم في مركز العنّقود (إعادة تصميم المجرة: شمس مركزية).
 *
 * البنية مطابقة لـ [PlanetColumn] حرفياً من كتلة التسمية فما تحتها — بما في
 * ذلك صندوق التسمية بارتفاع ثابت [GalaxyGeometry.LABELS_BOX_DP] المقصوص
 * وبلا حشوة خط — كي تظل قيود الحصر في GalaxyGeometry سارية على العمود
 * الأم كما هي على الأبناء. الفرق الوحيد: طبقتا توهج أقوى — هالة ذهبية واسعة
 * (روح «اللون الذهبي» في رسالة إعادة التصميم) ثم هالة بلون الكوكب ثم القرص.
 *
 * (إصلاح جلسة التحقق العميق) كان commit 443d3d6 يستدعي SunColumn في موضعين
 * دون أن يُنشئها أصلاً — ففشل البناء بـ Unresolved reference.
 */
@Composable
private fun SunColumn(
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
                    // هالة ذهبية واسعة — توهج «الشمس» الخارجي
                    drawCircle(
                        color = SUN_GOLD.copy(alpha = 0.16f),
                        radius = size.minDimension / 2f + 16.dp.toPx()
                    )
                    // هالة بلون الكوكب — توهج أقوى من توهج الكوكب العادي (0.25)
                    drawCircle(
                        color = color.copy(alpha = 0.40f),
                        radius = size.minDimension / 2f + 8.dp.toPx()
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.98f), color.copy(alpha = 0.72f))
                    )
                )
        )
        Spacer(Modifier.height(GalaxyGeometry.LABEL_SPACER_DP.dp))
        // كتلة التسمية المثبّتة — مطابقة لـ PlanetColumn بالضبط (انظر تعليقه هناك)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GalaxyGeometry.LABELS_BOX_DP.dp)
                .clipToBounds(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    lineHeight = 16.sp,
                    color = Color(0xFFE0E5E1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${folder.fileCount} ملف",
                    style = MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    lineHeight = 16.sp,
                    color = Color(0xFFA9B4AD),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** لون الهالة الذهبية لـ [SunColumn] — نفس ذهب الغبار النيزكي في الشاشة. */
private val SUN_GOLD = Color(0xFFFFD54F)

/**
 * مع اسمه وعدد ملفاته — العنصر قابل للنقر لفتح مجلده.
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
                    // (فحص يدوي — جلسة إصلاح المجرة): كانت القيمة 6f بكسلاً
                    // خاماً فتختلف سماكة التوهج من جهاز لآخر باختلاف الكثافة؛
                    // توحيدها بوحدة dp يجعل التوهج جزءاً ثابتاً من شكل الكوكب
                    // المتمركز داخل حلقته على أي شاشة.
                    drawCircle(
                        color = color.copy(alpha = 0.25f),
                        radius = size.minDimension / 2f + 6.dp.toPx()
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.6f))
                    )
                )
        )
        Spacer(Modifier.height(GalaxyGeometry.LABEL_SPACER_DP.dp))
        // كتلة التسمية المثبّتة — جوهر إصلاح «ملفات خارج الدائرة»: صندوق
        // بارتفاع ثابت [GalaxyGeometry.LABELS_BOX_DP] مقصوص (clipToBounds)
        // ومثبّت أعلاه، وبنصّين بارتفاع سطر مثبت (16) وبلا حشوة خط
        // (includeFontPadding = false). بذلك لا يتجاوز ارتفاع الكتلة الفعلي
        // قيمة الهندسة على أي جهاز أو خط أو حجم خط في النظام — فيبقى حدّ
        // الحصر في GalaxyGeometry مضموناً واقعياً مهما كثر الأبناء.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GalaxyGeometry.LABELS_BOX_DP.dp)
                .clipToBounds(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    lineHeight = 16.sp,
                    color = Color(0xFFE0E5E1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${folder.fileCount} ملف",
                    style = MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    lineHeight = 16.sp,
                    color = Color(0xFFA9B4AD),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
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
