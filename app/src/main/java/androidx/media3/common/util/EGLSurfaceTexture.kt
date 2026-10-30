package androidx.media3.common.util

import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import androidx.annotation.IntDef
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.common.util.GlUtil.checkGlError
import androidx.media3.common.util.GlUtil.checkGlException
import androidx.media3.common.util.Util.formatInvariant
import com.google.common.base.Preconditions

class EGLSurfaceTexture  constructor(
    private val handler: Handler,
    private val callback: TextureImageListener? = null
) : OnFrameAvailableListener, Runnable {
    interface TextureImageListener {
        fun onFrameAvailable()
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [SECURE_MODE_NONE, SECURE_MODE_SURFACELESS_CONTEXT, SECURE_MODE_PROTECTED_PBUFFER])
    annotation class SecureMode

    private val textureIdHolder: IntArray

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var texture: SurfaceTexture? = null

    init {
        textureIdHolder = IntArray(1)
    }

    @Throws(GlException::class)
    fun init(secureMode: @SecureMode Int) {
        display = defaultDisplay
        val config: EGLConfig? = chooseEGLConfig(display)
        context = createEGLContext(display, config, secureMode)
        surface = createEGLSurface(display, config, context, secureMode)
        generateTextureIds(textureIdHolder)
        texture = SurfaceTexture(textureIdHolder[0])
        texture!!.setOnFrameAvailableListener(this)
    }

    fun release() {
        handler.removeCallbacks(this)
        try {
            if (texture != null) {
                texture!!.release()
                GLES20.glDeleteTextures(1, textureIdHolder, 0)
            }
        } finally {
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            }
            if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
            }
            if (context != null) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglReleaseThread()
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                // Android is unusual in that it uses a reference-counted EGLDisplay.  So for
                // every eglInitialize() we need an eglTerminate().
                EGL14.eglTerminate(display)
            }
            display = null
            context = null
            surface = null
            texture = null
        }
    }

    val surfaceTexture: SurfaceTexture
        get() = texture!!
    // SurfaceTexture.OnFrameAvailableListener
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        handler.post(this)
    }

    // Runnable
    override fun run() {
        // Run on the provided handler thread when a new image frame is available.
        dispatchOnFrameAvailable()
        if (texture != null) {
            try {
                texture!!.updateTexImage()
            } catch (e: RuntimeException) {
                // Ignore
            }
        }
    }

    private fun dispatchOnFrameAvailable() {
        if (callback != null) {
            callback.onFrameAvailable()
        }
    }

    companion object {
        const val SECURE_MODE_NONE: Int = 0

        const val SECURE_MODE_SURFACELESS_CONTEXT: Int = 1

        const val SECURE_MODE_PROTECTED_PBUFFER: Int = 2

        private const val EGL_SURFACE_WIDTH = 1
        private const val EGL_SURFACE_HEIGHT = 1

        private val EGL_CONFIG_ATTRIBUTES = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_CONFIG_CAVEAT, EGL14.EGL_NONE,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )

        private const val EGL_PROTECTED_CONTENT_EXT = 0x32C0

        @get:Throws(GlException::class)
        private val defaultDisplay: EGLDisplay?
            get() {
                val display =
                    EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                checkGlException(display != null, "eglGetDisplay failed")

                val version = IntArray(2)
                val eglInitialized =
                    EGL14.eglInitialize(
                        display,
                        version,  
                        0,
                        version,  
                        1
                    )
                checkGlException(eglInitialized, "eglInitialize failed")
                return display
            }

        @Throws(GlException::class)
        private fun chooseEGLConfig(display: EGLDisplay?): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val success =
                EGL14.eglChooseConfig(
                    display,
                    EGL_CONFIG_ATTRIBUTES,  
                    0,
                    configs,  
                    0,  
                    1,
                    numConfigs,  
                    0
                )
            checkGlException(
                success && numConfigs[0] > 0 && configs[0] != null,
                formatInvariant( 
                    "eglChooseConfig failed: success=%b, numConfigs[0]=%d, configs[0]=%s",
                    success, numConfigs[0], configs[0]
                )
            )

            return configs[0]
        }

        @Throws(GlException::class)
        private fun createEGLContext(
            display: EGLDisplay?, config: EGLConfig?, secureMode: @SecureMode Int
        ): EGLContext? {
            val glAttributes: IntArray?
            if (secureMode == SECURE_MODE_NONE) {
                glAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            } else {
                glAttributes =
                    intArrayOf(
                        EGL14.EGL_CONTEXT_CLIENT_VERSION,
                        2,
                        EGL_PROTECTED_CONTENT_EXT,
                        EGL14.EGL_TRUE,
                        EGL14.EGL_NONE
                    )
            }
            val context =
                EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT, glAttributes, 0
                )
            checkGlException(context != null, "eglCreateContext failed")
            return context
        }

        @Throws(GlException::class)
        private fun createEGLSurface(
            display: EGLDisplay?,
            config: EGLConfig?,
            context: EGLContext?,
            secureMode: @SecureMode Int
        ): EGLSurface? {
            val surface: EGLSurface?
            if (secureMode == SECURE_MODE_SURFACELESS_CONTEXT) {
                surface = EGL14.EGL_NO_SURFACE
            } else {
                val pbufferAttributes: IntArray?
                if (secureMode == SECURE_MODE_PROTECTED_PBUFFER) {
                    pbufferAttributes =
                        intArrayOf(
                            EGL14.EGL_WIDTH,
                            EGL_SURFACE_WIDTH,
                            EGL14.EGL_HEIGHT,
                            EGL_SURFACE_HEIGHT,
                            EGL_PROTECTED_CONTENT_EXT,
                            EGL14.EGL_TRUE,
                            EGL14.EGL_NONE
                        )
                } else {
                    pbufferAttributes =
                        intArrayOf(
                            EGL14.EGL_WIDTH,
                            EGL_SURFACE_WIDTH,
                            EGL14.EGL_HEIGHT,
                            EGL_SURFACE_HEIGHT,
                            EGL14.EGL_NONE
                        )
                }
                surface = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    pbufferAttributes,  
                    0
                )
                checkGlException(surface != null, "eglCreatePbufferSurface failed")
            }

            val eglMadeCurrent =
                EGL14.eglMakeCurrent(display,  surface,  surface, context)
            checkGlException(eglMadeCurrent, "eglMakeCurrent failed")
            return surface
        }

        @Throws(GlException::class)
        private fun generateTextureIds(textureIdHolder: IntArray?) {
            GLES20.glGenTextures( 1, textureIdHolder,  0)
            checkGlError()
        }
    }
}
