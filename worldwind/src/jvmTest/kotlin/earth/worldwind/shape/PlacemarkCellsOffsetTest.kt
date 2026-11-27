package earth.worldwind.shape

import earth.worldwind.geom.Camera
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.render.RenderContext
import kotlin.math.PI
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Checks that each copy of a continuous 2D map gets its own placemark index and batch state: the copies are rendered
 * one after the other in a frame, and their Cartesian points differ by the globe's circumference.
 */
class PlacemarkCellsOffsetTest {
    private lateinit var rc: RenderContext
    private lateinit var globe: Globe

    @BeforeTest
    fun setUp() {
        globe = Globe(projection = MercatorProjection())
        rc = RenderContext()
        rc.globe = globe
        rc.globeState = globe.state
        rc.camera = Camera()
        rc.viewport.set(0, 0, 1000, 1000)
        // Empty terrain: clamped points come from the elevation model fallback.
        rc.terrain = BasicTerrain(emptyList(), Sector(), null)
    }

    private fun cellsAt(byOffset: PlacemarkCellsByOffset, offset: Globe.Offset): PlacemarkCells? {
        globe.offset = offset
        return byOffset.cellsFor(rc)
    }

    @Test
    fun eachOffsetHasItsOwnIndex() {
        val byOffset = PlacemarkCellsByOffset()
        val center = assertNotNull(cellsAt(byOffset, Globe.Offset.Center))
        val right = assertNotNull(cellsAt(byOffset, Globe.Offset.Right))
        val left = assertNotNull(cellsAt(byOffset, Globe.Offset.Left))
        assertNotSame(center, right)
        assertNotSame(center, left)
        assertNotSame(right, left)
        assertEquals(Globe.Offset.Right, right.offset)
        // The next frame gets the same indexes back.
        assertSame(center, cellsAt(byOffset, Globe.Offset.Center))
        assertSame(right, cellsAt(byOffset, Globe.Offset.Right))
    }

    @Test
    fun pickingCreatesNoIndex() {
        val byOffset = PlacemarkCellsByOffset()
        rc.isPickMode = true
        assertNull(cellsAt(byOffset, Globe.Offset.Right))
        rc.isPickMode = false
        val right = assertNotNull(cellsAt(byOffset, Globe.Offset.Right))
        rc.isPickMode = true
        assertSame(right, cellsAt(byOffset, Globe.Offset.Right))
    }

    @Test
    fun idleSideIndexIsDropped() {
        val byOffset = PlacemarkCellsByOffset()
        val right = assertNotNull(cellsAt(byOffset, Globe.Offset.Right))
        repeat(PlacemarkCellsByOffset.SIDE_IDLE_FRAMES.toInt()) { cellsAt(byOffset, Globe.Offset.Center) }
        assertSame(right, cellsAt(byOffset, Globe.Offset.Right)) // still within the idle limit
        repeat(PlacemarkCellsByOffset.SIDE_IDLE_FRAMES.toInt() + 1) { cellsAt(byOffset, Globe.Offset.Center) }
        assertNotSame(right, cellsAt(byOffset, Globe.Offset.Right))
    }

    @Test
    fun copiesKeepTheirOwnPointsAcrossPasses() {
        val placemark = Placemark(Position.fromDegrees(48.5, 179.5, 0.0))
        val centerCells = PlacemarkCells(Globe.Offset.Center)
        val rightCells = PlacemarkCells(Globe.Offset.Right)
        val centerBatch = PlacemarkBatch(centerCells, null, PlacemarkBatchDiagnostics())
        val rightBatch = PlacemarkBatch(rightCells, null, PlacemarkBatchDiagnostics())
        val centerPoint = Vec3()
        val rightPoint = Vec3()
        // Three frames of a wrapped view: center pass, then right pass.
        repeat(3) {
            globe.offset = Globe.Offset.Center
            centerCells.beginFrame(rc)
            centerBatch.resolvePlacePoint(rc, placemark, centerPoint)
            globe.offset = Globe.Offset.Right
            rightCells.beginFrame(rc)
            rightBatch.resolvePlacePoint(rc, placemark, rightPoint)
        }
        assertEquals(2.0 * PI * globe.ellipsoid.semiMajorAxis, rightPoint.x - centerPoint.x, 1.0e-3)
        assertEquals(centerPoint.y, rightPoint.y, 1.0e-6)
        val centerSlot = assertNotNull(placemark.batchSlot(centerCells.slotKey))
        val rightSlot = assertNotNull(placemark.batchSlot(rightCells.slotKey))
        assertSame(centerBatch, centerSlot.batch)
        assertSame(rightBatch, rightSlot.batch)
        // One slot per copy, kept across passes instead of being replaced on every pass.
        assertEquals(1, centerBatch.slotCount)
        assertEquals(1, rightBatch.slotCount)
        centerCells.clear()
        rightCells.clear()
    }
}
