package earth.worldwind.render.program

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks that [PlacemarkBatchProgram]'s vertex math, ported here line by line, places icon corners where
 * [earth.worldwind.shape.Placemark] does on the CPU: `RenderContext.projectWithDepth`, then `prepareImageTransform`,
 * then `Matrix4.setToScreenProjection`. Rotation direction and tilt sign are the easy things to get wrong.
 */
class PlacemarkBatchMathTest {
    private val viewportW = 1080
    private val viewportH = 2200

    @Test
    fun cornersMatchPlacemarkTransform() {
        val random = Random(42)
        repeat(500) {
            val screen = Vec3(random.nextDouble(0.0, viewportW.toDouble()), random.nextDouble(0.0, viewportH.toDouble()),
                random.nextDouble(0.0, 1.0))
            val w = random.nextDouble(8.0, 96.0)
            val h = random.nextDouble(8.0, 96.0)
            val ox = random.nextDouble(-0.5, 1.5) * w
            val oy = random.nextDouble(-0.5, 1.5) * h
            val rotation = random.nextDouble(-4.0, 4.0)
            val tilt = random.nextDouble(-1.5, 1.5)
            for (corner in 0..3) {
                val expected = cpuCorner(screen, corner, w, h, ox, oy, rotation, tilt)
                val actual = shaderCorner(screen, corner, w, h, ox, oy, rotation, tilt)
                for (k in 0..2) assertTrue(abs(expected[k] - actual[k]) < 1e-9,
                    "corner $corner axis $k: cpu ${expected.toList()} shader ${actual.toList()}")
            }
        }
    }

    @Test
    fun projectionMatchesProjectWithDepth() {
        val random = Random(7)
        val projection = Matrix4().setToPerspectiveProjection(viewportW, viewportH, 45.0.degrees, 10.0, 5e6)
        repeat(500) {
            // A camera looking down the -Z axis from a random place, rotated a little, with points in front of it.
            val modelview = Matrix4()
                .multiplyByRotation(1.0, 0.0, 0.0, random.nextDouble(-0.5, 0.5).radians)
                .multiplyByRotation(0.0, 1.0, 0.0, random.nextDouble(-0.5, 0.5).radians)
                .multiplyByTranslation(-6.3e6, -1.2e6, -2.1e6)
            val point = Vec3(6.3e6 + random.nextDouble(-2e4, 2e4), 1.2e6 + random.nextDouble(-2e4, 2e4),
                2.1e6 - random.nextDouble(1e3, 1e5))
            val origin = Vec3(6.29e6, 1.21e6, 2.05e6)
            val depthOffset = random.nextDouble(-0.03, 0.0)

            val expected = cpuProjectWithDepth(modelview, projection, point, depthOffset) ?: return@repeat
            val actual = shaderProject(modelview, projection, origin, point, depthOffset)
            for (k in 0..2) assertTrue(abs(expected[k] - actual[k]) < 1e-6 * maxOf(1.0, abs(expected[k])),
                "axis $k: cpu ${expected.toList()} shader ${actual.toList()}")
        }
    }

    /** Placemark: imageTransform (prepareImageTransform) applied to the unit square corner, then screen projection. */
    private fun cpuCorner(
        screen: Vec3, corner: Int, w: Double, h: Double, ox: Double, oy: Double, rotation: Double, tilt: Double
    ): DoubleArray {
        val transform = Matrix4()
            .multiplyByTranslation(screen.x, screen.y, screen.z)
            .multiplyByScale(1.0, 1.0, 1.0 / (1 shl 24))
            .multiplyByRotation(-1.0, 0.0, 0.0, tilt.radians)
            .multiplyByRotation(0.0, 0.0, 1.0, rotation.radians)
            .multiplyByTranslation(-ox, -oy, 0.0)
            .multiplyByScale(w, h, 1.0)
        val mvp = Matrix4().setToScreenProjection(viewportW.toDouble(), viewportH.toDouble()).multiplyByMatrix(transform)
        val u = (corner % 2).toDouble()
        val v = (corner / 2).toDouble()
        return transform4(mvp, u, v, 0.0, 1.0).let { doubleArrayOf(it[0] / it[3], it[1] / it[3], it[2] / it[3]) }
    }

    /** PlacemarkBatchProgram: corner placement in window coordinates, then window to clip coordinates. */
    private fun shaderCorner(
        screen: Vec3, corner: Int, w: Double, h: Double, ox: Double, oy: Double, rotation: Double, tilt: Double
    ): DoubleArray {
        val cornerX = corner.toDouble() % 2.0
        val cornerY = floor(corner * 0.5)
        var px = cornerX * w - ox
        var py = cornerY * h - oy
        val c = cos(rotation)
        val s = sin(rotation)
        val rx = px * c - py * s
        val ry = px * s + py * c
        px = rx
        py = ry
        val windowX = screen.x + px
        val windowY = screen.y + py * cos(tilt)
        val windowZ = screen.z - py * sin(tilt) / 16777216.0
        return doubleArrayOf(windowX * 2.0 / viewportW - 1.0, windowY * 2.0 / viewportH - 1.0, windowZ * 2.0 - 1.0)
    }

    /** RenderContext.projectWithDepth, viewport at the origin. */
    private fun cpuProjectWithDepth(modelview: Matrix4, projection: Matrix4, point: Vec3, depthOffset: Double): DoubleArray? {
        val eye = transform4(modelview, point.x, point.y, point.z, 1.0)
        val clip = transform4(projection, eye[0], eye[1], eye[2], eye[3])
        val w = clip[3]
        if (w == 0.0) return null
        val x = clip[0] / w
        val y = clip[1] / w
        val z = clip[2] / w
        if (z < -1 || z > 1) return null
        val p = projection.m
        val zOffset = ((p[8] * eye[0] + p[9] * eye[1] + p[10] * eye[2] * (1 + depthOffset) + p[11] * eye[3]) / w)
            .coerceIn(-1.0, 1.0)
        return doubleArrayOf((x * 0.5 + 0.5) * viewportW, (y * 0.5 + 0.5) * viewportH, zOffset * 0.5 + 0.5)
    }

    /** PlacemarkBatchProgram: projection with the origin folded into the modelview and the depth offset on eye Z. */
    private fun shaderProject(
        modelview: Matrix4, projection: Matrix4, origin: Vec3, point: Vec3, depthOffset: Double
    ): DoubleArray {
        val folded = Matrix4(modelview).multiplyByTranslation(origin.x, origin.y, origin.z)
        val eye = transform4(folded, point.x - origin.x, point.y - origin.y, point.z - origin.z, 1.0)
        val clip = transform4(projection, eye[0], eye[1], eye[2], eye[3])
        val clipOffset = transform4(projection, eye[0], eye[1], eye[2] * (1.0 + depthOffset), eye[3])
        val z = (clipOffset[2] / clip[3]).coerceIn(-1.0, 1.0)
        return doubleArrayOf(
            (clip[0] / clip[3] * 0.5 + 0.5) * viewportW, (clip[1] / clip[3] * 0.5 + 0.5) * viewportH, z * 0.5 + 0.5
        )
    }

    private fun transform4(matrix: Matrix4, x: Double, y: Double, z: Double, w: Double): DoubleArray {
        val m = matrix.m
        return doubleArrayOf(
            m[0] * x + m[1] * y + m[2] * z + m[3] * w,
            m[4] * x + m[5] * y + m[6] * z + m[7] * w,
            m[8] * x + m[9] * y + m[10] * z + m[11] * w,
            m[12] * x + m[13] * y + m[14] * z + m[15] * w,
        )
    }
}
