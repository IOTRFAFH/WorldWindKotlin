package earth.worldwind.shape

import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.render.RenderContext
import kotlin.math.floor
import kotlin.math.max

/**
 * A geographic quadtree of [PlacemarkBatch] cells for one layer's placemarks. Cells follow the terrain tile pyramid
 * (90 degree top-level cells, halved per level) and split when they hold more than [MAX_CELL_ICONS] icons, so busy
 * areas get small cells and sparse areas large ones.
 *
 * Each frame, a placemark goes to the cell of its location: the cached cell while its latitude and longitude are
 * unchanged, otherwise a walk down the tree. The first member that reaches a cell in a frame decides whether the cell
 * is in view, from its bounding sphere padded by its largest icon (see [isInView]). Members of a cell out of view are
 * skipped without any per-placemark work: no point, level of detail, attributes or record. A cell keeps its icons and
 * buffers while out of view and draws them again when it comes back.
 *
 * A placemark that moves into a cell out of view clears that cell's bounds, so the cell is visited, and the
 * placemark drawn, from the next frame on.
 *
 * An index serves one window and one [offset] of its globe: a continuous 2D globe draws up to three copies of the map
 * in one frame, each with its own Cartesian points, so each copy has its own index (see [PlacemarkCellsByOffset]), and
 * each window its own set (see [PlacemarkCellsByWindow]). A placemark holds one slot per index, found by [slotKey].
 *
 * Indexes are used on the render thread only, as are the slot keys they share.
 */
internal class PlacemarkCells(val offset: Globe.Offset = Globe.Offset.Center) {
    /**
     * A quadtree node: a leaf with a [batch], or an inner node with [children], created as placemarks reach them.
     */
    internal class Node(val level: Int, val row: Int, val column: Int) {
        var batch: PlacemarkBatch? = null
        var children: Array<Node?>? = null
    }

    private val diag = PlacemarkBatchDiagnostics() // DIAGNOSTICS
    /**
     * This index's key into every placemark's batch slots ([Placemark.batchSlot]): unique among the indexes in use,
     * taken when the index first renders and given back by [clear]; -1 while the index holds no slot.
     */
    var slotKey = -1
        private set
    private var frame = 0L
    private var pickFrame = 0L
    private var isPickCulling = false
    private var roots = newRoots()
    private val batches = ArrayList<PlacemarkBatch>()
    private val visited = ArrayList<PlacemarkBatch>()
    private var terrain: BasicTerrain? = null
    private var globe: Globe? = null
    private var globeState: Globe.State? = null

    fun beginFrame(rc: RenderContext) {
        if (slotKey < 0) slotKey = acquireSlotKey()
        frame++
        terrain = rc.terrain as? BasicTerrain
        // Bounds of cells out of view hold points of the previous globe state: visit every cell again.
        if (rc.globe !== globe || rc.globeState != globeState) {
            globe = rc.globe
            globeState = rc.globeState
            for (i in batches.indices) batches[i].invalidateBounds()
        }
    }

    /**
     * Renders [placemark] through the cell of its location, or skips it when that cell is out of view.
     */
    fun render(rc: RenderContext, placemark: Placemark) {
        val slot = placemark.batchSlot(slotKey)
        val cached = slot?.batch
        val batch = if (cached != null && cached.cells === this && cached.node != null && slot.isAtLocation()) cached
        else leafFor(placemark.position.latitude.inDegrees, placemark.position.longitude.inDegrees)
        if (!isInView(rc, batch)) {
            // Moved in while the cell is out of view: its bounds do not include this placemark yet.
            if (batch !== cached) batch.invalidateBounds()
            return
        }
        placemark.renderBatched(rc, batch)
    }

    fun endFrame(rc: RenderContext) {
        for (i in visited.indices) {
            val batch = visited[i]
            batch.endFrame(rc)
            val node = batch.node
            if (node != null && batch.slotCount > MAX_CELL_ICONS && node.level < MAX_LEVEL) split(node, batch)
        }
        diag.cellsVisited += visited.size // DIAGNOSTICS
        visited.clear()
        logDiagnostics() // DIAGNOSTICS
    }

