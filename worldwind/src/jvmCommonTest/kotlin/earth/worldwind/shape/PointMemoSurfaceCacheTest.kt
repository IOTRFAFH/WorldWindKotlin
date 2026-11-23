package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Camera
import earth.worldwind.geom.Location
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.AbstractEGM96Geoid
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.globe.terrain.TerrainTile
import earth.worldwind.render.RenderContext
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Terrain-dependent [PointMemo] points as `base + normal * h` with `h` cached against the terrain tile, compared with
 * [BasicTerrain.surfacePoint] on the same mesh.
 */
class PointMemoSurfaceCacheTest {
    /** Hills: a few hundred metres of relief, a few kilometres across, so tile meshes are far from flat. */
    private class HillCoverage : AbstractElevationCoverage() {
        var amplitude = 400.0
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
        override fun doGetElevationLimits(sector: Sector, result: FloatArray) {
            result[0] = -amplitude.toFloat(); result[1] = amplitude.toFloat()
        }
        fun height(lat: Double, lon: Double) = (amplitude * sin(lat * 40.0) * sin(lon * 55.0) + 200.0).toFloat()
        fun changed(sector: Sector = Sector().setFullSphere()) = updateTimestamp(sector)
    }

    private lateinit var rc: RenderContext
    private lateinit var globe: Globe
    private lateinit var coverage: HillCoverage
    private lateinit var tiles: List<TerrainTile>

    @BeforeTest
    fun setUp() {
        globe = Globe()
        // The geoid loads in the background and reads 0 until then: wait, so elevation fallbacks compare equal
        runBlocking { (globe.geoid as? AbstractEGM96Geoid)?.awaitDataLoaded() }
        coverage = HillCoverage()
        globe.elevationModel.addCoverage(coverage)
        rc = RenderContext()
        rc.globe = globe
        rc.globeState = globe.state
        rc.camera = Camera()
        rc.elevationModelTimestamp = globe.elevationModel.timestamp
        // 0.1 degree tiles (about 11 km, a mid terrain level) with the engine's 32x32 vertices; 10x10 tiles, so the
        // tile list is about as long as a frame's.
        val levelSet = LevelSet(Sector().setFullSphere(), Sector().setFullSphere(), Location.fromDegrees(0.1, 0.1), 1, 32, 32)
        tiles = buildList {
            for (r in 0 until 10) for (c in 0 until 10) {
                val sector = Sector.fromDegrees(48.0 + r * 0.1, 37.0 + c * 0.1, 0.1, 0.1)
                add(TerrainTile(sector, levelSet.firstLevel, r, c).also { it.prepare(rc) })
            }
        }
        rc.terrain = BasicTerrain(tiles, Sector.fromDegrees(48.0, 37.0, 1.0, 1.0), null)
    }

    private fun point(memo: PointMemo, lat: Double, lon: Double, alt: Double, mode: AltitudeMode) =
        Vec3().also { memo.geographicToCartesian(rc, fromDegrees(lat), fromDegrees(lon), alt, mode, it) }

    /** The uncached reference: the mesh point, offset along the normal for relative altitudes. */
    private fun meshPoint(lat: Double, lon: Double, alt: Double, mode: AltitudeMode) = Vec3().also {
        val latitude = fromDegrees(lat)
        val longitude = fromDegrees(lon)
        if (rc.terrain.surfacePoint(latitude, longitude, it)) {
            if (mode == AltitudeMode.RELATIVE_TO_GROUND) {
                val normal = globe.geographicToCartesianNormal(latitude, longitude, Vec3())
                it.add(normal.multiply(alt * globe.verticalExaggeration))
            }
        } else {
            val h = globe.getElevation(latitude, longitude) + if (mode == AltitudeMode.RELATIVE_TO_GROUND) alt else 0.0
            globe.geographicToCartesian(latitude, longitude, h, it)
        }
    }

