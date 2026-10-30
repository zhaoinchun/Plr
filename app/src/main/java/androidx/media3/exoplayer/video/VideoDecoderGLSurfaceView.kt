
package androidx.media3.exoplayer.video

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.common.util.Log.e

import androidx.media3.decoder.VideoDecoderOutputBuffer
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VideoDecoderGLSurfaceView  constructor(
    context: Context?,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), VideoDecoderOutputBufferRenderer {
    private val renderer: Renderer

    init {
        renderer = Renderer( this)
        setPreserveEGLContextOnPause(true)
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        setRenderMode(RENDERMODE_WHEN_DIRTY)
    }

    override fun setOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        renderer.setOutputBuffer(outputBuffer)
    }

    @get:Deprecated("This class implements {@link VideoDecoderOutputBufferRenderer} directly.")
    val videoDecoderOutputBufferRenderer: VideoDecoderOutputBufferRenderer
        get() = this

    private class Renderer(private val surfaceView: GLSurfaceView) : GLSurfaceView.Renderer {
        private val yuvTextures: IntArray
        private val texLocations: IntArray
        private val previousWidths: IntArray
        private val previousStrides: IntArray
        private val pendingOutputBufferReference: AtomicReference<VideoDecoderOutputBuffer?>

        // Kept in field rather than a local variable in order not to get garbage collected before
        // glDrawArrays uses it.
        private val textureCoords: Array<FloatBuffer?>

        private var program:GlProgram? = null
        private var colorMatrixLocation = 0

        // Accessed only from the GL thread.
        private var renderedOutputBuffer:VideoDecoderOutputBuffer? = null

        init {
            yuvTextures = IntArray(3)
            texLocations = IntArray(3)
            previousWidths = IntArray(3)
            previousStrides = IntArray(3)
            pendingOutputBufferReference = AtomicReference<VideoDecoderOutputBuffer?>()
            textureCoords = arrayOfNulls<FloatBuffer>(3)
            for (i in 0..2) {
                previousStrides[i] = -1
                previousWidths[i] = previousStrides[i]
            }
        }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            try {
                program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                val posLocation = program!!.getAttributeArrayLocationAndEnable("in_pos")
                GLES20.glVertexAttribPointer(
                    posLocation,
                    2,
                    GLES20.GL_FLOAT,  
                    false,  
                    0,
                    TEXTURE_VERTICES
                )
                texLocations[0] = program!!.getAttributeArrayLocationAndEnable("in_tc_y")
                texLocations[1] = program!!.getAttributeArrayLocationAndEnable("in_tc_u")
                texLocations[2] = program!!.getAttributeArrayLocationAndEnable("in_tc_v")
                colorMatrixLocation = program!!.getUniformLocation("mColorConversion")
                GlUtil.checkGlError()
                setupTextures()
                GlUtil.checkGlError()
            } catch (e: GlException) {
                e(TAG, "Failed to set up the textures and program", e)
            }
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(unused: GL10?) {
            val pendingOutputBuffer =
                pendingOutputBufferReference.getAndSet( null)
            if (pendingOutputBuffer == null && renderedOutputBuffer == null) {
                // There is no output buffer to render at the moment.
                return
            }
            if (pendingOutputBuffer != null) {
                if (renderedOutputBuffer != null) {
                    renderedOutputBuffer!!.release()
                }
                renderedOutputBuffer = pendingOutputBuffer
            }

            val outputBuffer =renderedOutputBuffer!!

            // Set color matrix. Assume BT709 if the color space is unknown.
            var colorConversion: FloatArray? = kColorConversion709
            when (outputBuffer.colorspace) {
                VideoDecoderOutputBuffer.COLORSPACE_BT601 -> colorConversion = kColorConversion601
                VideoDecoderOutputBuffer.COLORSPACE_BT2020 -> colorConversion = kColorConversion2020
                VideoDecoderOutputBuffer.COLORSPACE_BT709 -> {}
                else -> {}
            }
            GLES20.glUniformMatrix3fv(
                colorMatrixLocation,  
                1,  
                false,
                colorConversion,  
                0
            )

            val yuvStrides =outputBuffer.yuvStrides!!
            val yuvPlanes =outputBuffer.yuvPlanes!!

            for (i in 0..2) {
                val h = if (i == 0) outputBuffer.height else (outputBuffer.height + 1) / 2
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[i])
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,  
                    0,
                    GLES20.GL_LUMINANCE,
                    yuvStrides[i],
                    h,  
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    yuvPlanes[i]
                )
            }

            val widths = IntArray(3)
            widths[0] = outputBuffer.width
            // TODO(b/142097774): Handle streams where chroma channels are not stored at half width and
            // height compared to the luma channel. U and V planes are being stored at half width compared
            // to Y.
            widths[2] = (widths[0] + 1) / 2
            widths[1] = widths[2]
            for (i in 0..2) {
                // Set cropping of stride if either width or stride has changed.
                if (previousWidths[i] != widths[i] || previousStrides[i] != yuvStrides[i]) {
                    Preconditions.checkState(yuvStrides[i] != 0)
                    val widthRatio = widths[i].toFloat() / yuvStrides[i]
                    // These buffers are consumed during each call to glDrawArrays. They need to be member
                    // variables rather than local variables in order not to get garbage collected.
                    textureCoords[i] =
                        GlUtil.createBuffer(
                            floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f, widthRatio, 0.0f, widthRatio, 1.0f)
                        )
                    GLES20.glVertexAttribPointer(
                        texLocations[i],  
                        2,
                        GLES20.GL_FLOAT,  
                        false,  
                        0,
                        textureCoords[i]
                    )
                    previousWidths[i] = widths[i]
                    previousStrides[i] = yuvStrides[i]
                }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,  0,  4)
            try {
                GlUtil.checkGlError()
            } catch (e: GlException) {
                e(TAG, "Failed to draw a frame", e)
            }
        }

        fun setOutputBuffer(outputBuffer: VideoDecoderOutputBuffer?) {
            val oldPendingOutputBuffer =
                pendingOutputBufferReference.getAndSet(outputBuffer)
            if (oldPendingOutputBuffer != null) {
                // The old pending output buffer will never be used for rendering, so release it now.
                oldPendingOutputBuffer.release()
            }
            surfaceView.requestRender()
        }

        @RequiresNonNull("program")
        fun setupTextures() {
            try {
                GLES20.glGenTextures( 3, yuvTextures,  0)
                for (i in 0..2) {
                    GLES20.glUniform1i(program!!.getUniformLocation(TEXTURE_UNIFORMS[i]!!), i)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                    GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[i], GLES20.GL_LINEAR)
                }
                GlUtil.checkGlError()
            } catch (e: GlException) {
                e(TAG, "Failed to set up the textures", e)
            }
        }

        companion object {
            private val kColorConversion601 = floatArrayOf(
                1.164f, 1.164f, 1.164f,
                0.0f, -0.392f, 2.017f,
                1.596f, -0.813f, 0.0f,
            )

            private val kColorConversion709 = floatArrayOf(
                1.164f, 1.164f, 1.164f,
                0.0f, -0.213f, 2.112f,
                1.793f, -0.533f, 0.0f,
            )

            private val kColorConversion2020 = floatArrayOf(
                1.168f, 1.168f, 1.168f,
                0.0f, -0.188f, 2.148f,
                1.683f, -0.652f, 0.0f,
            )

            private val VERTEX_SHADER = ("varying vec2 interp_tc_y;\n"
                    + "varying vec2 interp_tc_u;\n"
                    + "varying vec2 interp_tc_v;\n"
                    + "attribute vec4 in_pos;\n"
                    + "attribute vec2 in_tc_y;\n"
                    + "attribute vec2 in_tc_u;\n"
                    + "attribute vec2 in_tc_v;\n"
                    + "void main() {\n"
                    + "  gl_Position = in_pos;\n"
                    + "  interp_tc_y = in_tc_y;\n"
                    + "  interp_tc_u = in_tc_u;\n"
                    + "  interp_tc_v = in_tc_v;\n"
                    + "}\n")
            private val TEXTURE_UNIFORMS = arrayOf<String?>("y_tex", "u_tex", "v_tex")
            private val FRAGMENT_SHADER = ("precision mediump float;\n"
                    + "varying vec2 interp_tc_y;\n"
                    + "varying vec2 interp_tc_u;\n"
                    + "varying vec2 interp_tc_v;\n"
                    + "uniform sampler2D y_tex;\n"
                    + "uniform sampler2D u_tex;\n"
                    + "uniform sampler2D v_tex;\n"
                    + "uniform mat3 mColorConversion;\n"
                    + "void main() {\n"
                    + "  vec3 yuv;\n"
                    + "  yuv.x = texture2D(y_tex, interp_tc_y).r - 0.0625;\n"
                    + "  yuv.y = texture2D(u_tex, interp_tc_u).r - 0.5;\n"
                    + "  yuv.z = texture2D(v_tex, interp_tc_v).r - 0.5;\n"
                    + "  gl_FragColor = vec4(mColorConversion * yuv, 1.0);\n"
                    + "}\n")

            private val TEXTURE_VERTICES = GlUtil.createBuffer(
                floatArrayOf(
                    -1.0f,
                    1.0f,
                    -1.0f,
                    -1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    -1.0f
                )
            )
        }
    }

    companion object {
        private const val TAG = "VideoDecoderGLSV"
    }
}
