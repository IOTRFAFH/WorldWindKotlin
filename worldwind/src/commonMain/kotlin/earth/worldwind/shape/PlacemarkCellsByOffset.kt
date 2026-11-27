package earth.worldwind.shape

import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext

/**
 * One window's [PlacemarkCells] for one layer, one per globe offset. A continuous 2D globe renders the layers once per
 * visible copy of the map in a frame (center, then right and left), and each copy has its own Cartesian points, so
 * its own cached points, bounds and frame counters. The side indexes are created when a wrapped view first draws
 * them, and dropped once no frame has drawn them for [SIDE_IDLE_FRAMES] frames, counted on the center copy.
 */
internal class PlacemarkCellsByOffset {
    private val cells = arrayOfNulls<PlacemarkCells>(OFFSETS)
    private val lastUsedFrame = LongArray(OFFSETS)
    private var centerFrames = 0L

    /**
     * The index for [rc]'s current globe offset. A picking frame creates no index and returns null instead: a copy
     * without one has nothing to cull with.
     */
    fun cellsFor(rc: RenderContext): PlacemarkCells? {
        val offset = rc.globe.offset
        val index = offset.ordinal
        val result = cells[index] ?: if (rc.isPickMode) return null else PlacemarkCells(offset).also { cells[index] = it }
        if (!rc.isPickMode) {
            if (offset == Globe.Offset.Center) {
                centerFrames++
                dropIdleSides()
            }
            lastUsedFrame[index] = centerFrames
        }
        return result
    }

    /**
     * Detaches every placemark and drops every index.
     */
    fun clear() {
        for (i in cells.indices) {
            cells[i]?.clear()
            cells[i] = null
        }
    }

    private fun dropIdleSides() {
        for (i in cells.indices) {
            if (i == Globe.Offset.Center.ordinal) continue
            val side = cells[i] ?: continue
            if (centerFrames - lastUsedFrame[i] > SIDE_IDLE_FRAMES) {
                side.clear()
                cells[i] = null
            }
        }
    }

    companion object {
        private val OFFSETS = Globe.Offset.entries.size
        /** Center frames without a side copy after which its index is dropped. */
        const val SIDE_IDLE_FRAMES = 600L
    }
}
