package earth.worldwind.shape

import earth.worldwind.shape.PlacemarkBatch.Companion.normalizeRadians
import earth.worldwind.shape.PlacemarkBatch.Companion.packBytes
import earth.worldwind.shape.PlacemarkBatch.Companion.packShorts
import earth.worldwind.shape.PlacemarkBatch.Companion.quarterPixels
import earth.worldwind.shape.PlacemarkBatch.Companion.signedByte
import earth.worldwind.shape.PlacemarkBatch.Companion.signedShort
import earth.worldwind.shape.PlacemarkBatch.Companion.unsignedByte
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that [PlacemarkBatch]'s packed record decodes, the way OpenGL ES reads vertex attributes from a little-endian
 * int buffer, back to the values the shader expects.
 */
class PlacemarkBatchPackingTest {
    /** The bytes of [ints] as uploaded: native order, little-endian on every supported platform. */
    private fun bytes(vararg ints: Int) = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN).also {
        for (i in ints) it.putInt(i)
        it.flip()
    }

    /** OpenGL ES 2.0 normalized signed conversion (spec 2.1.2): max(c / (2^(b-1) - 1), -1). */
    private fun normalized(c: Int, bits: Int) = max(c / ((1 shl (bits - 1)) - 1).toDouble(), -1.0)

    @Test
    fun bytesKeepTheirOrderAndSign() {
        val buffer = bytes(packBytes(signedByte(0.5), signedByte(-1.0), signedByte(1.0), 0))
        assertTrue(abs(normalized(buffer.get(0).toInt(), 8) - 0.5) < 1.0 / 127)
        assertEquals(-1.0, normalized(buffer.get(1).toInt(), 8))
        assertEquals(1.0, normalized(buffer.get(2).toInt(), 8))
        assertEquals(0, buffer.get(3).toInt())
    }

    @Test
    fun colorBytesAreUnsignedNormalized() {
        val buffer = bytes(packBytes(unsignedByte(1f), unsignedByte(0.5f), unsignedByte(0f), unsignedByte(0.25f)))
        assertEquals(255, buffer.get(0).toInt() and 0xFF)
        assertEquals(128, buffer.get(1).toInt() and 0xFF)
        assertEquals(0, buffer.get(2).toInt() and 0xFF)
        assertEquals(64, buffer.get(3).toInt() and 0xFF)
    }

    @Test
    fun sizesRoundTripInQuarterPixels() {
        val buffer = bytes(packShorts(quarterPixels(48.3), quarterPixels(-12.6)))
        assertEquals(48.25, buffer.getShort(0) * 0.25)
        assertEquals(-12.5, buffer.getShort(2) * 0.25)
        assertEquals(32767, quarterPixels(1e6)) // clamped, not wrapped
    }

    @Test
    fun anglesRoundTripThroughNormalizedShorts() {
        for (degrees in listOf(0.0, 45.0, -90.0, 179.0, 270.0, -450.0, 1000.0)) {
            val radians = degrees * PI / 180
            val buffer = bytes(packShorts(signedShort(normalizeRadians(radians) / PI), 0))
            val decoded = normalized(buffer.getShort(0).toInt(), 16) * PI
            // Same angle modulo a full turn, within the format's step of pi / 32767.
            val difference = abs(((decoded - radians) % (2 * PI) + 3 * PI) % (2 * PI) - PI)
            assertTrue(difference < 2 * PI / 32767, "$degrees deg decoded to ${decoded * 180 / PI} deg")
        }
    }
}
