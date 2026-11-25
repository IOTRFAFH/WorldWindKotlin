package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Camera
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.AbstractEGM96Geoid
import earth.worldwind.globe.projection.GeographicProjection
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.projection.Wgs84Projection
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.globe.terrain.Terrain
import earth.worldwind.globe.terrain.TerrainTile
import earth.worldwind.render.RenderContext
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.runBlocking
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PointMemo] keeps its terms per globe and globe offset: several windows (3D and 2D side by side, or two 3D ones with
 * their own terrain) and the offset passes of a continuous 2D globe each keep their measured terrain height, and the
 * memo holds no reference to a globe or a tile.
 */
class PointMemoSlotsTest {
    /** Hills, on 2D and 3D terrain alike. */
    private class HillCoverage : AbstractElevationCoverage() {
        override fun clear() {}
        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) =
            height(latitude.inDegrees, longitude.inDegrees)
        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
            for (r in 0 until gridHeight) for (c in 0 until gridWidth) {
                val lat = gridSector.minLatitude.inDegrees + gridSector.deltaLatitude.inDegrees * r / (gridHeight - 1)
                val lon = gridSector.minLongitude.inDegrees + gridSector.deltaLongitude.inDegrees * c / (gridWidth - 1)
                result[r * gridWidth + c] = height(lat, lon)
            }
        }
        override fun doGetElevationLimits(sector: Sector, result: FloatArray) { result[0] = -200f; result[1] = 600f }
        fun height(lat: Double, lon: Double) = (400.0 * sin(lat * 40.0) * sin(lon * 55.0) + 200.0).toFloat()
    }

    /** One window: its globe and its own terrain tiles. */
    private inner class View(projection: GeographicProjection) {
        val globe = Globe(projection = projection).also { it.elevationModel.addCoverage(coverage) }
        val tiles = tiles()
    }

    private val coverage = HillCoverage()
    private val rc = RenderContext().also { it.camera = Camera() }
    private val terrainSector = Sector.fromDegrees(48.0, 37.0, 1.0, 1.0)
    private val fullSphere = Sector().setFullSphere()
    private val levelSet = LevelSet(fullSphere, fullSphere, Location.fromDegrees(0.1, 0.1), 1, 32, 32)

    @BeforeTest
    fun setUp() {
        // The geoid loads in the background and reads 0 until then: wait, so elevation fallbacks compare equal
        runBlocking { (Globe().geoid as? AbstractEGM96Geoid)?.awaitDataLoaded() }
    }

    private fun tiles() = buildList {
        for (r in 0 until 10) for (c in 0 until 10) {
            add(TerrainTile(Sector.fromDegrees(48.0 + r * 0.1, 37.0 + c * 0.1, 0.1, 0.1), levelSet.firstLevel, r, c))
        }
    }

    /** One render pass of a window at a globe offset, with the tiles it tessellated. */
    private fun pass(view: View, offset: Globe.Offset = Globe.Offset.Center, tiles: List<TerrainTile> = view.tiles) {
        rc.globe = view.globe
        view.globe.offset = offset
        rc.globeState = view.globe.state
        rc.elevationModelTimestamp = view.globe.elevationModel.timestamp
        tiles.forEach { it.prepare(rc) }
        rc.terrain = BasicTerrain(tiles, terrainSector, null)
    }

    private fun memoPoint(memo: PointMemo, lat: Double, lon: Double, altitude: Double, mode: AltitudeMode) =
        memo.geographicToCartesian(rc, fromDegrees(lat), fromDegrees(lon), altitude, mode, Vec3())

    private fun expected(lat: Double, lon: Double, altitude: Double, mode: AltitudeMode) =
        rc.geographicToCartesian(fromDegrees(lat), fromDegrees(lon), altitude, mode, Vec3())

    private fun tileAt(tiles: List<TerrainTile>, lat: Double, lon: Double) =
        tiles.first { it.sector.contains(fromDegrees(lat), fromDegrees(lon)) }

    /**
     * Raises the tile's mesh by [metres] in place without a new points version: a cached height ignores it, a
     * re-measured one follows it. Tells a cache hit from a re-measure.
     */
    private fun raiseMeshSilently(tile: TerrainTile, metres: Float) {
        val points = tile.points
        val sector = tile.sector
        val normal = rc.globe.geographicToCartesianNormal(sector.centroidLatitude, sector.centroidLongitude, Vec3())
        for (i in points.indices step 3) {
            points[i] += (normal.x * metres).toFloat()
            points[i + 1] += (normal.y * metres).toFloat()
            points[i + 2] += (normal.z * metres).toFloat()
        }
    }

    private val modes = listOf(AltitudeMode.CLAMP_TO_GROUND to 0.0, AltitudeMode.RELATIVE_TO_GROUND to 150.0)

    @Test
    fun test3DAnd2DWindowsMatchTheRenderContext() {
        val views = listOf(View(Wgs84Projection()), View(MercatorProjection()))
        val memos = List(100) { PointMemo() }
        repeat(3) { round ->
            for (view in views) {
                pass(view)
                for ((i, memo) in memos.withIndex()) {
                    val lat = 48.0 + (i * 0.6180339887 % 1.0)
                    val lon = 37.0 + (i * 0.4142135623 % 1.0)
                    for ((mode, altitude) in modes) {
                        val actual = memoPoint(memo, lat, lon, altitude, mode)
                        val message = "round $round ${view.globe.projection.displayName} $mode at $lat,$lon"
                        assertEquals(0.0, actual.distanceTo(expected(lat, lon, altitude, mode)), 0.01, message)
                    }
                }
            }
        }
    }

    @Test
    fun testEachWindowKeepsItsHeight() {
        // Two 3D windows, each with its own tiles, and a 2D one
        val views = listOf(View(Wgs84Projection()), View(Wgs84Projection()), View(MercatorProjection()))
        val memo = PointMemo()
        val measured = views.map { view ->
            pass(view)
            memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        }
        // A re-measure would follow the raised meshes; every window still hits its own slot
        for (view in views) raiseMeshSilently(tileAt(view.tiles, 48.55, 37.55), 50f)
        repeat(2) {
            for ((i, view) in views.withIndex()) {
                pass(view)
                val point = memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
                assertEquals(0.0, point.distanceTo(measured[i]), 1e-6, "window $i")
            }
        }
    }

    @Test
    fun testEachOffsetPassKeepsItsHeight() {
        // A continuous 2D globe: each offset pass tessellates its own terrain
        val view = View(MercatorProjection())
        val offsetTiles = Globe.Offset.entries.associateWith { tiles() }
        val memo = PointMemo()
        val measured = Globe.Offset.entries.associateWith { offset ->
            pass(view, offset, offsetTiles.getValue(offset))
            memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND).also {
                assertEquals(0.0, it.distanceTo(expected(48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)), 0.01)
            }
        }
        for (tiles in offsetTiles.values) raiseMeshSilently(tileAt(tiles, 48.55, 37.55), 50f)
        repeat(2) {
            for (offset in Globe.Offset.entries) {
                pass(view, offset, offsetTiles.getValue(offset))
                val point = memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
                assertEquals(0.0, point.distanceTo(measured.getValue(offset)), 1e-6, "offset $offset")
            }
        }
    }

    @Test
    fun testOffsetPassesSharingTilesStayCorrect() {
        // Tiles shared between offset passes rebuild their mesh for each offset: re-measured, but correct
        val view = View(MercatorProjection())
        val memo = PointMemo()
        repeat(2) {
            for (offset in Globe.Offset.entries) {
                pass(view, offset)
                for ((mode, altitude) in modes) {
                    val actual = memoPoint(memo, 48.55, 37.55, altitude, mode)
                    assertEquals(0.0, actual.distanceTo(expected(48.55, 37.55, altitude, mode)), 0.01, "$offset $mode")
                }
            }
        }
    }

    @Test
    fun testProjectionSwitchResetsOnlyThatGlobe() {
        val switching = View(Wgs84Projection())
        val other = View(Wgs84Projection())
        val memo = PointMemo()
        pass(switching)
        memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        pass(other)
        val otherMeasured = memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        raiseMeshSilently(tileAt(other.tiles, 48.55, 37.55), 50f)

        switching.globe.projection = MercatorProjection()
        pass(switching)
        val switched = memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        assertEquals(0.0, switched.distanceTo(expected(48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)), 0.01)

        pass(other)
        val point = memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        assertEquals(0.0, point.distanceTo(otherMeasured), 1e-6, "the other globe's slot still hits")
    }

    @Test
    fun testRecreatedTileIsMeasuredAgain() {
        // The tessellator evicted the tile and made a new one for the same sector: a new points version
        val view = View(Wgs84Projection())
        val memo = PointMemo()
        pass(view)
        memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        val old = tileAt(view.tiles, 48.55, 37.55)
        val recreated = TerrainTile(old.sector, old.level, old.row, old.column)
        val tiles = view.tiles.map { if (it === old) recreated else it }
        pass(view, tiles = tiles)
        raiseMeshSilently(recreated, 50f)
        val point = memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        val raised = expected(48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        assertEquals(0.0, point.distanceTo(raised), 0.01, "measured on the recreated tile's mesh")
    }

    @Test
    fun testMoreWindowsThanSlotsStayCorrect() {
        val views = List(PointMemo.MAX_SLOTS + 2) { View(if (it % 2 == 0) Wgs84Projection() else MercatorProjection()) }
        val memo = PointMemo()
        repeat(2) {
            for ((i, view) in views.withIndex()) {
                pass(view)
                for ((mode, altitude) in modes) {
                    val actual = memoPoint(memo, 48.55, 37.55, altitude, mode)
                    val expected = expected(48.55, 37.55, altitude, mode)
                    assertEquals(0.0, actual.distanceTo(expected), 0.01, "window $i $mode")
                }
            }
        }
    }

    @Test
    fun testOffTheTerrainMatchesTheRenderContext() {
        for (view in listOf(View(Wgs84Projection()), View(MercatorProjection()))) {
            val memo = PointMemo()
            pass(view, tiles = view.tiles.filter { !it.sector.contains(fromDegrees(48.55), fromDegrees(37.55)) })
            for ((mode, altitude) in modes) {
                val actual = memoPoint(memo, 48.55, 37.55, altitude, mode)
                val message = "${view.globe.projection.displayName} $mode"
                assertEquals(0.0, actual.distanceTo(expected(48.55, 37.55, altitude, mode)), 1e-6, message)
            }
        }
    }

    @Test
    fun testHoldsNoGlobeOrTileReference() {
        // A memo and its slots never keep an evicted tile, a closed window's globe, its state or its terrain alive
        val forbidden = listOf(
            Globe::class.java, Globe.State::class.java, TerrainTile::class.java, Terrain::class.java,
            RenderContext::class.java
        )
        val slotClass = PointMemo::class.java.declaredClasses.single { it.simpleName == "Slot" }
        for (type in listOf(PointMemo::class.java, slotClass)) for (field in type.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            val fieldType = if (field.type.isArray) field.type.componentType else field.type
            val held = forbidden.filter { it.isAssignableFrom(fieldType) || fieldType == Any::class.java }
            assertTrue(held.isEmpty(), "${type.simpleName}.${field.name}: ${field.type.name}")
        }
    }
}
