package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.PlacemarkBatchProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_BYTE
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_SHORT
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import earth.worldwind.util.kgl.GL_UNSIGNED_SHORT
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/**
 * Draws a batch of placemark icons from chunked vertex buffers and a shared 16-bit index buffer: one draw call per
 * segment, a run of icons sharing a texture and depth test state within one chunk. Per-icon placement is computed by
 * [PlacemarkBatchProgram]; this drawable carries the frame's camera state, the chunk buffers and the segments.
 *
 * Vertex format, [STRIDE] bytes:
 *
 * | Bytes | Attribute         | Type                         | Content                                              |
 * |-------|-------------------|------------------------------|------------------------------------------------------|
 * | 0-11  | vertexPoint       | 3 floats                     | point relative to the batch origin                   |
 * | 12-15 | vertexFlags       | 4 unsigned bytes             | corner (0-3), rotation and tilt relative to globe,   |
 * |       |                   |                              | visible                                              |
 * | 16-19 | vertexNormal      | 4 normalized bytes           | billboard normal, zero when billboarding is off      |
 * | 20-27 | vertexSize        | 4 shorts                     | width, height, offset x, offset y in quarter pixels  |
 * | 28-35 | vertexAngles      | 4 normalized shorts          | rotation / pi, tilt / pi, minimum image scale / 32   |
 * | 36-39 | vertexColor       | 4 normalized unsigned bytes  | premultiplied color                                  |
 * | 40-43 | vertexThreshold   | 1 float                      | eye-distance scaling threshold, negative when off    |
 */
class DrawablePlacemarkBatch private constructor() : Drawable {
    var program: PlacemarkBatchProgram? = null
    var indexBuffer: BufferObject? = null
    /** Modelview with the batch origin folded in: vertex points are relative to the origin. */
    val modelview = Matrix4()
    val projection = Matrix4()
    /** Camera point relative to the batch origin. */
    val eyePoint = Vec3()
    var viewportX = 0f
    var viewportY = 0f
    var viewportWidth = 0f
    var viewportHeight = 0f
    var heading = 0f
    var tilt = 0f
    var pixelSizeFactor = 0f
    /** Viewing distance used as the camera distance in 2D; 0 in 3D. */
    var viewingDistance2D = 0f
    var horizonDistance = 0f
    var depthOffset = 0f
    var opacity = 1f
    var segmentCount = 0
        private set
    private var chunkBuffers = arrayOfNulls<BufferObject>(4)
    private var chunkCount = 0
    private var segmentChunk = IntArray(8)
    private var segmentFirst = IntArray(8)
    private var segmentSize = IntArray(8)
    private var segmentDepthTest = BooleanArray(8)
    private var segmentTextures = arrayOfNulls<Texture>(8)
    private var pool: Pool<DrawablePlacemarkBatch>? = null

    companion object {
        val KEY = DrawablePlacemarkBatch::class
        /** Bytes per vertex. */
        const val STRIDE = 44
        /** Ints per vertex: vertex data is written as packed ints. */
        const val STRIDE_INTS = STRIDE / Int.SIZE_BYTES
        private const val INDICES_PER_QUAD = 6

        @JvmStatic
        fun obtain(pool: Pool<DrawablePlacemarkBatch>): DrawablePlacemarkBatch {
            val instance = pool.acquire() ?: DrawablePlacemarkBatch()
            instance.pool = pool
            return instance
        }
    }

    /**
     * Captures the camera state this frame's icons are placed with, relative to the batch [origin].
     */
    fun prepareFrame(rc: RenderContext, origin: Vec3, defaultDepthOffset: Double) {
        modelview.copy(rc.modelview).multiplyByTranslation(origin.x, origin.y, origin.z)
        projection.copy(rc.projection)
        eyePoint.copy(rc.cameraPoint).subtract(origin)
        viewportX = rc.viewport.x.toFloat()
        viewportY = rc.viewport.y.toFloat()
        viewportWidth = rc.viewport.width.toFloat()
        viewportHeight = rc.viewport.height.toFloat()
        heading = rc.camera.heading.inRadians.toFloat()
        tilt = rc.camera.tilt.inRadians.toFloat()
        pixelSizeFactor = rc.pixelSizeAtDistance(1.0).toFloat()
        viewingDistance2D = if (rc.globe.is2D) rc.viewingDistance.toFloat() else 0f
        horizonDistance = rc.horizonDistance.toFloat()
        val absTilt = abs(rc.camera.tilt.inDegrees)
        depthOffset = if (absTilt <= 90) ((1 - absTilt / 90) * defaultDepthOffset).toFloat() else 0f
        opacity = rc.currentLayer.opacity
    }

