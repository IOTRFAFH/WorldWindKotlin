package earth.worldwind.render

import earth.worldwind.render.BatchRenderer

class PathBatchRenderer : BatchRenderer {

    /* return true if shape was batched */
    override fun addOrUpdateRenderable(renderable: Renderable) : Boolean {
        return  false
    }

    override fun removeRenderable(renderable : Renderable) {

    }

    override fun render(rc : RenderContext) {

    }

    override fun clear() {

    }
}