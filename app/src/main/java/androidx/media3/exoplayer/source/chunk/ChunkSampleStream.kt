package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Log.w

import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.usToMs
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.source.SequenceableLoader
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Preconditions

import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class ChunkSampleStream<T : ChunkSource>(
    val primaryTrackType: @C.TrackType Int,
    embeddedTrackTypes: IntArray?,
    embeddedTrackFormats: Array<Format>?,
    val chunkSource: T,
    private val callback: SequenceableLoader.Callback<ChunkSampleStream<T>>,
    allocator: Allocator,
    positionUs: Long,
    drmSessionManager: DrmSessionManager,
    drmEventDispatcher: DrmSessionEventListener.EventDispatcher,
    private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    private val mediaSourceEventDispatcher: MediaSourceEventListener.EventDispatcher,
    handleInitialDiscontinuity: Boolean,
    firstChunkStartTimeUs: Long,
    downloadExecutor: ReleasableExecutor?
) : SampleStream, SequenceableLoader, Loader.Callback<Chunk>, Loader.ReleaseCallback {
    interface ReleaseCallback<T : ChunkSource> {
        fun onSampleStreamReleased(chunkSampleStream: ChunkSampleStream<T>)
    }

    private val embeddedTrackTypes: IntArray = embeddedTrackTypes ?: IntArray(0)
    private val embeddedTrackFormats: Array<Format> = embeddedTrackFormats ?: arrayOf()
    private val embeddedTracksSelected: BooleanArray
    private val loader =
        if (downloadExecutor != null) Loader(downloadExecutor) else Loader("ChunkSampleStream")
    private val nextChunkHolder = ChunkHolder()
    private val mediaChunks: ArrayList<BaseMediaChunk> = ArrayList<BaseMediaChunk>()
    private val readOnlyMediaChunks = mediaChunks.toMutableList()
    private val primarySampleQueue: SampleQueue
    private val embeddedSampleQueues: Array<SampleQueue>
    private val chunkOutput: BaseMediaChunkOutput
    private var loadingChunk: Chunk? = null
    private var primaryDownstreamTrackFormat:Format? = null
    private var releaseCallback: ReleaseCallback<T>? = null
    private var pendingResetPositionUs: Long
    private var lastSeekPositionUs: Long
    private var nextNotifyPrimaryFormatMediaChunkIndex = 0
    private var canceledMediaChunk: BaseMediaChunk? = null
    private var needToEvaluateInitialDiscontinuity: Boolean
    private var hasInitialDiscontinuity = false
    private var usesStreamPrerollFlags = false
    private var suppressRead = false
    private var endPositionUs = C.TIME_END_OF_SOURCE

    var loadingFinished: Boolean = false

    init {
        val embeddedTrackCount = this.embeddedTrackTypes.size
        val embeddedSampleQueuesList = mutableListOf<SampleQueue>()
        embeddedTracksSelected = BooleanArray(embeddedTrackCount)
        val trackTypes = IntArray(1 + embeddedTrackCount)
        val sampleQueues = arrayOfNulls<SampleQueue>(1 + embeddedTrackCount)

        primarySampleQueue =
            SampleQueue.createWithDrm(allocator, drmSessionManager, drmEventDispatcher)
        trackTypes[0] = primaryTrackType
        sampleQueues[0] = primarySampleQueue

        for (i in 0..<embeddedTrackCount) {
            val sampleQueue: SampleQueue = SampleQueue.createWithoutDrm(allocator)
            embeddedSampleQueuesList.add(sampleQueue)
            sampleQueues[i + 1] = sampleQueue
            trackTypes[i + 1] = this.embeddedTrackTypes[i]
        }
        embeddedSampleQueues = embeddedSampleQueuesList.toTypedArray()

        chunkOutput = BaseMediaChunkOutput(trackTypes, sampleQueues as Array<SampleQueue>)
        pendingResetPositionUs = positionUs
        lastSeekPositionUs = positionUs

        needToEvaluateInitialDiscontinuity = handleInitialDiscontinuity
        if (handleInitialDiscontinuity && firstChunkStartTimeUs != C.TIME_UNSET) {
            // Evaluate need for initial discontinuity immediately if all information is available.
            needToEvaluateInitialDiscontinuity = false
            hasInitialDiscontinuity = firstChunkStartTimeUs < pendingResetPositionUs
        }
    }

    fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        if (this.isPendingReset) {
            return
        }
        val oldFirstSampleIndex = primarySampleQueue.firstIndex
        primarySampleQueue.discardTo(positionUs, toKeyframe, true)
        val newFirstSampleIndex = primarySampleQueue.firstIndex
        if (newFirstSampleIndex > oldFirstSampleIndex) {
            val discardToUs = primarySampleQueue.firstTimestampUs
            for (i in embeddedSampleQueues.indices) {
                embeddedSampleQueues[i].discardTo(
                    discardToUs,
                    toKeyframe,
                    embeddedTracksSelected[i]
                )
            }
        }
        discardDownstreamMediaChunks(newFirstSampleIndex)
    }

    fun selectEmbeddedTrack(positionUs: Long, trackType: Int): EmbeddedSampleStream {
        for (i in embeddedSampleQueues.indices) {
            if (embeddedTrackTypes[i] == trackType) {
                Preconditions.checkState(!embeddedTracksSelected[i])
                embeddedTracksSelected[i] = true
                embeddedSampleQueues[i].seekTo(positionUs, true)
                return EmbeddedSampleStream(this, embeddedSampleQueues[i], i)
            }
        }
        // Should never happen.
        throw IllegalStateException()
    }

    override val bufferedPositionUs: Long
        get() {
            if (loadingFinished) {
                return C.TIME_END_OF_SOURCE
            } else if (this.isPendingReset) {
                return pendingResetPositionUs
            } else {
                var bufferedPositionUs = lastSeekPositionUs
                val lastMediaChunk = this.lastMediaChunk
                val lastCompletedMediaChunk =
                    if (lastMediaChunk.isLoadCompleted)
                        lastMediaChunk
                    else
                        if (mediaChunks.size > 1) mediaChunks[mediaChunks.size - 2] else null
                if (lastCompletedMediaChunk != null) {
                    bufferedPositionUs =
                        max(bufferedPositionUs, lastCompletedMediaChunk.endTimeUs)
                }
                return max(
                    bufferedPositionUs,
                    primarySampleQueue.largestQueuedTimestampUs
                )
            }
        }

    fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        return chunkSource.getAdjustedSeekPositionUs(positionUs, seekParameters)
    }

    fun seekToUs(positionUs: Long) {
        lastSeekPositionUs = positionUs
        needToEvaluateInitialDiscontinuity = false
        hasInitialDiscontinuity = false
        if (this.isPendingReset) {
            // A reset is already pending. We only need to update its position.
            pendingResetPositionUs = positionUs
            return
        }

        // Detect whether the seek is to the start of a chunk that's at least partially buffered.
        var seekToMediaChunk: BaseMediaChunk? = null
        for (i in mediaChunks.indices) {
            val mediaChunk = mediaChunks[i]
            val mediaChunkStartTimeUs = mediaChunk.startTimeUs
            if (mediaChunkStartTimeUs == positionUs && mediaChunk.clippedStartTimeUs == C.TIME_UNSET) {
                seekToMediaChunk = mediaChunk
                break
            } else if (mediaChunkStartTimeUs > positionUs) {
                // We're not going to find a chunk with a matching start time.
                break
            }
        }

        // See if we can seek inside the primary sample queue.
        var seekInsideBuffer: Boolean
        if (seekToMediaChunk != null) {
            // When seeking to the start of a chunk we use the index of the first sample in the chunk
            // rather than the seek position. This ensures we seek to the keyframe at the start of the
            // chunk even if its timestamp is slightly earlier than the advertised chunk start time.
            seekInsideBuffer = primarySampleQueue.seekTo(seekToMediaChunk.getFirstSampleIndex(0))
        } else {
            val nextLoadPositionUs = nextLoadPositionUs
            val allowTimeBeyondBuffer =
                nextLoadPositionUs == C.TIME_END_OF_SOURCE || positionUs < nextLoadPositionUs
            seekInsideBuffer = primarySampleQueue.seekTo(positionUs, allowTimeBeyondBuffer)
        }

        if (seekInsideBuffer && canceledMediaChunk != null) {
            // Do not allow seeking into a chunk that is being canceled.
            if (canceledMediaChunk!!.getFirstSampleIndex(0) <= primarySampleQueue.readIndex) {
                seekInsideBuffer = false
            }
        }

        if (seekInsideBuffer) {
            // We can seek inside the buffer.
            nextNotifyPrimaryFormatMediaChunkIndex =
                primarySampleIndexToMediaChunkIndex(
                    primarySampleQueue.readIndex, 0
                )
            // Seek the embedded sample queues.
            for (embeddedSampleQueue in embeddedSampleQueues) {
                embeddedSampleQueue.seekTo(positionUs, true)
            }
        } else {
            // We can't seek inside the buffer, and so need to reset.
            pendingResetPositionUs = positionUs
            loadingFinished = false
            mediaChunks.clear()
            nextNotifyPrimaryFormatMediaChunkIndex = 0
            if (loader.isLoading) {
                // Discard as much as we can synchronously.
                primarySampleQueue.discardToEnd()
                for (embeddedSampleQueue in embeddedSampleQueues) {
                    embeddedSampleQueue.discardToEnd()
                }
                loader.cancelLoading()
            } else {
                loader.clearFatalError()
                resetSampleQueues()
            }
        }
    }

    fun setEndPositionUs(endPositionUs: Long) {
        val continueLoadingNeeded =
            loadingFinished
                    && this.endPositionUs != C.TIME_END_OF_SOURCE && (endPositionUs == C.TIME_END_OF_SOURCE || endPositionUs > this.endPositionUs)
        this.endPositionUs = endPositionUs
        primarySampleQueue.setReadEndTimeUs(endPositionUs)
        for (embeddedSampleQueue in embeddedSampleQueues) {
            embeddedSampleQueue.setReadEndTimeUs(endPositionUs)
        }
        if (continueLoadingNeeded) {
            loadingFinished = false
            callback.onContinueLoadingRequested(this)
        }
    }

    fun release(callback: ReleaseCallback<T>? = null) {
        this.releaseCallback = callback
        // Discard as much as we can synchronously.
        primarySampleQueue.preRelease()
        for (embeddedSampleQueue in embeddedSampleQueues) {
            embeddedSampleQueue.preRelease()
        }
        loader.release(this)
    }

    override fun onLoaderReleased() {
        primarySampleQueue.release()
        for (embeddedSampleQueue in embeddedSampleQueues) {
            embeddedSampleQueue.release()
        }
        chunkSource.release()
        if (releaseCallback != null) {
            releaseCallback!!.onSampleStreamReleased(this)
        }
    }

    override val isReady: Boolean
        // SampleStream implementation.
        get() = !this.isPendingReset && primarySampleQueue.isReady(loadingFinished)

    @Throws(IOException::class)
    override fun maybeThrowError() {
        loader.maybeThrowError()
        primarySampleQueue.maybeThrowError()
        if (!loader.isLoading) {
            chunkSource.maybeThrowError()
        }
    }

    override fun readData(
        formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
    ): Int {
        val shouldBlockForInitialDiscontinuity =
            !usesStreamPrerollFlags && mayHaveInitialDiscontinuity()
        if (this.isPendingReset || shouldBlockForInitialDiscontinuity || suppressRead) {
            return C.RESULT_NOTHING_READ
        }
        if (canceledMediaChunk != null
            && (canceledMediaChunk!!.getFirstSampleIndex(0)
                    <= primarySampleQueue.readIndex)
        ) {
            // Don't read into chunk that's going to be discarded.
            return C.RESULT_NOTHING_READ
        }
        maybeNotifyPrimaryTrackFormatChanged()

        return primarySampleQueue.read(formatHolder, buffer, readFlags, loadingFinished)
    }

    override fun skipData(positionUs: Long): Int {
        val shouldBlockForInitialDiscontinuity =
            !usesStreamPrerollFlags && mayHaveInitialDiscontinuity()
        if (this.isPendingReset || shouldBlockForInitialDiscontinuity || suppressRead) {
            return 0
        }
        var skipCount = primarySampleQueue.getSkipCount(positionUs, loadingFinished)
        if (canceledMediaChunk != null) {
            // Don't skip into chunk that's going to be discarded.
            val maxSkipCount =
                canceledMediaChunk!!.getFirstSampleIndex(0) - primarySampleQueue.readIndex
            skipCount = min(skipCount, maxSkipCount)
        }
        primarySampleQueue.skip(skipCount)
        maybeNotifyPrimaryTrackFormatChanged()
        return skipCount
    }

    // Loader.Callback implementation.
    override fun onLoadStarted(
        loadable: Chunk, elapsedRealtimeMs: Long, loadDurationMs: Long, retryCount: Int
    ) {
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
        if (retryCount != 0) {
            loadEventInfo
                .setUri(loadable.uri)
                .setResponseHeaders(loadable.responseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(loadable.bytesLoaded())
        }
        mediaSourceEventDispatcher.loadStarted(
            loadEventInfo.build(),
            loadable.type,
            primaryTrackType,
            loadable.trackFormat,
            loadable.trackSelectionReason,
            loadable.trackSelectionData,
            loadable.startTimeUs,
            loadable.endTimeUs,
            retryCount
        )
    }

    override fun onLoadCompleted(loadable: Chunk, elapsedRealtimeMs: Long, loadDurationMs: Long) {
        loadingChunk = null
        chunkSource.onChunkLoadCompleted(loadable)
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(loadable.uri)
                .setResponseHeaders(loadable.responseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(loadable.bytesLoaded())
                .build()
        loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        mediaSourceEventDispatcher.loadCompleted(
            loadEventInfo,
            loadable.type,
            primaryTrackType,
            loadable.trackFormat,
            loadable.trackSelectionReason,
            loadable.trackSelectionData,
            loadable.startTimeUs,
            loadable.endTimeUs
        )
        callback.onContinueLoadingRequested(this)
    }

    override fun onLoadCanceled(
        loadable: Chunk, elapsedRealtimeMs: Long, loadDurationMs: Long, released: Boolean
    ) {
        loadingChunk = null
        canceledMediaChunk = null
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(loadable.uri)
                .setResponseHeaders(loadable.responseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(loadable.bytesLoaded())
                .build()
        loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        mediaSourceEventDispatcher.loadCanceled(
            loadEventInfo,
            loadable.type,
            primaryTrackType,
            loadable.trackFormat,
            loadable.trackSelectionReason,
            loadable.trackSelectionData,
            loadable.startTimeUs,
            loadable.endTimeUs
        )
        if (!released) {
            if (this.isPendingReset) {
                resetSampleQueues()
            } else if (isMediaChunk(loadable)) {
                discardUpstreamMediaChunksFromIndex(mediaChunks.size - 1)
                if (mediaChunks.isEmpty()) {
                    pendingResetPositionUs = lastSeekPositionUs
                }
            }
            callback.onContinueLoadingRequested(this)
        }
    }

    override fun onLoadError(
        loadable: Chunk,
        elapsedRealtimeMs: Long,
        loadDurationMs: Long,
        error: IOException,
        errorCount: Int
    ): LoadErrorAction {
        val bytesLoaded = loadable.bytesLoaded()
        val isMediaChunk = isMediaChunk(loadable)
        val lastChunkIndex = mediaChunks.size - 1
        val cancelable =
            bytesLoaded == 0L || !isMediaChunk || !haveReadFromMediaChunk(lastChunkIndex)
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(loadable.uri)
                .setResponseHeaders(loadable.responseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(bytesLoaded)
                .build()
        val mediaLoadData =
            MediaLoadData(
                loadable.type,
                primaryTrackType,
                loadable.trackFormat,
                loadable.trackSelectionReason,
                loadable.trackSelectionData,
                usToMs(loadable.startTimeUs),
                usToMs(loadable.endTimeUs)
            )
        val loadErrorInfo =
            LoadErrorInfo(loadEventInfo, mediaLoadData, error, errorCount)

        var loadErrorAction: LoadErrorAction? = null
        if (chunkSource.onChunkLoadError(
                loadable, cancelable, loadErrorInfo, loadErrorHandlingPolicy
            )
        ) {
            if (cancelable) {
                loadErrorAction = Loader.DONT_RETRY
                if (isMediaChunk) {
                    val removed = discardUpstreamMediaChunksFromIndex(lastChunkIndex)
                    Preconditions.checkState(removed === loadable)
                    if (mediaChunks.isEmpty()) {
                        pendingResetPositionUs = lastSeekPositionUs
                    }
                }
            } else {
                w(TAG, "Ignoring attempt to cancel non-cancelable load.")
            }
        }

        if (loadErrorAction == null) {
            // The load was not cancelled. Either the load must be retried or the error propagated.
            val retryDelayMs = loadErrorHandlingPolicy.getRetryDelayMsFor(loadErrorInfo)
            loadErrorAction =
                if (retryDelayMs != C.TIME_UNSET)
                    Loader.createRetryAction(false, retryDelayMs)
                else
                    Loader.DONT_RETRY_FATAL
        }

        val canceled = !loadErrorAction.isRetry
        mediaSourceEventDispatcher.loadError(
            loadEventInfo,
            loadable.type,
            primaryTrackType,
            loadable.trackFormat,
            loadable.trackSelectionReason,
            loadable.trackSelectionData,
            loadable.startTimeUs,
            loadable.endTimeUs,
            error,
            canceled
        )
        if (canceled) {
            loadingChunk = null
            loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
            callback.onContinueLoadingRequested(this)
        }
        return loadErrorAction
    }

    // SequenceableLoader implementation
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        if (loadingFinished || loader.isLoading || loader.hasFatalError()) {
            return false
        }
        val pendingReset = this.isPendingReset
        val chunkQueue: MutableList<BaseMediaChunk>?
        val loadPositionUs: Long
        if (pendingReset) {
            chunkQueue = mutableListOf()
            loadPositionUs = pendingResetPositionUs
        } else {
            chunkQueue = readOnlyMediaChunks
            loadPositionUs = this.lastMediaChunk.endTimeUs
        }
        chunkSource.getNextChunk(loadingInfo, loadPositionUs, chunkQueue, nextChunkHolder)
        val endOfStream = nextChunkHolder.endOfStream
        val loadable = nextChunkHolder.chunk
        nextChunkHolder.clear()

        val nextChunkBeyondEndPositionUs =
            loadable != null && endPositionUs != C.TIME_END_OF_SOURCE && loadable.startTimeUs >= endPositionUs
        if (endOfStream || nextChunkBeyondEndPositionUs) {
            pendingResetPositionUs = C.TIME_UNSET
            loadingFinished = true
            return true
        }

        if (loadable == null) {
            return false
        }

        loadingChunk = loadable
        if (isMediaChunk(loadable)) {
            val mediaChunk = loadable as BaseMediaChunk
            if (pendingReset) {
                // Only set the queue start times if we're not seeking to a chunk boundary. If we are
                // seeking to a chunk boundary then we want the queue to pass through all of the samples in
                // the chunk. Doing this ensures we'll always output the keyframe at the start of the chunk,
                // even if its timestamp is slightly earlier than the advertised chunk start time.
                if (mediaChunk.startTimeUs < pendingResetPositionUs) {
                    primarySampleQueue.setStartTimeUs(pendingResetPositionUs)
                    for (embeddedSampleQueue in embeddedSampleQueues) {
                        embeddedSampleQueue.setStartTimeUs(pendingResetPositionUs)
                    }
                    hasInitialDiscontinuity = needToEvaluateInitialDiscontinuity
                }
                // Once we started loading the first media chunk, no more initial discontinuities can be
                // reported.
                needToEvaluateInitialDiscontinuity = false
                pendingResetPositionUs = C.TIME_UNSET
            }
            mediaChunk.init(chunkOutput)
            mediaChunks.add(mediaChunk)
        } else if (loadable is InitializationChunk) {
            loadable.init(chunkOutput)
        }
        loader.startLoading(
            loadable, this, loadErrorHandlingPolicy.getMinimumLoadableRetryCount(loadable.type)
        )
        return true
    }

    override val isLoading: Boolean
        get() = loader.isLoading

    override val nextLoadPositionUs: Long
        get() {
            return if (this.isPendingReset) {
                pendingResetPositionUs
            } else {
                if (loadingFinished) C.TIME_END_OF_SOURCE else this.lastMediaChunk.endTimeUs
            }
        }

    override fun reevaluateBuffer(positionUs: Long) {
        if (loader.hasFatalError() || this.isPendingReset) {
            return
        }

        if (loader.isLoading) {
            val loadingChunk = this.loadingChunk!!
            if (isMediaChunk(loadingChunk)
                && haveReadFromMediaChunk(mediaChunks.size - 1)
            ) {
                // Can't cancel anymore because the renderers have read from this chunk.
                return
            }
            if ((endPositionUs != C.TIME_END_OF_SOURCE && loadingChunk.startTimeUs >= endPositionUs)
                || chunkSource.shouldCancelLoad(positionUs, loadingChunk, readOnlyMediaChunks)
            ) {
                loader.cancelLoading()
                if (isMediaChunk(loadingChunk)) {
                    canceledMediaChunk = loadingChunk as BaseMediaChunk
                }
            }
            return
        }

        var preferredQueueSize =
            min(
                mediaChunks.size,
                chunkSource.getPreferredQueueSize(positionUs, readOnlyMediaChunks)
            )
        var discardedDataBeyondEndPositionUs = false
        if (endPositionUs != C.TIME_END_OF_SOURCE) {
            while (preferredQueueSize > 0
                && mediaChunks[preferredQueueSize - 1].startTimeUs >= endPositionUs
            ) {
                preferredQueueSize--
                discardedDataBeyondEndPositionUs = true
            }
        }
        if (preferredQueueSize < mediaChunks.size) {
            discardUpstream(preferredQueueSize)
        }
        if (discardedDataBeyondEndPositionUs) {
            pendingResetPositionUs = C.TIME_UNSET
            loadingFinished = true
        }
    }

    fun mayHaveInitialDiscontinuity(): Boolean {
        return (needToEvaluateInitialDiscontinuity || hasInitialDiscontinuity)
                && !loadingFinished && !loader.hasFatalError()
    }

    fun consumeInitialDiscontinuity(): Boolean {
        try {
            return hasInitialDiscontinuity
        } finally {
            hasInitialDiscontinuity = false
        }
    }

    fun setUsesStreamPrerollFlags() {
        this.usesStreamPrerollFlags = true
    }

    override val flags: Int
        get() {
            if (needToEvaluateInitialDiscontinuity) {
                return SampleStream.FLAG_MAYBE_HAS_PREROLL
            }
            return if (hasInitialDiscontinuity) SampleStream.FLAG_HAS_PREROLL else 0
        }

    fun setSuppressRead(suppressRead: Boolean) {
        this.suppressRead = suppressRead
    }

    fun discardUpstreamSamplesForClippedDuration(clippedDurationUs: Long) {
        Preconditions.checkState(!loader.isLoading)
        if (this.isPendingReset || clippedDurationUs == C.TIME_UNSET || mediaChunks.isEmpty()) {
            return
        }
        val lastMediaChunk = this.lastMediaChunk
        val lastMediaChunkEndTimeUs =
            if (lastMediaChunk.clippedEndTimeUs != C.TIME_UNSET)
                lastMediaChunk.clippedEndTimeUs
            else
                lastMediaChunk.endTimeUs
        if (lastMediaChunkEndTimeUs <= clippedDurationUs) {
            // Last chunk doesn't need to be clipped further.
            return
        }
        val largestQueuedTimestampUs = primarySampleQueue.largestQueuedTimestampUs
        if (largestQueuedTimestampUs <= clippedDurationUs) {
            // No data beyond new duration that needs to be clipped.
            return
        }
        var minDiscardPositionUs =
            max(clippedDurationUs, primarySampleQueue.largestReadTimestampUs + 1)
        primarySampleQueue.discardUpstreamFrom(minDiscardPositionUs)
        for (embeddedSampleQueue in embeddedSampleQueues) {
            minDiscardPositionUs =
                max(clippedDurationUs, embeddedSampleQueue.largestReadTimestampUs + 1)
            embeddedSampleQueue.discardUpstreamFrom(minDiscardPositionUs)
        }
        mediaSourceEventDispatcher.upstreamDiscarded(
            primaryTrackType, clippedDurationUs, largestQueuedTimestampUs
        )
    }

    private fun discardUpstream(preferredQueueSize: Int) {
        Preconditions.checkState(!loader.isLoading)

        val currentQueueSize = mediaChunks.size
        var newQueueSize = C.LENGTH_UNSET
        for (i in preferredQueueSize..<currentQueueSize) {
            if (!haveReadFromMediaChunk(i)) {
                // means that most chunks have been read from already. See [internal b/161126666].
                newQueueSize = i
                break
            }
        }
        if (newQueueSize == C.LENGTH_UNSET) {
            return
        }

        val endTimeUs = this.lastMediaChunk.endTimeUs
        val firstRemovedChunk = discardUpstreamMediaChunksFromIndex(newQueueSize)
        if (mediaChunks.isEmpty()) {
            pendingResetPositionUs = lastSeekPositionUs
        }
        loadingFinished = false
        mediaSourceEventDispatcher.upstreamDiscarded(
            primaryTrackType, firstRemovedChunk.startTimeUs, endTimeUs
        )
    }

    private fun isMediaChunk(chunk: Chunk?): Boolean {
        return chunk is BaseMediaChunk
    }

    private fun resetSampleQueues() {
        primarySampleQueue.reset()
        for (embeddedSampleQueue in embeddedSampleQueues) {
            embeddedSampleQueue.reset()
        }
    }

    private fun haveReadFromMediaChunk(mediaChunkIndex: Int): Boolean {
        val mediaChunk = mediaChunks[mediaChunkIndex]
        if (primarySampleQueue.readIndex > mediaChunk.getFirstSampleIndex(0)) {
            return true
        }
        for (i in embeddedSampleQueues.indices) {
            if (embeddedSampleQueues[i].readIndex > mediaChunk.getFirstSampleIndex(i + 1)) {
                return true
            }
        }
        return false
    }

    val isPendingReset: Boolean
        get() = pendingResetPositionUs != C.TIME_UNSET

    private fun discardDownstreamMediaChunks(discardToSampleIndex: Int) {
        var discardToMediaChunkIndex =
            primarySampleIndexToMediaChunkIndex(discardToSampleIndex, 0)
        // Don't discard any chunks that we haven't reported the primary format change for yet.
        discardToMediaChunkIndex =
            min(discardToMediaChunkIndex, nextNotifyPrimaryFormatMediaChunkIndex)
        if (discardToMediaChunkIndex > 0) {
            Util.removeRange(
                mediaChunks,
                0,
                discardToMediaChunkIndex
            )
            nextNotifyPrimaryFormatMediaChunkIndex -= discardToMediaChunkIndex
        }
    }

    private fun maybeNotifyPrimaryTrackFormatChanged() {
        val readSampleIndex = primarySampleQueue.readIndex
        val notifyToMediaChunkIndex =
            primarySampleIndexToMediaChunkIndex(
                readSampleIndex, nextNotifyPrimaryFormatMediaChunkIndex - 1
            )
        while (nextNotifyPrimaryFormatMediaChunkIndex <= notifyToMediaChunkIndex) {
            maybeNotifyPrimaryTrackFormatChanged(nextNotifyPrimaryFormatMediaChunkIndex++)
        }
    }

    private fun maybeNotifyPrimaryTrackFormatChanged(mediaChunkReadIndex: Int) {
        val currentChunk = mediaChunks[mediaChunkReadIndex]
        val trackFormat = currentChunk.trackFormat
        if (trackFormat != primaryDownstreamTrackFormat) {
            mediaSourceEventDispatcher.downstreamFormatChanged(
                primaryTrackType,
                trackFormat,
                currentChunk.trackSelectionReason,
                currentChunk.trackSelectionData,
                currentChunk.startTimeUs
            )
        }
        primaryDownstreamTrackFormat = trackFormat
    }

    private fun primarySampleIndexToMediaChunkIndex(
        primarySampleIndex: Int,
        minChunkIndex: Int
    ): Int {
        for (i in minChunkIndex + 1..<mediaChunks.size) {
            if (mediaChunks[i].getFirstSampleIndex(0) > primarySampleIndex) {
                return i - 1
            }
        }
        return mediaChunks.size - 1
    }

    private val lastMediaChunk: BaseMediaChunk
        get() = mediaChunks[mediaChunks.size - 1]

    private fun discardUpstreamMediaChunksFromIndex(chunkIndex: Int): BaseMediaChunk {
        val firstRemovedChunk = mediaChunks[chunkIndex]
        Util.removeRange(
            mediaChunks,
            chunkIndex,
            mediaChunks.size
        )
        nextNotifyPrimaryFormatMediaChunkIndex =
            max(nextNotifyPrimaryFormatMediaChunkIndex, mediaChunks.size)
        primarySampleQueue.discardUpstreamSamples(firstRemovedChunk.getFirstSampleIndex(0))
        for (i in embeddedSampleQueues.indices) {
            embeddedSampleQueues[i].discardUpstreamSamples(firstRemovedChunk.getFirstSampleIndex(i + 1))
        }
        return firstRemovedChunk
    }

    inner class EmbeddedSampleStream(
        val parent: ChunkSampleStream<T>,
        private val sampleQueue: SampleQueue,
        private val index: Int
    ) : SampleStream {
        private var notifiedDownstreamFormat = false

        override val isReady: Boolean
            get() = !this@ChunkSampleStream.isPendingReset && sampleQueue.isReady(loadingFinished)

        override fun skipData(positionUs: Long): Int {
            if (this@ChunkSampleStream.isPendingReset) {
                return 0
            }
            var skipCount = sampleQueue.getSkipCount(positionUs, loadingFinished)
            if (canceledMediaChunk != null) {
                // Don't skip into chunk that's going to be discarded.
                val maxSkipCount =
                    (canceledMediaChunk!!.getFirstSampleIndex(1 + index)
                            - sampleQueue.readIndex)
                skipCount = min(skipCount, maxSkipCount)
            }
            sampleQueue.skip(skipCount)
            if (skipCount > 0) {
                maybeNotifyDownstreamFormat()
            }
            return skipCount
        }

        override fun maybeThrowError() {
            // Do nothing. Errors will be thrown from the primary stream.
        }

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): Int {
            if (this@ChunkSampleStream.isPendingReset) {
                return C.RESULT_NOTHING_READ
            }
            if (canceledMediaChunk != null
                && (canceledMediaChunk!!.getFirstSampleIndex(1 + index)
                        <= sampleQueue.readIndex)
            ) {
                // Don't read into chunk that's going to be discarded.
                return C.RESULT_NOTHING_READ
            }
            maybeNotifyDownstreamFormat()
            return sampleQueue.read(formatHolder, buffer, readFlags, loadingFinished)
        }

        fun release() {
            Preconditions.checkState(embeddedTracksSelected[index])
            embeddedTracksSelected[index] = false
        }

        private fun maybeNotifyDownstreamFormat() {
            if (!notifiedDownstreamFormat) {
                mediaSourceEventDispatcher.downstreamFormatChanged(
                    embeddedTrackTypes[index],
                    embeddedTrackFormats[index],
                    C.SELECTION_REASON_UNKNOWN,
                    null,
                    lastSeekPositionUs
                )
                notifiedDownstreamFormat = true
            }
        }
    }

    companion object {
        private const val TAG = "ChunkSampleStream"
    }
}
