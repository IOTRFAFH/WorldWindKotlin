package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.render.RenderContext

/**
 * Per-shape memo of the window-independent terms of a geographic to Cartesian conversion, reused
 * across frames by point shapes ([Placemark], [Label]).
 *
 * Cached terms: the altitude-zero base point and the altitude direction (every projection is
 * linear in altitude: cartesian(lat, lon, h) = base + normal * h), the global elevation model
 * height, the geoid offset, and for terrain-dependent points the terrain mesh height along the
 * normal.
 *
 * The terms are kept per globe and globe offset in a few slots, so several windows (each with its
 * own globe and tessellated terrain, possibly 2D and 3D side by side) and the up to three offset
 * passes of a continuous 2D globe do not evict each other. A slot holds only numbers: the globe is
 * identified by its id, its configuration by its state version, and the terrain mesh by the
 * [earth.worldwind.globe.terrain.TerrainTile.pointsVersion] the height was measured on. The memo
 * never keeps a globe or a tile alive. Its size is fixed: one slot until a second globe or offset
 * renders the shape, at most [MAX_SLOTS]; beyond that the oldest slot is reused.
 *
 * The terrain height is reused while the tile covering the location still has the measured mesh:
 * the tile is looked up every call, and only a different tile or a rebuilt mesh (elevation load,
 * vertical exaggeration, globe state) re-measures it.
 *
 * Position and globe configuration changes are detected by value, so in-place position edits stay
 * safe. Altitude mode handling mirrors [RenderContext.geographicToCartesian] (without the useEM
 * flag, which point shapes do not use); the equivalence is pinned by PointMemoTest, which compares
 * both paths for every altitude mode.
 */
open class PointMemo {
    /** The terms of one globe at one globe offset. Only numbers, so it keeps nothing alive. */
    private class Slot(
        /** [Globe.id] and [Globe.offset] of the globe the terms belong to, see [keyOf]. */
        var key: Int
    ) {
        /** [Globe.stateVersion] the terms were computed for; -1 until the first use. */
        var stateVersion = -1L
        // Base point (altitude zero) and normal, as plain fields: no extra objects to reach per call
        var baseX = 0.0
        var baseY = 0.0
        var baseZ = 0.0
        var normalX = 0.0
        var normalY = 0.0
        var normalZ = 0.0
        var hasBase = false
        /** Terrain mesh height along the normal, measured on the mesh with [surfaceVersion]. */
        var surfaceHeight = 0.0
        var surfaceVersion = 0L
        var hasSurface = false
        /** Elevation model fallback height, checked against the update log since [elevationTimestamp]. */
        var elevation = 0.0
        var elevationTimestamp = 0L
        var hasElevation = false
        var geoidOffset = 0.0
        var hasGeoid = false

        fun reset() {
            hasBase = false
            hasSurface = false
            hasElevation = false
            hasGeoid = false
        }
    }

    private var latitude = ZERO
    private var longitude = ZERO
    private var hasLocation = false
    private val sector = Sector()
    /**
     * The slot of the current call, set by [prepare] and used by everything after it. Also the
     * first one [slotOf] checks: with one window it is the only slot, found without a lookup.
     */
    private var slot = Slot(NO_KEY)
    private var slots = arrayOf<Slot?>(slot)
    private var usedSlots = 0
    private var nextReused = 0

