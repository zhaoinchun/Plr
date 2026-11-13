package androidx.media3.exoplayer.mediacodec

import android.content.Context
import android.os.Build
import android.os.HandlerThread
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.Util.getTrackTypeString

class DefaultMediaCodecAdapterFactory(
    private val context: Context?,
    private val callbackThread: HandlerThread? = null,
    private val queueingThread: HandlerThread? = null
) : MediaCodecAdapter.Factory {
    private var asynchronousMode: Int = MODE_DEFAULT
    private var asyncCryptoFlagEnabled = true
    private var asyncCryptoSynchronizationEnabled = false

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
                if (callbackThread != null && queueingThread != null)
                    AsynchronousMediaCodecAdapter.Factory(
                        callbackThread, queueingThread
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
