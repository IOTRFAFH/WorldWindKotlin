package earth.worldwind.layer

import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkBatch
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

open class RenderableLayer @JvmOverloads constructor(displayName: String? = null): AbstractLayer(displayName), Iterable<Renderable> {
    protected val renderables = mutableListOf<Renderable>()
    val count get() = renderables.size
    /**
     * Draws the icons of this layer's placemarks as one batch: per-icon work is cached across frames and done on the
     * GPU, and icons sharing a texture are drawn with one draw call. Placemarks with a visible label or leader, or
     * drawn on top of everything, are still drawn individually. Off by default; serves one window.
     */
    var isPlacemarkBatching = false
        set(value) {
            field = value
            if (!value) {
                placemarkBatch?.clear()
                placemarkBatch = null
            }
        }
    private var placemarkBatch: PlacemarkBatch? = null

    constructor(layer: RenderableLayer): this(layer.displayName) { addAllRenderables(layer) }

    constructor(renderables: Iterable<Renderable>): this() { addAllRenderables(renderables) }

    fun isEmpty() = renderables.isEmpty()

    fun getRenderable(index: Int): Renderable {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "getRenderable", "invalidIndex")
        }
        return renderables[index]
    }

    fun getRenderablesByDisplayName(name: String): List<Renderable> {
        return renderables.filter { it.displayName?.contains(name, ignoreCase = true) == true }
    }

    fun setRenderable(index: Int, renderable: Renderable): Renderable {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "setRenderable", "invalidIndex")
        }
        return renderables.set(index, renderable)
    }

    fun indexOfRenderable(renderable: Renderable) = renderables.indexOf(renderable)

    fun indexOfRenderableNamed(name: String): Int {
        for (idx in renderables.indices) if (name == renderables[idx].displayName) return idx
        return -1
    }

    fun indexOfRenderableWithProperty(key: Any, value: Any): Int {
        for (idx in renderables.indices) {
            val renderable = renderables[idx]
            if (renderable.hasUserProperty(key) && value == renderable.getUserProperty(key)) return idx
        }
        return -1
    }

    fun addRenderable(renderable: Renderable) { renderables.add(renderable) }

    fun addRenderable(index: Int, renderable: Renderable) {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "addRenderable", "invalidIndex")
        }
        renderables.add(index, renderable)
    }

    fun addAllRenderables(layer: RenderableLayer) {
        //renderables.ensureCapacity(layer.renderables.size)
        for (renderable in layer.renderables) renderables.add(renderable) // we know the contents of layer.renderables is valid
    }

    fun addAllRenderables(iterable: Iterable<Renderable>) { for (renderable in iterable) renderables.add(renderable) }

    fun removeRenderable(renderable: Renderable) = renderables.remove(renderable)

    fun removeRenderable(index: Int): Renderable {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "removeRenderable", "invalidIndex")
        }
        return renderables.removeAt(index)
    }

    fun removeAllRenderables(renderables: Iterable<Renderable>): Boolean {
        var removed = false
        for (renderable in renderables) removed = removed or this.renderables.remove(renderable)
        return removed
    }

    fun clearRenderables() { renderables.clear() }

    override fun iterator() = renderables.iterator()

    override fun doRender(rc: RenderContext) {
        // Picking frames and other windows than the batch's own render placemarks individually.
        val batch = if (isPlacemarkBatching && !rc.isPickMode) {
            (placemarkBatch ?: PlacemarkBatch().also { placemarkBatch = it }).takeIf { it.accepts(rc) }
        } else null
        batch?.beginFrame(rc)
        for (i in renderables.indices) {
            val renderable = renderables[i]
            try {
                if (batch != null && renderable is Placemark) {
                    if (renderable.isEnabled) renderable.renderBatched(rc, batch)
                } else renderable.render(rc)
            } catch (e: Exception) {
                logMessage(
                    ERROR, "RenderableLayer", "doRender",
                    "Exception while rendering shape '${renderable.displayName}'", e
                )
                // Keep going. Draw the remaining renderables.
            }
        }
        batch?.endFrame(rc)
    }
}