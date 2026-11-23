package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.globe.terrain.TerrainTile
import earth.worldwind.render.RenderContext

/**
 * Per-shape memo of the window-independent terms of a geographic to Cartesian conversion, reused
 * across frames and rendering windows by point shapes ([Placemark], [Label]).
 *
 * Cached terms: the altitude-zero base point and the altitude direction (every projection is
 * linear in altitude: cartesian(lat, lon, h) = base + normal * h), the global elevation model
 * height and the geoid offset. All of them are identical for every window, so one memo per shape
 * is correct in multi-window rendering.
 *
 * Terrain-clamped points (3D only) are cached as the mesh height along the normal, together with
 * the terrain tile it was measured on and that tile's points version. The height is reused while
 * the tile is part of the current terrain and its mesh is unchanged; when tile selection changes
 * (camera movement, LoD) or the tile's mesh is rebuilt (elevation load, vertical exaggeration),
 * the tile is looked up and the height re-measured. The tessellated terrain differs per window,
 * so alternating windows re-measure, but stay correct.
 *
 * Position and globe configuration changes are detected by value, so in-place position edits stay
 * safe. Altitude mode handling mirrors [RenderContext.geographicToCartesian] (without the useEM
 * flag, which point shapes do not use); the equivalence is pinned by PointMemoTest, which compares
 * both paths for every altitude mode.
 */
open class PointMemo {
    private var latitude = ZERO
    private var longitude = ZERO
    private var globeState: Globe.State? = null
    // 2D continuous globes render each frame at up to three globe offsets; keep a base point per
    // offset so the passes do not evict each other. Center is used by every mode; the Right and
    // Left slots allocate lazily on the first 2D pass. The normal, elevation and geoid offset do
    // not depend on the globe offset and are shared.
    private val basePoints = arrayOfNulls<Vec3>(Globe.Offset.entries.size)
    private var basePoint = Vec3().also { basePoints[Globe.Offset.Center.ordinal] = it }
    private var baseMask = 0
    private val normal = Vec3()
    private val sector = Sector()
    private var hasBase = false
    private var elevation = 0.0
    private var elevationTimestamp = 0L
    private var hasElevation = false
    private var geoidOffset = 0f
    private var hasGeoid = false
    private var surfaceTile: TerrainTile? = null
    private var surfaceTileVersion = 0
    private var surfaceHeight = 0.0

    /**
     * Forgets all cached terms, forcing recomputation on next use.
     */
    fun invalidate() {
        hasBase = false
        hasElevation = false
        hasGeoid = false
        surfaceTile = null
    }

    /**
     * Converts a geographic position to Cartesian coordinates according to an [altitudeMode],
     * exactly as [RenderContext.geographicToCartesian] does, reusing the memoized terms.
     */
    fun geographicToCartesian(
        rc: RenderContext, position: Position, altitudeMode: AltitudeMode, result: Vec3
    ) = geographicToCartesian(rc, position.latitude, position.longitude, position.altitude, altitudeMode, result)

    /**
     * Converts a geographic location to Cartesian coordinates according to an [altitudeMode],
     * exactly as [RenderContext.geographicToCartesian] does, reusing the memoized terms.
     */
    fun geographicToCartesian(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode, result: Vec3
    ): Vec3 {
        prepare(rc, latitude, longitude)
        when (altitudeMode) {
            AltitudeMode.ABSOLUTE -> pointAt(altitude, result)
            AltitudeMode.ABOVE_SEA_LEVEL -> pointAt(altitude + geoidOffset(rc), result)
            AltitudeMode.CLAMP_TO_GROUND -> groundPoint(rc, latitude, longitude, 0.0, result)
            AltitudeMode.RELATIVE_TO_GROUND -> groundPoint(rc, latitude, longitude, altitude, result)
        }
        return result
    }

    /**
     * The point [altitude] above the terrain surface (clamped when zero), offset along the normal
     * at the surface point, or above the elevation model height when no terrain covers the location.
     * The surface height cache is keyed by terrain tile, not by globe offset, so 2D globes always
     * take the regular terrain path.
     */
    private fun groundPoint(rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, result: Vec3) {
        val surface = if (rc.globe.is2D) SURFACE_UNCACHED else cachedSurfacePoint(rc, latitude, longitude, result)
        val onTerrain = when (surface) {
            SURFACE_FOUND -> true
            // No terrain tile covers the location: skip scanning the terrain a second time
            SURFACE_NO_TILE -> false
            else -> rc.terrain.surfacePoint(latitude, longitude, result)
        }
        // Use elevation model height as a fallback
        if (!onTerrain) pointAt(altitude + elevation(rc), result)
        else if (altitude != 0.0) offsetAlongNormal(altitude * rc.globe.verticalExaggeration, result)
    }

