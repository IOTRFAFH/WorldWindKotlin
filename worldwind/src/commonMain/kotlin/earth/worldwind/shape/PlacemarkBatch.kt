package earth.worldwind.shape

import earth.worldwind.draw.DrawablePlacemarkBatch
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
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
import kotlin.math.roundToInt

/**
 * Draws the icons of many [Placemark]s from a few vertex buffers, with one draw call per texture and chunk, instead of
 * one drawable, one matrix and one draw call per icon. Uses OpenGL ES 2.0 features only.
 *
 * Each placemark's work is split by how often its inputs change:
 * - **When its data changes**: the Cartesian point (refreshed every [TERRAIN_REFRESH_PERIOD] frames when it depends
 *   on the terrain) and the packed instance record (size, offset, rotation, color, scaling), written into its chunk
 *   only when they differ from the stored ones.
 * - **Every frame**: the level-of-detail selector and the comparison against the stored record. Projection, pixel
 *   offset, rotation and tilt, eye-distance scaling, billboarding and depth offset run in the vertex shader
 *   ([PlacemarkBatchProgram]).
 *
 * Icons are quads of 4 vertices in a compact 44-byte vertex format ([DrawablePlacemarkBatch.STRIDE]), indexed by a
 * shared 16-bit index buffer, in chunks of [CHUNK_QUADS] icons. A change re-uploads only its chunk, with at most one
 * upload in flight per chunk, so memory stays bounded however far the GL thread lags.
 *
 * Placemarks that cannot be drawn as a plain icon this frame (see [Placemark.canDrawBatched]) are drawn individually,
 * and their batched icon is hidden. Picking frames bypass the batch. A batch serves one [RenderContext] (one window).
 */
internal class PlacemarkBatch {
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
        val point = Vec3()
        val normal = Vec3()
        val record = IntArray(RECORD_INTS)
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
        val data = IntArray(CHUNK_INTS)
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

    private var owner: RenderContext? = null
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
    private val indexBufferKey = Any()
    private val scratchPoint = Vec3()
    private val scratchRecord = IntArray(RECORD_INTS)
    private val scratchOffset = Vec2()
    /** Upload copies of chunk data, returned once the GL thread has uploaded them. All are [CHUNK_INTS] long. */
    private val uploadPool = SynchronizedPool<IntArray>()
    private var uploadBudget = 0
    private var uploadCursor = 0

    // DIAGNOSTICS (temporary): how often chunks are rewritten and uploaded, printed every DIAG_FRAMES frames.
    private var diagFrames = 0
    private var diagLayouts = 0
    private var diagDirtySlots = 0
    private var diagUploads = 0
    private var diagUploadAllocs = 0
    private var diagReuploads = 0
    private var diagLayoutUploads = 0
    private var diagBudgetUploads = 0
    private var diagRunChanges = 0
    private var diagEvictions = 0
    private var diagGlobeChanges = 0

    /**
     * Whether this batch can serve [rc]: the first render context it sees owns it.
     */
    fun accepts(rc: RenderContext): Boolean {
        val owner = owner ?: rc.also { owner = it }
        return owner === rc
    }

    fun beginFrame(rc: RenderContext) {
        frame++
        // A different globe or globe state (2D/3D, projection) moves every point: recompute them all.
        if (rc.globe !== globe || rc.globeState != globeState) {
            globe = rc.globe
            globeState = rc.globeState
            for (i in slots.indices) slots[i].hasPoint = false
            hasOrigin = false
            isLayoutDirty = true
            diagGlobeChanges++ // DIAGNOSTICS
        }
        // Pick up textures that finished loading or were evicted since the last frame.
        for (i in runs.indices) {
            val run = runs[i]
            val texture = rc.getTexture(run.imageSource, null, false)
            if (texture !== run.texture) run.texture = texture // records see the new size in update()
        }
    }

