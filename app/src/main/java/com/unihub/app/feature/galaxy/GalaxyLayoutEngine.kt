package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal const val GALAXY_FIRST_ORBIT_RADIUS_DP = 160f
internal const val GALAXY_ORBIT_RADIAL_STEP_DP = 132f
internal const val GALAXY_ORBITER_WIDTH_DP = 100f
internal const val GALAXY_ORBITER_HEIGHT_DP = 76f
internal const val GALAXY_MIN_ORBITER_SPACING_DP = 128f
internal const val GALAXY_ORBIT_CONTENT_PADDING_DP = 26f
internal const val GALAXY_MAX_ZOOM = 12f
internal const val GALAXY_INITIAL_COMFORT_SCALE = 0.72f
internal const val GALAXY_SHARED_ORBIT_DURATION_MILLIS = 90_000

internal data class OrbitingFolder(
    val folder: FolderWithFileCount,
    val ringIndex: Int,
    val indexInRing: Int,
    val ringSize: Int,
    val radiusDp: Float,
    val startAngleRadians: Double,
    /** الأب في شجرة العرض بعد معالجة الدورات والمراجع المفقودة. */
    val parentFolderId: Long? = null
)

internal data class GalaxyFolderConnection(val parentFolderId: Long, val childFolderId: Long)

/** أبعاد عالم افتراضي فقط؛ لا يجوز استخدامها لقياس عنصر Compose بحجم العالم. */
internal data class GalaxyOrbitLayout(
    val placements: List<OrbitingFolder>,
    val ringRadiiDp: List<Float>,
    val connections: List<GalaxyFolderConnection>,
    val contentSizeDp: Float,
    val contentSizePx: Float,
    val fitAllScale: Float
)

private data class TreeFolder(val folder: FolderWithFileCount, val parentId: Long?, val depth: Int)

/**
 * كل نهاية في الشجرة تحصل على مقعد زاوي واحد. يشغل كل أب مجموع مقاعد عائلته،
 * لذلك تبقى العائلات متجاورة دون تقسيم القطاع بالتساوي مراراً وتضييقه أُسياً.
 * العبور وحساب الأوزان تكراريان: حتى الأشجار العميقة لا تستهلك مكدس الاستدعاء.
 */