    @Test
    fun testCachedHeightMatchesMeshPoint() {
        var maxError = 0.0
        for (i in 0 until 2000) {
            val lat = 48.0 + (i * 0.6180339887 % 1.0)
            val lon = 37.0 + (i * 0.4142135623 % 1.0)
            for (mode in listOf(AltitudeMode.CLAMP_TO_GROUND, AltitudeMode.RELATIVE_TO_GROUND)) {
                val regular = meshPoint(lat, lon, 30.0, mode)
                val memo = PointMemo()
                // First call measures and caches the height, second call is the per-frame hit
                point(memo, lat, lon, 30.0, mode)
                val cached = point(memo, lat, lon, 30.0, mode)
                maxError = max(maxError, regular.distanceTo(cached))
            }
        }
        // Only the mesh point's lateral offset from the vertical is dropped: well under a centimetre.
        assertTrue(maxError < 0.01, "max error $maxError m")
    }

    @Test
    fun testCachedHeightFollowsTerrainChanges() {
        val memo = PointMemo()
        val lat = 48.55
        val lon = 37.55
        val first = point(memo, lat, lon, 0.0, AltitudeMode.CLAMP_TO_GROUND)

        // Mesh rebuilt with new elevations within the same terrain: the tile's points version changes, the cached
        // height must follow
        coverage.amplitude = 800.0
        coverage.changed()
        rc.elevationModelTimestamp = globe.elevationModel.timestamp
        tiles.forEach { it.prepare(rc) }
        val expected = meshPoint(lat, lon, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        val refreshed = point(memo, lat, lon, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        assertTrue(first.distanceTo(expected) > 1.0, "test needs a visible height change")
        assertEquals(0.0, refreshed.distanceTo(expected), 0.01, "refreshed after mesh change")

        // The tile leaves the terrain (camera moved away): fall back to the regular path (elevation model height)
        rc.terrain = BasicTerrain(tiles.filter { !it.sector.contains(fromDegrees(lat), fromDegrees(lon)) }, Sector(), null)
        val fallback = meshPoint(lat, lon, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        val afterLeave = point(memo, lat, lon, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        assertEquals(0.0, afterLeave.distanceTo(fallback), 0.01, "fallback after tile left the terrain")
    }

    @Test
    fun testElevationLoadElsewhereKeepsTileMeshes() {
        val versions = tiles.map { it.pointsVersion }
        coverage.changed(Sector.fromDegrees(10.0, 10.0, 1.0, 1.0))
        rc.elevationModelTimestamp = globe.elevationModel.timestamp
        tiles.forEach { it.prepare(rc) }
        assertEquals(versions, tiles.map { it.pointsVersion }, "tiles away from the load keep their mesh")

        coverage.changed(Sector.fromDegrees(48.05, 37.05, 0.01, 0.01))
        rc.elevationModelTimestamp = globe.elevationModel.timestamp
        tiles.forEach { it.prepare(rc) }
        val rebuilt = tiles.indices.filter { tiles[it].pointsVersion != versions[it] }
        assertEquals(listOf(0), rebuilt, "only the tile under the load is rebuilt")
    }

    @Test
    fun testUncoveredLocationFallsBackWithoutSecondScan() {
        // Counts the regular path's terrain scans: an uncovered location must not reach it after the cached lookup
        var surfacePointCalls = 0
        val covered = tiles.filter { it.sector.minLatitude.inDegrees < 48.5 }
        rc.terrain = object : BasicTerrain(covered, Sector.fromDegrees(48.0, 37.0, 1.0, 1.0), null) {
            override fun surfacePoint(latitude: Angle, longitude: Angle, result: Vec3): Boolean {
                surfacePointCalls++
                return super.surfacePoint(latitude, longitude, result)
            }
        }
        // Inside the terrain sector but over a gap in the tiles, and outside the terrain sector
        for ((lat, lon) in listOf(48.75 to 37.55, 52.0 to 30.0)) {
            for (mode in listOf(AltitudeMode.CLAMP_TO_GROUND, AltitudeMode.RELATIVE_TO_GROUND)) {
                val memo = PointMemo()
                repeat(2) {
                    val cached = point(memo, lat, lon, 30.0, mode)
                    assertEquals(0.0, cached.distanceTo(meshPoint(lat, lon, 30.0, mode)), 0.01, "fallback at $lat,$lon $mode")
                }
            }
        }
        // meshPoint calls surfacePoint once per check; the memo itself never does
        assertEquals(8, surfacePointCalls, "PointMemo must not rescan the terrain for uncovered locations")
    }
}