    /**
     * Detaches every placemark and drops every cell.
     */
    fun clear() {
        for (i in batches.indices) batches[i].clear()
        batches.clear()
        visited.clear()
        roots = newRoots()
        if (slotKey >= 0) releaseSlotKey(slotKey)
        slotKey = -1
    }

    /**
     * Starts a picking frame. Picking draws placemarks individually, in pick colors; the cells only skip the members
     * of cells outside the pick frustum, from the bounds the last drawn frame left. Batch state is not touched.
     */
    fun beginPick(rc: RenderContext) {
        pickFrame++
        // Bounds hold points of the drawn globe and globe state: on any other, skip no cell.
        isPickCulling = rc.globe === globe && rc.globeState == globeState
    }

    /**
     * Renders [placemark] individually for picking, unless its cell is outside the pick frustum. The test uses the
     * same padded bounds as drawing, so a placemark culled here was not drawn in the frame being picked either. A
     * placemark the cells cannot vouch for (not in a cell yet, moved since its point was cached, or picked by its
     * leader, which reaches outside the bounds) is always rendered.
     */
    fun pick(rc: RenderContext, placemark: Placemark) {
        val slot = if (slotKey < 0) null else placemark.batchSlot(slotKey)
        val batch = slot?.batch
        val canCull = isPickCulling && batch != null && batch.cells === this && batch.node != null &&
                slot.isAtLocation() && !placemark.isLeaderPickingEnabled
        if (!canCull || isInPickView(rc, batch!!)) placemark.render(rc)
    }

    private fun isInPickView(rc: RenderContext, batch: PlacemarkBatch): Boolean {
        if (batch.pickTestedFrame != pickFrame) {
            batch.pickTestedFrame = pickFrame
            batch.isInPickView = !batch.hasBounds || intersectsFrustum(rc, batch)
        }
        return batch.isInPickView
    }

    /**
     * Decides once per frame whether [batch] is in view, and starts its frame when it is. A cell without bounds (new,
     * or a member moved in) is always visited.
     */
    private fun isInView(rc: RenderContext, batch: PlacemarkBatch): Boolean {
        if (batch.testedFrame == frame) return batch.isVisibleThisFrame
        batch.testedFrame = frame
        val inView = !batch.hasBounds || intersectsFrustum(rc, batch)
        batch.isVisibleThisFrame = inView
        if (inView) {
            batch.beginFrame(rc, terrain)
            visited.add(batch)
        } else diag.cellsCulled++ // DIAGNOSTICS
        return inView
    }

    /**
     * Tests the cell's bounding sphere, padded by its largest icon at the far side of the sphere, against the frustum.
     * An icon is drawn in screen space around its point, so a point just outside the frustum can still show part of
     * its icon.
     */
    private fun intersectsFrustum(rc: RenderContext, batch: PlacemarkBatch): Boolean {
        val center = batch.center
        val farDistance = if (rc.globe.is2D) rc.viewingDistance else rc.cameraPoint.distanceTo(center) + batch.radius
        val radius = batch.radius + batch.maxIconPixels * rc.pixelSizeAtDistance(max(farDistance, 0.0))
        val planes = rc.frustum.planes
        for (i in planes.indices) if (planes[i].distanceToPoint(center) <= -radius) return false
        return true
    }

    /**
     * The leaf cell holding a location, creating the nodes on the way.
     */
    private fun leafFor(latitude: Double, longitude: Double): PlacemarkBatch {
        var node = roots[indexOf(latitude, longitude, 0, 0, 0, ROOT_ROWS, ROOT_COLUMNS)]!!
        while (true) {
            val children = node.children ?: break
            val level = node.level + 1
            val index = indexOf(latitude, longitude, level, node.row * 2, node.column * 2, 2, 2)
            node = children[index] ?: Node(level, node.row * 2 + index / 2, node.column * 2 + index % 2)
                .also { children[index] = it }
        }
        return node.batch ?: PlacemarkBatch(this, node, diag).also {
            node.batch = it
            batches.add(it)
        }
    }

