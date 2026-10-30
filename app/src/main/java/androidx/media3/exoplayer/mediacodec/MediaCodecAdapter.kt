package androidx.media3.exoplayer.mediacodec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.PersistableBundle
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.CryptoInfo
import java.io.IOException
import java.nio.ByteBuffer

interface MediaCodecAdapter {
    class Configuration private constructor(
        val codecInfo: MediaCodecInfo,
        val mediaFormat: MediaFormat,
        val format: Format,
        val surface: Surface?,
        val crypto: MediaCrypto?,
        val loudnessCodecController: LoudnessCodecController?
    ) {
        companion object {
            fun createForAudioDecoding(
                codecInfo: MediaCodecInfo,
                mediaFormat: MediaFormat,
                format: Format,
                crypto: MediaCrypto?,
                loudnessCodecController: LoudnessCodecController?
            ): Configuration {
                return Configuration(
                    codecInfo,
                    mediaFormat,
                    format,
                    null,
                    crypto,
                    loudnessCodecController
                )
            }

            fun createForVideoDecoding(
                codecInfo: MediaCodecInfo,
                mediaFormat: MediaFormat,
                format: Format,
                surface: Surface?,
                crypto: MediaCrypto?
            ): Configuration {
                return Configuration(
                    codecInfo,
                    mediaFormat,
                    format,
                    surface,
                    crypto,
                    null
                )
            }
        }
    }

    interface Factory {
        @Throws(IOException::class)
        fun createAdapter(configuration: Configuration): MediaCodecAdapter

        companion object {
            fun getDefault(context: Context): Factory {
                return DefaultMediaCodecAdapterFactory(context)
            }
        }
    }

    interface OnFrameRenderedListener {
        fun onFrameRendered(codec: MediaCodecAdapter, presentationTimeUs: Long, nanoTime: Long)
    }

    interface OnBufferAvailableListener {
        fun onInputBufferAvailable() {}
        fun onOutputBufferAvailable() {}
    }

    fun dequeueInputBufferIndex(): Int
    fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int
    val outputFormat: MediaFormat
    fun getInputBuffer(index: Int): ByteBuffer?
    fun useInputBuffer(runnable: Runnable) {
        runnable.run()
    }

    fun getOutputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)

    fun queueSecureInputBuffer(
        index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int
    )

    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun releaseOutputBuffer(index: Int, renderTimeStampNs: Long)
    fun flush()
    fun release()
    fun setOnFrameRenderedListener(listener: OnFrameRenderedListener, handler: Handler)
    fun registerOnBufferAvailableListener(listener: OnBufferAvailableListener): Boolean {
        return false
    }

    fun setOutputSurface(surface: Surface)

    @RequiresApi(35)
    fun detachOutputSurface()
    fun setParameters(params: Bundle)
    fun setVideoScalingMode(@C.VideoScalingMode scalingMode: @C.VideoScalingMode Int)
    fun needsReconfiguration(): Boolean

    @get:RequiresApi(26)
    val metrics: PersistableBundle

    @RequiresApi(31)
    fun subscribeToVendorParameters(names: List<String>)

    @RequiresApi(31)
    fun unsubscribeFromVendorParameters(names: List<String>)
}