    fun setChunkBuffer(chunk: Int, buffer: BufferObject) {
        if (chunk >= chunkBuffers.size) chunkBuffers = chunkBuffers.copyOf(maxOf(chunk + 1, chunkBuffers.size * 2))
        chunkBuffers[chunk] = buffer
        if (chunk >= chunkCount) chunkCount = chunk + 1
    }

    /**
     * Adds a draw of [quadCount] icons from [firstQuad] within [chunk]. Segments must be added in chunk order.
     */
    fun addSegment(chunk: Int, firstQuad: Int, quadCount: Int, texture: Texture?, isDepthTest: Boolean) {
        if (segmentCount == segmentFirst.size) {
            val capacity = segmentCount * 2
            segmentChunk = segmentChunk.copyOf(capacity)
            segmentFirst = segmentFirst.copyOf(capacity)
            segmentSize = segmentSize.copyOf(capacity)
            segmentDepthTest = segmentDepthTest.copyOf(capacity)
            segmentTextures = segmentTextures.copyOf(capacity)
        }
        segmentChunk[segmentCount] = chunk
        segmentFirst[segmentCount] = firstQuad
        segmentSize[segmentCount] = quadCount
        segmentDepthTest[segmentCount] = isDepthTest
        segmentTextures[segmentCount] = texture
        segmentCount++
    }

    override fun recycle() {
        for (i in 0 until segmentCount) segmentTextures[i] = null
        for (i in 0 until chunkCount) chunkBuffers[i] = null
        segmentCount = 0
        chunkCount = 0
        program = null
        indexBuffer = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return
        val indexBuffer = indexBuffer ?: return
        if (!program.useProgram(dc)) return
        if (!indexBuffer.bindBuffer(dc)) return // the first upload has not happened yet

        program.loadFrame(this)
        dc.activeTextureUnit(GL_TEXTURE0)

        val gl = dc.gl
        for (i in 1..6) gl.enableVertexAttribArray(i) // only vertexPoint is enabled by default
        var boundChunk = -1
        var chunkReady = false
        for (i in 0 until segmentCount) {
            val chunk = segmentChunk[i]
            if (chunk != boundChunk) {
                boundChunk = chunk
                chunkReady = chunkBuffers[chunk]?.bindBuffer(dc) == true // not uploaded yet: skip its segments
                if (chunkReady) setAttributePointers(dc)
            }
            if (!chunkReady) continue

            val texture = segmentTextures[i]
            if (texture?.bindTexture(dc) == true) {
                program.enableTexture(true)
                program.loadTexCoordMatrix(texture.coordTransform)
            } else {
                program.enableTexture(false)
                // prevent "RENDER WARNING: there is no texture bound to unit 0"
                dc.defaultTexture.bindTexture(dc)
            }
            val isDepthTest = segmentDepthTest[i]
            if (!isDepthTest) gl.disable(GL_DEPTH_TEST)
            gl.drawElements(
                GL_TRIANGLES, segmentSize[i] * INDICES_PER_QUAD, GL_UNSIGNED_SHORT,
                segmentFirst[i] * INDICES_PER_QUAD * Short.SIZE_BYTES
            )
            if (!isDepthTest) gl.enable(GL_DEPTH_TEST)
        }

        // Restore the default WorldWind OpenGL state.
        for (i in 1..6) gl.disableVertexAttribArray(i)
    }

    private fun setAttributePointers(dc: DrawContext) {
        val gl = dc.gl
        gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, STRIDE, 0)
        gl.vertexAttribPointer(1 /*vertexFlags*/, 4, GL_UNSIGNED_BYTE, false, STRIDE, 12)
        gl.vertexAttribPointer(2 /*vertexNormal*/, 4, GL_BYTE, true, STRIDE, 16)
        gl.vertexAttribPointer(3 /*vertexSize*/, 4, GL_SHORT, false, STRIDE, 20)
        gl.vertexAttribPointer(4 /*vertexAngles*/, 4, GL_SHORT, true, STRIDE, 28)
        gl.vertexAttribPointer(5 /*vertexColor*/, 4, GL_UNSIGNED_BYTE, true, STRIDE, 36)
        gl.vertexAttribPointer(6 /*vertexThreshold*/, 1, GL_FLOAT, false, STRIDE, 40)
    }
}
