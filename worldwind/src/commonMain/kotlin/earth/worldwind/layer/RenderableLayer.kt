package earth.worldwind.layer

import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.render.BatchedLines
import earth.worldwind.shape.LineSetAttributes
import earth.worldwind.shape.Path
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

open class RenderableLayer @JvmOverloads constructor(displayName: String? = null): AbstractLayer(displayName), Iterable<Renderable> {
    protected val renderables = mutableListOf<Renderable>()
    val count get() = renderables.size

    private val batchedLines = mutableListOf<BatchedLines>()
    private val attributesToBatchedLines = mutableMapOf<LineSetAttributes, BatchedLines>()
    private val pathToBatchedLines = mutableMapOf<Path, BatchedLines>()

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
        if (oldRenderable is Path) {
            removePathFromBatch(oldRenderable)
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

    fun removeRenderable(renderable: Renderable) : Boolean {
        if (renderables.remove(renderable)) {
            if (renderable is Path)
                removePathFromBatch(renderable)
            return true
        }
        return false
    }

    fun removeRenderable(index: Int): Renderable {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "removeRenderable", "invalidIndex")
        }
        val renderable = renderables[index]
        if (renderable is Path) {
            removePathFromBatch(renderable)
        }
        return renderables.removeAt(index)
    }

    fun removeAllRenderables(renderables: Iterable<Renderable>): Boolean {
        var removed = false
        for (renderable in renderables) {
            removed = removed or this.renderables.remove(renderable)
            if (renderable is Path) {
                removePathFromBatch(renderable)
            }
        }
        return removed
    }

    fun clearRenderables() {
        renderables.clear()
        batchedLines.clear()
        attributesToBatchedLines.clear()
        pathToBatchedLines.clear()
    }

    private fun addPathToBatch(path: Path) {
        val pathAttributes = LineSetAttributes(path)
        var batch = attributesToBatchedLines[pathAttributes]
        if(batch == null) {
            batch = BatchedLines(pathAttributes)
            batchedLines.add(batch)
            attributesToBatchedLines[pathAttributes] = batch
        }
        batch.addPath(path)
        pathToBatchedLines[path] = batch
    }

    private fun removePathFromBatch(path : Path) {
        val currentBatch = pathToBatchedLines[path] ?: return
        currentBatch.removePath(path)
        pathToBatchedLines.remove(path)
    }

    private fun updatePath(path: Path) {
        val pathAttributes = LineSetAttributes(path)
        val currentBatch = pathToBatchedLines[path] ?: return
        if (!currentBatch.isAttributesEqual(pathAttributes)) {
            removePathFromBatch(path)
            addPathToBatch(path)
        }
    }

    override fun iterator() = renderables.iterator()

    override fun doRender(rc: RenderContext) {
        for (i in renderables.indices) {
            val renderable = renderables[i]
            try {
                // Here we're batching Paths
                if(renderable is Path) {
                    renderable.updateAttributes(rc)
                    val pathCanBeBatched = renderable.canBeBatched(rc)
                    val pathWasBatched = pathToBatchedLines[renderable] != null
                    if (pathCanBeBatched) {
                        if (!pathWasBatched) {
                            renderable.reset()
                            addPathToBatch(renderable)
                        } else if (pathWasBatched) {
                            updatePath(renderable)
                        }
                        continue // skip path rendering if it was batched
                    } else if (!pathCanBeBatched && pathWasBatched) {
                        removePathFromBatch(renderable)
                    }
                }
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
            for (batch in batchedLines) {
                batch.render(rc)
            }
        } catch (e: Exception) {
            logMessage(
                ERROR, "RenderableLayer", "doRender",
                "Exception while rendering surfaceLinesBatch", e
            )
        }
    }
}