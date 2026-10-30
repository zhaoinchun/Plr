package androidx.media3.common.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLU
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import androidx.annotation.IntRange
import androidx.annotation.RequiresApi
import androidx.annotation.RestrictTo
import androidx.media3.common.C
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10

object GlUtil {
    
    const val HOMOGENEOUS_COORDINATE_VECTOR_SIZE: Int = 4

    // TODO: b/356072337 - Consider reading this from GL_MAX_TEXTURE_SIZE. This requires an active
    //  OpenGL context.
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    const val MAX_BITMAP_DECODING_SIZE: Int = 4096

    const val LENGTH_NDC: Float = 2f

    val EGL_CONFIG_ATTRIBUTES_RGBA_8888: IntArray = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE,  8,
        EGL14.EGL_GREEN_SIZE,  8,
        EGL14.EGL_BLUE_SIZE,  8,
        EGL14.EGL_ALPHA_SIZE,  8,
        EGL14.EGL_DEPTH_SIZE,  0,
        EGL14.EGL_STENCIL_SIZE,  0,
        EGL14.EGL_NONE
    )
    val EGL_CONFIG_ATTRIBUTES_RGBA_1010102: IntArray = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE,  10,
        EGL14.EGL_GREEN_SIZE,  10,
        EGL14.EGL_BLUE_SIZE,  10,
        EGL14.EGL_ALPHA_SIZE,  2,
        EGL14.EGL_DEPTH_SIZE,  0,
        EGL14.EGL_STENCIL_SIZE,  0,
        EGL14.EGL_NONE
    )

     // TODO: b/449956776 - Remove once FrameConsumer API is finalized.

    val GL_FENCE_SYNC_UNSET: Long = -1

    // https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glFenceSync.xhtml
    private const val GL_FENCE_SYNC_FAILED: Long = 0

    // https://www.khronos.org/registry/EGL/extensions/EXT/EGL_EXT_protected_content.txt
    private const val EXTENSION_PROTECTED_CONTENT = "EGL_EXT_protected_content"

    // https://www.khronos.org/registry/EGL/extensions/KHR/EGL_KHR_surfaceless_context.txt
    private const val EXTENSION_SURFACELESS_CONTEXT = "EGL_KHR_surfaceless_context"

    // https://www.khronos.org/registry/OpenGL/extensions/EXT/EXT_YUV_target.txt
    private const val EXTENSION_YUV_TARGET = "GL_EXT_YUV_target"

    // https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_gl_colorspace_bt2020_linear.txt
    private const val EXTENSION_COLORSPACE_BT2020_PQ = "EGL_EXT_gl_colorspace_bt2020_pq"
    private const val EXTENSION_COLORSPACE_BT2020_HLG = "EGL_EXT_gl_colorspace_bt2020_hlg"

    // https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_gl_colorspace.txt
    private const val EGL_GL_COLORSPACE_KHR = 0x309D

    // https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_gl_colorspace_bt2020_linear.txt
    private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
    private val EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_PQ = intArrayOf(
        EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE, EGL14.EGL_NONE
    )
    private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    private val EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_HLG = intArrayOf(
        EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE, EGL14.EGL_NONE
    )
    private val EGL_WINDOW_SURFACE_ATTRIBUTES_NONE = intArrayOf(EGL14.EGL_NONE)

    val normalizedCoordinateBounds: FloatArray
        
        get() = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )

    val textureCoordinateBounds: FloatArray
        
        get() = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )

    fun create4x4IdentityMatrix(): FloatArray {
        val matrix = FloatArray(16)
        setToIdentity(matrix)
        return matrix
    }

    fun setToIdentity(matrix: FloatArray?) {
        Matrix.setIdentityM(matrix,  0)
    }

    fun createVertexBuffer(vertexList: MutableList<FloatArray?>): FloatArray {
        val vertexBuffer = FloatArray(HOMOGENEOUS_COORDINATE_VECTOR_SIZE * vertexList.size)
        for (i in vertexList.indices) {
            System.arraycopy( 
                vertexList[i],  
                0,  
                vertexBuffer,  
                HOMOGENEOUS_COORDINATE_VECTOR_SIZE * i,  
                HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        }
        return vertexBuffer
    }

    @Throws(GlException::class)
    fun isProtectedContentExtensionSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 24) {
            return false
        }
        if (Build.VERSION.SDK_INT < 26 && ("samsung" == Build.MANUFACTURER || "XT1650" == Build.MODEL)) {
            // Samsung devices running Nougat are known to be broken. See
            // https://github.com/google/ExoPlayer/issues/3373 and [Internal: b/37197802].
            // Moto Z XT1650 is also affected. See
            // https://github.com/google/ExoPlayer/issues/3215.
            return false
        }
        if (Build.VERSION.SDK_INT < 26
            && !context
                .getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE)
        ) {
            // Pre API level 26 devices were not well tested unless they supported VR mode.
            return false
        }

        return isExtensionSupported(EXTENSION_PROTECTED_CONTENT)
    }

    @get:Throws(GlException::class)
    val isSurfacelessContextExtensionSupported: Boolean
        
        get() = isExtensionSupported(EXTENSION_SURFACELESS_CONTEXT)

    val isYuvTargetExtensionSupported: Boolean
        
        get() {
            val glExtensions: String?
            if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
                // Create a placeholder context and make it current to allow calling GLES20.glGetString().
                try {
                    val eglDisplay: EGLDisplay =
                        defaultEglDisplay
                    val eglContext =
                        createEglContext(eglDisplay)
                    createFocusedPlaceholderEglSurface(
                        eglContext,
                        eglDisplay
                    )
                    glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
                    destroyEglContext(eglDisplay, eglContext)
                } catch (e: GlException) {
                    return false
                }
            } else {
                glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            }

            return glExtensions != null && glExtensions.contains(EXTENSION_YUV_TARGET)
        }

    @Throws(GlException::class)
    fun isColorTransferSupported(colorTransfer: @C.ColorTransfer Int): Boolean {
        if (colorTransfer == C.COLOR_TRANSFER_ST2084) {
            return isBt2020PqExtensionSupported
        } else if (colorTransfer == C.COLOR_TRANSFER_HLG) {
            return isBt2020HlgExtensionSupported
        }
        return true
    }

    @get:Throws(GlException::class)
    val isBt2020PqExtensionSupported: Boolean
        
        get() =// On API<33, the system cannot display PQ content correctly regardless of whether BT2020 PQ
            // GL extension is supported. Context: http://b/252537203#comment5.
            Build.VERSION.SDK_INT >= 33 && isExtensionSupported(
                EXTENSION_COLORSPACE_BT2020_PQ
            )

    @get:Throws(GlException::class)
    val isBt2020HlgExtensionSupported: Boolean
        
        get() = isExtensionSupported(EXTENSION_COLORSPACE_BT2020_HLG)

    @get:Throws(GlException::class)
    val defaultEglDisplay: EGLDisplay
        
        get() {
            val eglDisplay =
                EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            checkGlException(
                eglDisplay != EGL14.EGL_NO_DISPLAY,
                "No EGL display."
            )
            checkGlException(
                EGL14.eglInitialize(
                    eglDisplay,  
                    IntArray(1),  
                    0,  
                    IntArray(1),  
                    0
                ),
                "Error in eglInitialize."
            )
            checkEglException("Error in getDefaultEglDisplay")
            return eglDisplay
        }

    @Throws(GlException::class)
    fun createEglContext(eglDisplay: EGLDisplay?): EGLContext {
        return createEglContext(
            EGL14.EGL_NO_CONTEXT,
            eglDisplay,  
            2,
            EGL_CONFIG_ATTRIBUTES_RGBA_8888
        )
    }

    @Throws(GlException::class)
    fun createEglContext(
        sharedContext: EGLContext?,
        eglDisplay: EGLDisplay?,
        @IntRange(from = 2, to = 3) openGlVersion: Int,
        configAttributes: IntArray?
    ): EGLContext {
        Preconditions.checkArgument(
            configAttributes.contentEquals(EGL_CONFIG_ATTRIBUTES_RGBA_8888) || configAttributes.contentEquals(
                EGL_CONFIG_ATTRIBUTES_RGBA_1010102
            )
        )
        Preconditions.checkArgument(openGlVersion == 2 || openGlVersion == 3)
        val contextAttributes =
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, openGlVersion, EGL14.EGL_NONE)
        val eglContext =
            EGL14.eglCreateContext(
                eglDisplay,
                getEglConfig(eglDisplay, configAttributes),
                sharedContext,
                contextAttributes,  
                0
            )
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(eglDisplay)
            throw GlException(
                ("eglCreateContext() failed to create a valid context. The device may not support EGL"
                        + " version "
                        + openGlVersion)
            )
        }
        checkEglException("Error in createEglContext")
        return eglContext
    }

    @Throws(GlException::class)
    fun createEglSurface(
        eglDisplay: EGLDisplay?,
        surface: Any?,
        colorTransfer: @C.ColorTransfer Int,
        isEncoderInputSurface: Boolean
    ): EGLSurface? {
        val configAttributes: IntArray?
        val windowAttributes: IntArray?
        if (colorTransfer == C.COLOR_TRANSFER_SDR || colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2) {
            configAttributes = EGL_CONFIG_ATTRIBUTES_RGBA_8888
            windowAttributes = EGL_WINDOW_SURFACE_ATTRIBUTES_NONE
        } else if (colorTransfer == C.COLOR_TRANSFER_HLG || colorTransfer == C.COLOR_TRANSFER_ST2084) {
            configAttributes = EGL_CONFIG_ATTRIBUTES_RGBA_1010102
            // !isEncoderInputSurface means outputting to a display surface. HDR display surfaces
            // require EGL_GL_COLORSPACE_BT2020_PQ_EXT or EGL_GL_COLORSPACE_BT2020_HLG_EXT.
            if (isEncoderInputSurface) {
                // Outputting BT2020 PQ or HLG with EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_PQ to an encoder
                // causes the encoder to incorrectly switch to full range color, even if the encoder is
                // configured with limited range color, because EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_PQ sets
                // full range color output, and GL windowAttributes overrides encoder settings.
                windowAttributes = EGL_WINDOW_SURFACE_ATTRIBUTES_NONE
            } else if (colorTransfer == C.COLOR_TRANSFER_ST2084) {
                if (!isBt2020PqExtensionSupported) {
                    throw GlException("BT.2020 PQ OpenGL output isn't supported.")
                }
                // TODO(b/262259999): HDR10 PQ content looks dark on the screen, on API 33.
                windowAttributes = EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_PQ
            } else {
                if (!isBt2020HlgExtensionSupported) {
                    throw GlException("BT.2020 HLG OpenGL output isn't supported.")
                }
                windowAttributes = EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_HLG
            }
        } else {
            throw IllegalArgumentException("Unsupported color transfer: " + colorTransfer)
        }
        val eglSurface =
            EGL14.eglCreateWindowSurface(
                eglDisplay,
                getEglConfig(eglDisplay, configAttributes),
                surface,
                windowAttributes,  
                0
            )
        checkEglException("Error creating a new EGL surface")
        return eglSurface
    }

    @Throws(GlException::class)
    private fun createPbufferSurface(
        eglDisplay: EGLDisplay?, width: Int, height: Int, configAttributes: IntArray?
    ): EGLSurface? {
        val pbufferAttributes =
            intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
        val eglSurface =
            EGL14.eglCreatePbufferSurface(
                eglDisplay,
                getEglConfig(eglDisplay, configAttributes),
                pbufferAttributes,  
                0
            )
        checkEglException("Error creating a new EGL Pbuffer surface")
        return eglSurface
    }

    @Throws(GlException::class)
    fun createFocusedPlaceholderEglSurface(
        eglContext: EGLContext?, eglDisplay: EGLDisplay?
    ): EGLSurface {
        // EGL_CONFIG_ATTRIBUTES_RGBA_1010102 could be used for HDR input, but EGL14.EGL_NO_SURFACE
        // support was added before EGL 2, so HDR-capable devices should have support for EGL_NO_SURFACE
        // and therefore configAttributes shouldn't matter for HDR.
        val configAttributes = EGL_CONFIG_ATTRIBUTES_RGBA_8888
        val eglSurface =
            (if (androidx.media3.common.util.GlUtil.isSurfacelessContextExtensionSupported)
                EGL14.EGL_NO_SURFACE
            else
                androidx.media3.common.util.GlUtil.createPbufferSurface(
                    eglDisplay,  
                    1,  
                    1,
                    configAttributes
                ))!!

        focusEglSurface(eglDisplay, eglContext, eglSurface,  1,  1)
        return eglSurface
    }

    @get:Throws(GlException::class)
    val contextMajorVersion: Long
        
        get() {
            val currentEglContextVersion = IntArray(1)
            EGL14.eglQueryContext(
                EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY),
                EGL14.eglGetCurrentContext(),
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                currentEglContextVersion,  
                0
            )
            checkGlError()
            return currentEglContextVersion[0].toLong()
        }

    @Throws(GlException::class)
    fun createGlSyncFence(): Long {
        if (contextMajorVersion >= 3) {
            val syncObject =
                GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE,  0)
            checkGlError()
            // Due to specifics of OpenGL, it might happen that the fence creation command is not yet
            // sent into the GPU command queue, which can cause other threads to wait infinitely if
            // the glSyncWait/glClientSyncWait command went into the GPU earlier. Hence, we have to
            // call glFlush to ensure that glFenceSync is inside of the GPU command queue.
            GLES20.glFlush()
            checkGlError()
            return syncObject
        } else {
            return 0
        }
    }

    @Throws(GlException::class)
    fun deleteSyncObject(syncObject: Long) {
        deleteSyncObjectQuietly(syncObject)
        checkGlError()
    }

    fun deleteSyncObjectQuietly(syncObject: Long) {
        if (syncObject == GL_FENCE_SYNC_UNSET) {
            return
        }
        GLES30.glDeleteSync(syncObject)
    }

    @Throws(GlException::class)
    fun awaitSyncObject(syncObject: Long) {
        if (syncObject == GL_FENCE_SYNC_UNSET) {
            return
        }
        if (syncObject == GL_FENCE_SYNC_FAILED) {
            // Fallback to using glFinish for synchronization when fence creation failed.
            GLES20.glFinish()
        } else {
            GLES30.glWaitSync(syncObject,  0, GLES30.GL_TIMEOUT_IGNORED)
            checkGlError()
        }
    }

    val currentContext: EGLContext?
        
        get() = EGL14.eglGetCurrentContext()

    @Throws(GlException::class)
    fun checkGlError() {
        val errorMessageBuilder = StringBuilder()
        var foundError = false
        var error: Int
        val errorCodes = mutableListOf<Int>()
        while ((GLES20.glGetError().also { error = it }) != GLES20.GL_NO_ERROR) {
            if (foundError) {
                errorMessageBuilder.append('\n')
            }
            var errorString = GLU.gluErrorString(error)
            if (errorString == null) {
                errorString = "error code: 0x" + Integer.toHexString(error)
            }
            errorMessageBuilder.append("glError: ").append(errorString)
            foundError = true
            errorCodes.add(error)
        }
        if (foundError) {
            throw GlException(errorMessageBuilder.toString(), errorCodes)
        }
    }

    @Throws(GlException::class)
    fun checkEglException(errorMessage: String?) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw GlException(
                errorMessage + ", error code: 0x" + Integer.toHexString(error),  
                listOf(error)
            )
        }
    }

    @Throws(GlException::class)
    private fun assertValidTextureSize(width: Int, height: Int) {
        // TODO(b/201293185): Consider handling adjustments for sizes > GL_MAX_TEXTURE_SIZE
        //  (ex. downscaling appropriately) in a shader program instead of asserting incorrect
        //  values.
        // For valid GL sizes, see:
        // https://www.khronos.org/registry/OpenGL-Refpages/es2.0/xhtml/glTexImage2D.xml
        val maxTextureSizeBuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSizeBuffer, 0)
        val maxTextureSize = maxTextureSizeBuffer[0]
        Preconditions.checkState(
            maxTextureSize > 0,
            "Create a OpenGL context first or run the GL methods on an OpenGL thread."
        )

        if (width < 0 || height < 0) {
            throw GlException("width or height is less than 0")
        }
        if (width > maxTextureSize || height > maxTextureSize) {
            throw GlException(
                "width or height is greater than GL_MAX_TEXTURE_SIZE " + maxTextureSize
            )
        }
    }

    @Throws(GlException::class)
    fun clearFocusedBuffers() {
        clearFocusedBuffersInternal( 0f,  0f,  0f,  0f)
    }

    @Throws(GlException::class)
    fun clearFocusedBuffersOpaque() {
        clearFocusedBuffersInternal( 0f,  0f,  0f,  1f)
    }

    @Throws(GlException::class)
    fun focusEglSurface(
        eglDisplay: EGLDisplay?,
        eglContext: EGLContext?,
        eglSurface: EGLSurface,
        width: Int,
        height: Int
    ) {
        focusRenderTarget(eglDisplay, eglContext, eglSurface,  0, width, height)
    }

    @Throws(GlException::class)
    fun focusFramebuffer(
        eglDisplay: EGLDisplay?,
        eglContext: EGLContext?,
        eglSurface: EGLSurface,
        framebuffer: Int,
        width: Int,
        height: Int
    ) {
        focusRenderTarget(eglDisplay, eglContext, eglSurface, framebuffer, width, height)
    }

    @Throws(GlException::class)
    fun focusFramebufferUsingCurrentContext(framebuffer: Int, width: Int, height: Int) {
        val boundFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFramebuffer,  0)
        if (boundFramebuffer[0] != framebuffer) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        }
        checkGlError()
        GLES20.glViewport( 0,  0, width, height)
        checkGlError()
    }

    fun createBuffer(data: FloatArray): FloatBuffer {
        return createBuffer(data.size).put(data).flip() as FloatBuffer
    }

    private fun createBuffer(capacity: Int): FloatBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(capacity * C.BYTES_PER_FLOAT)
        return byteBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    @Throws(GlException::class)
    fun createExternalTexture(): Int {
        val texId = generateTexture()
        bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId, GLES20.GL_LINEAR)
        return texId
    }

    @Throws(GlException::class)
    fun createTexture(bitmap: Bitmap): Int {
        val texId = generateTexture()
        setTexture(texId, bitmap)
        return texId
    }

    @Throws(GlException::class)
    fun createTexture(width: Int, height: Int, useHighPrecisionColorComponents: Boolean): Int {
        // TODO(b/227624622): Implement a pixel test that confirms 16f has less posterization.
        // TODO - b/309459038: Consider renaming the method, as the created textures are uninitialized.
        if (useHighPrecisionColorComponents) {
            return createTextureUninitialized(
                width,
                height,
                GLES30.GL_RGBA16F,
                GLES30.GL_HALF_FLOAT
            )
        }
        return createTextureUninitialized(width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE)
    }

    @Throws(GlException::class)
    fun createRgb10A2Texture(width: Int, height: Int): Int {
        return createTextureUninitialized(
            width,
            height,  
            GLES30.GL_RGB10_A2,  
            GLES30.GL_UNSIGNED_INT_2_10_10_10_REV
        )
    }

    @Throws(GlException::class)
    private fun createTextureUninitialized(
        width: Int,
        height: Int,
        internalFormat: Int,
        type: Int
    ): Int {
        assertValidTextureSize(width, height)
        val texId = generateTexture()
        bindTexture(GLES20.GL_TEXTURE_2D, texId, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,  
            0,
            internalFormat,
            width,
            height,  
            0,
            GLES20.GL_RGBA,
            type,  
            null
        )
        checkGlError()
        return texId
    }

    @Throws(GlException::class)
    fun generateTexture(): Int {
        val texId = IntArray(1)
        GLES20.glGenTextures( 1, texId,  0)
        checkGlError()
        return texId[0]
    }

    @Throws(GlException::class)
    fun setTexture(texId: Int, bitmap: Bitmap) {
        assertValidTextureSize(bitmap.getWidth(), bitmap.getHeight())
        bindTexture(GLES20.GL_TEXTURE_2D, texId, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,  0, bitmap,  0)
        checkGlError()
    }

    @Throws(GlException::class)
    fun bindTexture(textureTarget: Int, texId: Int, sampleFilter: Int) {
        GLES20.glBindTexture(textureTarget, texId)
        checkGlError()
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, sampleFilter)
        checkGlError()
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, sampleFilter)
        checkGlError()
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError()
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError()
    }

    @Throws(GlException::class)
    fun createFboForTexture(texId: Int): Int {
        val fboId = IntArray(1)
        GLES20.glGenFramebuffers( 1, fboId,  0)
        checkGlError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
        checkGlError()
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0
        )
        checkGlError()
        return fboId[0]
    }

    @Throws(GlException::class)
    fun deleteTexture(textureId: Int) {
        GLES20.glDeleteTextures( 1, intArrayOf(textureId),  0)
        checkGlError()
    }

    @Throws(GlException::class)
    fun destroyEglContext(
        eglDisplay: EGLDisplay?, eglContext: EGLContext?
    ) {
        if (eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return
        }
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        checkEglException("Error releasing context")
        if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            checkEglException("Error destroying context")
        }
    }

    @Throws(GlException::class)
    fun terminate(eglDisplay: EGLDisplay?) {
        EGL14.eglReleaseThread()
        checkEglException("Error releasing thread")
        EGL14.eglTerminate(eglDisplay)
        checkEglException("Error terminating display")
    }

    @Throws(GlException::class)
    fun destroyEglSurface(
        eglDisplay: EGLDisplay?, eglSurface: EGLSurface?
    ) {
        if (eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return
        }
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }

        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        checkEglException("Error destroying surface")
    }

    @Throws(GlException::class)
    fun deleteFbo(fboId: Int) {
        GLES20.glDeleteFramebuffers( 1, intArrayOf(fboId),  0)
        checkGlError()
    }

    @Throws(GlException::class)
    fun deleteRbo(rboId: Int) {
        GLES20.glDeleteRenderbuffers( 
            1,  intArrayOf(rboId),  0
        )
        checkGlError()
    }

    @Throws(GlException::class)
    fun blitFrameBuffer(readFboId: Int, readRect: GlRect, drawFboId: Int, drawRect: GlRect) {
        val boundFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFramebuffer,  0)
        checkGlError()
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFboId)
        checkGlError()
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFboId)
        checkGlError()
        GLES30.glBlitFramebuffer(
            readRect.left,
            readRect.bottom,
            readRect.right,
            readRect.top,
            drawRect.left,
            drawRect.bottom,
            drawRect.right,
            drawRect.top,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_LINEAR
        )
        checkGlError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,  boundFramebuffer[0])
        checkGlError()
    }

    @Throws(GlException::class)
    fun createPixelBufferObject(size: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers( 1, ids,  0)
        checkGlError()

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, ids[0])
        checkGlError()

        GLES30.glBufferData(
            GLES30.GL_PIXEL_PACK_BUFFER,  size,  null, GLES30.GL_DYNAMIC_READ
        )
        checkGlError()

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,  0)
        checkGlError()
        return ids[0]
    }

    @RequiresApi(24)
    @Throws(GlException::class)
    fun schedulePixelBufferRead(readFboId: Int, width: Int, height: Int, bufferId: Int) {
        focusFramebufferUsingCurrentContext(readFboId, width, height)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, bufferId)
        checkGlError()

        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
        GLES30.glReadPixels( 
            0,  
            0,
            width,
            height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,  
            0
        )
        checkGlError()

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,  0)
        checkGlError()
    }

    @RequiresApi(24)
    @Throws(GlException::class)
    fun mapPixelBufferObject(bufferId: Int, size: Int): ByteBuffer? {
        GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, bufferId)
        checkGlError()
        val mappedPixelBuffer =
            GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER,  
                0,  
                size,
                GLES30.GL_MAP_READ_BIT
            ) as ByteBuffer?
        checkGlError()
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,  0)
        checkGlError()
        return mappedPixelBuffer
    }

    @RequiresApi(24)
    @Throws(GlException::class)
    fun unmapPixelBufferObject(bufferId: Int) {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, bufferId)
        checkGlError()
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        checkGlError()
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,  0)
        checkGlError()
    }

    @Throws(GlException::class)
    fun deleteBuffer(bufferId: Int) {
        GLES20.glDeleteBuffers( 1, intArrayOf(bufferId),  0)
        checkGlError()
    }

    @Throws(GlException::class)
    fun checkGlException(expression: Boolean, errorMessage: String?) {
        if (!expression) {
            throw GlException(errorMessage)
        }
    }

    @Throws(GlException::class)
    private fun getEglConfig(eglDisplay: EGLDisplay?, attributes: IntArray?): EGLConfig? {
        val eglConfigs = arrayOfNulls<EGLConfig>(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay,
                attributes,  
                0,
                eglConfigs,  
                0,  
                1,  
                IntArray(1),  
                0
            )
        ) {
            throw GlException("eglChooseConfig failed.")
        }
        return eglConfigs[0]
    }

    @Throws(GlException::class)
    private fun isExtensionSupported(extensionName: String): Boolean {
        val display: EGLDisplay =
            defaultEglDisplay
        val eglExtensions = EGL14.eglQueryString(display, EGL10.EGL_EXTENSIONS)
        return eglExtensions != null && eglExtensions.contains(extensionName)
    }

    @Throws(GlException::class)
    private fun focusRenderTarget(
        eglDisplay: EGLDisplay?,
        eglContext: EGLContext?,
        eglSurface: EGLSurface,
        framebuffer: Int,
        width: Int,
        height: Int
    ) {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        checkEglException("Error making context current")
        focusFramebufferUsingCurrentContext(framebuffer, width, height)
        // When transitioning from a surfaceless context (EGL_NO_SURFACE) to a context with a valid
        // surface, the GL_DRAW_BUFFER and GL_READ_BUFFER states may still be GL_NONE (per EGL spec for
        // surfaceless contexts). Explicitly set them to GL_BACK for the default framebuffer to ensure
        // rendering goes to the bound surface. See https://github.com/androidx/media/issues/2982.
        if ((eglSurface != EGL14.EGL_NO_SURFACE) && framebuffer == 0 && contextMajorVersion >= 3) {
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_BACK), 0)
            checkGlError()
            GLES30.glReadBuffer(GLES30.GL_BACK)
            checkGlError()
        }
    }

    @Throws(GlException::class)
    private fun clearFocusedBuffersInternal(r: Float, g: Float, b: Float, a: Float) {
        GLES20.glClearColor(r, g, b, a)
        GLES20.glClearDepthf(1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        checkGlError()
    }

    class GlException  constructor(
        message: String?,
        errorCodes: List<Int> = listOf()
    ) : Exception(message) {
        val errorCodes: List<Int> = errorCodes.toList()
    }
}
