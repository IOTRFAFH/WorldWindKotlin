package earth.worldwind.shape

import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext

/**
 * One layer's placemark indexes, one set per window ([RenderContext]) that renders the layer. Each window has its own
 * globe, camera and terrain, so its own points, bounds and batches; the GPU buffers of every window live in the shared
 * render resource cache under keys of their own.
 *
 * A window that stops rendering the layer (closed, recreated, or hidden) is dropped once the other windows have drawn
 * [WINDOW_IDLE_FRAMES] frames without it, so a recreated window neither keeps the old one's state alive nor waits for
 * it. If it comes back later, it starts over as on its first frame.
 */
internal class PlacemarkCellsByWindow {
    private class Window(val rc: RenderContext) {
        val cells = PlacemarkCellsByOffset()
        var lastUsedFrame = 0L
    }

    private val windows = ArrayList<Window>(2)
    /** Center-copy frames drawn by any window. */
    private var frames = 0L

    /**
     * The index for [rc]'s window and current globe offset. A picking frame creates no index and returns null instead:
     * a window or copy without one has nothing to cull with.
     */
    fun cellsFor(rc: RenderContext): PlacemarkCells? {
        var window = windowOf(rc)
        if (window == null) {
            if (rc.isPickMode) return null
            window = Window(rc).also { windows.add(it) }
        }
        if (!rc.isPickMode && rc.globe.offset == Globe.Offset.Center) {
            window.lastUsedFrame = ++frames
            dropIdleWindows()
        }
        return window.cells.cellsFor(rc)
    }

    /**
     * Detaches every placemark and drops every window's indexes.
     */
    fun clear() {
        for (i in windows.indices) windows[i].cells.clear()
        windows.clear()
    }

    /** The number of windows with indexes, for tests. */
    internal val windowCount get() = windows.size

    private fun windowOf(rc: RenderContext): Window? {
        for (i in windows.indices) if (windows[i].rc === rc) return windows[i]
        return null
    }

    private fun dropIdleWindows() {
        if (windows.size < 2) return
        windows.removeAll { window ->
            (frames - window.lastUsedFrame > WINDOW_IDLE_FRAMES).also { if (it) window.cells.clear() }
        }
    }

    companion object {
        /** Frames drawn by the other windows after which a window that did not draw the layer is dropped. */
        const val WINDOW_IDLE_FRAMES = 600L
    }
}
