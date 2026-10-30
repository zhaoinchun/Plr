package androidx.media3.exoplayer.mediacodec

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PersistableBundle
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection

import androidx.media3.decoder.CryptoInfo
import com.google.common.base.Preconditions
import java.io.IOException
import java.nio.ByteBuffer

class SynchronousMediaCodecAdapter private constructor(
    private val codec: MediaCodec,
    private val loudnessCodecController: LoudnessCodecController?
) : MediaCodecAdapter {
    class Factory : MediaCodecAdapter.Factory {
        @SuppressLint("WrongConstant") // Can't verify codec flag IntDef
        @Throws(IOException::class)
        override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
            var codec: MediaCodec? = null
            try {
                codec = createCodec(configuration)
                beginSection("configureCodec")
                var flags = 0
                if (configuration.surface == null && configuration.codecInfo.detachedSurfaceSupported
                    && Build.VERSION.SDK_INT >= 35
                ) {
                    flags = flags or MediaCodec.CONFIGURE_FLAG_DETACHED_SURFACE
                }
                codec.configure(
                    configuration.mediaFormat, configuration.surface, configuration.crypto, flags
                )
                endSection()
                beginSection("startCodec")
                codec.start()
                endSection()
                return SynchronousMediaCodecAdapter(codec, configuration.loudnessCodecController)
            } catch (e: IOException) {
                codec?.release()
                throw e
            } catch (e: RuntimeException) {
                codec?.release()
                throw e
            }
        }

        @Throws(IOException::class)
        private fun createCodec(configuration: MediaCodecAdapter.Configuration): MediaCodec {
            configuration.codecInfo
            val codecName = configuration.codecInfo.name
            beginSection("createCodec:$codecName")
            val mediaCodec = MediaCodec.createByCodecName(codecName)
            endSection()
            return mediaCodec
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 35 && loudnessCodecController != null) {
            loudnessCodecController.addMediaCodec(codec)
        }
    }

    override fun needsReconfiguration(): Boolean {
        return false
    }

    override fun dequeueInputBufferIndex(): Int {
        return codec.dequeueInputBuffer(0)
    }

    override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int {
        var index: Int
        do {
            index = codec.dequeueOutputBuffer(bufferInfo, 0)
        } while (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)

        return index
    }

    override val outputFormat: MediaFormat
        get() = codec.outputFormat

    override fun getInputBuffer(index: Int): ByteBuffer? {
        return codec.getInputBuffer(index)
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? {
        return codec.getOutputBuffer(index)
    }

    override fun queueInputBuffer(
        index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int
    ) {
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun queueSecureInputBuffer(
        index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int
    ) {
        codec.queueSecureInputBuffer(
            index, offset, info.frameworkCryptoInfo, presentationTimeUs, flags
        )
    }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        codec.releaseOutputBuffer(index, render)
    }

    override fun releaseOutputBuffer(index: Int, renderTimeStampNs: Long) {
        codec.releaseOutputBuffer(index, renderTimeStampNs)
    }

    override fun flush() {
        codec.flush()
    }

    override fun release() {
        try {
            if (Build.VERSION.SDK_INT in 30..<33) {
                // Stopping the codec before releasing it works around a bug on APIs 30, 31 and 32 where
                // MediaCodec.release() returns too early before fully detaching a Surface, and a
                // subsequent MediaCodec.configure() call using the same Surface then fails. See
                // https://github.com/google/ExoPlayer/issues/8696 and b/191966399.
                codec.stop()
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 35 && loudnessCodecController != null) {
                loudnessCodecController.removeMediaCodec(codec)
            }
            codec.release()
        }
    }

    override fun setOnFrameRenderedListener(
        listener: MediaCodecAdapter.OnFrameRenderedListener,
        handler: Handler
    ) {
        codec.setOnFrameRenderedListener(
            { codec: MediaCodec?, presentationTimeUs: Long, nanoTime: Long ->
                listener.onFrameRendered(
                    this@SynchronousMediaCodecAdapter, presentationTimeUs, nanoTime
                )
            },
            handler
        )
    }

    override fun setOutputSurface(surface: Surface) {
        codec.setOutputSurface(surface)
    }

    @RequiresApi(35)
    override fun detachOutputSurface() {
        codec.detachOutputSurface()
    }

    override fun setParameters(params: Bundle) {
        codec.setParameters(params)
    }

    override fun setVideoScalingMode(@C.VideoScalingMode scalingMode: @C.VideoScalingMode Int) {
        codec.setVideoScalingMode(scalingMode)
    }

    override val metrics: PersistableBundle
        @RequiresApi(26)
        get() = codec.getMetrics()

    @RequiresApi(31)
    override fun subscribeToVendorParameters(names: List<String>) {
        codec.subscribeToVendorParameters(names)
    }

    @RequiresApi(31)
    override fun unsubscribeFromVendorParameters(names: List<String>) {
        codec.unsubscribeFromVendorParameters(names)
    }
}