internal fun computeGalaxyOrbitLayout(
    folders: List<FolderWithFileCount>,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    density: Float
): GalaxyOrbitLayout {
    require(viewportWidthPx.isFinite() && viewportHeightPx.isFinite() &&
        viewportWidthPx > 0f && viewportHeightPx > 0f) { "Galaxy viewport dimensions must be finite and positive" }
    require(density.isFinite() && density > 0f) { "Galaxy density must be finite and positive" }

    val uniqueFolders = folders.distinctBy { it.folderId }
    if (uniqueFolders.isEmpty()) {
        val sizeDp = (GALAXY_FIRST_ORBIT_RADIUS_DP + GALAXY_ORBIT_CONTENT_PADDING_DP) * 2f
        val sizePx = sizeDp * density
        return GalaxyOrbitLayout(emptyList(), emptyList(), emptyList(), sizeDp, sizePx,
            minOf(viewportWidthPx / sizePx, viewportHeightPx / sizePx, 1f))
    }

    val byId = uniqueFolders.associateBy { it.folderId }
    val childrenByParent = uniqueFolders.filter { folder ->
        val parentId = folder.parentId
        parentId != null && parentId != folder.folderId && parentId in byId
    }.groupBy { it.parentId }
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

    // نبني أولاً غابة صحيحة ونقطع روابط الدورات قبل حساب أوزان العائلات.
    val pending = ArrayDeque<TreeFolder>()
    roots.asReversed().forEach { pending.addLast(TreeFolder(it, null, 0)) }
    val tree = ArrayList<TreeFolder>(uniqueFolders.size)
    val assigned = HashSet<Long>(uniqueFolders.size)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (!assigned.add(current.folder.folderId)) continue
        tree += current
        childrenByParent[current.folder.folderId].orEmpty().asReversed().forEach { child ->
            if (child.folderId !in assigned) pending.addLast(TreeFolder(child, current.folder.folderId, current.depth + 1))
        }
    }
    val canonicalChildren = tree.filter { it.parentId != null }.groupBy { it.parentId }
    val canonicalRoots = tree.filter { it.parentId == null }
    val leafCounts = HashMap<Long, Int>(tree.size)
    tree.asReversed().forEach { item ->
        val count = leafCounts[item.folder.folderId] ?: 1
        leafCounts[item.folder.folderId] = count
        item.parentId?.let { parent -> leafCounts[parent] = (leafCounts[parent] ?: 0) + count }
    }
    val totalLeaves = canonicalRoots.sumOf { leafCounts.getValue(it.folder.folderId) }
    val leafStarts = HashMap<Long, Int>(tree.size)
    var cursor = 0
    canonicalRoots.forEach { root ->
        leafStarts[root.folder.folderId] = cursor
        cursor += leafCounts.getValue(root.folder.folderId)
    }
    tree.forEach { item ->
        var childCursor = leafStarts.getValue(item.folder.folderId)
        canonicalChildren[item.folder.folderId].orEmpty().forEach { child ->
            leafStarts[child.folder.folderId] = childCursor
            childCursor += leafCounts.getValue(child.folder.folderId)
        }
    }
    // الحساب من المقعد الصحيح مباشرةً يمنع تراكم أخطاء القسمة في آلاف المستويات.
    val anglePerLeaf = 2.0 * PI / totalLeaves
    val origin = -PI / 2.0 - leafCounts.getValue(canonicalRoots.first().folder.folderId) * anglePerLeaf / 2.0
    val angles = tree.associate { item ->
        val id = item.folder.folderId
        id to (origin + (leafStarts.getValue(id) + leafCounts.getValue(id) / 2.0) * anglePerLeaf)
    }
    val byDepth = tree.groupBy { it.depth }
    val maxDepth = tree.maxOf { it.depth }
    val ringRadii = ArrayList<Float>(maxDepth + 1)
    var previousRadius = 0f
    for (depth in 0..maxDepth) {
        val level = byDepth.getValue(depth)
        val needed = minimumRadiusForAngularSpacing(level.map { angles.getValue(it.folder.folderId) })
        val standard = GALAXY_FIRST_ORBIT_RADIUS_DP + depth * GALAXY_ORBIT_RADIAL_STEP_DP
        val separated = if (depth == 0) standard else previousRadius + GALAXY_ORBIT_RADIAL_STEP_DP
        val radius = max(max(standard, separated), needed)
        ringRadii += radius
        previousRadius = radius
    }
    val ringIndices = HashMap<Long, Int>(tree.size)
    byDepth.values.forEach { level -> level.forEachIndexed { index, item -> ringIndices[item.folder.folderId] = index } }
    val placements = tree.map { item ->
        OrbitingFolder(item.folder, item.depth, ringIndices.getValue(item.folder.folderId),
            byDepth.getValue(item.depth).size, ringRadii[item.depth], angles.getValue(item.folder.folderId), item.parentId)
    }
    val connections = placements.mapNotNull { item ->
        item.parentFolderId?.let { GalaxyFolderConnection(it, item.folder.folderId) }
    }
    val contentSizeDp = (ringRadii.last() + max(GALAXY_ORBITER_WIDTH_DP, GALAXY_ORBITER_HEIGHT_DP) / 2f +
        GALAXY_ORBIT_CONTENT_PADDING_DP) * 2f
    val contentSizePx = contentSizeDp * density
    return GalaxyOrbitLayout(placements, ringRadii, connections, contentSizeDp, contentSizePx,
        minOf(viewportWidthPx / contentSizePx, viewportHeightPx / contentSizePx, 1f))
}

private fun minimumRadiusForAngularSpacing(angles: List<Double>): Float {
    if (angles.size <= 1) return GALAXY_FIRST_ORBIT_RADIUS_DP
    val fullTurn = 2.0 * PI
    val normalized = angles.map { ((it % fullTurn) + fullTurn) % fullTurn }.sorted()
    val smallestGap = normalized.indices.minOf { index ->
        val next = if (index == normalized.lastIndex) normalized.first() + fullTurn else normalized[index + 1]
        next - normalized[index]
    }
    check(smallestGap > 0.0) { "Distinct leaf seats must have distinct angles" }
    val radius = max(GALAXY_FIRST_ORBIT_RADIUS_DP.toDouble(),
        GALAXY_MIN_ORBITER_SPACING_DP / (2.0 * sin(smallestGap / 2.0)))
    // التقريب للأعلى يحافظ على المسافة المطلوبة؛ لا سقف يُخفي التداخل بصمت.
    return Math.nextUp(radius.toFloat())
}

internal fun orbitDurationMillis(ringIndex: Int): Int {
    require(ringIndex >= 0) { "Orbit ring index cannot be negative" }
    return GALAXY_SHARED_ORBIT_DURATION_MILLIS
}

internal fun orbitDirectionDegrees(ringIndex: Int): Float {
    require(ringIndex >= 0) { "Orbit ring index cannot be negative" }
    return 360f
}

internal fun folderPlanetSizeDp(fileCount: Int): Float =
    (28f + sqrt(fileCount.coerceAtLeast(0).toFloat()) * 1.2f).coerceIn(28f, 40f)
