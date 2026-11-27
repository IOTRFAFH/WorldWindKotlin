package earth.worldwind.shape

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawablePlacemarkBatch
import earth.worldwind.draw.DrawableQueue
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Camera
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.RenderContext
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.render.image.ImageSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks where a [PlacemarkBatch]'s drawables sort among the frame's other shape drawables: depth-tested icons at the
 * farthest visible icon's distance, icons drawn without the depth test at the nearest one's.
 */
class PlacemarkBatchOrderTest {
    /** A drawable that only marks a place in the queue. */
    private class Marker(val name: String) : Drawable {
        override fun draw(dc: DrawContext) {}
        override fun recycle() {}
        override fun toString() = name
    }

    private lateinit var rc: RenderContext
    private lateinit var queue: DrawableQueue
    private val image = ImageSource.fromUrlString("https://example.invalid/icon.png")

    @BeforeTest
    fun setUp() {
        val globe = Globe()
        rc = RenderContext()
        rc.globe = globe
        rc.globeState = globe.state
        rc.camera = Camera()
        rc.viewport.set(0, 0, 1000, 1000)
        rc.terrain = BasicTerrain(emptyList(), Sector(), null)
        rc.renderResourceCache = RenderResourceCache()
        rc.currentLayer = RenderableLayer()
        queue = DrawableQueue()
        rc.drawableQueue = queue
        // Camera 1000 km above Kyiv; the frustum stays the unit one, so no texture is requested.
        globe.geographicToCartesian(fromDegrees(50.45), fromDegrees(30.52), 1.0e6, rc.cameraPoint)
    }

    private fun placemark(latitude: Double, isDepthTest: Boolean = true) =
        Placemark(Position.fromDegrees(latitude, 30.52, 0.0)).apply {
            attributes = PlacemarkAttributes.createWithImage(image).apply { this.isDepthTest = isDepthTest }
        }

    private fun distanceSq(placemark: Placemark) =
        rc.globe.geographicToCartesian(placemark.position.latitude, placemark.position.longitude, 0.0, Vec3())
            .distanceToSquared(rc.cameraPoint)

    /** Renders [placemarks] through one batch for one frame and returns the sorted queue. */
    private fun drawOrder(placemarks: List<Placemark>, markers: Map<Marker, Double>): List<Drawable> {
        val cells = PlacemarkCells()
        cells.beginFrame(rc)
        val batch = PlacemarkBatch(cells, null, PlacemarkBatchDiagnostics())
        batch.beginFrame(rc, rc.terrain as BasicTerrain)
        val point = Vec3()
        for (placemark in placemarks) {
            batch.resolvePlacePoint(rc, placemark, point)
            batch.update(rc, placemark, placemark.attributes)
        }
        for ((marker, distanceSq) in markers) rc.offerShapeDrawable(marker, distanceSq)
        batch.endFrame(rc)
        queue.sortDrawables()
        val order = (0 until queue.count).mapNotNull { queue.getDrawable(it) }
        batch.clear()
        cells.clear()
        return order
    }

    @Test
    fun depthTestedIconsSortAtTheFarthestIcon() {
        val near = placemark(50.45)
        val far = placemark(46.0)
        val nearSq = distanceSq(near)
        val farSq = distanceSq(far)
        val beyond = Marker("beyond")
        val between = Marker("between")
        val highlighted = Marker("highlighted")
        val order = drawOrder(
            listOf(near, far), mapOf(beyond to farSq * 1.5, between to (nearSq + farSq) / 2, highlighted to 0.0)
        )
        val batch = order.indexOfFirst { it is DrawablePlacemarkBatch }
        assertEquals(1, order.count { it is DrawablePlacemarkBatch })
        assertTrue(order.indexOf(beyond) < batch, "farther than every icon: drawn before the batch")
        assertTrue(order.indexOf(between) > batch, "nearer than the farthest icon: drawn over the batch")
        assertTrue(order.indexOf(highlighted) > batch, "always-on-top placemarks: drawn over the batch")
    }

    @Test
    fun iconsWithoutDepthTestSortAtTheNearestIcon() {
        val near = placemark(50.45, isDepthTest = false)
        val far = placemark(46.0, isDepthTest = false)
        val nearSq = distanceSq(near)
        val farSq = distanceSq(far)
        val between = Marker("between")
        val nearer = Marker("nearer")
        val order = drawOrder(listOf(near, far), mapOf(between to (nearSq + farSq) / 2, nearer to nearSq / 2))
        val batch = order.indexOfFirst { it is DrawablePlacemarkBatch }
        assertTrue(order.indexOf(between) < batch, "farther than the nearest icon: covered by the batch")
        assertTrue(order.indexOf(nearer) > batch, "nearer than every icon: drawn over the batch")
    }

    @Test
    fun mixedDepthTestGetsOneDrawableEach() {
        val tested = placemark(50.45)
        val overlay = placemark(46.0, isDepthTest = false)
        val order = drawOrder(listOf(tested, overlay), emptyMap())
        assertEquals(2, order.count { it is DrawablePlacemarkBatch })
    }
}
