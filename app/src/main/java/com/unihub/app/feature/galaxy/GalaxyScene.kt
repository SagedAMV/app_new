package com.unihub.app.feature.galaxy

import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** World coordinates are dp relative to the university disk, never Android layout sizes. */
internal data class GalaxyPoint(val x: Double, val y: Double) {
    companion object { val ZERO = GalaxyPoint(0.0, 0.0) }
}

internal data class GalaxyRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun intersects(other: GalaxyRect): Boolean = left <= other.right && right >= other.left &&
        top <= other.bottom && bottom >= other.top
    fun expanded(margin: Double) = GalaxyRect(left - margin, top - margin, right + margin, bottom + margin)
    fun union(other: GalaxyRect) = GalaxyRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    /** Liang–Barsky clipping also retains crossing lines whose endpoints are both off-screen. */
    fun intersectsSegment(start: GalaxyPoint, end: GalaxyPoint): Boolean {
        var lower = 0.0
        var upper = 1.0
        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val ratio = q / p
            if (p < 0.0) lower = max(lower, ratio) else upper = min(upper, ratio)
            return lower <= upper
        }
        val dx = end.x - start.x
        val dy = end.y - start.y
        return clip(-dx, start.x - left) && clip(dx, right - start.x) &&
            clip(-dy, start.y - top) && clip(dy, bottom - start.y)
    }

    companion object {
        fun between(first: GalaxyPoint, second: GalaxyPoint) = GalaxyRect(
            min(first.x, second.x), min(first.y, second.y), max(first.x, second.x), max(first.y, second.y)
        )
    }
}

internal data class GalaxyCamera(val scale: Float = 1f, val focus: GalaxyPoint = GalaxyPoint.ZERO)

/** Only the real viewport is measured. Even a millions-of-dp world has a bounded canvas. */
internal data class GalaxyViewport(
    val widthPx: Float,
    val heightPx: Float,
    val density: Float,
    val contentSizeDp: Float,
    val camera: GalaxyCamera
) {
    init {
        require(widthPx.isFinite() && widthPx > 0f && heightPx.isFinite() && heightPx > 0f)
        require(density.isFinite() && density > 0f && contentSizeDp.isFinite() && contentSizeDp > 0f)
        require(camera.scale.isFinite() && camera.scale > 0f && camera.focus.x.isFinite() && camera.focus.y.isFinite())
    }
    val screenBounds get() = GalaxyRect(0.0, 0.0, widthPx.toDouble(), heightPx.toDouble())
    fun projection(rotationRadians: Double) = GalaxyProjection(this, rotationRadians)
    fun overviewCamera(): GalaxyCamera = GalaxyCamera(minOf(widthPx / (contentSizeDp * density),
        heightPx / (contentSizeDp * density), 1f))

    fun focusedCamera(rotatedWorldPoint: GalaxyPoint): GalaxyCamera = clampedCamera(
        GalaxyCamera(max(camera.scale, GALAXY_INITIAL_COMFORT_SCALE).coerceAtMost(GALAXY_MAX_ZOOM), rotatedWorldPoint)
    )

    fun transformed(centroid: GalaxyPoint, pan: GalaxyPoint, zoom: Float, minimumScale: Float): GalaxyCamera {
        if (!zoom.isFinite() || zoom <= 0f || !centroid.x.isFinite() || !centroid.y.isFinite() ||
            !pan.x.isFinite() || !pan.y.isFinite()) return camera
        val nextScale = (camera.scale.toDouble() * zoom).coerceIn(minimumScale.toDouble(), GALAXY_MAX_ZOOM.toDouble()).toFloat()
        val oldFactor = camera.scale.toDouble() * density
        val nextFactor = nextScale.toDouble() * density
        val cx = centroid.x - widthPx / 2.0
        val cy = centroid.y - heightPx / 2.0
        val nextFocus = GalaxyPoint(camera.focus.x + cx / oldFactor - (cx + pan.x) / nextFactor,
            camera.focus.y + cy / oldFactor - (cy + pan.y) / nextFactor)
        return clampedCamera(GalaxyCamera(nextScale, nextFocus))
    }

    fun clampedCamera(value: GalaxyCamera): GalaxyCamera {
        val factor = value.scale.toDouble() * density
        val halfWorld = contentSizeDp / 2.0
        val keepVisible = 56.0 * density
        val limitX = halfWorld + max(0.0, widthPx / 2.0 - keepVisible) / factor
        val limitY = halfWorld + max(0.0, heightPx / 2.0 - keepVisible) / factor
        return value.copy(focus = GalaxyPoint(value.focus.x.coerceIn(-limitX, limitX), value.focus.y.coerceIn(-limitY, limitY)))
    }
}

