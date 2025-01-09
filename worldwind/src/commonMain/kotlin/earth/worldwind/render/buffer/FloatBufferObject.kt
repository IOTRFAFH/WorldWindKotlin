package earth.worldwind.render.buffer

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.GL_STATIC_DRAW
import kotlin.concurrent.Volatile

open class FloatBufferObject(
    target: Int, array: FloatArray, size: Int = array.size
) : AbstractBufferObject(target, size * Float.SIZE_BYTES) {
    @Volatile protected var array: FloatArray? = array

    override fun release(dc: DrawContext) {
        super.release(dc)
        array = null // array can be non-null if the object has not been bound
    }

    override fun bindBuffer(dc: DrawContext): Boolean {
        array?.let { loadBuffer(dc) }.also { array = null }
        return super.bindBuffer(dc)
    }

    override fun loadBufferObjectData(dc: DrawContext) {
        array?.let { dc.gl.bufferData(target, byteCount, it, GL_STATIC_DRAW) }
    }

    fun updateArray(newArray: FloatArray?)
    {
        if(array === newArray)
            return
        val newSize = newArray?.let { it.size * Float.SIZE_BYTES } ?: 0
        array = newArray
        byteCount = newSize
    }
}