    /**
     * Turns a leaf into an inner node and moves its members, with their cached points and records, into the child
     * cells; children still over [MAX_CELL_ICONS] split in turn. The new cells have no bounds, so they are visited
     * next frame. Members without a point yet are detached and placed by their next render.
     */
    private fun split(node: Node, batch: PlacemarkBatch) {
        node.batch = null
        node.children = arrayOfNulls(4)
        batch.node = null
        batches.remove(batch)
        batch.forEachSlot { slot -> if (slot.hasPoint) leafFor(slot.latitude, slot.longitude).adopt(slot) }
        batch.clear() // detaches only the members not adopted
        diag.splits++ // DIAGNOSTICS
        val children = node.children ?: return
        for (child in children) {
            val childBatch = child?.batch ?: continue
            if (childBatch.slotCount > MAX_CELL_ICONS && child.level < MAX_LEVEL) split(child, childBatch)
        }
    }

    private fun logDiagnostics() {
        if (++diag.frames < DIAG_FRAMES) return
        var slots = 0
        for (i in batches.indices) slots += batches[i].slotCount
        // println: c4ds's R8 rules strip Log.isLoggable, which silences Logger in release-based builds.
        println(
            "PlacemarkBatch frames=${diag.frames} cells=${batches.size} slots=$slots visited=${diag.cellsVisited} " +
                    "culled=${diag.cellsCulled} splits=${diag.splits} points=${diag.pointComputes} " +
                    "lodReused=${diag.lodReused} lodEvaluated=${diag.lodEvaluated} " +
                    "terrainDirty=${diag.terrainDirtyCells} terrainSets=${diag.terrainSetRebuilds} " +
                    "layouts=${diag.layouts} (runChanges=${diag.runChanges} evictions=${diag.evictions} " +
                    "globe=${diag.globeChanges}) dirtySlots=${diag.dirtySlots} uploads=${diag.uploads} " +
                    "(layout=${diag.layoutUploads} budget=${diag.budgetUploads} reupload=${diag.reuploads}) " +
                    "uploadAllocs=${diag.uploadAllocs}"
        )
        diag.reset()
    }

    companion object {
        /** Icons above which a cell splits into four. */
        const val MAX_CELL_ICONS = 2048
        /** Deepest level: 90 / 2^12 degrees, about 2.4 km of latitude. */
        const val MAX_LEVEL = 12
        private const val ROOT_ROWS = 2
        private const val ROOT_COLUMNS = 4
        private const val DIAG_FRAMES = 30 // DIAGNOSTICS

        private fun newRoots() = Array<Node?>(ROOT_ROWS * ROOT_COLUMNS) { Node(0, it / ROOT_COLUMNS, it % ROOT_COLUMNS) }

        /** Slot keys in use, by key. Render thread only. */
        private val usedSlotKeys = ArrayList<Boolean>()

        /**
         * Takes the lowest free slot key: key 0, which placemarks hold in a field rather than an array, goes to the
         * first index in use, normally the main window's center copy.
         */
        internal fun acquireSlotKey(): Int {
            val free = usedSlotKeys.indexOf(false)
            if (free >= 0) {
                usedSlotKeys[free] = true
                return free
            }
            usedSlotKeys.add(true)
            return usedSlotKeys.lastIndex
        }

        internal fun releaseSlotKey(key: Int) {
            usedSlotKeys[key] = false
        }

        /**
         * The index, within a [rows] by [columns] block starting at ([firstRow], [firstColumn]), of the cell of
         * [level] that holds a location. Rows count from the south, columns from the antimeridian.
         */
        internal fun indexOf(
            latitude: Double, longitude: Double, level: Int, firstRow: Int, firstColumn: Int, rows: Int, columns: Int
        ): Int {
            val delta = 90.0 / (1 shl level)
            val row = (floor((latitude + 90.0) / delta).toInt() - firstRow).coerceIn(0, rows - 1)
            val column = (floor((longitude + 180.0) / delta).toInt() - firstColumn).coerceIn(0, columns - 1)
            return row * columns + column
        }
    }
}
