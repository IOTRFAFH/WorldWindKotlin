package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.IntBufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.kgl.*

open class LinesBatch(private val isSurfaceShape : Boolean): Boundable {

    protected var vertexArray = FloatArray(0)
    protected var colorArray = IntArray(0)
    protected var pickColorArray = IntArray(0)
    protected var widthArray = FloatArray(0)
    protected var vertexIndex = 0
    protected val outlineElements = mutableListOf<Int>()
    protected val paths: Array<Path?> = arrayOfNulls(MAX_PATHS)
    protected var pathCount: Int = 0
    protected lateinit var vertexBufferKey: Any
    protected lateinit var colorBufferKey: Any
    protected lateinit var pickColorBufferKey: Any
    protected lateinit var widthBufferKey: Any
    protected lateinit var elementBufferKey: Any
    protected val vertexOrigin = Vec3()
    private val point = Vec3()
    private val prevPoint = Vec3()
    private val intermediateLocation = Location()

    override val boundingSector = Sector()
    override val boundingBox = BoundingBox()
    override val scratchPoint = Vec3()

    companion object {
        protected const val MAX_PATHS = 256
        protected const val VERTEX_STRIDE = 8
        const val NEAR_ZERO_THRESHOLD = 1.0e-10

        protected fun nextCacheKey() = Any()
    }

    fun isFull() : Boolean
    {
        return pathCount == MAX_PATHS
    }

    fun addPath(path : Path) : Boolean {
        if (isFull()) return false
        paths[pathCount++] = path
        reset()
        return true
    }

    fun removePath(path : Path) : Boolean {
        if (pathCount == 0) return false
        val index = paths.indexOf(path)
        if (index == -1) return false
        paths[index] = paths[--pathCount]
        paths[pathCount] = null
        reset()
        return true
    }

    // Override doRender here to remove pick related logic from AbstractShape, it's handled by individual lines
     fun render(rc: RenderContext) {
        if (!isWithinProjectionLimits(rc)) return

        // Don't render anything if the shape is not visible.
        if (!intersectsFrustum(rc)) return

        // Enqueue drawables for processing on the OpenGL thread.
        makeDrawable(rc)
    }

    fun reset() {
        boundingBox.setToUnitBox()
        boundingSector.setEmpty()
        vertexArray = FloatArray(0)
        colorArray = IntArray(0)
        pickColorArray = IntArray(0)
        widthArray = FloatArray(0)
        outlineElements.clear()
    }

    private fun makeDrawable(rc: RenderContext) {
        if (pathCount == 0) return  // nothing to draw

        var assemblePositions = vertexArray.isEmpty()
        for (idx in 0 until pathCount ) {
            val path = paths[idx] ?: break
            if (path.positions.isEmpty()) continue

            assemblePositions = assemblePositions || path.forceRecreateBatch
            path.forceRecreateBatch = false

            if(rc.isPickMode) rc.offerPickedObject(PickedObject.fromRenderable(path.pickedObjectId, path, rc.currentLayer))
        }

        // reset caches depending on flags
        if (assemblePositions) {
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
            colorBufferKey = nextCacheKey()
            pickColorBufferKey = nextCacheKey()
            widthBufferKey = nextCacheKey()
        }

        // assemble buffer depending on flags
        assembleBuffers(rc, assemblePositions, assemblePositions, assemblePositions, assemblePositions)

        // Obtain a drawable form the render context pool, and compute distance to the render camera.
        val drawable: Drawable
        val drawState: DrawShapeState
        val cameraDistance: Double
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool<DrawableSurfaceShape>()
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceGeographic(rc, boundingSector)
            drawable.offset = rc.globe.offset
            drawable.sector.copy(boundingSector)
        } else {
            val pool = rc.getDrawablePool<DrawableShape>()
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceCartesian(
                rc, vertexArray, vertexArray.size,
                VERTEX_STRIDE, vertexOrigin
            )
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        val vertexBuffer = rc.getBufferObject(vertexBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, vertexArray) }
        drawState.vertexState.addAttribute(0, vertexBuffer,4, GL_FLOAT, false, 16, 0) // pointA
        drawState.vertexState.addAttribute(1, vertexBuffer, 4, GL_FLOAT, false, 16, 32) // pointB
        drawState.vertexState.addAttribute(2, vertexBuffer, 4, GL_FLOAT, false, 16, 64) // pointC
        drawState.vertexState.addAttribute(3, vertexBuffer, 1, GL_FLOAT, false, 0,0) // texCoord

