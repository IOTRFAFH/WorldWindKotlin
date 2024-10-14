package earth.worldwind.layer

import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.render.BatchRenderer
import earth.worldwind.shape.milstd2525.AbstractMilStd2525TacticalGraphic
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads
import kotlin.reflect.KClass

open class RenderableLayer @JvmOverloads constructor(displayName: String? = null): AbstractLayer(displayName), Iterable<Renderable> {
    protected val renderables = mutableListOf<Renderable>()
    val count get() = renderables.size

    val batchRenderers = mutableMapOf<KClass<out Renderable>, BatchRenderer>()

    constructor(layer: RenderableLayer): this(layer.displayName) { addAllRenderables(layer) }

    constructor(renderables: Iterable<Renderable>): this() { addAllRenderables(renderables) }

    fun isEmpty() = renderables.isEmpty()

    fun getRenderable(index: Int): Renderable {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "getRenderable", "invalidIndex")
        }
        return renderables[index]
    }

    fun setRenderable(index: Int, renderable: Renderable): Renderable {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "setRenderable", "invalidIndex")
        }
        val oldRenderable = renderables[index]
        batchRenderers[oldRenderable::class]?.removeRenderable(oldRenderable)
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

    fun removeRenderable(renderable: Renderable) : Boolean {
        if (renderables.remove(renderable)) {
            batchRenderers[renderable::class]?.removeRenderable(renderable)
            return true
        }
        return false
    }

    fun removeRenderable(index: Int): Renderable {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "removeRenderable", "invalidIndex")
        }
        val renderable = renderables[index]
        batchRenderers[renderable::class]?.removeRenderable(renderable)
        return renderables.removeAt(index)
    }

    fun removeAllRenderables(renderables: Iterable<Renderable>): Boolean {
        var removed = false
        for (renderable in renderables) {
            removed = removed or this.renderables.remove(renderable)
            batchRenderers[renderable::class]?.removeRenderable(renderable)
        }
        return removed
    }

    fun clearRenderables() {
        renderables.clear()
        for(batchRenderer in batchRenderers.values) {
            batchRenderer.clear()
        }
    }

    override fun iterator() = renderables.iterator()

    override fun doRender(rc: RenderContext) {
        for (i in renderables.indices) {
            val renderable = renderables[i]
            try {
                renderable.render(rc)
            } catch (e: Exception) {
                logMessage(
                    ERROR, "RenderableLayer", "doRender",
                    "Exception while rendering shape '${renderable.displayName}'", e
                )
                // Keep going. Draw the remaining renderables.
            }
        }
        try {
            for(batchRenderer in batchRenderers.values) {
                batchRenderer.render(rc)
            }
        } catch (e: Exception) {
            logMessage(
                ERROR, "RenderableLayer", "doRender",
                "Exception while rendering surfaceLinesBatch", e
            )
        }
    }
}