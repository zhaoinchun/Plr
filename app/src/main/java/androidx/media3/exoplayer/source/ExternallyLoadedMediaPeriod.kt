package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ExternalLoader.LoadRequest
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ExternallyLoadedMediaPeriod(
    private val uri: Uri,
    mimeType: String?,
    private val externalLoader: ExternalLoader
) : MediaPeriod {
    private val tracks: TrackGroupArray
    private val sampleData: ByteArray = uri.toString().toByteArray(StandardCharsets.UTF_8)
    private val loadingFinished = AtomicBoolean()
    private val loadingThrowable: AtomicReference<Throwable> = AtomicReference<Throwable>()
    private val sampleStreams: MutableList<SampleStreamImpl> = mutableListOf()
    private var loadingFuture: ListenableFuture<*>? = null

    init {
        val format = Format.Builder().setSampleMimeType(mimeType).build()
        tracks = TrackGroupArray(arrayOf(TrackGroup(listOf(format))))
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        callback.onPrepared(this)
        loadingFuture = externalLoader.load(LoadRequest(uri))
        Futures.addCallback(
            loadingFuture,
            object : FutureCallback<Any?> {
                override fun onSuccess(result: Any?) {
                    loadingFinished.set(true)
                }

                override fun onFailure(t: Throwable) {
                    loadingThrowable.set(t)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    override fun maybeThrowPrepareError() {
        // Do nothing.
    }

    override val trackGroups: TrackGroupArray = tracks

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        for (i in selections.indices) {
            if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                sampleStreams.remove(streams[i])
                streams[i] = null
            }
            if (streams[i] == null && selections[i] != null) {
                val stream = SampleStreamImpl()
                sampleStreams.add(stream)
                streams[i] = stream
                streamResetFlags[i] = true
            }
        }
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        // Do nothing.
    }

    override fun readDiscontinuity(): Long {
        return C.TIME_UNSET
    }

    override fun seekToUs(positionUs: Long): Long {
        for (i in sampleStreams.indices) {
            sampleStreams[i].reset()
        }
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        return positionUs
    }

    override val bufferedPositionUs: Long
        get() = if (loadingFinished.get()) C.TIME_END_OF_SOURCE else 0

    override val nextLoadPositionUs: Long
        get() = if (loadingFinished.get()) C.TIME_END_OF_SOURCE else 0

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        return !loadingFinished.get()
    }

    override val isLoading: Boolean
        get() = !loadingFinished.get()

    override fun reevaluateBuffer(positionUs: Long) {
        // Do nothing.
    }

    fun releasePeriod() {
        if (loadingFuture != null) {
            loadingFuture!!.cancel(false)
        }
    }

    private inner class SampleStreamImpl : SampleStream {
        private var streamState: Int

        init {
            streamState = STREAM_STATE_SEND_FORMAT
        }

        fun reset() {
            if (streamState == STREAM_STATE_END_OF_STREAM) {
                streamState = STREAM_STATE_SEND_SAMPLE
            }
        }

        override val isReady: Boolean
            get() = loadingFinished.get()

        @Throws(IOException::class)
        override fun maybeThrowError() {
            val loadingThrowable = this@ExternallyLoadedMediaPeriod.loadingThrowable.get()
            if (loadingThrowable != null) {
                throw IOException(loadingThrowable)
            }
        }

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): @ReadDataResult Int {
            if (streamState == STREAM_STATE_END_OF_STREAM) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }

            if ((readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0 || streamState == STREAM_STATE_SEND_FORMAT) {
                formatHolder.format = tracks.get(0).getFormat(0)
                streamState = STREAM_STATE_SEND_SAMPLE
                return C.RESULT_FORMAT_READ
            }

            if (!loadingFinished.get()) {
                return C.RESULT_NOTHING_READ
            }

            val sampleSize = sampleData.size
            buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
            buffer.timeUs = 0
            if ((readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0) {
                buffer.ensureSpaceForWrite(sampleSize)
                buffer.data!!.put(sampleData,  0, sampleSize)
            }
            if ((readFlags and SampleStream.FLAG_PEEK) == 0) {
                streamState = STREAM_STATE_END_OF_STREAM
            }
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long): Int {
            // We should never skip our sample because the sample before any positive time is our only
            // sample in the stream.
            return 0
        }

    }

    companion object {
        private const val STREAM_STATE_SEND_FORMAT = 0
        private const val STREAM_STATE_SEND_SAMPLE = 1
        private const val STREAM_STATE_END_OF_STREAM = 2
    }
}
