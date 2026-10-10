package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** أقرب مسافة مقبولة بين مركزي مجلدين على الحلقة نفسها. */
internal const val GALAXY_FIRST_ORBIT_RADIUS_DP = 160f
internal const val GALAXY_ORBIT_RADIAL_STEP_DP = 132f
internal const val GALAXY_ORBITER_WIDTH_DP = 100f
internal const val GALAXY_ORBITER_HEIGHT_DP = 76f
internal const val GALAXY_MIN_ORBITER_SPACING_DP = 128f
internal const val GALAXY_ORBIT_CONTENT_PADDING_DP = 26f
internal const val GALAXY_MAX_ZOOM = 12f
internal const val GALAXY_INITIAL_COMFORT_SCALE = 0.72f
internal const val GALAXY_SHARED_ORBIT_DURATION_MILLIS = 90_000

/** موضع مجلد على حلقة تمثل مستوى عمقه داخل شجرة المجلدات. */
internal data class OrbitingFolder(
    val folder: FolderWithFileCount,
    val ringIndex: Int,
    val indexInRing: Int,
    val ringSize: Int,
    val radiusDp: Float,
    val startAngleRadians: Double,
    /** المعرّف الفعلي للأب في شجرة العرض؛ null للمجلد الجذري. */
    val parentFolderId: Long? = null
)

/** وصلة مرئية بين مجلد وأبيه المباشر. تُرسم خلف الكواكب كخيط رفيع. */
internal data class GalaxyFolderConnection(
    val parentFolderId: Long,
    val childFolderId: Long
)

/** نتيجة التخطيط كاملة، وتشمل علاقات الأبوة التي لا تظهر في تخطيط مداري مسطّح. */
internal data class GalaxyOrbitLayout(
    val placements: List<OrbitingFolder>,
    val ringRadiiDp: List<Float>,
    val connections: List<GalaxyFolderConnection>,
    val contentSizeDp: Float,
    val contentSizePx: Float,
    val fitAllScale: Float
)

private data class PendingFolder(
    val folder: FolderWithFileCount,
    val parentFolderId: Long?,
    val depth: Int,
    val sectorStart: Double,
    val sectorWidth: Double,
    val angle: Double
)

