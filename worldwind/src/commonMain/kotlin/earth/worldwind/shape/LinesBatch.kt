package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.draw.VertexBufferWithAttribs
import earth.worldwind.geom.*
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.IntBufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmOverloads

open class StaticPathData(
    var positions: List<Position>,  var color: Color, var lineWidth : Float)
{
}

open class LinesBatch @JvmOverloads constructor(
    pathes: MutableList<StaticPathData> = mutableListOf<StaticPathData>(), attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {

    protected val pathes  = pathes
    val pathesCount get() = pathes.size

    protected var vertexArray = FloatArray(0)
    protected var colorArray = IntArray(0)
    protected var widthArray = FloatArray(0)
    protected var vertexIndex = 0
    // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
    protected val outlineElements = mutableListOf<Int>()
    protected lateinit var vertexBufferKey: Any
    protected lateinit var colorBufferKey: Any
    protected lateinit var widthBufferKey: Any
    protected lateinit var elementBufferKey: Any
    protected val vertexOrigin = Vec3()
    private val point = Vec3()
    private val prevPoint = Vec3()
    private val intermediateLocation = Location()

    companion object {
        protected const val VERTEX_STRIDE = 8

        protected fun nextCacheKey() = Any()
    }

    fun addPath(staticPathData : StaticPathData): Boolean {
        reset()
        // TODO Make deep copy of positions the same way as for single position shapes?
        return pathes.add(staticPathData)
    }

    override fun reset() {
        super.reset()
        vertexArray = FloatArray(0)
        colorArray = IntArray(0)
        widthArray = FloatArray(0)
        outlineElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (pathes.isEmpty()) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            assembleGeometry(rc)
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
            colorBufferKey = nextCacheKey()
            widthBufferKey = nextCacheKey()
        }

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
        val vertexBuffer = VertexBufferWithAttribs()
        vertexBuffer.vertexBuffer = rc.getBufferObject(vertexBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, vertexArray) }
        vertexBuffer.addAttribute(0, 4, GL_FLOAT, false, 16, 0) // pointA
        vertexBuffer.addAttribute(1, 4, GL_FLOAT, false, 16, 32) // pointB
        vertexBuffer.addAttribute(2, 4, GL_FLOAT, false, 16, 64) // pointC
        vertexBuffer.addAttribute(3, 1, GL_FLOAT, false, 0,0) // texCoord
        drawState.addVertexBuffer(vertexBuffer)

        val colorBuffer = VertexBufferWithAttribs()
        colorBuffer.vertexBuffer = rc.getBufferObject(colorBufferKey) { IntBufferObject(GL_ARRAY_BUFFER, colorArray) }
        colorBuffer.addAttribute(4, 4, GL_UNSIGNED_BYTE, true, 4,0) // color
        drawState.addVertexBuffer(colorBuffer)

        val widthBuffer = VertexBufferWithAttribs()
        widthBuffer.vertexBuffer = rc.getBufferObject(widthBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, widthArray) }
        widthBuffer.addAttribute(5, 1, GL_FLOAT, false, 4,0) // lineWidth
        drawState.addVertexBuffer(widthBuffer)

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
        if (activeAttributes.isDrawOutline) {
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.drawElements(
                GL_TRIANGLES, outlineElements.size,
                GL_UNSIGNED_INT, 0
            )
        }

        drawState.isLine = true
        drawState.isStatic = true
        // Configure the drawable according to the shape's attributes.
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)
    }

    protected open fun mustAssembleGeometry(rc: RenderContext) = vertexArray.isEmpty()

    protected open fun assembleGeometry(rc: RenderContext) {
        val noIntermediatePoints = maximumIntermediatePoints <= 0 || pathType == LINEAR

        // Determine the number of vertexes
        var vertexCount = 0
        for (i in pathes.indices) {
            val p = pathes[i].positions

            if (p.isEmpty()) continue

            if (noIntermediatePoints) {
                vertexCount += p.size + 2
            } else {
                vertexCount += 2 + p.size + (p.size - 1) * maximumIntermediatePoints
            }
        }

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        vertexArray = FloatArray(vertexCount * VERTEX_STRIDE)
        colorArray = IntArray(vertexCount * 2)
        widthArray = FloatArray(vertexCount * 2)
        outlineElements.clear()

        var tempVertexIndex = 0
        for (i in pathes.indices) {
            val positions = pathes[i].positions
            if (positions.isEmpty()) continue  // no boundary positions to assemble

            // Add the first vertex.
            var begin = positions[0]
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, false)
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, false)
            // Add the remaining vertices, inserting vertices along each edge as indicated by the path's properties.
            for (idx in 1 until positions.size) {
                val end = positions[idx]
                addIntermediateVertices(rc, begin, end)
                addVertex(rc, end.latitude, end.longitude, end.altitude, true)
                begin = end
            }
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude,true )

            for(idx in tempVertexIndex until vertexIndex * 2 / VERTEX_STRIDE) {
                colorArray[idx] = pathes[i].color.toColorIntRGBA()
                widthArray[idx] = pathes[i].lineWidth + if (isSurfaceShape) 0.5f else 0f
            }

            tempVertexIndex = vertexIndex * 2 / VERTEX_STRIDE
        }

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
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

    protected open fun addIntermediateVertices(rc: RenderContext, begin: Position, end: Position) {
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
            addVertex(rc, loc.latitude, loc.longitude, alt, true )
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, addIndices : Boolean
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