/** One shared rotation matrix per frame, rather than trigonometry for every endpoint. */
internal class GalaxyProjection(val viewport: GalaxyViewport, rotationRadians: Double) {
    private val cosine = cos(rotationRadians)
    private val sine = sin(rotationRadians)
    val factor = viewport.camera.scale.toDouble() * viewport.density
    fun rotated(point: GalaxyPoint) = GalaxyPoint(point.x * cosine - point.y * sine, point.x * sine + point.y * cosine)
    fun project(point: GalaxyPoint): GalaxyPoint {
        val rotated = rotated(point)
        return GalaxyPoint(viewport.widthPx / 2.0 + (rotated.x - viewport.camera.focus.x) * factor,
            viewport.heightPx / 2.0 + (rotated.y - viewport.camera.focus.y) * factor)
    }
    fun unproject(screen: GalaxyPoint): GalaxyPoint {
        val x = (screen.x - viewport.widthPx / 2.0) / factor + viewport.camera.focus.x
        val y = (screen.y - viewport.heightPx / 2.0) / factor + viewport.camera.focus.y
        return GalaxyPoint(x * cosine + y * sine, -x * sine + y * cosine)
    }
    fun worldBounds(screen: GalaxyRect = viewport.screenBounds): GalaxyRect {
        val corners = listOf(unproject(GalaxyPoint(screen.left, screen.top)), unproject(GalaxyPoint(screen.right, screen.top)),
            unproject(GalaxyPoint(screen.left, screen.bottom)), unproject(GalaxyPoint(screen.right, screen.bottom)))
        return GalaxyRect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
    fun nodeBounds(point: GalaxyPoint, planetDiameterDp: Float, labelHeightDp: Float): GalaxyRect {
        val center = project(point)
        val halfWidth = GALAXY_ORBITER_WIDTH_DP / 2.0 * factor
        val radius = planetDiameterDp / 2.0 * factor
        return GalaxyRect(center.x - halfWidth, center.y - radius, center.x + halfWidth,
            center.y + radius + labelHeightDp * factor)
    }
}

internal data class GalaxyDiskAnchor(val translation: GalaxyPoint, val pivot: GalaxyPoint, val scale: Float,
    val pivotFractionX: Float, val pivotFractionY: Float) {
    fun transform(localPoint: GalaxyPoint) = GalaxyPoint(translation.x + pivot.x + (localPoint.x - pivot.x) * scale,
        translation.y + pivot.y + (localPoint.y - pivot.y) * scale)
}

/** Anchor the disk, not its column: independent of label heights, RTL and zoom. */
internal fun galaxyDiskAnchor(center: GalaxyPoint, widthPx: Float, heightPx: Float, diameterPx: Float, scale: Float): GalaxyDiskAnchor {
    require(widthPx > 0f && heightPx > 0f)
    val pivot = GalaxyPoint(widthPx / 2.0, diameterPx / 2.0)
    return GalaxyDiskAnchor(GalaxyPoint(center.x - pivot.x, center.y - pivot.y), pivot, scale, .5f, diameterPx / (2f * heightPx))
}

internal data class GalaxySceneNode(val placement: OrbitingFolder, val position: GalaxyPoint)
internal data class GalaxySceneEdge(val connection: GalaxyFolderConnection, val start: GalaxyPoint, val end: GalaxyPoint)

internal class GalaxyScene(val layout: GalaxyOrbitLayout) {
    val nodes = layout.placements.map { item ->
        GalaxySceneNode(item, GalaxyPoint(item.radiusDp * cos(item.startAngleRadians), item.radiusDp * sin(item.startAngleRadians)))
    }
    private val nodesById = nodes.associateBy { it.placement.folder.folderId }
    val edges = layout.connections.map { edge -> GalaxySceneEdge(edge,
        nodesById.getValue(edge.parentFolderId).position, nodesById.getValue(edge.childFolderId).position) }
    private val nodeIndex = GalaxySpatialIndex(nodes.map { SpatialEntry(it, GalaxyRect.between(it.position, it.position)) })
    private val edgeIndex = GalaxySpatialIndex(edges.map { SpatialEntry(it, GalaxyRect.between(it.start, it.end)) })
    fun position(id: Long): GalaxyPoint = nodesById.getValue(id).position

