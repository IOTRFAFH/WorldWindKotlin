package earth.worldwind.shape

import earth.worldwind.draw.DrawablePlacemarkBatch
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.globe.terrain.TerrainTile
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.program.PlacemarkBatchProgram
import earth.worldwind.util.NumericArray
import earth.worldwind.util.SynchronizedPool
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws the icons of the [Placemark]s in one spatial cell of a [PlacemarkCells] index from a few vertex buffers, with
 * one draw call per texture and chunk, instead of one drawable, one matrix and one draw call per icon. Uses OpenGL ES
 * 2.0 features only.
 *
 * Each placemark's work is split by how often its inputs change:
 * - **When its data changes**: the Cartesian point and the packed instance record (size, offset, rotation, color,
 *   scaling), written into its chunk only when they differ from the stored ones. A point that depends on the terrain
 *   is recomputed only when the terrain tile it was read from leaves the terrain or changes its points (see
 *   [resolvePlacePoint]); the cell checks its few tiles once per frame and skips the per-icon checks when none changed.
 * - **Every frame the cell is in view**: the level-of-detail selector and the comparison against the stored record.
 *   Projection, pixel offset, rotation and tilt, eye-distance scaling, billboarding and depth offset run in the vertex
 *   shader ([PlacemarkBatchProgram]).
 *
 * The cell keeps a bounding sphere of its icons' points and the largest icon size, which [PlacemarkCells] tests against
 * the frustum before touching any member. Points are stored relative to the cell's origin, which keeps float precision.
 *
 * Icons are quads of 4 vertices in a compact 44-byte vertex format ([DrawablePlacemarkBatch.STRIDE]), indexed by a
 * 16-bit index buffer shared by every cell, in chunks of up to [CHUNK_QUADS] icons. A change re-uploads only its chunk,
 * with at most one upload in flight per chunk, so memory stays bounded however far the GL thread lags.
 *
 * Placemarks that cannot be drawn as a plain icon this frame (see [Placemark.canDrawBatched]) are drawn individually,
 * and their batched icon is hidden. Picking frames bypass the batch. A batch serves one [RenderContext] (one window).
 */
