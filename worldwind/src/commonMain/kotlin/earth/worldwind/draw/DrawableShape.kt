package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_CULL_FACE
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic

open class DrawableShape protected constructor(): Drawable {
    val drawState = DrawShapeState()
    private var pool: Pool<DrawableShape>? = null
    private val mvpMatrix = Matrix4()

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableShape>): DrawableShape {
            val instance = pool.acquire() ?: DrawableShape()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        drawState.reset()
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        // TODO shape batching
        val program = drawState.program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build
        if (drawState.elementBuffer?.bindBuffer(dc) != true) return  // element buffer unspecified or failed to bind

        // Use the shape's vertex point attribute and vertex texture coordinate attribute.
        dc.gl.enableVertexAttribArray(1 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(2 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(3 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(4 /*vertexTexCoord*/)
        dc.gl.enableVertexAttribArray(5 /*lineWidth*/)

        var bufferBound = false
        for (vertexBuffer in drawState.vertexBuffers) {
            bufferBound = vertexBuffer.vertexBuffer?.bindBuffer(dc) == true
            if (bufferBound) {
                for (vertexAttribute in vertexBuffer.attributes)
                    dc.gl.vertexAttribPointer(
                        vertexAttribute.index /*pointA*/,
                        vertexAttribute.size,
                        vertexAttribute.type,
                        vertexAttribute.normalized,
                        vertexAttribute.stride,
                        vertexAttribute.offset
                    )
            }
            else
            {
                break
            }
        }

        if(!bufferBound)
            return

        // Use the draw context's pick mode.
        program.enablePickMode(dc.isPickMode)

        // Use the draw context's modelview projection matrix, transformed to shape local coordinates.
        if (drawState.depthOffset != 0.0) {
            mvpMatrix.copy(dc.projection).offsetProjectionDepth(drawState.depthOffset)
            mvpMatrix.multiplyByMatrix(dc.modelview)
        } else {
            mvpMatrix.copy(dc.modelviewProjection)
        }
        mvpMatrix.multiplyByTranslation(
            drawState.vertexOrigin.x,
            drawState.vertexOrigin.y,
            drawState.vertexOrigin.z
        )
        program.loadModelviewProjection(mvpMatrix)

        // Disable triangle back face culling if requested.
        if (!drawState.enableCullFace) dc.gl.disable(GL_CULL_FACE)

        // Disable depth testing if requested.
        if (!drawState.enableDepthTest) dc.gl.disable(GL_DEPTH_TEST)

        // Disable depth writing if requested.
        if (!drawState.enableDepthWrite) dc.gl.depthMask(false)

        // Make multi-texture unit 0 active.
        dc.activeTextureUnit(GL_TEXTURE0)


        program.enableLinesMode(drawState.isLine)
        program.loadScreen(dc.viewport.width.toFloat(), dc.viewport.height.toFloat())

        // Draw the specified primitives.
        for (idx in 0 until drawState.primCount) {
            val prim = drawState.prims[idx]
            program.loadOpacity(prim.opacity)
            if (prim.texture?.bindTexture(dc) == true) {
                program.loadTexCoordMatrix(prim.texCoordMatrix)
                program.enableTexture(true)
            } else {
                program.enableTexture(false)
            }
            if (!drawState.isLine) {
                program.loadColor(prim.color)
                dc.gl.lineWidth(prim.lineWidth)
            }
            dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
        }

        // Restore the default WorldWind OpenGL state.
        if (!drawState.enableCullFace) dc.gl.enable(GL_CULL_FACE)
        if (!drawState.enableDepthTest) dc.gl.enable(GL_DEPTH_TEST)
        if (!drawState.enableDepthWrite) dc.gl.depthMask(true)
        dc.gl.lineWidth(1f)
        dc.gl.enable(GL_CULL_FACE)
        dc.gl.disableVertexAttribArray(1 /*vertexTexCoord*/)
        dc.gl.disableVertexAttribArray(2 /*vertexTexCoord*/)
        dc.gl.disableVertexAttribArray(3 /*vertexTexCoord*/)
        dc.gl.disableVertexAttribArray(4 /*vertexTexCoord*/)
        dc.gl.disableVertexAttribArray(5 /*vertexTexCoord*/)
    }
}