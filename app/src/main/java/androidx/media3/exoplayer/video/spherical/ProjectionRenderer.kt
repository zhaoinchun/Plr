package androidx.media3.exoplayer.video.spherical

import android.opengl.GLES11Ext
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.common.util.Log.e
import androidx.media3.exoplayer.video.spherical.Projection.SubMesh
import java.nio.FloatBuffer

internal class ProjectionRenderer {
    private var stereoMode = 0
    private var leftMeshData: MeshData? = null
    private var rightMeshData: MeshData? = null
    private var program: GlProgram? = null

    // Program related GL items. These are only valid if Program is valid.
    private var mvpMatrixHandle = 0
    private var uTexMatrixHandle = 0
    private var positionHandle = 0
    private var texCoordsHandle = 0
    private var textureHandle = 0

    fun setProjection(projection: Projection) {
        if (!isSupported(projection)) {
            return
        }
        stereoMode = projection.stereoMode
        leftMeshData = MeshData(projection.leftMesh.getSubMesh(0))
        rightMeshData =
            if (projection.singleMesh) leftMeshData else MeshData(projection.rightMesh.getSubMesh(0))
    }

    fun init() {
        try {
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            val program = program!!
            mvpMatrixHandle = program.getUniformLocation("uMvpMatrix")
            uTexMatrixHandle = program.getUniformLocation("uTexMatrix")
            positionHandle = program.getAttributeArrayLocationAndEnable("aPosition")
            texCoordsHandle = program.getAttributeArrayLocationAndEnable("aTexCoords")
            textureHandle = program.getUniformLocation("uTexture")
        } catch (e: GlException) {
            e(TAG, "Failed to initialize the program", e)
        }
    }

    fun draw(textureId: Int, mvpMatrix: FloatArray, rightEye: Boolean) {
        val meshData = if (rightEye) rightMeshData else leftMeshData
        if (meshData == null) {
            return
        }

        // Configure shader.
        val texMatrix: FloatArray = if (stereoMode == C.STEREO_MODE_TOP_BOTTOM) {
            if (rightEye) TEX_MATRIX_BOTTOM else TEX_MATRIX_TOP
        } else if (stereoMode == C.STEREO_MODE_LEFT_RIGHT) {
            if (rightEye) TEX_MATRIX_RIGHT else TEX_MATRIX_LEFT
        } else {
            TEX_MATRIX_WHOLE
        }
        GLES20.glUniformMatrix3fv(uTexMatrixHandle, 1, false, texMatrix, 0)

        // TODO(b/205002913): Update to use GlProgram.Uniform.bind().
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)
        try {
            GlUtil.checkGlError()
        } catch (e: GlException) {
            e(TAG, "Failed to bind uniforms", e)
        }

        // Load position data.
        GLES20.glVertexAttribPointer(
            positionHandle,
            Projection.POSITION_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            Projection.POSITION_COORDS_PER_VERTEX * C.BYTES_PER_FLOAT,
            meshData.vertexBuffer
        )
        try {
            GlUtil.checkGlError()
        } catch (e: GlException) {
            e(TAG, "Failed to load position data", e)
        }

        // Load texture data.
        GLES20.glVertexAttribPointer(
            texCoordsHandle,
            Projection.TEXTURE_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            Projection.TEXTURE_COORDS_PER_VERTEX * C.BYTES_PER_FLOAT,
            meshData.textureBuffer
        )
        try {
            GlUtil.checkGlError()
        } catch (e: GlException) {
            e(TAG, "Failed to load texture data", e)
        }

        // Render.
        GLES20.glDrawArrays(meshData.drawMode,  0, meshData.vertexCount)
        try {
            GlUtil.checkGlError()
        } catch (e: GlException) {
            e(TAG, "Failed to render", e)
        }
    }

    fun shutdown() {
        if (program != null) {
            try {
                program!!.delete()
            } catch (e: GlException) {
                e(TAG, "Failed to delete the shader program", e)
            }
        }
    }

    private class MeshData(subMesh: SubMesh) {
        val vertexCount: Int = subMesh.vertexCount
        val vertexBuffer: FloatBuffer = GlUtil.createBuffer(subMesh.vertices)
        val textureBuffer: FloatBuffer = GlUtil.createBuffer(subMesh.textureCoords)
        val drawMode: Int = when (subMesh.mode) {
            Projection.DRAW_MODE_TRIANGLES_STRIP -> GLES20.GL_TRIANGLE_STRIP

            Projection.DRAW_MODE_TRIANGLES_FAN -> GLES20.GL_TRIANGLE_FAN
            Projection.DRAW_MODE_TRIANGLES -> GLES20.GL_TRIANGLES
            else -> GLES20.GL_TRIANGLES
        }
    }

    companion object {
        fun isSupported(projection: Projection): Boolean {
            val leftMesh = projection.leftMesh
            val rightMesh = projection.rightMesh
            return leftMesh.subMeshCount == 1 && leftMesh.getSubMesh(0).textureId == SubMesh.VIDEO_TEXTURE_ID && rightMesh.subMeshCount == 1 && rightMesh.getSubMesh(
                0
            ).textureId == SubMesh.VIDEO_TEXTURE_ID
        }

        private const val TAG = "ProjectionRenderer"

        // Basic vertex & fragment shaders to render a mesh with 3D position & 2D texture data.
        private val VERTEX_SHADER = ("uniform mat4 uMvpMatrix;\n"
                + "uniform mat3 uTexMatrix;\n"
                + "attribute vec4 aPosition;\n"
                + "attribute vec2 aTexCoords;\n"
                + "varying vec2 vTexCoords;\n"
                + "// Standard transformation.\n"
                + "void main() {\n"
                + "  gl_Position = uMvpMatrix * aPosition;\n"
                + "  vTexCoords = (uTexMatrix * vec3(aTexCoords, 1)).xy;\n"
                + "}\n")
        private val FRAGMENT_SHADER =
            ("// This is required since the texture data is GL_TEXTURE_EXTERNAL_OES.\n"
                    + "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "// Standard texture rendering shader.\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "varying vec2 vTexCoords;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTexture, vTexCoords);\n"
                    + "}\n")

        // Texture transform matrices.
        private val TEX_MATRIX_WHOLE = floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 1.0f
        )
        private val TEX_MATRIX_TOP = floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f, -0.5f, 0.0f, 0.0f, 0.5f, 1.0f
        )
        private val TEX_MATRIX_BOTTOM = floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f, -0.5f, 0.0f, 0.0f, 1.0f, 1.0f
        )
        private val TEX_MATRIX_LEFT = floatArrayOf(
            0.5f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 1.0f, 1.0f
        )
        private val TEX_MATRIX_RIGHT = floatArrayOf(
            0.5f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, 0.5f, 1.0f, 1.0f
        )
    }
}