    /**
     * Writes [placemark]'s place point into [result], from the cache when its position has not changed. Points that
     * depend on the terrain are refreshed every [TERRAIN_REFRESH_PERIOD] frames, staggered across placemarks.
     */
    fun resolvePlacePoint(rc: RenderContext, placemark: Placemark, result: Vec3) {
        val slot = slotFor(placemark)
        val position = placemark.position
        val latitude = position.latitude.inDegrees
        val longitude = position.longitude.inDegrees
        val altitude = position.altitude
        val moved = !slot.hasPoint || latitude != slot.latitude || longitude != slot.longitude ||
                altitude != slot.altitude || placemark.altitudeMode != slot.altitudeMode
        val refresh = !moved && placemark.isTerrainDependent(rc) &&
                (frame + slot.refreshPhase) % TERRAIN_REFRESH_PERIOD == 0L
        if (moved || refresh) {
            placemark.computePlacePoint(rc, scratchPoint)
            // A refresh follows terrain detail changes while the camera moves: rewrite the icon only when its point
            // moved by more than a quarter pixel at its distance, which a refresh at country scale rarely does.
            if (moved || isVisibleShift(rc, slot.point, scratchPoint)) {
                slot.point.copy(scratchPoint)
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
        result.copy(slot.point)
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
                diagRunChanges++ // DIAGNOSTICS
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
        packRecord(placemark, attributes, run.texture, slot.normal, scratchRecord)
        if (!slot.isVisible || !scratchRecord.contentEquals(slot.record)) {
            scratchRecord.copyInto(slot.record)
            slot.isVisible = true
            queue(slot)
        }
    }

    fun endFrame(rc: RenderContext) {
        evictUnseen()
        diagFrames++ // DIAGNOSTICS
        if (isLayoutDirty) {
            diagLayouts++ // DIAGNOSTICS
            layout()
        } else for (i in dirtySlots.indices) writeQuad(dirtySlots[i])
        diagDirtySlots += dirtySlots.size // DIAGNOSTICS
        for (i in dirtySlots.indices) dirtySlots[i].isQueued = false
        dirtySlots.clear()
        logDiagnostics() // DIAGNOSTICS
        if (quadCount == 0) return

        val indexBuffer = rc.getBufferObject(indexBufferKey) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(indexBufferKey, 1) { NumericArray.Shorts(QUAD_INDICES) }

        val pool = rc.getDrawablePool(DrawablePlacemarkBatch.KEY)
        val drawable = DrawablePlacemarkBatch.obtain(pool)
        drawable.program = rc.getShaderProgram(PROGRAM_KEY) { PlacemarkBatchProgram() }
        drawable.indexBuffer = indexBuffer
        drawable.prepareFrame(rc, origin, Placemark.BATCH_DEPTH_OFFSET)
        uploadBudget = MAX_CHUNK_UPLOADS_PER_FRAME
        for (i in 0 until chunkCount()) {
            // Start after the chunk that last used the budget, so every changed chunk gets its turn.
            val index = (i + uploadCursor) % chunkCount()
            drawable.setChunkBuffer(index, uploadChunk(rc, chunks[index], index))
        }
        // Split each visible run at chunk boundaries: one draw per texture per chunk.
        for (i in runs.indices) {
            val run = runs[i]
            if (run.visibleFrame != frame) continue
            var quad = run.firstQuad
            val end = run.firstQuad + run.slots.size
            while (quad < end) {
                val chunk = quad / CHUNK_QUADS
                val count = minOf(end, (chunk + 1) * CHUNK_QUADS) - quad
                drawable.addSegment(chunk, quad - chunk * CHUNK_QUADS, count, run.texture, run.isDepthTest)
                quad += count
            }
        }
        if (drawable.segmentCount == 0) {
            drawable.recycle()
            return
        }
        // Draw after the shapes at the same depth, as the nearest placemark would be.
        rc.offerShapeDrawable(drawable, 0.0)
    }

    /**
     * Detaches every placemark from this batch.
     */
    fun clear() {
        for (i in slots.indices) slots[i].placemark.let { if (it.batchSlot === slots[i]) it.batchSlot = null }
        slots.clear()
        runs.clear()
        depthTestRuns.clear()
        noDepthTestRuns.clear()
        chunks.clear()
        dirtySlots.clear()
        quadCount = 0
        owner = null
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
            if (chunk.isLayoutDirty) diagLayoutUploads++ else diagBudgetUploads++ // DIAGNOSTICS
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
                val array = uploadPool.acquire() ?: IntArray(CHUNK_INTS).also { diagUploadAllocs++ }
                val ints = chunk.quadCount * VERTICES_PER_QUAD * DrawablePlacemarkBatch.STRIDE_INTS
                chunk.data.copyInto(array, 0, 0, ints)
                upload = array
                if (chunk.builtVersion == chunk.version) diagReuploads++ // DIAGNOSTICS: same version again = evicted
                chunk.builtVersion = chunk.version
                chunk.isUploading = true
                chunk.uploadFrame = frame
                diagUploads++ // DIAGNOSTICS
                NumericArray.Ints(array, ints * Int.SIZE_BYTES)
            }
        }
        return buffer
    }

