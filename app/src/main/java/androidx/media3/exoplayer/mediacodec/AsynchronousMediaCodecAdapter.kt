package androidx.media3.exoplayer.mediacodec

import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PersistableBundle
import android.view.Surface
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C

import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection
import androidx.media3.decoder.CryptoInfo
import com.google.common.base.Supplier
import java.io.IOException
import java.nio.ByteBuffer

internal class AsynchronousMediaCodecAdapter private constructor(
    private val codec: MediaCodec,
    callbackThread: HandlerThread,
    bufferEnqueuer: MediaCodecBufferEnqueuer,
    private val loudnessCodecController: LoudnessCodecController?
) : MediaCodecAdapter {
    class Factory(
        private val callbackThreadSupplier: Supplier<HandlerThread>,
        private val queueingThreadSupplier: Supplier<HandlerThread>
    ) : MediaCodecAdapter.Factory {
        private var enableSynchronousBufferQueueingWithAsyncCryptoFlag = true
        private var enableAsyncCryptoSynchronization = false

        constructor(trackType: @C.TrackType Int) : this(
            Supplier { HandlerThread(createCallbackThreadLabel(trackType)) },
            Supplier { HandlerThread(createQueueingThreadLabel(trackType)) })

        fun setAsyncCryptoFlagEnabled(enableAsyncCryptoFlag: Boolean) {
            enableSynchronousBufferQueueingWithAsyncCryptoFlag = enableAsyncCryptoFlag
        }

        fun setAsyncCryptoSynchronizationEnabled(enableAsyncCryptoSynchronization: Boolean) {
            this.enableAsyncCryptoSynchronization = enableAsyncCryptoSynchronization
        }

        @Throws(IOException::class)
        override fun createAdapter(configuration: MediaCodecAdapter.Configuration): AsynchronousMediaCodecAdapter {
            val codecName = configuration.codecInfo.name
            var codecAdapter: AsynchronousMediaCodecAdapter? = null
            var codec: MediaCodec? = null
            try {
                beginSection("createCodec:$codecName")
                codec = MediaCodec.createByCodecName(codecName)
                var flags = 0
                val bufferEnqueuer: MediaCodecBufferEnqueuer?
                if (enableSynchronousBufferQueueingWithAsyncCryptoFlag
                    && useSynchronousBufferQueueingWithAsyncCryptoFlag()
                ) {
                    bufferEnqueuer = SynchronousMediaCodecBufferEnqueuer(codec)
                    flags = flags or MediaCodec.CONFIGURE_FLAG_USE_CRYPTO_ASYNC
                } else {
                    bufferEnqueuer =
                        AsynchronousMediaCodecBufferEnqueuer(
                            codec, queueingThreadSupplier.get(), enableAsyncCryptoSynchronization
                        )
                }
                codecAdapter =
                    AsynchronousMediaCodecAdapter(
                        codec,
                        callbackThreadSupplier.get(),
                        bufferEnqueuer,
                        configuration.loudnessCodecController
                    )
                endSection()
                if (configuration.surface == null && configuration.codecInfo.detachedSurfaceSupported
                    && Build.VERSION.SDK_INT >= 35
                ) {
                    flags = flags or MediaCodec.CONFIGURE_FLAG_DETACHED_SURFACE
                }
                codecAdapter.initialize(
                    configuration.mediaFormat, configuration.surface, configuration.crypto, flags
                )
                return codecAdapter
            } catch (e: Exception) {
                if (codecAdapter != null) {
                    codecAdapter.release()
                } else {
                    codec?.release()
                }
                throw e
            }
        }

        companion object {
            @ChecksSdkIntAtLeast(api = 36)
            private fun useSynchronousBufferQueueingWithAsyncCryptoFlag(): Boolean {
                // CONFIGURE_FLAG_USE_CRYPTO_ASYNC causes timeout errors on API < 36, see b/362450802.
                return Build.VERSION.SDK_INT >= 36
            }
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [STATE_CREATED, STATE_INITIALIZED, STATE_SHUT_DOWN])
    private annotation class State

    private val asynchronousMediaCodecCallback = AsynchronousMediaCodecCallback(callbackThread)
    private val bufferEnqueuer: MediaCodecBufferEnqueuer by lazy { bufferEnqueuer }
    private var codecReleased = false
    private var state: @State Int = STATE_CREATED

    private fun initialize(
        mediaFormat: MediaFormat?,
        surface: Surface?,
        crypto: MediaCrypto?,
        flags: Int
    ) {
        asynchronousMediaCodecCallback.initialize(codec)
        beginSection("configureCodec")
        codec.configure(mediaFormat, surface, crypto, flags)
        endSection()
        bufferEnqueuer.start()
        beginSection("startCodec")
        codec.start()
        endSection()
        if (Build.VERSION.SDK_INT >= 35 && loudnessCodecController != null) {
            loudnessCodecController.addMediaCodec(codec)
        }
        state = STATE_INITIALIZED
    }

    override fun needsReconfiguration(): Boolean {
        return false
    }

    override fun queueInputBuffer(
        index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int
    ) {
        bufferEnqueuer.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun queueSecureInputBuffer(
        index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int
    ) {
        bufferEnqueuer.queueSecureInputBuffer(index, offset, info, presentationTimeUs, flags)
    }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        codec.releaseOutputBuffer(index, render)
    }

    override fun releaseOutputBuffer(index: Int, renderTimeStampNs: Long) {
        codec.releaseOutputBuffer(index, renderTimeStampNs)
    }

    override fun dequeueInputBufferIndex(): Int {
        bufferEnqueuer.maybeThrowException()
        return asynchronousMediaCodecCallback.dequeueInputBufferIndex()
    }

    override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int {
        bufferEnqueuer.maybeThrowException()
        return asynchronousMediaCodecCallback.dequeueOutputBufferIndex(bufferInfo)
    }

    override val outputFormat: MediaFormat
        get() = asynchronousMediaCodecCallback.outputFormat

    override fun getInputBuffer(index: Int): ByteBuffer? {
        return codec.getInputBuffer(index)
    }

    override fun useInputBuffer(runnable: Runnable) {
        asynchronousMediaCodecCallback.useInputBuffer {
            bufferEnqueuer.maybeThrowException()
            asynchronousMediaCodecCallback.useInputBuffer(runnable)
        }
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? {
        return codec.getOutputBuffer(index)
    }

    override fun flush() {
        // The order of calls is important:
        // 1. Flush the bufferEnqueuer to stop queueing input buffers.
        // 2. Flush the codec to stop producing available input/output buffers.
        // 3. Flush the callback so that in-flight callbacks are discarded.
        // 4. Start the codec. The asynchronous callback will drop pending callbacks and we can start
        //    the codec now.
        bufferEnqueuer.flush()
        codec.flush()
        asynchronousMediaCodecCallback.flush()
        codec.start()
    }

    override fun release() {
        try {
            if (state == STATE_INITIALIZED) {
                bufferEnqueuer.shutdown()
                asynchronousMediaCodecCallback.shutdown()
            }
            state = STATE_SHUT_DOWN
        } finally {
            if (!codecReleased) {
                try {
                    // Stopping the codec before releasing it works around a bug on APIs 30, 31 and 32 where
                    // MediaCodec.release() returns too early before fully detaching a Surface, and a
                    // subsequent MediaCodec.configure() call using the same Surface then fails. See
                    // https://github.com/google/ExoPlayer/issues/8696 and b/191966399.
                    if (Build.VERSION.SDK_INT in 30..<33) {
                        codec.stop()
                    }
                } finally {
                    if (Build.VERSION.SDK_INT >= 35 && loudnessCodecController != null) {
                        loudnessCodecController.removeMediaCodec(codec)
                    }
                    codec.release()
                    codecReleased = true
                }
            }
        }
    }

    override fun setOnFrameRenderedListener(
        listener: MediaCodecAdapter.OnFrameRenderedListener,
        handler: Handler
    ) {
        codec.setOnFrameRenderedListener(
            { codec: MediaCodec?, presentationTimeUs: Long, nanoTime: Long ->
                listener.onFrameRendered(
                    this@AsynchronousMediaCodecAdapter, presentationTimeUs, nanoTime
                )
            },
            handler
        )
    }

    override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean {
        asynchronousMediaCodecCallback.setOnBufferAvailableListener(listener)
        return true
    }

    override fun setOutputSurface(surface: Surface) {
        codec.setOutputSurface(surface!!)
    }

    @RequiresApi(35)
    override fun detachOutputSurface() {
        codec.detachOutputSurface()
    }

    override fun setParameters(params: Bundle) {
        bufferEnqueuer.setParameters(params)
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

    @VisibleForTesting
    fun onError(error: CodecException?) {
        asynchronousMediaCodecCallback.onError(codec, error!!)
    }

    @VisibleForTesting
    fun onOutputFormatChanged(format: MediaFormat?) {
        asynchronousMediaCodecCallback.onOutputFormatChanged(codec, format!!)
    }

    companion object {
        private const val STATE_CREATED = 0
        private const val STATE_INITIALIZED = 1
        private const val STATE_SHUT_DOWN = 2

        private fun createCallbackThreadLabel(trackType: @C.TrackType Int): String {
            return createThreadLabel(trackType,  "ExoPlayer:MediaCodecAsyncAdapter:")
        }

        private fun createQueueingThreadLabel(trackType: @C.TrackType Int): String {
            return createThreadLabel(trackType,  "ExoPlayer:MediaCodecQueueingThread:")
        }

        private fun createThreadLabel(trackType: @C.TrackType Int, prefix: String): String {
            val labelBuilder = StringBuilder(prefix)
            when (trackType) {
                C.TRACK_TYPE_AUDIO -> {
                    labelBuilder.append("Audio")
                }
                C.TRACK_TYPE_VIDEO -> {
                    labelBuilder.append("Video")
                }
                else -> {
                    labelBuilder.append("Unknown(").append(trackType).append(")")
                }
            }
            return labelBuilder.toString()
        }
    }
}