    fun visibleFolders(projection: GalaxyProjection, labelHeightDp: Float): List<GalaxySceneNode> {
        val margin = max(GALAXY_ORBITER_WIDTH_DP / 2.0, 20.0 + labelHeightDp) * projection.factor
        return nodeIndex.query(projection.worldBounds(projection.viewport.screenBounds.expanded(margin))).filter { node ->
            projection.nodeBounds(node.position, folderPlanetSizeDp(node.placement.folder.fileCount), labelHeightDp)
                .intersects(projection.viewport.screenBounds)
        }
    }

    fun visibleEdges(projection: GalaxyProjection): List<GalaxySceneEdge> {
        val screen = projection.viewport.screenBounds.expanded(2.0 * projection.viewport.density)
        return edgeIndex.query(projection.worldBounds(screen)).filter { edge ->
            screen.intersectsSegment(projection.project(edge.start), projection.project(edge.end))
        }
    }

    fun nearestFolder(projection: GalaxyProjection, tap: GalaxyPoint, radiusPx: Double): GalaxySceneNode? {
        val search = GalaxyRect(tap.x - radiusPx, tap.y - radiusPx, tap.x + radiusPx, tap.y + radiusPx)
        return nodeIndex.query(projection.worldBounds(search)).map { node ->
            val position = projection.project(node.position)
            node to hypot(position.x - tap.x, position.y - tap.y)
        }.filter { it.second <= radiusPx }.minByOrNull { it.second }?.first
    }

    fun visibleRingIndices(projection: GalaxyProjection): IntRange {
        val center = projection.project(GalaxyPoint.ZERO)
        val screen = projection.viewport.screenBounds
        val dx = max(max(screen.left - center.x, 0.0), center.x - screen.right)
        val dy = max(max(screen.top - center.y, 0.0), center.y - screen.bottom)
        val nearest = hypot(dx, dy) / projection.factor
        val farthest = maxOf(hypot(center.x, center.y), hypot(center.x - screen.right, center.y),
            hypot(center.x, center.y - screen.bottom), hypot(center.x - screen.right, center.y - screen.bottom)) / projection.factor
        fun lowerBound(radius: Double, inclusive: Boolean): Int {
            var low = 0
            var high = layout.ringRadiiDp.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (layout.ringRadiiDp[middle] < radius || (!inclusive && layout.ringRadiiDp[middle].toDouble() == radius)) low = middle + 1
                else high = middle
            }
            return low
        }
        return lowerBound(nearest - 2.0, true) until lowerBound(farthest + 2.0, false)
    }
}

private data class SpatialEntry<T>(val value: T, val bounds: GalaxyRect)

/** Balanced bounds tree: construction is cached; per-frame queries visit only relevant branches. */
private class GalaxySpatialIndex<T>(entries: List<SpatialEntry<T>>) {
    private class Branch<T>(val bounds: GalaxyRect, val entries: List<SpatialEntry<T>>,
        val left: Branch<T>? = null, val right: Branch<T>? = null)
    private fun build(entries: List<SpatialEntry<T>>): Branch<T>? {
        if (entries.isEmpty()) return null
        val bounds = entries.map { it.bounds }.reduce { first, second -> first.union(second) }
        if (entries.size <= 8) return Branch(bounds, entries)
        val horizontal = bounds.right - bounds.left >= bounds.bottom - bounds.top
        val sorted = entries.sortedBy { if (horizontal) (it.bounds.left + it.bounds.right) / 2.0 else (it.bounds.top + it.bounds.bottom) / 2.0 }
        val middle = sorted.size / 2
        return Branch(bounds, emptyList(), build(sorted.subList(0, middle)), build(sorted.subList(middle, sorted.size)))
    }
    private val root = build(entries)
    fun query(bounds: GalaxyRect): List<T> {
        val result = ArrayList<T>()
        val pending = ArrayDeque<Branch<T>>()
        root?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            val branch = pending.removeLast()
            if (!branch.bounds.intersects(bounds)) continue
            branch.entries.forEach { if (it.bounds.intersects(bounds)) result += it.value }
            branch.left?.let(pending::addLast)
            branch.right?.let(pending::addLast)
        }
        return result
    }
}

/** Monotonic time; no restarting animation clock can teleport particles every 50 seconds. */
internal fun galaxyDustProgress(elapsedSeconds: Double, speed: Float, phase: Float): Double =
    ((elapsedSeconds * speed / 50.0 + phase) % 1.0 + 1.0) % 1.0
