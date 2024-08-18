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
import kotlin.jvm.JvmOverloads

open class StaticPathData(
    val positions: MutableList<Position>, color: Color, lineWidth : Float, highlightColor: Color, highlightLineWidth : Float, val displayName: String?)
    : Highlightable {

    private var color = color
        set(value) {
            if (field != value) {
                field.copy(value)
                isColorDirty = isColorDirty || !isHighlighted
            }
        }

    private var lineWidth = lineWidth
        set(value) {
            if (field != value) {
                field = value
                isLineWidthDirty = isLineWidthDirty || !isHighlighted
            }
        }

    private var highLightedColor = highlightColor
        set(value) {
            if (field != value) {
                field.copy(value)
                isColorDirty = isColorDirty || isHighlighted
            }
        }

    private var highlightedLineWidth = highlightLineWidth
        set(value) {
            if (field != value) {
                field = value
                isLineWidthDirty = isLineWidthDirty || isHighlighted
            }
        }

    var pickColorIdKey = Any()
        private set

    var pickColorId: Int = 0
        set(value) {
            if (field != value) {
                field = value
                PickedObject.identifierToUniqueColor(value, pickColor)
                isPickColorDirty = true
            }
        }

    val pickColor = Color()

    var vertexCount: Int = 0
    var activeColor = Color()
    var activeLineWeight = 0.0f
    var isColorDirty = false
    var isPickColorDirty = false
    var isLineWidthDirty = false
    var isPositionsDirty = false

    override var isHighlighted = false
        set(value) {
            if (field != value) {
                field = value
                isColorDirty = true
                isLineWidthDirty = true
            }
        }

    fun determineActiveAttribs() {
        activeColor = if (isHighlighted) highLightedColor else color
        activeLineWeight = if (isHighlighted) highlightedLineWidth else lineWidth
    }

    fun removePosition(position: Position) {
        positions.remove(position)
        isPositionsDirty = true
    }

    fun addPosition(position: Position) {
        positions.add(position)
        isPositionsDirty = true
    }
}

open class LinesBatch @JvmOverloads constructor(
    protected val paths: MutableList<StaticPathData> = mutableListOf<StaticPathData>(), attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {

    protected var vertexArray = FloatArray(0)
    protected var colorArray = IntArray(0)
    protected var pickColorArray = IntArray(0)
    protected var widthArray = FloatArray(0)
    protected var vertexIndex = 0
    protected val outlineElements = mutableListOf<Int>()
    protected lateinit var vertexBufferKey: Any
    protected lateinit var colorBufferKey: Any
    protected lateinit var pickColorBufferKey: Any
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
        return paths.add(staticPathData)
    }

    // Override doRender here to remove pick related logic from AbstractShape, it's handled by individual lines
    override fun doRender(rc: RenderContext) {
        checkGlobeState(rc)
        if (!isWithinProjectionLimits(rc)) return

        // Don't render anything if the shape is not visible.
        if (!intersectsFrustum(rc)) return

        // Select the currently active attributes. Don't render anything if the attributes are unspecified.
        determineActiveAttributes(rc)

        // Determine whether the shape geometry must be assembled as Cartesian geometry or as geographic geometry.
        isSurfaceShape = rc.globe.is2D || altitudeMode == AltitudeMode.CLAMP_TO_GROUND && isFollowTerrain

        // Enqueue drawables for processing on the OpenGL thread.
        makeDrawable(rc)
    }

    override fun reset() {
        super.reset()
        vertexArray = FloatArray(0)
        colorArray = IntArray(0)
        pickColorArray = IntArray(0)
        widthArray = FloatArray(0)
        outlineElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (paths.isEmpty()) return  // nothing to draw

        var assemblePositions = vertexArray.isEmpty()
        var assembleColor = false
        var assemblePickColor = false
        var assembleLineWidth = false
        for (path in paths) {
            if (path.positions.isEmpty()) continue

            // update pickColor cache
            path.pickColorId = rc.nextPickedObjectId(path.pickColorIdKey)
            path.determineActiveAttribs()

            if(rc.isPickMode) rc.offerPickedObject(PickedObject.fromAny(path.pickColorId, path))

            // reset everything if positions have been modified
            assemblePositions = assemblePositions || path.isPositionsDirty
            assembleColor = assemblePositions || assembleColor || path.isColorDirty
            assemblePickColor = assemblePositions || assemblePickColor || path.isPickColorDirty
            assembleLineWidth = assemblePositions || assembleLineWidth || path.isLineWidthDirty

            path.isPositionsDirty = false
            path.isPickColorDirty = false
            path.isColorDirty = false
            path.isLineWidthDirty = false
        }

        // reset caches depending on flags
        if (assemblePositions) {
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
        }
        if(assembleColor) colorBufferKey = nextCacheKey()
        if(assemblePickColor) pickColorBufferKey = nextCacheKey()
        if(assembleLineWidth) widthBufferKey = nextCacheKey()

        // assemble buffer depending on flags
        assembleBuffers(rc, assemblePositions, assembleColor, assemblePickColor, assembleLineWidth)

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
        if (activeAttributes.isDrawOutline) {
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.drawElements(
                GL_TRIANGLES, outlineElements.size,
                GL_UNSIGNED_INT, 0
            )
        }

        // Configure the drawable according to the shape's attributes.
        drawState.isLine = true
        drawState.isStatic = true
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)
    }

    protected open fun assembleBuffers(rc: RenderContext, assembleGeometry : Boolean, assembleColor : Boolean, assemblePickColor : Boolean, assembleWidth: Boolean) {
        if(!(assembleGeometry || assembleColor || assemblePickColor || assembleWidth)) return

        val noIntermediatePoints = maximumIntermediatePoints <= 0 || pathType == LINEAR

        // Determine the number of vertexes
        var vertexCount = 0
        for (path in paths) {
            val p = path.positions

            if (p.isEmpty()) continue

            if (noIntermediatePoints) {
                path.vertexCount = p.size + 2
            } else {
                path.vertexCount = 2 + p.size + (p.size - 1) * maximumIntermediatePoints
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
        for (path in paths) {
            val positions = path.positions
            if (positions.isEmpty()) continue  // no boundary positions to assemble

            if(assembleGeometry) {
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
                addVertex(rc, begin.latitude, begin.longitude, begin.altitude, true)
            }

            if(assembleColor || assemblePickColor || assembleWidth) {
                for (idx in 0 until 2 * path.vertexCount) {
                    if (assembleColor) colorArray[tempVertexIndex] = path.activeColor.toColorIntRGBA()
                    if (assemblePickColor) pickColorArray[tempVertexIndex] = path.pickColor.toColorIntRGBA()
                    if (assembleWidth) widthArray[tempVertexIndex] = path.activeLineWeight + if (isSurfaceShape) 0.5f else 0f
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