    /**
     * Forgets all cached terms, forcing recomputation on next use.
     */
    fun invalidate() {
        for (i in 0 until usedSlots) slots[i]?.reset()
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
     * Provides the globe's Cartesian surface normal at a geographic location, reusing the memoized value.
     */
    fun geographicToCartesianNormal(rc: RenderContext, latitude: Angle, longitude: Angle, result: Vec3): Vec3 {
        prepare(rc, latitude, longitude)
        return result.set(slot.normalX, slot.normalY, slot.normalZ)
    }

    /**
     * The point [altitude] above the terrain surface (clamped when zero), offset along the normal
     * at the surface point, or above the elevation model height when no terrain covers the location.
     */
    private fun groundPoint(rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, result: Vec3) {
        val onTerrain = when (cachedSurfacePoint(rc, latitude, longitude, result)) {
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
     * The terrain surface point as `base + normal * h`, with `h` measured on the mesh of the tile
     * covering the location and kept with that mesh's version. A hit costs the tile lookup, one
     * compare and three multiply-adds; the bilinear interpolation runs only when a different tile
     * covers the location or its mesh was rebuilt. Returns [SURFACE_FOUND] with the point in
     * [result], [SURFACE_NO_TILE] when no tile covers the location, or [SURFACE_UNCACHED] (use the
     * regular path) when the terrain is not a [BasicTerrain].
     */
    private fun cachedSurfacePoint(rc: RenderContext, latitude: Angle, longitude: Angle, result: Vec3): Int {
        val terrain = rc.terrain as? BasicTerrain ?: return SURFACE_UNCACHED
        val tile = terrain.surfaceTile(latitude, longitude) ?: return SURFACE_NO_TILE
        val slot = slot
        if (!slot.hasSurface || slot.surfaceVersion != tile.pointsVersion) {
            BasicTerrain.interpolateSurfacePoint(tile, latitude, longitude, result)
            // Height of the mesh point along the normal; the lateral residual (well under a centimetre) is dropped.
            slot.surfaceHeight = (result.x - slot.baseX) * slot.normalX + (result.y - slot.baseY) * slot.normalY +
                    (result.z - slot.baseZ) * slot.normalZ
            slot.surfaceVersion = tile.pointsVersion
            slot.hasSurface = true
        }
        pointAt(slot.surfaceHeight, result)
        return SURFACE_FOUND
    }

    /**
     * Revalidates the memoized terms against the position, selects the slot of the current globe
     * and globe offset, and revalidates that slot against the globe configuration.
     */
    private fun prepare(rc: RenderContext, latitude: Angle, longitude: Angle) {
        if (!hasLocation || latitude != this.latitude || longitude != this.longitude) {
            this.latitude = latitude
            this.longitude = longitude
            hasLocation = true
            // Small non-degenerate sector around the location for elevation update intersection tests
            sector.setDegrees(
                latitude.inDegrees - SECTOR_RADIUS_DEGREES, longitude.inDegrees - SECTOR_RADIUS_DEGREES,
                2 * SECTOR_RADIUS_DEGREES, 2 * SECTOR_RADIUS_DEGREES
            )
            invalidate()
        }
        val globe = rc.globe
        val key = keyOf(globe)
        val slot = if (slot.key == key) slot else slotOf(key).also { slot = it }
        val stateVersion = globe.stateVersion
        if (slot.stateVersion != stateVersion) {
            slot.stateVersion = stateVersion
            slot.reset()
        }
        if (!slot.hasBase) {
            globe.geographicToCartesianNormal(latitude, longitude, scratch)
            slot.normalX = scratch.x
            slot.normalY = scratch.y
            slot.normalZ = scratch.z
            globe.geographicToCartesian(latitude, longitude, 0.0, scratch)
            slot.baseX = scratch.x
            slot.baseY = scratch.y
            slot.baseZ = scratch.z
            slot.hasBase = true
        }
    }

    /**
     * The slot of a globe and offset [key] when it is not the current one: an existing one, a free
     * one (the first is preallocated), or the oldest one reused.
     */
    private fun slotOf(key: Int): Slot {
        for (i in 0 until usedSlots) {
            val slot = slots[i]!!
            if (slot.key == key) return slot
        }
        if (usedSlots == slots.size && slots.size < MAX_SLOTS) slots = slots.copyOf(minOf(slots.size * 2, MAX_SLOTS))
        val slot = if (usedSlots < slots.size) {
            (slots[usedSlots] ?: Slot(key).also { slots[usedSlots] = it }).also { usedSlots++ }
        } else {
            // All slots taken: reuse the oldest
            slots[nextReused]!!.also { nextReused = (nextReused + 1) % MAX_SLOTS }
        }
        slot.key = key
        slot.stateVersion = -1L
        slot.reset()
        return slot
    }

    private fun pointAt(altitude: Double, result: Vec3): Vec3 {
        val slot = slot
        return result.set(
            slot.baseX + slot.normalX * altitude,
            slot.baseY + slot.normalY * altitude,
            slot.baseZ + slot.normalZ * altitude
        )
    }

    private fun offsetAlongNormal(distance: Double, point: Vec3): Vec3 {
        val slot = slot
        return point.set(
            point.x + slot.normalX * distance, point.y + slot.normalY * distance, point.z + slot.normalZ * distance
        )
    }

    /**
     * The global elevation model height incl. geoid offset, matching [Globe.getElevation].
     * Guarded by the sector-scoped elevation update log, so tile loads elsewhere on the globe
     * keep the cached height; the timestamp advances on every check to stay within the bounded
     * update log window.
     */
    private fun elevation(rc: RenderContext): Double {
        val slot = slot
        val timestamp = rc.elevationModelTimestamp
        if (!slot.hasElevation ||
            timestamp != slot.elevationTimestamp && rc.globe.isElevationChangedSince(slot.elevationTimestamp, sector)
        ) {
            slot.elevation = rc.globe.getElevation(latitude, longitude)
            slot.hasElevation = true
        }
        slot.elevationTimestamp = timestamp
        return slot.elevation
    }

    private fun geoidOffset(rc: RenderContext): Double {
        val slot = slot
        if (!slot.hasGeoid) {
            slot.geoidOffset = rc.globe.geoid.getOffset(latitude, longitude).toDouble()
            slot.hasGeoid = true
        }
        return slot.geoidOffset
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
        /**
         * Most globe and offset pairs a memo keeps at once without reuse: for example a 3D window
         * next to a continuous 2D one rendering all three offsets.
         */
        internal const val MAX_SLOTS = 4

        /** Key of a slot not assigned to any globe yet (globe ids start at 1). */
        private const val NO_KEY = -1
        // Rendering is single-threaded; the scratch vector never outlives a prepare call
        private val scratch = Vec3()

        /** One number for a globe and its current offset: the globe id with the offset in the low two bits. */
        private fun keyOf(globe: Globe) = globe.id shl 2 or globe.offset.ordinal
    }
}