        val colorBuffer = rc.getBufferObject(colorBufferKey) { IntBufferObject(GL_ARRAY_BUFFER, colorArray) }
        val pickColorBuffer = rc.getBufferObject(pickColorBufferKey) { IntBufferObject(GL_ARRAY_BUFFER, pickColorArray) }
        drawState.vertexState.addAttribute(4, if(rc.isPickMode) pickColorBuffer else colorBuffer, 4, GL_UNSIGNED_BYTE, true, 4, 0) // color

        val widthBuffer = rc.getBufferObject(widthBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, widthArray) }
        drawState.vertexState.addAttribute(5, widthBuffer, 1, GL_FLOAT, false, 4,0) // lineWidth

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(elementBufferKey) {
            val array = IntArray(outlineElements.size)
            var index = 0
            for (element in outlineElements) array[index++] = element
            IntBufferObject(GL_ELEMENT_ARRAY_BUFFER, array)
        }

        // Disable texturing
        drawState.texture(null)

        // Configure the drawable to display the shape's extruded verticals.
        drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
        drawState.drawElements(
            GL_TRIANGLES, outlineElements.size,
            GL_UNSIGNED_INT, 0
        )

        // Configure the drawable according to the shape's attributes.
        drawState.isLine = true
        drawState.isStatic = true
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.enableCullFace = false
        drawState.enableDepthTest = true
        drawState.enableDepthWrite = true

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)
    }

    protected open fun assembleBuffers(rc: RenderContext, assembleGeometry : Boolean, assembleColor : Boolean, assemblePickColor : Boolean, assembleWidth: Boolean) {
        if(!(assembleGeometry || assembleColor || assemblePickColor || assembleWidth)) return


        // Determine the number of vertexes
        var vertexCount = 0
        for (idx in 0 until pathCount ) {
            val path = paths[idx] ?: break // break never gonna be hit, need something better
            val p = path.positions

            if (p.isEmpty()) continue

            val noIntermediatePoints = path.maximumIntermediatePoints <= 0 || path.pathType == LINEAR

            if (noIntermediatePoints) {
                path.vertexCount = p.size + 2
            } else {
                path.vertexCount = 2 + p.size + (p.size - 1) * path.maximumIntermediatePoints
            }

            vertexCount += path.vertexCount
        }

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        if(assembleGeometry) {
            vertexIndex = 0
            vertexArray = FloatArray(vertexCount * VERTEX_STRIDE)
            outlineElements.clear()
        }
        if(assembleColor) colorArray = IntArray(vertexCount * 2)
        if(assemblePickColor) pickColorArray = IntArray(vertexCount * 2)
        if(assembleWidth) widthArray = FloatArray(vertexCount * 2)

        var tempVertexIndex = 0
        for (idx in 0 until pathCount ) {
            val path = paths[idx] ?: break
            val positions = path.positions
            if (positions.isEmpty()) continue  // no boundary positions to assemble

            if(assembleGeometry) {
                // Add the first vertex.
                var begin = positions[0]
                addVertex(rc, begin.latitude, begin.longitude, begin.altitude, path.altitudeMode, false)
                addVertex(rc, begin.latitude, begin.longitude, begin.altitude, path.altitudeMode,false)
                // Add the remaining vertices, inserting vertices along each edge as indicated by the path's properties.
                for (vertexIdx in 1 until positions.size) {
                    val end = positions[vertexIdx]
                    addIntermediateVertices(rc, begin, end, path.maximumIntermediatePoints, path.pathType, path.altitudeMode)
                    addVertex(rc, end.latitude, end.longitude, end.altitude, path.altitudeMode,true)
                    begin = end
                }
                addVertex(rc, begin.latitude, begin.longitude, begin.altitude, path.altitudeMode,true)
            }

            if(assembleColor || assemblePickColor || assembleWidth) {
                for (vertexIdx in 0 until 2 * path.vertexCount) {
                   // PickedObject.identifierToUniqueColor(path.pickedObjectId, path.pickColor)
                    if (assembleColor) colorArray[tempVertexIndex] = path.activeAttributes.outlineColor.toColorIntRGBA()
                    if (assemblePickColor) pickColorArray[tempVertexIndex] = path.pickColor.toColorIntRGBA()
                    if (assembleWidth) widthArray[tempVertexIndex] = path.activeAttributes.outlineWidth + if(isSurfaceShape) 0.5f else 0f
                    ++tempVertexIndex
                }
            }
        }

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        if(assembleGeometry) {
            if (isSurfaceShape) {
                boundingSector.setEmpty()
                boundingSector.union(vertexArray, vertexIndex, VERTEX_STRIDE)
                boundingSector.translate(vertexOrigin.y /*latitude*/, vertexOrigin.x /*longitude*/)
                boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
            } else {
                boundingBox.setToPoints(vertexArray, vertexIndex, VERTEX_STRIDE)
                boundingBox.translate(vertexOrigin.x, vertexOrigin.y, vertexOrigin.z)
                boundingSector.setEmpty() // Cartesian shape bounding sector is unused
            }
        }
    }

    protected open fun addIntermediateVertices(rc: RenderContext, begin: Position, end: Position, maximumIntermediatePoints : Int, pathType: PathType, altitudeMode: AltitudeMode) {
        if (maximumIntermediatePoints <= 0) return  // suppress intermediate vertices when configured to do so
        val azimuth: Angle
        val length: Double
        when (pathType) {
            GREAT_CIRCLE -> {
                azimuth = begin.greatCircleAzimuth(end)
                length = begin.greatCircleDistance(end)
            }
            RHUMB_LINE -> {
                azimuth = begin.rhumbAzimuth(end)
                length = begin.rhumbDistance(end)
            }
            else -> return  // suppress intermediate vertices when the path type is linear
        }
        if (length < NEAR_ZERO_THRESHOLD) return  // suppress intermediate vertices when the edge length less than a millimeter (on Earth)
        val numSubsegments = maximumIntermediatePoints + 1
        val deltaDist = length / numSubsegments
        val deltaAlt = (end.altitude - begin.altitude) / numSubsegments
        var dist = deltaDist
        var alt = begin.altitude + deltaAlt
        for (idx in 1 until numSubsegments) {
            val loc = intermediateLocation
            when (pathType) {
                GREAT_CIRCLE -> begin.greatCircleLocation(azimuth, dist, loc)
                RHUMB_LINE -> begin.rhumbLocation(azimuth, dist, loc)
                else -> {}
            }
            addVertex(rc, loc.latitude, loc.longitude, alt, altitudeMode, true )
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode, addIndices : Boolean
    ) {
        val vertex = (vertexIndex / VERTEX_STRIDE - 1) * 2
        val point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, point)
        if (vertexIndex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude)
            else vertexOrigin.copy(point)
        }
        prevPoint.copy(point)
        if (isSurfaceShape) {
            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = 1.0f

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = -1.0f
            if (addIndices) {
                outlineElements.add(vertex - 2) // 0    0 -- 2
                outlineElements.add(vertex - 1) // 1    |  / | ----> line goes this way
                outlineElements.add(vertex) // 2        | /  | ----> line goes this way
                outlineElements.add(vertex) // 2        1 -- 3
                outlineElements.add(vertex - 1) // 1
                outlineElements.add(vertex + 1) // 3
            }
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = 1.0f
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = -1.0f
            if (addIndices) {
                outlineElements.add(vertex - 2) // 0    0 -- 2
                outlineElements.add(vertex - 1) // 1    |  / | ----> line goes this way
                outlineElements.add(vertex) // 2        | /  | ----> line goes this way
                outlineElements.add(vertex) // 2        1 -- 3
                outlineElements.add(vertex - 1) // 1
                outlineElements.add(vertex + 1) // 3
            }
        }
    }
}