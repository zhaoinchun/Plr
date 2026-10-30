
package androidx.media3.exoplayer.mediacodec

import android.content.Context
import android.os.Build
import android.os.HandlerThread
import androidx.annotation.IntDef
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.Util.getTrackTypeString
import com.google.common.base.Supplier
import java.io.IOException

class DefaultMediaCodecAdapterFactory : MediaCodecAdapter.Factory {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [MODE_DEFAULT, MODE_ENABLED, MODE_DISABLED])
    private annotation class Mode

    private val context: Context?
    private val callbackThreadSupplier: Supplier<HandlerThread>?
    private val queueingThreadSupplier: Supplier<HandlerThread>?
    private var asynchronousMode: @Mode Int
    private var asyncCryptoFlagEnabled = false
    private var asyncCryptoSynchronizationEnabled = false

    @Deprecated("Use {@link #DefaultMediaCodecAdapterFactory(Context)} instead.")
    constructor() {
        asynchronousMode = MODE_DEFAULT
        context = null
        callbackThreadSupplier = null
        queueingThreadSupplier = null
    }

    constructor(
        context: Context?,
        callbackThreadSupplier: Supplier<HandlerThread>? = null,
        queueingThreadSupplier: Supplier<HandlerThread>? = null
    ) {
        this.context = context
        asynchronousMode = MODE_DEFAULT
        asyncCryptoFlagEnabled = true
        this.callbackThreadSupplier = callbackThreadSupplier
        this.queueingThreadSupplier = queueingThreadSupplier
    }

    fun forceEnableAsynchronous(): DefaultMediaCodecAdapterFactory {
        asynchronousMode = MODE_ENABLED
        return this
    }

    fun forceDisableAsynchronous(): DefaultMediaCodecAdapterFactory {
        asynchronousMode = MODE_DISABLED
        return this
    }

    fun setAsyncCryptoFlagEnabled(enableAsyncCryptoFlag: Boolean): DefaultMediaCodecAdapterFactory {
        asyncCryptoFlagEnabled = enableAsyncCryptoFlag
        return this
    }

    fun setAsyncCryptoSynchronizationEnabled(
        enableAsyncCryptoSynchronization: Boolean
    ): DefaultMediaCodecAdapterFactory {
        asyncCryptoSynchronizationEnabled = enableAsyncCryptoSynchronization
        return this
    }

    @Throws(IOException::class)
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        if (asynchronousMode == MODE_ENABLED
            || (asynchronousMode == MODE_DEFAULT && shouldUseAsynchronousAdapterInDefaultMode())
        ) {
            val trackType = getTrackType(configuration.format.sampleMimeType)
            i(
                TAG,
                "Creating an asynchronous MediaCodec adapter for track type "
                        + getTrackTypeString(trackType)
            )
            val factory =
                if (callbackThreadSupplier != null && queueingThreadSupplier != null)
                    AsynchronousMediaCodecAdapter.Factory(
                        callbackThreadSupplier, queueingThreadSupplier
                    )
                else
                    AsynchronousMediaCodecAdapter.Factory(trackType)
            factory.setAsyncCryptoFlagEnabled(asyncCryptoFlagEnabled)
            factory.setAsyncCryptoSynchronizationEnabled(asyncCryptoSynchronizationEnabled)
            return factory.createAdapter(configuration)
        }
        return SynchronousMediaCodecAdapter.Factory().createAdapter(configuration)
    }

    private fun shouldUseAsynchronousAdapterInDefaultMode(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            // Asynchronous codec interactions started to be reliable for all devices on API 31+.
            return true
        }
        // Allow additional devices that work reliably with the asynchronous adapter and show
        // performance problems when not using it.
        if (context != null && Build.VERSION.SDK_INT >= 28 && context.packageManager
                .hasSystemFeature("com.amazon.hardware.tv_screen")
        ) {
            return true
        }
        return false
    }

    companion object {
        private const val MODE_DEFAULT = 0
        private const val MODE_ENABLED = 1
        private const val MODE_DISABLED = 2
        private const val TAG = "DMCodecAdapterFactory"
    }
}
