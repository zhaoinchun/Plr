
package androidx.media3.exoplayer.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.view.Surface
import androidx.media3.common.util.EGLSurfaceTexture
import androidx.media3.common.util.EGLSurfaceTexture.SecureMode
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.common.util.Log.e

import com.google.common.base.Preconditions
import com.google.errorprone.annotations.InlineMe

class PlaceholderSurface private constructor(
    private val thread: PlaceholderSurfaceThread, surfaceTexture: SurfaceTexture?,
    val secure: Boolean
) : Surface(surfaceTexture) {
    private var threadReleased = false

    override fun release() {
        super.release()
        // The Surface may be released multiple times (explicitly and by Surface.finalize()). The
        // implementation of super.release() has its own deduplication logic. Below we need to
        // deduplicate ourselves. Synchronization is required as we don't control the thread on which
        // Surface.finalize() is called.
        synchronized(thread) {
            if (!threadReleased) {
                thread.release()
                threadReleased = true
            }
        }
    }

    private class PlaceholderSurfaceThread : HandlerThread("ExoPlayer:PlaceholderSurface"),
        Handler.Callback {
        private var eglSurfaceTexture:EGLSurfaceTexture? = null
        private var handler:Handler? = null
        private var initError: Error? = null
        private var initException: RuntimeException? = null
        private var surface: PlaceholderSurface? = null

        fun init(secureMode: @SecureMode Int): PlaceholderSurface {
            start()
            handler = Handler(getLooper(),  this)
            eglSurfaceTexture = EGLSurfaceTexture(handler!!)
            var wasInterrupted = false
            synchronized(this) {
                handler!!.obtainMessage(MSG_INIT, secureMode, 0).sendToTarget()
                while (surface == null && initException == null && initError == null) {
                    try {
                        (this as Object).wait()
                    } catch (e: InterruptedException) {
                        wasInterrupted = true
                    }
                }
            }
            if (wasInterrupted) {
                // Restore the interrupted status.
                currentThread().interrupt()
            }
            if (initException != null) {
                throw initException!!
            } else if (initError != null) {
                throw initError!!
            } else {
                return surface!!
            }
        }

        fun release() {
            handler!!.sendEmptyMessage(MSG_RELEASE)
        }

        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                MSG_INIT -> {
                    try {
                        initInternal( msg.arg1)
                    } catch (e: RuntimeException) {
                        e(TAG, "Failed to initialize placeholder surface", e)
                        initException = e
                    } catch (e: GlException) {
                        e(TAG, "Failed to initialize placeholder surface", e)
                        initException = IllegalStateException(e)
                    } catch (e: Error) {
                        e(TAG, "Failed to initialize placeholder surface", e)
                        initError = e
                    } finally {
                        synchronized(this) {
                            (this as Object).notify()
                        }
                    }
                    return true
                }

                MSG_RELEASE -> {
                    try {
                        releaseInternal()
                    } catch (e: Throwable) {
                        e(TAG, "Failed to release placeholder surface", e)
                    } finally {
                        quit()
                    }
                    return true
                }

                else -> return true
            }
        }

        @Throws(GlException::class)
        fun initInternal(secureMode: @SecureMode Int) {
            eglSurfaceTexture!!.init(secureMode)
            this.surface =
                PlaceholderSurface(
                    this,
                    eglSurfaceTexture!!.surfaceTexture,
                    secureMode != EGLSurfaceTexture.SECURE_MODE_NONE
                )
        }

        fun releaseInternal() {
            eglSurfaceTexture!!.release()
        }

        companion object {
            private const val MSG_INIT = 1
            private const val MSG_RELEASE = 2
        }
    }

    companion object {
        private const val TAG = "PlaceholderSurface"

        private var secureMode: @SecureMode Int = 0
        private var secureModeInitialized = false

        @Synchronized
        fun isSecureSupported(context: Context): Boolean {
            if (!secureModeInitialized) {
                secureMode = getSecureMode(context)
                secureModeInitialized = true
            }
            return secureMode != EGLSurfaceTexture.SECURE_MODE_NONE
        }

        @InlineMe(
            replacement = "PlaceholderSurface.newInstance(context, secure)",
            imports = ["androidx.media3.exoplayer.video.PlaceholderSurface"]
        )
        @Deprecated("Use {@link #newInstance(Context, boolean)} instead.")
        fun newInstanceV17(context: Context, secure: Boolean): PlaceholderSurface {
            return newInstance(context, secure)
        }

        fun newInstance(context: Context, secure: Boolean): PlaceholderSurface {
            Preconditions.checkState(!secure || isSecureSupported(context))
            val thread = PlaceholderSurfaceThread()
            return thread.init(if (secure) secureMode else EGLSurfaceTexture.SECURE_MODE_NONE)
        }

        private fun getSecureMode(context: Context): @SecureMode Int {
            try {
                if (GlUtil.isProtectedContentExtensionSupported(context)) {
                    if (GlUtil.isSurfacelessContextExtensionSupported) {
                        return EGLSurfaceTexture.SECURE_MODE_SURFACELESS_CONTEXT
                    } else {
                        // If we can't use surfaceless contexts, we use a protected 1 * 1 pixel buffer surface.
                        // This may require support for EXT_protected_surface, but in practice it works on some
                        // devices that don't have that extension. See also
                        // https://github.com/google/ExoPlayer/issues/3558.
                        return EGLSurfaceTexture.SECURE_MODE_PROTECTED_PBUFFER
                    }
                } else {
                    return EGLSurfaceTexture.SECURE_MODE_NONE
                }
            } catch (e: GlException) {
                e(TAG, "Failed to determine secure mode due to GL error: " + e.message)
                return EGLSurfaceTexture.SECURE_MODE_NONE
            }
        }
    }
}