    private fun slotFor(placemark: Placemark): Slot {
        val existing = placemark.batchSlot
        val slot = if (existing != null && existing.batch === this) existing else {
            Slot(this, placemark, slotCounter++ % TERRAIN_REFRESH_PERIOD).also {
                placemark.batchSlot = it
                slots.add(it)
            }
        }
        slot.seenFrame = frame
        return slot
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
     * Drops the placemarks not rendered this frame: removed from the layer or disabled.
     */
    private fun evictUnseen() {
        var removed = false
        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.seenFrame != frame) {
                if (slot.placemark.batchSlot === slot) slot.placemark.batchSlot = null
                removed = true
            }
        }
        if (removed) {
            diagEvictions++ // DIAGNOSTICS
            slots.removeAll { it.seenFrame != frame }
            isLayoutDirty = true
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
            chunks[i].quadCount = minOf(CHUNK_QUADS, quadCount - i * CHUNK_QUADS)
            chunks[i].isDirty = true
            chunks[i].isLayoutDirty = true
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
        var offset = (quad % CHUNK_QUADS) * VERTICES_PER_QUAD * DrawablePlacemarkBatch.STRIDE_INTS
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
     */
    private fun packRecord(
        placemark: Placemark, attributes: PlacemarkAttributes, texture: Texture?, normal: Vec3, record: IntArray
    ) {
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
    }

    companion object {
        /** Frames between refreshes of a terrain-dependent point, staggered across placemarks. */
        const val TERRAIN_REFRESH_PERIOD = 8
        /** Icons per chunk: 4 vertices each, so the largest index (16383) fits a 16-bit index buffer. */
        const val CHUNK_QUADS = 4096
        private const val VERTICES_PER_QUAD = 4
        private const val CHUNK_INTS = CHUNK_QUADS * VERTICES_PER_QUAD * DrawablePlacemarkBatch.STRIDE_INTS
        /** Packed record ints: the flags of vertex int 3, then vertex ints 4 to 10. */
        const val RECORD_INTS = 8
        private const val UPLOAD_TIMEOUT_FRAMES = 30
        /** Changed chunks uploaded per frame, outside layouts: about 1.4 MB. */
        private const val MAX_CHUNK_UPLOADS_PER_FRAME = 2
        /** Smallest screen shift of a refreshed point, in pixels, that rewrites its icon. */
        private const val MIN_POINT_SHIFT_PIXELS = 0.25
        private const val PLACEHOLDER_SIZE = 24.0
        /** Upper bound of the minimum image scale the format holds. */
        private const val MAX_MINIMUM_SCALE = 32.0
        private const val DIAG_FRAMES = 3 // DIAGNOSTICS
        /** Class literals go through reflection on each evaluation: keep the shader program key. */
        private val PROGRAM_KEY = PlacemarkBatchProgram::class

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

    private fun logDiagnostics() {
        if (diagFrames < DIAG_FRAMES) return
        // println: c4ds's R8 rules strip Log.isLoggable, which silences Logger in release-based builds.
        println(
            "PlacemarkBatch frames=$diagFrames layouts=$diagLayouts (runChanges=$diagRunChanges " +
                    "evictions=$diagEvictions globe=$diagGlobeChanges) dirtySlots=$diagDirtySlots uploads=$diagUploads " +
                    "(layout=$diagLayoutUploads budget=$diagBudgetUploads reupload=$diagReuploads) " +
                    "uploadAllocs=$diagUploadAllocs slots=${slots.size} runs=${runs.size} chunks=${chunks.size}"
        )
        diagFrames = 0; diagLayouts = 0; diagDirtySlots = 0; diagUploads = 0; diagReuploads = 0
        diagLayoutUploads = 0; diagBudgetUploads = 0; diagRunChanges = 0; diagEvictions = 0; diagGlobeChanges = 0
    }
}
