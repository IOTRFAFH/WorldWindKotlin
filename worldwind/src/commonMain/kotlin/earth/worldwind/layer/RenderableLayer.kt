package earth.worldwind.layer

import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.LinesBatch
import earth.worldwind.shape.Path
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

open class RenderableLayer @JvmOverloads constructor(displayName: String? = null): AbstractLayer(displayName), Iterable<Renderable> {
    protected val renderables = mutableListOf<Renderable>()
    val count get() = renderables.size

    private class LineBatchesContainer(val isSurfaceShape : Boolean) {
        private val batches = mutableListOf<LinesBatch>()
        private val freeBatches =
            mutableListOf<LinesBatch>() // duplicate batches that aren't full here
        private val pathToBatch = mutableMapOf<Path, LinesBatch>()

        fun addPath(path: Path) {
            if (freeBatches.isEmpty()) {
                val newBatch = LinesBatch(isSurfaceShape)
                newBatch.addPath(path)
                pathToBatch[path] = newBatch

                batches.add(newBatch)
                freeBatches.add(newBatch)
            } else {
                val freeBatch = freeBatches[0]
                freeBatch.addPath(path)
                pathToBatch[path] = freeBatch

                if (freeBatch.isFull()) freeBatches.remove(freeBatch)
            }
        }

        fun removePath(path: Path) {
            val batch = pathToBatch[path] ?: return
            batch.removePath(path)
            freeBatches.add(batch)
            pathToBatch.remove(path)
        }

        fun containsPath(path : Path) : Boolean {
            return pathToBatch[path] != null
        }

        fun render(rc: RenderContext) {
            for (batch in batches) {
                batch.render(rc)
            }
        }
    }

    private val surfaceLinesBatch = LineBatchesContainer(true)
    private val globeLinesBatch = LineBatchesContainer(false)

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

    fun addRenderable(renderable: Renderable) {
        renderables.add(renderable)
    }

    fun addRenderable(index: Int, renderable: Renderable) {
        require(index in renderables.indices) {
            logMessage(ERROR, "RenderableLayer", "addRenderable", "invalidIndex")
        }
        renderables.add(index, renderable)
    }

    fun addAllRenderables(layer: RenderableLayer) {
        //renderables.ensureCapacity(layer.renderables.size)
        for (renderable in layer.renderables) {
            renderables.add(renderable) // we know the contents of layer.renderables is valid
        }
    }

    fun addAllRenderables(iterable: Iterable<Renderable>) { for (renderable in iterable) {
        renderables.add(renderable)
    } }

    fun removeRenderable(renderable: Renderable) {
        renderables.remove(renderable)
        if (renderable is Path) {
            removePathFromBatch(renderable)
        }
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

    fun clearRenderables() = renderables.clear()

    private fun addPathToBatch(path: Path) {
        if (path.isSurfaceShape) {
            surfaceLinesBatch.addPath(path)
        } else {
            globeLinesBatch.addPath(path)
        }
    }

    private fun removePathFromBatch(path : Path) {
        if (surfaceLinesBatch.containsPath(path)) {
            surfaceLinesBatch.removePath(path)
        } else if (globeLinesBatch.containsPath(path)) {
            globeLinesBatch.removePath(path)
        }
    }

    private fun updatePath(path: Path) {
        val changeBatch = surfaceLinesBatch.containsPath(path) && !path.isSurfaceShape
                || globeLinesBatch.containsPath(path) && path.isSurfaceShape
        if (changeBatch) {
            removePathFromBatch(path)
            addPathToBatch(path)
        }
    }

    override fun iterator() = renderables.iterator()

    override fun doRender(rc: RenderContext) {
        for (i in renderables.indices) {
            val renderable = renderables[i]
            try {
                renderable.render(rc)

                // Here we're batching Paths
                if(renderable is Path && renderable.canBeBatched(rc)) {
                    val pathWasBatched =
                        surfaceLinesBatch.containsPath(renderable) || globeLinesBatch.containsPath(
                            renderable
                        )
                    if (!pathWasBatched) {
                        addPathToBatch(renderable)
                    } else if (pathWasBatched) {
                        updatePath(renderable)
                    }
                }
            } catch (e: Exception) {
                logMessage(
                    ERROR, "RenderableLayer", "doRender",
                    "Exception while rendering shape '${renderable.displayName}'", e
                )
                // Keep going. Draw the remaining renderables.
            }
        }
        try {
            surfaceLinesBatch.render(rc)
        } catch (e: Exception) {
            logMessage(
                ERROR, "RenderableLayer", "doRender",
                "Exception while rendering surfaceLinesBatch", e
            )
        }
        try {
            globeLinesBatch.render(rc)
        } catch (e: Exception) {
            logMessage(
                ERROR, "RenderableLayer", "doRender",
                "Exception while rendering shape globeLinesBatch", e
            )
        }
    }
}