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
) {
    private var asynchronousMode: Int = MODE_DEFAULT
    var asyncCryptoFlagEnabled = true
    var asyncCryptoSynchronizationEnabled = false

    fun forceEnableAsynchronous() {
        asynchronousMode = MODE_ENABLED
    }

    fun forceDisableAsynchronous() {
        asynchronousMode = MODE_DISABLED
    }

    fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        if (asynchronousMode == MODE_ENABLED
            || (asynchronousMode == MODE_DEFAULT && shouldUseAsynchronousAdapterInDefaultMode())
        ) {
            val trackType = getTrackType(configuration.format.sampleMimeType)
            i(
                TAG,
                "Creating an asynchronous MediaCodec adapter for track type "
                        + getTrackTypeString(trackType)
            )
            return AsynchronousMediaCodecAdapter.create(
                configuration, trackType,
                callbackThread,
                queueingThread,
                asyncCryptoSynchronizationEnabled,
                asyncCryptoFlagEnabled
            )
        }
        return SynchronousMediaCodecAdapter.create(configuration)
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
