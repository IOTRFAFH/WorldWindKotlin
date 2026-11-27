package earth.worldwind.shape

import earth.worldwind.geom.Camera
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.render.RenderContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Checks that every window rendering a layer gets its own placemark indexes and batch state, and that a window that
 * stops rendering is dropped and gives its slot keys back.
 */
class PlacemarkCellsWindowTest {
    private fun window(globe: Globe) = RenderContext().also {
        it.globe = globe
        it.globeState = globe.state
        it.camera = Camera()
        it.viewport.set(0, 0, 1000, 1000)
        it.terrain = BasicTerrain(emptyList(), Sector(), null)
    }

    @Test
    fun eachWindowHasItsOwnIndex() {
        val byWindow = PlacemarkCellsByWindow()
        val main = window(Globe())
        val second = window(Globe(projection = MercatorProjection()))
        val mainCells = assertNotNull(byWindow.cellsFor(main))
        val secondCells = assertNotNull(byWindow.cellsFor(second))
        assertNotSame(mainCells, secondCells)
        assertSame(mainCells, byWindow.cellsFor(main))
        assertSame(secondCells, byWindow.cellsFor(second))
        mainCells.beginFrame(main)
        secondCells.beginFrame(second)
        assertNotEquals(mainCells.slotKey, secondCells.slotKey)
        byWindow.clear()
    }

    @Test
    fun windowsKeepTheirOwnPointsForTheSamePlacemark() {
        val main = window(Globe())
        val second = window(Globe(projection = MercatorProjection()))
        val mainCells = PlacemarkCells()
        val secondCells = PlacemarkCells()
        val mainBatch = PlacemarkBatch(mainCells, null, PlacemarkBatchDiagnostics())
        val secondBatch = PlacemarkBatch(secondCells, null, PlacemarkBatchDiagnostics())
        val placemark = Placemark(Position.fromDegrees(48.5, 31.0, 0.0))
        val mainPoint = Vec3()
        val secondPoint = Vec3()
        // Both windows draw the same placemark every frame, one after the other.
        repeat(3) {
            mainCells.beginFrame(main)
            mainBatch.resolvePlacePoint(main, placemark, mainPoint)
            secondCells.beginFrame(second)
            secondBatch.resolvePlacePoint(second, placemark, secondPoint)
        }
        assertEquals(main.globe.geographicToCartesian(placemark.position.latitude, placemark.position.longitude,
            main.globe.getElevation(placemark.position.latitude, placemark.position.longitude), Vec3()), mainPoint)
        assertNotEquals(mainPoint, secondPoint) // 3D and Mercator: different Cartesian systems
        assertSame(mainBatch, placemark.batchSlot(mainCells.slotKey)?.batch)
        assertSame(secondBatch, placemark.batchSlot(secondCells.slotKey)?.batch)
        assertEquals(1, mainBatch.slotCount)
        assertEquals(1, secondBatch.slotCount)
        // Clearing one window's batch detaches only that window's slot.
        mainBatch.clear()
        assertNull(placemark.batchSlot(mainCells.slotKey))
        assertSame(secondBatch, placemark.batchSlot(secondCells.slotKey)?.batch)
        secondBatch.clear()
        mainCells.clear()
        secondCells.clear()
    }

    @Test
    fun idleWindowIsDroppedAndGivesItsKeysBack() {
        val byWindow = PlacemarkCellsByWindow()
        val old = window(Globe())
        val recreated = window(Globe())
        val oldCells = assertNotNull(byWindow.cellsFor(old))
        oldCells.beginFrame(old)
        val oldKey = oldCells.slotKey
        repeat(PlacemarkCellsByWindow.WINDOW_IDLE_FRAMES.toInt() + 2) { byWindow.cellsFor(recreated) }
        assertEquals(1, byWindow.windowCount)
        assertEquals(-1, oldCells.slotKey)
        // The freed key is the lowest free one again: the next index to start takes it.
        val probe = PlacemarkCells().also { it.beginFrame(recreated) }
        assertTrue(probe.slotKey <= oldKey)
        probe.clear()
        byWindow.clear()
    }

    @Test
    fun singleWindowIsNeverDropped() {
        val byWindow = PlacemarkCellsByWindow()
        val main = window(Globe())
        val cells = assertNotNull(byWindow.cellsFor(main))
        repeat(PlacemarkCellsByWindow.WINDOW_IDLE_FRAMES.toInt() * 2) { byWindow.cellsFor(main) }
        assertSame(cells, byWindow.cellsFor(main))
        assertEquals(1, byWindow.windowCount)
        byWindow.clear()
    }

    @Test
    fun pickingInANewWindowCreatesNoIndex() {
        val byWindow = PlacemarkCellsByWindow()
        val main = window(Globe())
        main.isPickMode = true
        assertNull(byWindow.cellsFor(main))
        assertEquals(0, byWindow.windowCount)
    }
}
