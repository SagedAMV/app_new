package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * محرك تخطيط المجرة — الدوال النقية الخالصة التي كانت مدمجة داخل
 * [GalaxyScreen] (ملف يحمل اعتماديات Compose كلها)، فأعيد استخراجها هنا لتكون
 * قابلة للترجمة والاختبار على JVM مباشرة وبلا أي اعتماد على أندرويد، تماماً كما
 * هو حال [GalaxyGeometry]. السلوك مطابق حرفياً للنسخة السابقة — لا شيء تغيّر
 * سوى مكان التعريف؛ فالتخطيط والشاشة في نفس الحزمة ولا مستدعٍ خارجها.
 *
 * (جلسة التحقق وفق تعليمات.md: اختبارات الوحدة المحلية كانت تدّعي أن هذه
 * الدوال «تُختبر على JVM مباشرة» بينما وجودها في ملف Compose يجعل ذلك غير
 * ممكن فعلاً — الاستخراج يجعل الادعاء حقيقة.)
 */

/** ثوابت التخطيط — مقاسة بوحدات dp مستقلة ثم تُحوَّل للبكسل وقت الحساب */
internal const val GALAXY_GAP_DP = 14f
internal const val GALAXY_CONTENT_MARGIN_DP = 12f
internal const val GALAXY_MIN_CELL_DP = 104f
internal const val GALAXY_MAX_CELL_DP = 300f
internal const val GALAXY_MAX_CLUSTER_SCALE = 1.9f
internal const val GALAXY_ABSOLUTE_MIN_SCALE = 0.2f

/**
 * عنقود مجرّي واحد: المجلد الأم + مجلداته الفرعية.
 * المجلدات الفرعية تُعرض كواكب صغيرة مرتبطة بالكوكب الأم داخل دائرة حاضنة.
 */
data class GalaxyCluster(
    val parent: FolderWithFileCount,
    val children: List<FolderWithFileCount>
)

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
 *
 * العقد: القائمة غير فارغة — حالة «بلا مجلدات» تعالجها [GalaxyScreen] قبل
 * الوصول إلى هنا، و[require] يجعل العقد صريحاً بدل استثناء غامض من [max].
 */
internal fun computeGalaxyLayout(
    clusters: List<GalaxyCluster>,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    density: Float
): GalaxyLayout {
    require(clusters.isNotEmpty()) { "computeGalaxyLayout: قائمة العناقيد فارغة" }
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
 * بصمة عنقود واحد بوحدات dp المستقلة — تفويض كامل إلى [GalaxyGeometry]
 * حتى تبقى حسابات التحجيم هنا ورسم الدوائر في [FolderClusterView] على
 * مصدر حقيقة واحد (إصلاح جلسة اليوم: ازدواج الصيغة كان جذر انزياح
 * الدائرة الحاضنة عن محتواها).
 */
internal fun clusterFootprint(cluster: GalaxyCluster): Float {
    return GalaxyGeometry.footprintDp(
        childCount = cluster.children.size,
        maxChildFileCount = cluster.children.maxOfOrNull { it.fileCount } ?: 0,
        parentFileCount = cluster.parent.fileCount
    )
}