    /**
     * The terrain surface point as `base + normal * h`, with `h` cached against the terrain tile it
     * was measured on. A hit costs two compares and three multiply-adds; the tile scan and the
     * bilinear interpolation run only when the tile left the terrain or its mesh changed. Returns
     * [SURFACE_FOUND] with the point in [result], [SURFACE_NO_TILE] when no tile covers the location,
     * or [SURFACE_UNCACHED] (use the regular path) when the terrain is not a [BasicTerrain].
     */
    private fun cachedSurfacePoint(rc: RenderContext, latitude: Angle, longitude: Angle, result: Vec3): Int {
        val terrain = rc.terrain as? BasicTerrain ?: return SURFACE_UNCACHED
        val cached = surfaceTile
        if (cached == null || cached.terrainStamp != terrain.stamp || cached.pointsVersion != surfaceTileVersion) {
            val tile = terrain.surfaceTile(latitude, longitude) ?: run { surfaceTile = null; return SURFACE_NO_TILE }
            BasicTerrain.interpolateSurfacePoint(tile, latitude, longitude, result)
            // Height of the mesh point along the normal; the lateral residual (well under a centimetre) is dropped.
            surfaceHeight = (result.x - basePoint.x) * normal.x + (result.y - basePoint.y) * normal.y +
                    (result.z - basePoint.z) * normal.z
            surfaceTile = tile
            surfaceTileVersion = tile.pointsVersion
        }
        pointAt(surfaceHeight, result)
        return SURFACE_FOUND
    }

    /**
     * Provides the globe's Cartesian surface normal at a geographic location, reusing the memoized value.
     */
    fun geographicToCartesianNormal(rc: RenderContext, latitude: Angle, longitude: Angle, result: Vec3): Vec3 {
        prepare(rc, latitude, longitude)
        return result.copy(normal)
    }

    /**
     * Revalidates the memoized terms against the position and globe configuration, and selects
     * the base point slot of the globe offset rendered by the current pass.
     */
    private fun prepare(rc: RenderContext, latitude: Angle, longitude: Angle) {
        if (!hasBase || latitude != this.latitude || longitude != this.longitude || rc.globeState != globeState) {
            this.latitude = latitude
            this.longitude = longitude
            // Small non-degenerate sector around the location for elevation update intersection tests
            sector.setDegrees(
                latitude.inDegrees - SECTOR_RADIUS_DEGREES, longitude.inDegrees - SECTOR_RADIUS_DEGREES,
                2 * SECTOR_RADIUS_DEGREES, 2 * SECTOR_RADIUS_DEGREES
            )
            globeState = rc.globeState
            rc.globe.geographicToCartesianNormal(latitude, longitude, normal)
            baseMask = 0
            surfaceTile = null
            hasBase = true
            hasElevation = false
            hasGeoid = false
        }
        val index = rc.globe.offset.ordinal
        basePoint = basePoints[index] ?: Vec3().also { basePoints[index] = it }
        if (baseMask and (1 shl index) == 0) {
            rc.globe.geographicToCartesian(latitude, longitude, 0.0, basePoint)
            baseMask = baseMask or (1 shl index)
        }
    }

    private fun pointAt(altitude: Double, result: Vec3) = result.set(
        basePoint.x + normal.x * altitude,
        basePoint.y + normal.y * altitude,
        basePoint.z + normal.z * altitude
    )

    private fun offsetAlongNormal(distance: Double, point: Vec3) = point.set(
        point.x + normal.x * distance,
        point.y + normal.y * distance,
        point.z + normal.z * distance
    )

    /**
     * The global elevation model height incl. geoid offset, matching [Globe.getElevation].
     * Guarded by the sector-scoped elevation update log, so tile loads elsewhere on the globe
     * keep the cached height; the timestamp advances on every check to stay within the bounded
     * update log window.
     */
    private fun elevation(rc: RenderContext): Double {
        val timestamp = rc.elevationModelTimestamp
        if (!hasElevation || timestamp != elevationTimestamp && rc.globe.isElevationChangedSince(elevationTimestamp, sector)) {
            elevation = rc.globe.getElevation(latitude, longitude)
            hasElevation = true
        }
        elevationTimestamp = timestamp
        return elevation
    }

    private fun geoidOffset(rc: RenderContext): Float {
        if (!hasGeoid) {
            geoidOffset = rc.globe.geoid.getOffset(latitude, longitude)
            hasGeoid = true
        }
        return geoidOffset
    }

    companion object {
        private const val SURFACE_FOUND = 0
        private const val SURFACE_NO_TILE = 1
        private const val SURFACE_UNCACHED = 2
        /**
         * Half-size of the elevation invalidation sector. Keeps the sector non-degenerate for the
         * strict [Sector.intersects] used by the update log while staying far below any elevation
         * tile size (~11 meters).
         */
        private const val SECTOR_RADIUS_DEGREES = 1.0e-4
    }
}
