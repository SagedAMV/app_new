package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * تخطيط كوني مسطّح: كل مجلد هو كوكب مستقل يدور حول الشمس المركزية «جامعتي».
 * لا يحمل التخطيط أي علاقة مدارية بين الأب والأبناء؛ parentId لا يغيّر مكان
 * المجلد أو مداره، بل يُستخدم فقط في منطق التنقل وبنية البيانات خارج هذه الشاشة.
 */
internal const val GALAXY_FIRST_ORBIT_RADIUS_DP = 160f
internal const val GALAXY_ORBIT_RADIAL_STEP_DP = 132f
internal const val GALAXY_ORBITER_WIDTH_DP = 100f
internal const val GALAXY_ORBITER_HEIGHT_DP = 76f
internal const val GALAXY_MIN_ORBITER_SPACING_DP = 128f
internal const val GALAXY_ORBIT_CONTENT_PADDING_DP = 22f
internal const val GALAXY_MAX_ZOOM = 12f
internal const val GALAXY_INITIAL_COMFORT_SCALE = 0.72f

/** موضع مجلد واحد على إحدى حلقات الشمس، بالوحدات المستقلة dp والراديان. */
internal data class OrbitingFolder(
    val folder: FolderWithFileCount,
    val ringIndex: Int,
    val indexInRing: Int,
    val ringSize: Int,
    val radiusDp: Float,
    val startAngleRadians: Double
)

/** نتيجة تخطيط المجرة بأكملها. */
internal data class GalaxyOrbitLayout(
    val placements: List<OrbitingFolder>,
    val ringRadiiDp: List<Float>,
    val contentSizeDp: Float,
    val contentSizePx: Float,
    val fitAllScale: Float
)

/**
 * يوزّع كل المجلدات على حلقات متحدة المركز.
 *
 * تضمن قاعدة المسافة الهندسية أن بُعد مراكز عنصرين على الحلقة نفسها لا يقل عن
 * [GALAXY_MIN_ORBITER_SPACING_DP]، لأن أبعد مسافة قطرية تسمح بتداخل صندوقين
 * بحجم 100×76dp أصغر من 128dp. أما المسافة بين الحلقات فهي 132dp، لذا لا تتداخل
 * صناديق مجلدات من حلقتين مختلفتين حتى عندما يختلف طور دورانهما.
 */
internal fun computeGalaxyOrbitLayout(
    folders: List<FolderWithFileCount>,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    density: Float
): GalaxyOrbitLayout {
    require(viewportWidthPx > 0f && viewportHeightPx > 0f) {
        "Galaxy viewport dimensions must be positive"
    }
    require(density > 0f) { "Galaxy density must be positive" }

    // احتفظ بترتيب المستودع (sortOrder ثم الاسم) حتى لا يتغير ترتيب العرض
    // الذي يتوقعه المستخدم؛ بنية parentId لا تؤثر في توزيع المدارات.
    val ringRadii = mutableListOf<Float>()
    val placements = ArrayList<OrbitingFolder>(folders.size)
    var nextFolderIndex = 0
    var ringIndex = 0

    while (nextFolderIndex < folders.size) {
        val radius = GALAXY_FIRST_ORBIT_RADIUS_DP + ringIndex * GALAXY_ORBIT_RADIAL_STEP_DP
        val capacity = maxItemsOnOrbit(radius)
        val ringSize = min(capacity, folders.size - nextFolderIndex)
        ringRadii += radius

        for (indexInRing in 0 until ringSize) {
            // تتناوب بدايات الحلقات لتجنب صفوف شعاعية متراصفة بصرياً.
            val stagger = if (ringIndex % 2 == 0) 0.0 else PI / ringSize
            val startAngle = -PI / 2.0 + stagger + (2.0 * PI * indexInRing / ringSize)
            placements += OrbitingFolder(
                folder = folders[nextFolderIndex + indexInRing],
                ringIndex = ringIndex,
                indexInRing = indexInRing,
                ringSize = ringSize,
                radiusDp = radius,
                startAngleRadians = startAngle
            )
        }

        nextFolderIndex += ringSize
        ringIndex++
    }

    val outerRadius = ringRadii.lastOrNull() ?: GALAXY_FIRST_ORBIT_RADIUS_DP
    val halfExtent = maxOf(
        outerRadius + GALAXY_ORBITER_WIDTH_DP / 2f,
        outerRadius + GALAXY_ORBITER_HEIGHT_DP / 2f
    ) + GALAXY_ORBIT_CONTENT_PADDING_DP
    val contentSizeDp = halfExtent * 2f
    val contentSizePx = contentSizeDp * density
    val fitAllScale = min(viewportWidthPx / contentSizePx, viewportHeightPx / contentSizePx)
        .coerceAtMost(1f)

    return GalaxyOrbitLayout(
        placements = placements,
        ringRadiiDp = ringRadii,
        contentSizeDp = contentSizeDp,
        contentSizePx = contentSizePx,
        fitAllScale = fitAllScale
    )
}

/** أكبر عدد نقاط على دائرة مع الحفاظ على حد أدنى لمسافة الوتر بين النقاط. */
internal fun maxItemsOnOrbit(radiusDp: Float): Int {
    require(radiusDp > 0f) { "Orbit radius must be positive" }
    if (radiusDp * 2f < GALAXY_MIN_ORBITER_SPACING_DP) return 1
    val angleHalf = asin((GALAXY_MIN_ORBITER_SPACING_DP / (2f * radiusDp)).coerceIn(0f, 1f))
    return floor((PI / angleHalf).toFloat()).toInt().coerceAtLeast(1)
}

/** زمن دورة كل مدار؛ المدارات الداخلية أسرع، والخارجية أبطأ حتى 120 ثانية. */
internal fun orbitDurationMillis(ringIndex: Int): Int {
    require(ringIndex >= 0) { "Orbit ring index cannot be negative" }
    return (32_000L + ringIndex.toLong() * 12_000L).coerceAtMost(120_000L).toInt()
}

/** تتناوب اتجاهات الدوران بين الحلقات لتكوين حركة كونية أكثر طبيعية. */
internal fun orbitDirectionDegrees(ringIndex: Int): Float {
    require(ringIndex >= 0) { "Orbit ring index cannot be negative" }
    return if (ringIndex % 2 == 0) 360f else -360f
}

/** قطر مرئي مناسب يتدرج بلطف بحسب عدد الملفات دون أن يصبح الكوكب ضخماً. */
internal fun folderPlanetSizeDp(fileCount: Int): Float =
    (28f + sqrt(fileCount.coerceAtLeast(0).toFloat()) * 1.2f).coerceIn(28f, 40f)
