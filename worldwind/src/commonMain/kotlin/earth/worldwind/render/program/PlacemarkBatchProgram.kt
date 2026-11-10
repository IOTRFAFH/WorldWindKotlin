package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawablePlacemarkBatch
import earth.worldwind.geom.Matrix3
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Draws batched placemark icons ([DrawablePlacemarkBatch]). The vertex shader does per icon what
 * [earth.worldwind.shape.Placemark] does on the CPU for each icon: eye-distance scaling, billboard lift along the
 * surface normal, projection with a depth offset ([earth.worldwind.render.RenderContext.projectWithDepth]) and the
 * screen-space image transform (pivot offset, rotation, tilt). The fragment stage matches [BasicShaderProgram] without
 * pick mode: picking frames bypass the batch.
 */
class PlacemarkBatchProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 modelview;  /* with the batch origin folded in */
            uniform mat4 projection;
            uniform vec4 viewport;   /* x, y, width, height */
            uniform vec3 eyePoint;   /* relative to the batch origin */
            uniform vec4 camera;     /* heading, tilt (radians), pixel size per meter of distance, 2D viewing distance (0 in 3D) */
            uniform vec2 depth;      /* horizon distance, depth offset */
            uniform bool enableTexture;
            uniform mat3 texCoordMatrix;

            /* Compact vertex format, see DrawablePlacemarkBatch. */
            attribute vec3 vertexPoint;      /* point relative to the batch origin */
            attribute vec4 vertexFlags;      /* corner (0-3), rotation relative to globe, tilt relative to globe, visible */
            attribute vec4 vertexNormal;     /* billboard normal, zero when billboarding is off */
            attribute vec4 vertexSize;       /* width, height, offset x, offset y in quarter pixels (before scaling) */
            attribute vec4 vertexAngles;     /* rotation / pi, tilt / pi, minimum image scale / 32 */
            attribute vec4 vertexColor;      /* premultiplied */
            attribute float vertexThreshold; /* eye-distance scaling threshold in meters, negative when off */

            varying vec2 texCoord;
            varying vec4 color;

            const float PI = 3.141592653589793;

            void main() {
                vec3 point = vertexPoint;
                float dist = camera.w > 0.0 ? camera.w : length(point - eyePoint);

                /* Scale distant icons down, between the minimum image scale and 1. */
                float minScale = vertexAngles.z * 32.0;
                float scale = vertexThreshold < 0.0 ? 1.0 : clamp(vertexThreshold / dist, minScale, 1.0);
                vec4 size = vertexSize * (0.25 * scale);

                /* Lift billboarded icons along the surface normal by their offset so they do not cut into the terrain. */
                point += vertexNormal.xyz * (size.w * camera.z * dist * sin(camera.y));

                vec4 eye = modelview * vec4(point, 1.0);
                vec4 clip = projection * eye;
                float ndcZ = clip.z / clip.w;
                if (vertexFlags.w < 0.5 || clip.w <= 0.0 || ndcZ < -1.0 || ndcZ > 1.0) {
                    /* Clipped by the near or far plane, or hidden: move the vertex outside the clip volume. */
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    texCoord = vec2(0.0);
                    color = vec4(0.0);
                    return;
                }

                /* Pull the point toward the eye with the depth offset applied to the eye Z, inside the horizon. */
                float offset = dist < depth.x ? depth.y : 0.0;
                vec4 clipOffset = projection * vec4(eye.xy, eye.z * (1.0 + offset), eye.w);
                float z = clamp(clipOffset.z / clip.w, -1.0, 1.0);
                vec2 screen = (clip.xy / clip.w * 0.5 + 0.5) * viewport.zw + viewport.xy;
                float screenZ = z * 0.5 + 0.5;

                /* Scale the unit square corner, move the pivot to the origin, rotate it, then tilt it back. */
                vec2 corner = vec2(mod(vertexFlags.x, 2.0), floor(vertexFlags.x * 0.5));
                vec2 p = corner * size.xy - size.zw;
                float imageRotation = vertexAngles.x * PI;
                float imageTilt = vertexAngles.y * PI;
                float rotation = vertexFlags.y > 0.5 ? camera.x - imageRotation : -imageRotation;
                float tilt = vertexFlags.z > 0.5 ? camera.y + imageTilt : imageTilt;
                float c = cos(rotation);
                float s = sin(rotation);
                p = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
                /* Divide the tilt depth by 2^24 (the depth buffer bit size) to prevent texture clipping when tilting. */
                vec3 window = vec3(screen.x + p.x, screen.y + p.y * cos(tilt), screenZ - p.y * sin(tilt) / 16777216.0);

                /* Window to clip coordinates, as Matrix4.setToScreenProjection does (viewport origin at 0, 0). */
                gl_Position = vec4(window.x * 2.0 / viewport.z - 1.0, window.y * 2.0 / viewport.w - 1.0,
                    window.z * 2.0 - 1.0, 1.0);

                if (enableTexture) texCoord = (texCoordMatrix * vec3(corner, 1.0)).st;
                color = vertexColor;
            }
        """.trimIndent(),
        """
            #ifdef GL_ES
            precision mediump float;
            #endif

            uniform bool enableTexture;
            uniform float opacity;
            uniform sampler2D texSampler;

            varying vec2 texCoord;
            varying vec4 color;

            void main() {
                if (enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's RGBA color. */
                    gl_FragColor = color * texture2D(texSampler, texCoord) * opacity;
                } else {
                    gl_FragColor = color * opacity;
                }
                /* Skip near-transparent textured fragments (filtered icon halos) so they don't write depth. */
                if (enableTexture && gl_FragColor.a < 0.001) discard;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf(
        "vertexPoint", "vertexFlags", "vertexNormal", "vertexSize", "vertexAngles", "vertexColor", "vertexThreshold"
    )

    private var enableTexture = false
    private val texCoordMatrix = Matrix3()
    private var modelviewId = KglUniformLocation.NONE
    private var projectionId = KglUniformLocation.NONE
    private var viewportId = KglUniformLocation.NONE
    private var eyePointId = KglUniformLocation.NONE
    private var cameraId = KglUniformLocation.NONE
    private var depthId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        modelviewId = gl.getUniformLocation(program, "modelview")
        projectionId = gl.getUniformLocation(program, "projection")
        viewportId = gl.getUniformLocation(program, "viewport")
        eyePointId = gl.getUniformLocation(program, "eyePoint")
        cameraId = gl.getUniformLocation(program, "camera")
        depthId = gl.getUniformLocation(program, "depth")
        opacityId = gl.getUniformLocation(program, "opacity")
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, if (enableTexture) 1 else 0)
        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0
    }

    /**
     * Loads the camera state of [drawable]'s frame. Called once per batch.
     */
    fun loadFrame(drawable: DrawablePlacemarkBatch) {
        drawable.modelview.transposeToArray(array, 0)
        gl.uniformMatrix4fv(modelviewId, 1, false, array, 0)
        drawable.projection.transposeToArray(array, 0)
        gl.uniformMatrix4fv(projectionId, 1, false, array, 0)
        gl.uniform4f(viewportId, drawable.viewportX, drawable.viewportY, drawable.viewportWidth, drawable.viewportHeight)
        val eye = drawable.eyePoint
        gl.uniform3f(eyePointId, eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat())
        gl.uniform4f(cameraId, drawable.heading, drawable.tilt, drawable.pixelSizeFactor, drawable.viewingDistance2D)
        gl.uniform2f(depthId, drawable.horizonDistance, drawable.depthOffset)
        gl.uniform1f(opacityId, drawable.opacity)
    }

    fun enableTexture(enable: Boolean) {
        if (enableTexture != enable) {
            enableTexture = enable
            gl.uniform1i(enableTextureId, if (enable) 1 else 0)
        }
    }

    fun loadTexCoordMatrix(matrix: Matrix3) {
        if (texCoordMatrix != matrix) {
            texCoordMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        }
    }
}