internal class PlacemarkBatch(
    /** The index this cell belongs to. */
    val cells: PlacemarkCells,
    /** The index node holding this cell, null once the cell was split and retired. */
    var node: PlacemarkCells.Node?,
    private val diag: PlacemarkBatchDiagnostics
) {
    /**
     * A placemark's state in the batch: its cached place point and the packed record last written to its chunk.
     */
    internal class Slot(val batch: PlacemarkBatch, val placemark: Placemark, val refreshPhase: Int) {
        var seenFrame = -1L
        /** This slot's quad in the batch (chunk [quad] / [CHUNK_QUADS]), or -1 when not placed. */
        var quad = -1
        var run: Run? = null
        /** The image source and depth test this slot's run was chosen for: identity checks skip the run lookup. */
        var imageSource: ImageSource? = null
        var isDepthTest = true
        /** Whether the icon was written visible rather than hidden. */
        var isVisible = false
        var isQueued = false
        var hasPoint = false
        var latitude = 0.0
        var longitude = 0.0
        var altitude = 0.0
        var altitudeMode = AltitudeMode.ABSOLUTE
        /** Whether [point] depends on the terrain. */
        var isTerrainDependent = false
        /**
         * [TerrainTile.pointsVersion] of the tile mesh [point] was read from, or 0 when it came from the elevation
         * model fallback. Only the number, so a slot never keeps an evicted tile alive.
         */
        var terrainVersion = 0L
        /** Batch frame in which [point] last changed. */
        var pointFrame = -1L
        // The last full evaluation (level of detail, attributes, record), reused while its inputs hold: see reuseFrameState.
        var lodState = LOD_NONE
        var lodDistance = 0.0
        var lodHighlighted = false
        var lodAttributes: PlacemarkAttributes? = null
        var lodHighlightAttributes: PlacemarkAttributes? = null
        /** The texture [record] was packed with: a texture that finishes loading changes the icon size. */
        var recordTexture: Texture? = null
        /** The icon's screen extent from its point, in pixels, for the cell bounds. */
        var iconPixels = 0.0
        val point = Vec3()
        val normal = Vec3()
        val record = IntArray(RECORD_INTS)

        /** Whether [placemark] is still at the geographic location this slot was placed for. */
        fun isAtLocation(): Boolean {
            val position = placemark.position
            return hasPoint && position.latitude.inDegrees == latitude && position.longitude.inDegrees == longitude
        }
    }

    /**
     * The icons sharing one image source and depth test state: drawn with one texture.
     */
    internal class Run(val imageSource: ImageSource, val isDepthTest: Boolean) {
        val slots = ArrayList<Slot>()
        var texture: Texture? = null
        var firstQuad = 0
        var visibleFrame = -1L
        var retrieveFrame = -1L
    }

    /**
     * A vertex buffer holding up to [CHUNK_QUADS] icons, uploaded as a whole when any of them changes.
     */
    internal class Chunk {
        /** Vertex data, sized to [quadCount] rounded up to [CHUNK_GROWTH_QUADS]. */
        var data = IntArray(0)
        val bufferKey = Any()
        var quadCount = 0
        var version = 0
        var isDirty = false
        /** Set by a layout: the chunk must upload this frame, whatever the upload budget, to match the new runs. */
        var isLayoutDirty = false
        /** Set while an upload of this chunk is queued; cleared on the GL thread once uploaded. */
        @Volatile var isUploading = false
        var uploadFrame = 0L
        var builtVersion = -1 // DIAGNOSTICS
    }

    private var frame = 0L
    private var slotCounter = 0
    private val slots = ArrayList<Slot>()
    private val runs = ArrayList<Run>()
    private val depthTestRuns = HashMap<ImageSource, Run>()
    private val noDepthTestRuns = HashMap<ImageSource, Run>()
    private val chunks = ArrayList<Chunk>()
    private var quadCount = 0
    private val dirtySlots = ArrayList<Slot>()
    private var isLayoutDirty = false
    private var globeState: Globe.State? = null
    private var globe: Globe? = null
    private var hasOrigin = false
    private val origin = Vec3()
    private val scratchPoint = Vec3()
    private val scratchRecord = IntArray(RECORD_INTS)
    private val scratchOffset = Vec2()
    /** Upload copies of chunk data, returned once the GL thread has uploaded them. */
    private val uploadPool = SynchronizedPool<IntArray>()
    private var uploadBudget = 0
    private var uploadCursor = 0

    // Terrain dependency: the distinct tile mesh versions the members' points were read from.
    private var terrain: BasicTerrain? = null
    private var terrainVersions = LongArray(8)
    private var terrainVersionCount = 0
    /** Members whose terrain-dependent point came from the elevation model fallback, refreshed periodically. */
    private var fallbackCount = 0
    /** Whether [terrainVersions] must be rebuilt from the members at the end of the frame. */
    private var isTerrainSetDirty = false
    /** Whether no member's terrain tile changed since last frame: terrain-dependent points stay as they are. */
    private var isTerrainClean = false

    // Bounds of the members' points, accumulated while they are resolved and kept while the cell is out of view.
    /** Whether [center], [radius] and [maxIconPixels] describe the members: false until a full visit. */
    var hasBounds = false
        private set
    val center = Vec3()
    var radius = 0.0
        private set
    /** Largest screen extent of a member's icon from its point, in pixels, including a label margin when drawn. */
    var maxIconPixels = 0.0
        private set
    private var frameMinX = 0.0
    private var frameMinY = 0.0
    private var frameMinZ = 0.0
    private var frameMaxX = 0.0
    private var frameMaxY = 0.0
    private var frameMaxZ = 0.0
    private var framePoints = 0
    private var frameIconPixels = 0.0
    private var frameDrewIndividually = false
    // Squared camera distances that order this frame's drawables among the others (see endFrame): the farthest
    // visible depth-tested icon, and the nearest visible icon drawn without the depth test.
    private var frameFarthestDepthTestedSq = 0.0
    private var frameNearestOverlaySq = 0.0
    /** Chunk buffers fetched this frame, shared by the frame's drawables. */
    private var frameChunkBuffers = arrayOfNulls<BufferObject>(4)

    /** Frame of [PlacemarkCells] in which this cell's visibility was last decided, and the decision. */
    var testedFrame = -1L
    var isVisibleThisFrame = false
    /** Pick of [PlacemarkCells] in which this cell was last tested against the pick frustum, and the outcome. */
    var pickTestedFrame = -1L
    var isInPickView = false

    val slotCount get() = slots.size

    fun beginFrame(rc: RenderContext, terrain: BasicTerrain?) {
        frame++
        // A different globe or globe state (2D/3D, projection) moves every point: recompute them all.
        if (rc.globe !== globe || rc.globeState != globeState) {
            globe = rc.globe
            globeState = rc.globeState
            for (i in slots.indices) slots[i].hasPoint = false
            hasOrigin = false
            isLayoutDirty = true
            terrainVersionCount = 0
            fallbackCount = 0
            diag.globeChanges++ // DIAGNOSTICS
        }
        this.terrain = terrain
        isTerrainClean = terrain != null && fallbackCount == 0 && !isTerrainSetDirty && areTerrainTilesCurrent(terrain)
        if (!isTerrainClean) diag.terrainDirtyCells++ // DIAGNOSTICS
        framePoints = 0
        frameIconPixels = 0.0
        frameDrewIndividually = false
        frameFarthestDepthTestedSq = -1.0
        frameNearestOverlaySq = Double.MAX_VALUE
        // Pick up textures that finished loading or were evicted since the last frame.
        for (i in runs.indices) {
            val run = runs[i]
            val texture = rc.getTexture(run.imageSource, null, false)
            if (texture !== run.texture) run.texture = texture // records see the new size in update()
        }
    }

    private fun areTerrainTilesCurrent(terrain: BasicTerrain): Boolean {
        for (i in 0 until terrainVersionCount) if (!terrain.hasTileVersion(terrainVersions[i])) return false
        return true
    }

    /**
     * Forgets the bounds, so the cell is visited next frame: a member moved in while it was out of view.
     */
    fun invalidateBounds() {
        hasBounds = false
    }

    /**
     * Writes [placemark]'s place point into [result], from the cache when neither its position nor the terrain under
     * it changed. A terrain-dependent point is recomputed when the tile covering it has a different mesh version;
     * one that came from the elevation model fallback (no tile under it) is refreshed every [TERRAIN_REFRESH_PERIOD]
     * frames, staggered across placemarks.
     */
    fun resolvePlacePoint(rc: RenderContext, placemark: Placemark, result: Vec3) {
        val slot = slotFor(placemark)
        val position = placemark.position
        val latitude = position.latitude.inDegrees
        val longitude = position.longitude.inDegrees
        val altitude = position.altitude
        val moved = !slot.hasPoint || latitude != slot.latitude || longitude != slot.longitude ||
                altitude != slot.altitude || placemark.altitudeMode != slot.altitudeMode
        val refresh = !moved && slot.isTerrainDependent && !isTerrainClean && isTerrainChanged(slot)
        if (moved || refresh) {
            placemark.computePlacePoint(rc, scratchPoint)
            diag.pointComputes++ // DIAGNOSTICS
            val isTerrainDependent = placemark.isTerrainDependent(rc)
            // The tile computePlacePoint interpolated: BasicTerrain.surfacePoint reads the first one covering the point
            val version = if (isTerrainDependent) terrainVersionAt(placemark) else 0L
            if (isTerrainDependent != slot.isTerrainDependent || version != slot.terrainVersion) {
                slot.isTerrainDependent = isTerrainDependent
                slot.terrainVersion = version
                isTerrainSetDirty = true
            }
            // A refresh follows terrain detail changes while the camera moves: rewrite the icon only when its point
            // moved by more than a quarter pixel at its distance, which a refresh at country scale rarely does.
            if (moved || isVisibleShift(rc, slot.point, scratchPoint)) {
                slot.point.copy(scratchPoint)
                slot.pointFrame = frame
                queue(slot)
            }
            if (moved) {
                placemark.computeSurfaceNormal(rc, slot.normal)
                slot.latitude = latitude
                slot.longitude = longitude
                slot.altitude = altitude
                slot.altitudeMode = placemark.altitudeMode
                slot.hasPoint = true
            }
        }
        includeInBounds(slot.point)
        result.copy(slot.point)
    }

    private fun isTerrainChanged(slot: Slot): Boolean {
        if (slot.terrainVersion == 0L) return (frame + slot.refreshPhase) % TERRAIN_REFRESH_PERIOD == 0L
        if (terrain == null) return true
        return terrainVersionAt(slot.placemark) != slot.terrainVersion
    }

    /** [TerrainTile.pointsVersion] of the tile covering [placemark]'s position, or 0 when no tile covers it. */
    private fun terrainVersionAt(placemark: Placemark): Long {
        val position = placemark.position
        return terrain?.surfaceTile(position.latitude, position.longitude)?.pointsVersion ?: 0L
    }

    private fun includeInBounds(point: Vec3) {
        if (framePoints == 0) {
            frameMinX = point.x; frameMaxX = point.x
            frameMinY = point.y; frameMaxY = point.y
            frameMinZ = point.z; frameMaxZ = point.z
        } else {
            if (point.x < frameMinX) frameMinX = point.x else if (point.x > frameMaxX) frameMaxX = point.x
            if (point.y < frameMinY) frameMinY = point.y else if (point.y > frameMaxY) frameMaxY = point.y
            if (point.z < frameMinZ) frameMinZ = point.z else if (point.z > frameMaxZ) frameMaxZ = point.z
        }
        framePoints++
    }

    private fun isVisibleShift(rc: RenderContext, from: Vec3, to: Vec3): Boolean {
        val distance = if (rc.globe.is2D) rc.viewingDistance else rc.cameraPoint.distanceTo(to)
        val threshold = MIN_POINT_SHIFT_PIXELS * rc.pixelSizeAtDistance(distance)
        return from.distanceToSquared(to) > threshold * threshold
    }

    /**
     * Hides [placemark]'s batched icon this frame: not drawn by the level-of-detail selector, or drawn individually.
     */
    fun hide(placemark: Placemark) {
        val slot = slotFor(placemark)
        if (slot.isVisible) {
            slot.isVisible = false
            queue(slot)
        }
    }

    /**
     * Keeps [placemark]'s last evaluated state this frame, without running its level-of-detail selector, attribute
     * selection or record packing, when nothing that state depends on has changed: its point, its highlight state and
     * attribute objects, its texture, and its camera distance within [LOD_DISTANCE_TOLERANCE]. Every
     * [LOD_REFRESH_PERIOD]th visited frame, staggered across placemarks, it is evaluated anyway, which picks up
     * in-place changes the checks cannot see (selector inputs such as model visibility rules, time-based alpha, AR
     * mode). Placemarks drawn individually (label or leader) are always evaluated: their drawables are per frame.
     *
     * @return true when the last state was kept and the caller must skip the evaluation
     */
    fun reuseFrameState(rc: RenderContext, placemark: Placemark): Boolean {
        val slot = slotFor(placemark)
        val state = slot.lodState
        if (state != LOD_BATCHED && state != LOD_HIDDEN) return false
        if (slot.pointFrame == frame || (frame + slot.refreshPhase) % LOD_REFRESH_PERIOD == 0L) return false
        if (placemark.isHighlighted != slot.lodHighlighted || placemark.attributes !== slot.lodAttributes ||
            placemark.highlightAttributes !== slot.lodHighlightAttributes) return false
        val run = slot.run
        if (state == LOD_BATCHED && (run == null || run.texture !== slot.recordTexture)) return false
        val distance = cameraDistance(rc, slot.point)
        if (abs(distance - slot.lodDistance) > LOD_DISTANCE_TOLERANCE * slot.lodDistance) return false
        if (state == LOD_BATCHED && slot.isVisible) {
            run!!.visibleFrame = frame
            if (slot.iconPixels > frameIconPixels) frameIconPixels = slot.iconPixels
            includeInOrder(slot.isDepthTest, distance * distance)
        }
        diag.lodReused++ // DIAGNOSTICS
        return true
    }

    /**
     * Records the outcome of a full evaluation of [placemark] this frame: hidden by the level-of-detail selector,
     * drawn as a batched icon, or drawn individually. [reuseFrameState] keeps it while its inputs hold.
     */
    fun recordFrameState(rc: RenderContext, placemark: Placemark, state: Int) {
        val slot = slotFor(placemark)
        slot.lodState = state
        slot.lodDistance = cameraDistance(rc, slot.point)
        slot.lodHighlighted = placemark.isHighlighted
        slot.lodAttributes = placemark.attributes
        slot.lodHighlightAttributes = placemark.highlightAttributes
        diag.lodEvaluated++ // DIAGNOSTICS
    }

    private fun cameraDistance(rc: RenderContext, point: Vec3) =
        if (rc.globe.is2D) rc.viewingDistance else rc.cameraPoint.distanceTo(point)

    /** The squared camera distance Placemark orders its own drawables by. */
    private fun cameraDistanceSq(rc: RenderContext, point: Vec3) =
        if (rc.globe.is2D) rc.viewingDistance * rc.viewingDistance else rc.cameraPoint.distanceToSquared(point)

    /** Takes a visible icon's squared camera distance into this frame's drawable order. */
    private fun includeInOrder(isDepthTest: Boolean, distanceSq: Double) {
        if (isDepthTest) {
            if (distanceSq > frameFarthestDepthTestedSq) frameFarthestDepthTestedSq = distanceSq
        } else if (distanceSq < frameNearestOverlaySq) frameNearestOverlaySq = distanceSq
    }

    /**
     * Notes that a member was drawn individually this frame (label or leader): culling then allows a label margin.
     */
    fun markDrawnIndividually() {
        frameDrewIndividually = true
    }

    /**
     * Shows [placemark]'s icon with this frame's [attributes], rewriting its record when anything changed.
     */
    fun update(rc: RenderContext, placemark: Placemark, attributes: PlacemarkAttributes) {
        val slot = slotFor(placemark)
        val imageSource = attributes.imageSource ?: return hide(placemark)
        val isDepthTest = attributes.isDepthTest
        var run = slot.run
        // Runs are keyed by image source equality: placemarks often hold equal but distinct sources, so compare the
        // slot's own source by identity first and move the slot only when the run actually changes.
        if (run == null || slot.imageSource !== imageSource || slot.isDepthTest != isDepthTest) {
            slot.imageSource = imageSource
            slot.isDepthTest = isDepthTest
            val newRun = runFor(rc, imageSource, isDepthTest)
            if (newRun !== run) {
                diag.runChanges++ // DIAGNOSTICS
                slot.run = newRun
                isLayoutDirty = true
            }
            run = newRun
        }
        run.visibleFrame = frame

        // Start loading the texture when the icon is probably in view, as Placemark does.
        if (run.texture == null && run.retrieveFrame != frame && rc.frustum.containsPoint(slot.point)) {
            run.retrieveFrame = frame
            run.texture = rc.getTexture(imageSource, null, true)
        }

        // Records are compared after packing, so changes below the format's precision do not rewrite the chunk.
        val iconPixels = packRecord(placemark, attributes, run.texture, slot.normal, scratchRecord)
        if (iconPixels > frameIconPixels) frameIconPixels = iconPixels
        includeInOrder(isDepthTest, cameraDistanceSq(rc, slot.point))
        slot.iconPixels = iconPixels
        slot.recordTexture = run.texture
        if (!slot.isVisible || !scratchRecord.contentEquals(slot.record)) {
            scratchRecord.copyInto(slot.record)
            slot.isVisible = true
            queue(slot)
        }
    }

    fun endFrame(rc: RenderContext) {
        evictUnseen()
        if (isTerrainSetDirty) rebuildTerrainSet()
        updateBounds()
        if (isLayoutDirty) {
            diag.layouts++ // DIAGNOSTICS
            layout()
        } else for (i in dirtySlots.indices) writeQuad(dirtySlots[i])
        diag.dirtySlots += dirtySlots.size // DIAGNOSTICS
        for (i in dirtySlots.indices) dirtySlots[i].isQueued = false
        dirtySlots.clear()
        if (quadCount == 0) return

        val indexBuffer = rc.getBufferObject(INDEX_BUFFER_KEY) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(INDEX_BUFFER_KEY, 1) { NumericArray.Shorts(QUAD_INDICES) }

        uploadBudget = MAX_CHUNK_UPLOADS_PER_FRAME
        val chunkCount = chunkCount()
        if (frameChunkBuffers.size < chunkCount) frameChunkBuffers = arrayOfNulls(chunkCount)
        for (i in 0 until chunkCount) {
            // Start after the chunk that last used the budget, so every changed chunk gets its turn.
            val index = (i + uploadCursor) % chunkCount
            frameChunkBuffers[index] = uploadChunk(rc, chunks[index], index)
        }
        // Split each visible run at chunk boundaries: one draw per texture per chunk. Depth-tested icons and icons
        // drawn over everything go to separate drawables, ordered differently among the frame's other drawables.
        var depthTested: DrawablePlacemarkBatch? = null
        var overlay: DrawablePlacemarkBatch? = null
        for (i in runs.indices) {
            val run = runs[i]
            if (run.visibleFrame != frame) continue
            val drawable = if (run.isDepthTest) depthTested ?: obtainDrawable(rc, indexBuffer, chunkCount)
                .also { depthTested = it }
            else overlay ?: obtainDrawable(rc, indexBuffer, chunkCount).also { overlay = it }
            var quad = run.firstQuad
            val end = run.firstQuad + run.slots.size
            while (quad < end) {
                val chunk = quad / CHUNK_QUADS
                val count = minOf(end, (chunk + 1) * CHUNK_QUADS) - quad
                drawable.addSegment(chunk, quad - chunk * CHUNK_QUADS, count, run.texture, run.isDepthTest)
                quad += count
            }
        }
        for (i in 0 until chunkCount) frameChunkBuffers[i] = null
        // Order each drawable among the frame's others by camera distance, as Placemark orders its icons. A batch
        // draws its icons together, so it takes one distance for all of them:
        // - Depth-tested icons write depth: the farthest one's distance draws them before anything nearer than any of
        //   them (translucent shapes, labels and highlighted placemarks then blend over them), and whatever farther
        //   is drawn after them fails the depth test where they were drawn.
        // - Icons without the depth test do not write depth either, so anything drawn after them covers them: the
        //   nearest one's distance draws them after everything farther than all of them.
        depthTested?.let { rc.offerShapeDrawable(it, max(frameFarthestDepthTestedSq, 0.0)) }
        overlay?.let {
            rc.offerShapeDrawable(it, if (frameNearestOverlaySq == Double.MAX_VALUE) 0.0 else frameNearestOverlaySq)
        }
    }

    private fun obtainDrawable(rc: RenderContext, indexBuffer: BufferObject, chunkCount: Int): DrawablePlacemarkBatch {
        val drawable = DrawablePlacemarkBatch.obtain(rc.getDrawablePool(DrawablePlacemarkBatch.KEY))
        drawable.program = rc.getShaderProgram(PROGRAM_KEY) { PlacemarkBatchProgram() }
        drawable.indexBuffer = indexBuffer
        drawable.prepareFrame(rc, origin, Placemark.BATCH_DEPTH_OFFSET)
        for (i in 0 until chunkCount) frameChunkBuffers[i]?.let { drawable.setChunkBuffer(i, it) }
        return drawable
    }

    /**
     * Sets the bounds from the points resolved this frame: a box around them, as a sphere.
     */
    private fun updateBounds() {
        if (framePoints == 0) {
            hasBounds = false
            return
        }
        center.set((frameMinX + frameMaxX) * 0.5, (frameMinY + frameMaxY) * 0.5, (frameMinZ + frameMaxZ) * 0.5)
        val dx = frameMaxX - frameMinX
        val dy = frameMaxY - frameMinY
        val dz = frameMaxZ - frameMinZ
        radius = 0.5 * sqrt(dx * dx + dy * dy + dz * dz)
        maxIconPixels = if (frameDrewIndividually) max(frameIconPixels, LABEL_MARGIN_PIXELS) else frameIconPixels
        hasBounds = true
    }

    /**
     * Collects the distinct tile mesh versions the members' points were read from, and counts the fallback points.
     */
    private fun rebuildTerrainSet() {
        var count = 0
        fallbackCount = 0
        for (i in slots.indices) {
            val slot = slots[i]
            if (!slot.hasPoint || !slot.isTerrainDependent) continue
            val version = slot.terrainVersion
            if (version == 0L) {
                fallbackCount++
                continue
            }
            // Neighbouring members mostly share a tile: skip a repeat of the last version before sorting
            if (count > 0 && terrainVersions[count - 1] == version) continue
            if (count == terrainVersions.size) terrainVersions = terrainVersions.copyOf(count * 2)
            terrainVersions[count++] = version
        }
        terrainVersions.sort(0, count)
        var distinct = 0
        for (i in 0 until count) {
            val version = terrainVersions[i]
            if (distinct == 0 || terrainVersions[distinct - 1] != version) terrainVersions[distinct++] = version
        }
        terrainVersionCount = distinct
        isTerrainSetDirty = false
        diag.terrainSetRebuilds++ // DIAGNOSTICS
    }

    /**
     * Calls [action] for each member with its cached state.
     */
    fun forEachSlot(action: (Slot) -> Unit) {
        for (i in slots.indices) action(slots[i])
    }

    /**
     * Takes over [from], a member of another cell, with its cached point, terrain version and record, so moving it here
     * (a cell split) costs a layout but no point or record computation.
     */
    fun adopt(from: Slot) {
        val slot = Slot(this, from.placemark, slotCounter++ % TERRAIN_REFRESH_PERIOD)
        slot.seenFrame = frame
        slot.hasPoint = from.hasPoint
        slot.latitude = from.latitude
        slot.longitude = from.longitude
        slot.altitude = from.altitude
        slot.altitudeMode = from.altitudeMode
        slot.isTerrainDependent = from.isTerrainDependent
        slot.terrainVersion = from.terrainVersion
        slot.point.copy(from.point)
        slot.normal.copy(from.normal)
        from.record.copyInto(slot.record)
        // isVisible stays false: the next update() finds its run here and writes the icon.
        from.placemark.setBatchSlot(cells.slotKey, slot)
        slots.add(slot)
        isLayoutDirty = true
        isTerrainSetDirty = true
    }

    /**
     * Detaches every placemark from this batch.
     */
    fun clear() {
        for (i in slots.indices) detach(slots[i])
        slots.clear()
        runs.clear()
        depthTestRuns.clear()
        noDepthTestRuns.clear()
        chunks.clear()
        dirtySlots.clear()
        terrainVersionCount = 0
        fallbackCount = 0
        quadCount = 0
        hasBounds = false
    }

    private fun chunkCount() = (quadCount + CHUNK_QUADS - 1) / CHUNK_QUADS

    /**
     * Returns [chunk]'s vertex buffer, queueing an upload when its data changed (or the cached buffer was evicted)
     * and no upload of it is already in flight.
     */
    private fun uploadChunk(rc: RenderContext, chunk: Chunk, index: Int): BufferObject {
        val buffer = rc.getBufferObject(chunk.bufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        // A frame that is never drawn drops its uploads without calling back: stop waiting after a while.
        if (chunk.isUploading && frame - chunk.uploadFrame > UPLOAD_TIMEOUT_FRAMES) chunk.isUploading = false
        if (chunk.isUploading) return buffer
        // Changed chunks share a per-frame upload budget and keep their previous content until their turn; a layout
        // uploads every chunk at once.
        if (chunk.isDirty && (chunk.isLayoutDirty || uploadBudget > 0)) {
            if (chunk.isLayoutDirty) diag.layoutUploads++ else diag.budgetUploads++ // DIAGNOSTICS
            if (!chunk.isLayoutDirty) {
                uploadBudget--
                uploadCursor = index + 1
            }
            chunk.isDirty = false
            chunk.isLayoutDirty = false
            chunk.version++
        }
        run {
            var upload: IntArray? = null
            rc.offerGLBufferUpload(chunk.bufferKey, chunk.version, {
                chunk.isUploading = false
                upload?.let { uploadPool.release(it) }
            }) {
                val ints = chunk.quadCount * VERTICES_PER_QUAD * DrawablePlacemarkBatch.STRIDE_INTS
                val pooled = uploadPool.acquire()
                val array = if (pooled != null && pooled.size >= ints) pooled
                else IntArray(chunk.data.size).also { diag.uploadAllocs++ } // DIAGNOSTICS
                chunk.data.copyInto(array, 0, 0, ints)
                upload = array
                if (chunk.builtVersion == chunk.version) diag.reuploads++ // DIAGNOSTICS: same version again = evicted
                chunk.builtVersion = chunk.version
                chunk.isUploading = true
                chunk.uploadFrame = frame
                diag.uploads++ // DIAGNOSTICS
                NumericArray.Ints(array, ints * Int.SIZE_BYTES)
            }
        }
        return buffer
    }

    private fun slotFor(placemark: Placemark): Slot {
        val existing = placemark.batchSlot(cells.slotKey)
        val slot = if (existing != null && existing.batch === this) existing else {
            Slot(this, placemark, slotCounter++ % TERRAIN_REFRESH_PERIOD).also {
                placemark.setBatchSlot(cells.slotKey, it)
                slots.add(it)
            }
        }
        slot.seenFrame = frame
        return slot
    }

    /** Clears [slot]'s placemark reference to it, unless the placemark has moved on to another slot. */
    private fun detach(slot: Slot) {
        if (slot.placemark.batchSlot(cells.slotKey) === slot) slot.placemark.setBatchSlot(cells.slotKey, null)
    }

    private fun runFor(rc: RenderContext, imageSource: ImageSource, isDepthTest: Boolean): Run {
        val map = if (isDepthTest) depthTestRuns else noDepthTestRuns
        return map[imageSource] ?: Run(imageSource, isDepthTest).also {
            it.texture = rc.getTexture(imageSource, null, false)
            map[imageSource] = it
            runs.add(it)
        }
    }

    private fun queue(slot: Slot) {
        if (!slot.isQueued) {
            slot.isQueued = true
            dirtySlots.add(slot)
        }
    }

    /**
     * Drops the placemarks not rendered this frame: removed from the layer, disabled or moved to another cell.
     */
    private fun evictUnseen() {
        var removed = false
        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.seenFrame != frame) {
                detach(slot)
                removed = true
            }
        }
        if (removed) {
            diag.evictions++ // DIAGNOSTICS
            slots.removeAll { it.seenFrame != frame }
            isLayoutDirty = true
            isTerrainSetDirty = true
        }
    }

    /**
     * Groups the icons by run, places each run contiguously across the chunks and rewrites every icon.
     */
    private fun layout() {
        for (i in runs.indices) runs[i].slots.clear()
        for (i in slots.indices) {
            val slot = slots[i]
            slot.quad = -1
            slot.run?.slots?.add(slot)
        }
        runs.removeAll { run ->
            run.slots.isEmpty().also {
                if (it) (if (run.isDepthTest) depthTestRuns else noDepthTestRuns).remove(run.imageSource)
            }
        }
        var quad = 0
        for (i in runs.indices) {
            val run = runs[i]
            run.firstQuad = quad
            for (j in run.slots.indices) run.slots[j].quad = quad + j
            quad += run.slots.size
        }
        quadCount = quad
        val chunkCount = chunkCount()
        while (chunks.size < chunkCount) chunks.add(Chunk())
        while (chunks.size > chunkCount) chunks.removeAt(chunks.size - 1)
        for (i in 0 until chunkCount) {
            val chunk = chunks[i]
            chunk.quadCount = minOf(CHUNK_QUADS, quadCount - i * CHUNK_QUADS)
            val capacity = minOf(CHUNK_QUADS, roundUp(chunk.quadCount, CHUNK_GROWTH_QUADS)) * QUAD_INTS
            if (chunk.data.size < capacity) chunk.data = IntArray(capacity)
            chunk.isDirty = true
            chunk.isLayoutDirty = true
        }
        if (!hasOrigin) slots.firstOrNull { it.hasPoint }?.let {
            origin.copy(it.point)
            hasOrigin = true
        }
        for (i in runs.indices) {
            val run = runs[i]
            for (j in run.slots.indices) writeQuad(run.slots[j])
        }
        isLayoutDirty = false
    }

    /**
     * Writes [slot]'s quad into its chunk: four vertices sharing the point and the record, differing in the corner.
     */
    private fun writeQuad(slot: Slot) {
        val quad = slot.quad
        if (quad < 0) return // not placed yet; the next layout writes it
        val chunk = chunks[quad / CHUNK_QUADS]
        chunk.isDirty = true
        val data = chunk.data
        val px = (slot.point.x - origin.x).toFloat().toRawBits()
        val py = (slot.point.y - origin.y).toFloat().toRawBits()
        val pz = (slot.point.z - origin.z).toFloat().toRawBits()
        val record = slot.record
        val visible = if (slot.isVisible) 1 shl 24 else 0
        var offset = (quad % CHUNK_QUADS) * QUAD_INTS
        for (corner in 0 until VERTICES_PER_QUAD) {
            data[offset] = px
            data[offset + 1] = py
            data[offset + 2] = pz
            data[offset + 3] = corner or record[0] or visible
            record.copyInto(data, offset + 4, 1, RECORD_INTS)
            offset += DrawablePlacemarkBatch.STRIDE_INTS
        }
    }

    /**
     * Packs the instance record from the same inputs Placemark uses: the image size and offset before eye-distance
     * scaling (applied in the shader), the billboard normal, orientation, color and scaling limits. The layout matches
     * the vertex attributes declared by [DrawablePlacemarkBatch]: ints 4 to 10 of a vertex, plus the orientation flags
     * of int 3.
     *
     * @return the icon's largest screen extent from its point in pixels, for the cell's culling margin
     */
    private fun packRecord(
        placemark: Placemark, attributes: PlacemarkAttributes, texture: Texture?, normal: Vec3, record: IntArray
    ): Double {
        // Int 3 (bytes 1 and 2): rotation and tilt relative to the globe. Byte 0 is the corner, byte 3 visibility.
        val rotationRelative = if (placemark.imageRotationReference == OrientationMode.RELATIVE_TO_GLOBE) 1 else 0
        val tiltRelative = if (placemark.imageTiltReference == OrientationMode.RELATIVE_TO_GLOBE) 1 else 0
        record[0] = (rotationRelative shl 8) or (tiltRelative shl 16)

        // Int 4: billboard normal as signed normalized bytes, zero when billboarding is off.
        record[1] = if (placemark.isBillboardingEnabled) packBytes(
            signedByte(normal.x), signedByte(normal.y), signedByte(normal.z), 0
        ) else 0

        // Ints 5 and 6: width, height, offset x and offset y as shorts in quarter pixels.
        val w: Double
        val h: Double
        val scaleX: Double
        val scaleY: Double
        if (texture != null) {
            w = texture.width.toDouble()
            h = texture.height.toDouble()
            scaleX = attributes.imageScaleX
            scaleY = attributes.imageScaleY
        } else {
            // A texture that has not loaded yet: the typical size of a "small" icon, as Placemark draws it.
            w = PLACEHOLDER_SIZE
            h = PLACEHOLDER_SIZE
            scaleX = 1.0
            scaleY = 1.0
        }
        attributes.imageOffset.offsetForSize(w, h, scratchOffset)
        record[2] = packShorts(quarterPixels(w * scaleX), quarterPixels(h * scaleY))
        record[3] = packShorts(quarterPixels(scratchOffset.x * scaleX), quarterPixels(scratchOffset.y * scaleY))

        // Ints 7 and 8: rotation / pi, tilt / pi and minimum image scale / 32 as signed normalized shorts.
        record[4] = packShorts(
            signedShort(normalizeRadians(placemark.imageRotation.inRadians) / PI),
            signedShort(normalizeRadians(placemark.imageTilt.inRadians) / PI)
        )
        record[5] = packShorts(signedShort(attributes.minimumImageScale / MAX_MINIMUM_SCALE), 0)

        // Int 9: premultiplied color as unsigned normalized bytes.
        val color = attributes.imageColor
        val alpha = color.alpha
        record[6] = packBytes(
            unsignedByte(color.red * alpha), unsignedByte(color.green * alpha), unsignedByte(color.blue * alpha),
            unsignedByte(alpha)
        )

        // Int 10: eye-distance scaling threshold in meters, negative when scaling is off.
        record[7] = (if (placemark.isEyeDistanceScaling) placemark.eyeDistanceScalingThreshold.toFloat() else -1f)
            .toRawBits()

        // Rotation can turn any corner towards any side, so take the size plus the offset in either direction. The
        // shader scales icons down with distance, but never above 1 unless the minimum scale is above 1.
        val sx = abs(scaleX)
        val sy = abs(scaleY)
        return (max(w * sx, h * sy) + max(abs(scratchOffset.x * sx), abs(scratchOffset.y * sy))) *
                max(1.0, attributes.minimumImageScale)
    }

    companion object {
        /** Frames between refreshes of a point from the elevation model fallback, staggered across placemarks. */
        const val TERRAIN_REFRESH_PERIOD = 8
        /** Visited frames between forced full evaluations of a placemark whose state is reused, staggered. */
        const val LOD_REFRESH_PERIOD = 4
        /** Relative camera distance change that forces a full evaluation (level-of-detail thresholds). */
        const val LOD_DISTANCE_TOLERANCE = 0.02
        /** Outcomes of a full evaluation, see recordFrameState. */
        const val LOD_NONE = -1
        const val LOD_HIDDEN = 0
        const val LOD_BATCHED = 1
        const val LOD_INDIVIDUAL = 2
        /** Icons per chunk: 4 vertices each, so the largest index (16383) fits a 16-bit index buffer. */
        const val CHUNK_QUADS = 4096
        /** Chunk data grows in steps of this many icons. */
        private const val CHUNK_GROWTH_QUADS = 256
        private const val VERTICES_PER_QUAD = 4
        private const val QUAD_INTS = VERTICES_PER_QUAD * DrawablePlacemarkBatch.STRIDE_INTS
        /** Packed record ints: the flags of vertex int 3, then vertex ints 4 to 10. */
        const val RECORD_INTS = 8
        private const val UPLOAD_TIMEOUT_FRAMES = 30
        /** Changed chunks uploaded per frame per cell, outside layouts. */
        private const val MAX_CHUNK_UPLOADS_PER_FRAME = 2
        /** Smallest screen shift of a refreshed point, in pixels, that rewrites its icon. */
        private const val MIN_POINT_SHIFT_PIXELS = 0.25
        /** Culling margin in pixels for cells with members drawn individually: labels reach further than icons. */
        private const val LABEL_MARGIN_PIXELS = 300.0
        private const val PLACEHOLDER_SIZE = 24.0
        /** Upper bound of the minimum image scale the format holds. */
        private const val MAX_MINIMUM_SCALE = 32.0
        /** Class literals go through reflection on each evaluation: keep the shader program key. */
        private val PROGRAM_KEY = PlacemarkBatchProgram::class
        /** Every cell draws from the same index buffer. */
        private val INDEX_BUFFER_KEY = Any()

        /** Two triangles per quad, (0, 1, 2) and (2, 1, 3), for every quad of a chunk. */
        private val QUAD_INDICES = ShortArray(CHUNK_QUADS * 6).also {
            for (q in 0 until CHUNK_QUADS) {
                val v = q * VERTICES_PER_QUAD
                val i = q * 6
                it[i] = v.toShort()
                it[i + 1] = (v + 1).toShort()
                it[i + 2] = (v + 2).toShort()
                it[i + 3] = (v + 2).toShort()
                it[i + 4] = (v + 1).toShort()
                it[i + 5] = (v + 3).toShort()
            }
        }

        private fun roundUp(value: Int, step: Int) = (value + step - 1) / step * step

        internal fun packBytes(b0: Int, b1: Int, b2: Int, b3: Int) =
            (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)

        internal fun packShorts(s0: Int, s1: Int) = (s0 and 0xFFFF) or ((s1 and 0xFFFF) shl 16)

        internal fun signedByte(v: Double) = (v.coerceIn(-1.0, 1.0) * 127.0).roundToInt()

        internal fun unsignedByte(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()

        internal fun signedShort(v: Double) = (v.coerceIn(-1.0, 1.0) * 32767.0).roundToInt()

        internal fun quarterPixels(v: Double) = (v * 4.0).roundToInt().coerceIn(-32768, 32767)

        /** The angle in [-pi, pi]. */
        internal fun normalizeRadians(radians: Double): Double {
            var r = radians % (2 * PI)
            if (r > PI) r -= 2 * PI else if (r < -PI) r += 2 * PI
            return r
        }
    }
}

/**
 * DIAGNOSTICS (temporary): counters summed over every cell of a [PlacemarkCells] index and printed periodically.
 */
internal class PlacemarkBatchDiagnostics {
    var frames = 0
    var cellsVisited = 0
    var cellsCulled = 0
    var splits = 0
    var pointComputes = 0
    var lodReused = 0
    var lodEvaluated = 0
    var terrainDirtyCells = 0
    var terrainSetRebuilds = 0
    var layouts = 0
    var runChanges = 0
    var evictions = 0
    var globeChanges = 0
    var dirtySlots = 0
    var uploads = 0
    var layoutUploads = 0
    var budgetUploads = 0
    var reuploads = 0
    var uploadAllocs = 0

    fun reset() {
        frames = 0; cellsVisited = 0; cellsCulled = 0; splits = 0; pointComputes = 0; terrainDirtyCells = 0
        lodReused = 0; lodEvaluated = 0
        terrainSetRebuilds = 0; layouts = 0; runChanges = 0; evictions = 0; globeChanges = 0; dirtySlots = 0
        uploads = 0; layoutUploads = 0; budgetUploads = 0; reuploads = 0
    }
}
