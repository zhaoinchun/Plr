package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.usToMs
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil.closeQuietly
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.source.SingleSampleMediaPeriod.SourceLoadable
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction
import androidx.media3.exoplayer.upstream.Loader.Loadable
import androidx.media3.exoplayer.util.ReleasableExecutor

import java.io.IOException

internal class SingleSampleMediaPeriod(
    private val dataSpec: DataSpec,
    private val dataSourceFactory: DataSource.Factory,
    private val transferListener: TransferListener?,
    val format: Format,
    val durationUs: Long,
    private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    private val eventDispatcher: MediaSourceEventListener.EventDispatcher,
    private val treatLoadErrorsAsEndOfStream: Boolean,
    downloadExecutor: ReleasableExecutor?
) : MediaPeriod, Loader.Callback<SourceLoadable> {
    override val trackGroups: TrackGroupArray = TrackGroupArray(arrayOf(TrackGroup(listOf(format))))
    private val sampleStreams: MutableList<SampleStreamImpl> = mutableListOf()
    val loader: Loader = downloadExecutor?.let { Loader(it) }
        ?: Loader("SingleSampleMediaPeriod")
    var loadingFinished: Boolean = false
    var sampleData:ByteArray? = null
    var sampleSize: Int = 0

    fun release() {
        loader.release()
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        callback.onPrepared(this)
    }

    override fun maybeThrowPrepareError() {
        // Do nothing.
    }

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
                val stream =SampleStreamImpl()
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

    override fun reevaluateBuffer(positionUs: Long) {
        // Do nothing.
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        if (loadingFinished || loader.isLoading || loader.hasFatalError()) {
            return false
        }
        val dataSource = dataSourceFactory.createDataSource()
        if (transferListener != null) {
            dataSource.addTransferListener(transferListener)
        }
        val loadable = SourceLoadable(dataSpec, dataSource)
        loader.startLoading<SourceLoadable>(
            loadable,  
            this,
            loadErrorHandlingPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA)
        )
        return true
    }

    override val isLoading: Boolean
        get() = loader.isLoading

    override fun readDiscontinuity(): Long {
        return C.TIME_UNSET
    }

    override val nextLoadPositionUs: Long
        get() = if (loadingFinished || loader.isLoading) C.TIME_END_OF_SOURCE else 0

    override val bufferedPositionUs: Long
        get() = if (loadingFinished) C.TIME_END_OF_SOURCE else 0

    override fun seekToUs(positionUs: Long): Long {
        for (i in sampleStreams.indices) {
            sampleStreams[i].reset()
        }
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(
        positionUs: Long,
        seekParameters: SeekParameters
    ): Long {
        return positionUs
    }

    // Loader.Callback implementation.
    override fun onLoadStarted(
        loadable: SourceLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long, retryCount: Int
    ) {
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
        if (retryCount != 0) {
            loadEventInfo
                .setUri(dataSource.lastOpenedUri)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(dataSource.bytesRead)
        }
        eventDispatcher.loadStarted(
            loadEventInfo.build(),
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,
            format,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            0,
            durationUs,
            retryCount
        )
    }

    override fun onLoadCompleted(
        loadable: SourceLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long
    ) {
        sampleSize = loadable.dataSource.bytesRead.toInt()
        sampleData = loadable.sampleData!!
        loadingFinished = true
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(dataSource.lastOpenedUri)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(sampleSize.toLong())
                .build()
        loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        eventDispatcher.loadCompleted(
            loadEventInfo,
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,
            format,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            0,
            durationUs
        )
    }

    override fun onLoadCanceled(
        loadable: SourceLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long, released: Boolean
    ) {
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(dataSource.lastOpenedUri)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(dataSource.bytesRead)
                .build()
        loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        eventDispatcher.loadCanceled(
            loadEventInfo,
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,  
            null,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            0,
            durationUs
        )
    }

    override fun onLoadError(
        loadable: SourceLoadable,
        elapsedRealtimeMs: Long,
        loadDurationMs: Long,
        error: IOException,
        errorCount: Int
    ): LoadErrorAction {
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(dataSource.lastOpenedUri)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(dataSource.bytesRead)
                .build()
        val mediaLoadData =
            MediaLoadData(
                C.DATA_TYPE_MEDIA,
                C.TRACK_TYPE_UNKNOWN,
                format,
                C.SELECTION_REASON_UNKNOWN,  
                null,  
                0,
                usToMs(durationUs)
            )
        val retryDelay =
            loadErrorHandlingPolicy.getRetryDelayMsFor(
                LoadErrorInfo(loadEventInfo, mediaLoadData, error, errorCount)
            )
        val errorCanBePropagated =
            retryDelay == C.TIME_UNSET
                    || (errorCount
                    >= loadErrorHandlingPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))

        val action: LoadErrorAction
        if (treatLoadErrorsAsEndOfStream && errorCanBePropagated) {
            w(TAG, "Loading failed, treating as end-of-stream.", error)
            loadingFinished = true
            action = Loader.DONT_RETRY
        } else {
            action =
                if (retryDelay != C.TIME_UNSET)
                    Loader.createRetryAction( false, retryDelay)
                else
                    Loader.DONT_RETRY_FATAL
        }
        val wasCanceled = !action.isRetry
        eventDispatcher.loadError(
            loadEventInfo,
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,
            format,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            0,
            durationUs,
            error,
            wasCanceled
        )
        if (wasCanceled) {
            loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        }
        return action
    }

    private inner class SampleStreamImpl : SampleStream {
        private var streamState = 0
        private var notifiedDownstreamFormat = false

        fun reset() {
            if (streamState == STREAM_STATE_END_OF_STREAM) {
                streamState = STREAM_STATE_SEND_SAMPLE
            }
        }

        override val isReady: Boolean
            get() = loadingFinished

        @Throws(IOException::class)
        override fun maybeThrowError() {
            if (!treatLoadErrorsAsEndOfStream) {
                loader.maybeThrowError()
            }
        }

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): Int {
            maybeNotifyDownstreamFormat()
            if (loadingFinished && sampleData == null) {
                streamState = STREAM_STATE_END_OF_STREAM
            }

            if (streamState == STREAM_STATE_END_OF_STREAM) {
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }

            if ((readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0 || streamState == STREAM_STATE_SEND_FORMAT) {
                formatHolder.format = format
                streamState = STREAM_STATE_SEND_SAMPLE
                return C.RESULT_FORMAT_READ
            }

            if (!loadingFinished) {
                return C.RESULT_NOTHING_READ
            }
            val sampleData = sampleData!!

            buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
            buffer.timeUs = 0
            if ((readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0) {
                buffer.ensureSpaceForWrite(sampleSize)
                buffer.data!!.put(sampleData, 0, sampleSize)
            }
            if ((readFlags and SampleStream.FLAG_PEEK) == 0) {
                streamState = STREAM_STATE_END_OF_STREAM
            }
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long): Int {
            maybeNotifyDownstreamFormat()
            if (positionUs > 0 && streamState != STREAM_STATE_END_OF_STREAM) {
                streamState = STREAM_STATE_END_OF_STREAM
                return 1
            }
            return 0
        }

        fun maybeNotifyDownstreamFormat() {
            if (!notifiedDownstreamFormat) {
                eventDispatcher.downstreamFormatChanged(
                    getTrackType(format.sampleMimeType),
                    format,
                    C.SELECTION_REASON_UNKNOWN,  
                    null,  
                    0
                )
                notifiedDownstreamFormat = true
            }
        }

    }

    internal class SourceLoadable(val dataSpec: DataSpec, dataSource: DataSource) : Loadable {
        val loadTaskId: Long = LoadEventInfo.newId
        val dataSource: StatsDataSource = StatsDataSource(dataSource)
        var sampleData: ByteArray?=null

        override fun cancelLoad() {
            // Never happens.
        }

        @Throws(IOException::class)
        override fun load() {
            // We always load from the beginning, so reset bytesRead to 0.
            dataSource.resetBytesRead()
            try {
                // Create and open the input.
                dataSource.open(dataSpec)
                // Load the sample data.
                var result = 0
                while (result != C.RESULT_END_OF_INPUT) {
                    val sampleSize = dataSource.bytesRead.toInt()
                    if (sampleData == null) {
                        sampleData = ByteArray(INITIAL_SAMPLE_SIZE)
                    } else if (sampleSize == sampleData!!.size) {
                        sampleData = sampleData!!.copyOf(sampleData!!.size * 2)
                    }
                    result =
                        dataSource.read(sampleData!!, sampleSize, sampleData!!.size - sampleSize)
                }
            } finally {
                closeQuietly(dataSource)
            }
        }
    }

    companion object {
        private const val TAG = "SingleSampleMediaPeriod"
        private const val INITIAL_SAMPLE_SIZE = 1024

        private const val STREAM_STATE_SEND_FORMAT = 0
        private const val STREAM_STATE_SEND_SAMPLE = 1
        private const val STREAM_STATE_END_OF_STREAM = 2
    }
}
