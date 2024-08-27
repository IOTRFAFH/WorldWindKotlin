package earth.worldwind.render

import earth.worldwind.shape.LineSet
import earth.worldwind.shape.Path

class BatchedLines(val isSurfaceShape : Boolean) {
    private val batches = mutableListOf<LineSet>()
    private val freeBatches =
        mutableListOf<LineSet>() // duplicate batches that aren't full here
    private val pathToBatch = mutableMapOf<Path, LineSet>()

    fun addPath(path: Path) {
        if (freeBatches.isEmpty()) {
            val newBatch = LineSet(isSurfaceShape)
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
        if (batch.removePath(path) && !freeBatches.contains(batch)) freeBatches.add(batch)
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