/**
 * تخطيط هرمي شعاعي:
 * - الجذور قريبة من شمس «جامعتي».
 * - كل مستوى من الأبناء أبعد من المستوى الذي يحتوي آباءه.
 * - يتقاسم الأبناء القطاع الزاوي لأبيهم كي تبقى كل عائلة متجاورة بصريًا.
 * - لكل ابن وصلة مرئية مباشرة إلى أبيه.
 * - جميع المستويات تستخدم زاوية دوران مشتركة في الشاشة، لذلك لا تنقلب
 *   اتجاهات المدارات ولا تنفصل الوصلات عن العلاقات الصحيحة أثناء الحركة.
 *
 * تُعالَج المراجع اليتيمة والدورات غير المتوقعة في parentId بتكوين جذور عرض
 * احتياطية؛ فلا تضيع مجلدات ولا يدخل التخطيط في حلقة لا نهائية إذا تلفت البيانات.
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

    // المفترض أن folderId فريد في قاعدة البيانات. إزالة التكرار هنا دفاع إضافي
    // يمنع كوكبين متراكبين في حال وصلت قائمة غير سليمة من مصدر آخر.
    val uniqueFolders = folders.distinctBy { it.folderId }
    val byId = uniqueFolders.associateBy { it.folderId }
    val childrenByParent = uniqueFolders
        .asSequence()
        .filter { folder ->
            val parentId = folder.parentId
            parentId != null && parentId != folder.folderId && parentId in byId
        }
        .groupBy { it.parentId }
        .mapValues { (_, children) -> children }

    // نبدأ بالجذور الطبيعية، ثم نضيف جذرًا احتياطيًا لكل مكوّن لا يمكن الوصول
    // إليه بسبب دورة في علاقات الأبوة. الحفاظ على ترتيب الإدخال مهم لاستقرار العرض.
    val roots = uniqueFolders.filter { folder ->
        folder.parentId == null || folder.parentId !in byId || folder.parentId == folder.folderId
    }.toMutableList()
    val discovered = HashSet<Long>(uniqueFolders.size)

    fun markReachable(start: FolderWithFileCount) {
        val stack = ArrayDeque<FolderWithFileCount>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!discovered.add(current.folderId)) continue
            childrenByParent[current.folderId].orEmpty().forEach(stack::addLast)
        }
    }

    roots.forEach(::markReachable)
    uniqueFolders.forEach { folder ->
        if (folder.folderId !in discovered) {
            roots += folder
            markReachable(folder)
        }
    }

    if (uniqueFolders.isEmpty()) {
        val emptySizeDp = (GALAXY_FIRST_ORBIT_RADIUS_DP + GALAXY_ORBIT_CONTENT_PADDING_DP) * 2f
        val emptySizePx = emptySizeDp * density
        return GalaxyOrbitLayout(
            placements = emptyList(),
            ringRadiiDp = emptyList(),
            connections = emptyList(),
            contentSizeDp = emptySizeDp,
            contentSizePx = emptySizePx,
            fitAllScale = minOf(viewportWidthPx / emptySizePx, viewportHeightPx / emptySizePx, 1f)
        )
    }

    val rootSectorWidth = 2.0 * PI / roots.size
    val pending = ArrayDeque<PendingFolder>()
    roots.asReversed().forEachIndexed { reversedIndex, folder ->
        val index = roots.lastIndex - reversedIndex
        val sectorStart = -PI / 2.0 - rootSectorWidth / 2.0 + index * rootSectorWidth
        pending.addLast(
            PendingFolder(
                folder = folder,
                parentFolderId = null,
                depth = 0,
                sectorStart = sectorStart,
                sectorWidth = rootSectorWidth,
                angle = sectorStart + rootSectorWidth / 2.0
            )
        )
    }

    val treePlacements = ArrayList<PendingFolder>(uniqueFolders.size)
    val assigned = HashSet<Long>(uniqueFolders.size)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (!assigned.add(current.folder.folderId)) continue
        treePlacements += current

        val children = childrenByParent[current.folder.folderId].orEmpty()
            .filter { it.folderId !in assigned }
        if (children.isEmpty()) continue

        val childSectorWidth = current.sectorWidth / children.size
        // الإضافة بالعكس مع إزالة آخر عنصر تضمن بقاء ترتيب المجلدات الأصلي عند السحب.
        children.asReversed().forEachIndexed { reversedChildIndex, child ->
            val childIndex = children.lastIndex - reversedChildIndex
            val sectorStart = current.sectorStart + childIndex * childSectorWidth
            pending.addLast(
                PendingFolder(
                    folder = child,
                    parentFolderId = current.folder.folderId,
                    depth = current.depth + 1,
                    sectorStart = sectorStart,
                    sectorWidth = childSectorWidth,
                    angle = sectorStart + childSectorWidth / 2.0
                )
            )
        }
    }

    // تحديد نصف قطر كل مستوى حسب أصغر فجوة زاوية فعلية بين عناصره؛ هذا يحافظ
    // على مسافة لمس آمنة حتى عندما تكون فروع الشجرة متفاوتة الاتساع.
    val maxDepth = treePlacements.maxOfOrNull { it.depth } ?: 0
    val placementsByDepth = treePlacements.groupBy { it.depth }
    val ringRadii = ArrayList<Float>(maxDepth + 1)
    var previousRadius = 0f
    for (depth in 0..maxDepth) {
        val level = placementsByDepth[depth].orEmpty()
        if (level.isEmpty()) continue
        val neededRadius = minimumRadiusForAngularSpacing(level.map { it.angle })
        val standardRadius = GALAXY_FIRST_ORBIT_RADIUS_DP + depth * GALAXY_ORBIT_RADIAL_STEP_DP
        val separatedRadius = if (depth == 0) standardRadius else previousRadius + GALAXY_ORBIT_RADIAL_STEP_DP
        val radius = max(max(standardRadius, separatedRadius), neededRadius)
        ringRadii += radius
        previousRadius = radius
    }

    val radiusByDepth = ringRadii
    val indexByFolderId = placementsByDepth.mapValues { (_, levelItems) ->
        levelItems.mapIndexed { index, item -> item.folder.folderId to index }.toMap()
    }
    val placements = treePlacements.map { item ->
        OrbitingFolder(
            folder = item.folder,
            ringIndex = item.depth,
            indexInRing = indexByFolderId[item.depth]?.get(item.folder.folderId) ?: 0,
            ringSize = placementsByDepth[item.depth].orEmpty().size,
            radiusDp = radiusByDepth[item.depth],
            startAngleRadians = item.angle,
            parentFolderId = item.parentFolderId
        )
    }
    val connections = placements.mapNotNull { item ->
        item.parentFolderId?.let { parentId ->
            GalaxyFolderConnection(parentFolderId = parentId, childFolderId = item.folder.folderId)
        }
    }

    val outerRadius = ringRadii.lastOrNull() ?: GALAXY_FIRST_ORBIT_RADIUS_DP
    val contentSizeDp = (outerRadius + max(GALAXY_ORBITER_WIDTH_DP, GALAXY_ORBITER_HEIGHT_DP) / 2f +
        GALAXY_ORBIT_CONTENT_PADDING_DP) * 2f
    val contentSizePx = contentSizeDp * density
    val fitAllScale = minOf(viewportWidthPx / contentSizePx, viewportHeightPx / contentSizePx, 1f)

    return GalaxyOrbitLayout(
        placements = placements,
        ringRadiiDp = ringRadii,
        connections = connections,
        contentSizeDp = contentSizeDp,
        contentSizePx = contentSizePx,
        fitAllScale = fitAllScale
    )
}

/** نصف القطر الذي يحفظ حدّ المسافة بين أقرب عنصرين على الحلقة. */
private fun minimumRadiusForAngularSpacing(angles: List<Double>): Float {
    if (angles.size <= 1) return GALAXY_FIRST_ORBIT_RADIUS_DP
    val fullTurn = 2.0 * PI
    val normalized = angles.map { angle -> ((angle % fullTurn) + fullTurn) % fullTurn }.sorted()
    var smallestGap = fullTurn
    for (index in normalized.indices) {
        val current = normalized[index]
        val next = if (index == normalized.lastIndex) normalized[0] + fullTurn else normalized[index + 1]
        smallestGap = minOf(smallestGap, abs(next - current))
    }
    // الحماية من قسمة هائلة إذا وصلت شجرة تالفة ذات عمق/تفرع اصطناعي ضخم.
    val safeGap = smallestGap.coerceAtLeast(1e-6)
    val denominator = 2.0 * sin(safeGap / 2.0)
    val radius = GALAXY_MIN_ORBITER_SPACING_DP / denominator
    return radius.coerceIn(GALAXY_FIRST_ORBIT_RADIUS_DP.toDouble(), 20_000_000.0).toFloat()
}

/** كل المستويات تدور في الاتجاه والسرعة الزاوية نفسيهما كي تبقى العائلات متماسكة. */
internal fun orbitDurationMillis(ringIndex: Int): Int {
    require(ringIndex >= 0) { "Orbit ring index cannot be negative" }
    return GALAXY_SHARED_ORBIT_DURATION_MILLIS
}

/** إشارة واحدة موجبة لكل المدارات؛ لا توجد حلقات تعكس اتجاهها بعد الآن. */
internal fun orbitDirectionDegrees(ringIndex: Int): Float {
    require(ringIndex >= 0) { "Orbit ring index cannot be negative" }
    return 360f
}

/** حجم الكوكب يتدرج بلطف بحسب عدد الملفات دون أن يصبح ضخماً. */
internal fun folderPlanetSizeDp(fileCount: Int): Float =
    (28f + sqrt(fileCount.coerceAtLeast(0).toFloat()) * 1.2f).coerceIn(28f, 40f)
