package androidx.media3.exoplayer.source

import android.net.Uri
import android.os.Build
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.MimeTypes.isAudio
import androidx.media3.common.MimeTypes.isImage
import androidx.media3.common.MimeTypes.isVideo
import androidx.media3.common.ParserException
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.ConditionVariable
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import androidx.media3.common.util.Util.usToMs
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil.closeQuietly
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.ProgressiveMediaPeriod.ExtractingLoadable
import androidx.media3.exoplayer.source.SampleQueue.UpstreamFormatChangedListener
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction
import androidx.media3.exoplayer.upstream.Loader.Loadable
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingSeekMap
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.icy.IcyHeaders
import com.google.common.base.Preconditions
import com.google.common.net.HttpHeaders

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

internal class ProgressiveMediaPeriod
    (
    private val uri: Uri,
    private val dataSource: DataSource,
    private val progressiveMediaExtractor: ProgressiveMediaExtractor,
    private val drmSessionManager: DrmSessionManager,
    private val drmEventDispatcher: DrmSessionEventListener.EventDispatcher,
    private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    private val mediaSourceEventDispatcher: MediaSourceEventListener.EventDispatcher,
    private val listener: Listener,
    private val allocator: Allocator,
    private val customCacheKey: String?,
    continueLoadingCheckIntervalBytes: Int,
    private val loadOnlySelectedTracks: Boolean,
    private val experimentalEnableHagcPlayback: Boolean,
    private val singleTrackId: Int,
    private val singleTrackFormat: Format?,
    private val singleSampleDurationUs: Long,
    downloadExecutor: ReleasableExecutor?
) : MediaPeriod, ExtractorOutput, Loader.Callback<ExtractingLoadable>, Loader.ReleaseCallback,
    UpstreamFormatChangedListener {
    internal interface Listener {
        fun onSourceInfoRefreshed(durationUs: Long, seekMap: SeekMap, isLive: Boolean)
    }

    private val continueLoadingCheckIntervalBytes: Long = continueLoadingCheckIntervalBytes.toLong()
    private val loader: Loader = if (downloadExecutor != null)
        Loader(downloadExecutor)
    else
        Loader("ProgressiveMediaPeriod")
    private val loadCondition: ConditionVariable = ConditionVariable()
    private val maybeFinishPrepareRunnable: Runnable = Runnable { maybeFinishPrepare() }
    private val onContinueLoadingRequestedRunnable: Runnable = Runnable {
        if (!released) {
            callback!!.onContinueLoadingRequested(this@ProgressiveMediaPeriod)
        }
    }
    private val handler: Handler

    private var callback: MediaPeriod.Callback? = null
    private var icyHeaders: IcyHeaders? = null

    // TODO(b/457738425) - Consider merging controledTrackOutputs, sampleQueues together, since they
    // are essentially the same objects.
    private var controlledTrackOutputs: Array<ControlledTrackOutput> = arrayOf()
    private var sampleQueues: Array<SampleQueue> = arrayOf()
    private var sampleQueueTrackIds: Array<TrackId> = arrayOf()
    private var sampleQueuesBuilt = false

    private var prepared = false
    private var haveAudioVideoTracks = false
    private var isSingleSample = false
    private var trackState:TrackState? = null
    private var seekMap:SeekMap? = null
    private var durationUs: Long = 0
    private var isLive = false
    private var dataType: @C.DataType Int = C.DATA_TYPE_MEDIA
    private var endPositionUs: Long = C.TIME_END_OF_SOURCE

    private var seenFirstTrackSelection = false
    private var notifyDiscontinuity = false
    private var pendingInitialDiscontinuity = false
    private var usesStreamPrerollFlags = false
    private var enabledTrackCount = 0
    private val mergingSampleStreams: MutableList<MergingMetadataSampleStream> = mutableListOf()
    private var isLengthKnown = false

    private var lastSeekPositionUs: Long = 0
    private var pendingResetPositionUs: Long = C.TIME_UNSET
    private var pendingDeferredRetry = false

    private var extractedSamplesCountAtStartOfLoad = 0
    private var loadingFinished = false
    private var released = false

    // maybeFinishPrepare is not posted to the handler until initialization completes.
    init {
        handler = createHandlerForCurrentLooper()
    }

    fun release() {
        if (prepared) {
            // Discard as much as we can synchronously. We only do this if we're prepared, since otherwise
            // sampleQueues may still be being modified by the loading thread.
            for (sampleQueue in sampleQueues) {
                sampleQueue.preRelease()
            }
        }
        loader.release( this)
        handler.removeCallbacksAndMessages(null)
        callback = null
        released = true
    }

    override fun onLoaderReleased() {
        for (sampleQueue in sampleQueues) {
            sampleQueue.release()
        }
        progressiveMediaExtractor.release()
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        if (singleTrackFormat != null) {
            // track() and endTracks() are meant to be called on the loading thread, which doesn't exist
            // yet (we're on the playback thread here). Starting the loading thread will provide a memory
            // barrier to ensure any changes done here are visible on the loading thread after it starts.
            val track = track(singleTrackId, C.TRACK_TYPE_TEXT)
            track.format(singleTrackFormat)
            setSeekMap(
                IndexSeekMap(listOf(0), listOf(0), C.TIME_UNSET)
            )
            endTracks()
            pendingResetPositionUs = positionUs
        } else {
            loadCondition.open()
            startLoading()
        }
    }

    @Throws(IOException::class)
    override fun maybeThrowPrepareError() {
        maybeThrowError()
        if (loadingFinished && !prepared) {
            throw ParserException.createForMalformedContainer(
                "Loading finished before preparation is complete.",  null
            )
        }
    }

    override val trackGroups: TrackGroupArray
        get() {
            assertPrepared()
            return trackState!!.tracks
        }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        var positionUs = positionUs
        assertPrepared()
        val tracks = trackState!!.tracks
        val trackEnabledStates = trackState!!.trackEnabledStates
        val oldEnabledTrackCount = enabledTrackCount

        var isT35TrackExplicitlySelected = false
        for (i in selections.indices) {
            if (selections[i] != null) {
                val track = tracks.indexOf(selections[i]!!.trackGroup)
                if (tracks.get(track)
                        .getFormat(0).sampleMimeType == MimeTypes.APPLICATION_ITUT_T35
                ) {
                    isT35TrackExplicitlySelected = true
                    break
                }
            }
        }

        // Deselect old tracks.
        for (i in selections.indices) {
            if (streams[i] != null) {
                val shouldDeselect =
                    selections[i] == null || !mayRetainStreamFlags[i] || (isT35TrackExplicitlySelected
                            && streams[i] is MergingMetadataSampleStream)
                if (shouldDeselect) {
                    if (streams[i] is MergingMetadataSampleStream) {
                        val mergingStream = streams[i] as MergingMetadataSampleStream?
                        mergingSampleStreams.remove(mergingStream)
                        val primaryTrack =
                            (mergingStream!!.primaryStream as SampleStreamImpl).track
                        Preconditions.checkState(trackEnabledStates[primaryTrack])
                        enabledTrackCount--
                        trackEnabledStates[primaryTrack] = false

                        val metadataTrack =
                            (mergingStream.metadataStream as SampleStreamImpl).track
                        Preconditions.checkState(trackEnabledStates[metadataTrack])
                        enabledTrackCount--
                        trackEnabledStates[metadataTrack] = false
                    } else {
                        val track = (streams[i] as SampleStreamImpl).track
                        Preconditions.checkState(trackEnabledStates[track])
                        enabledTrackCount--
                        trackEnabledStates[track] = false
                    }
                    streams[i] = null
                }
            }
        }
        // We'll always need to seek if this is a first selection to a non-zero position (except for
        // when we have a single sample only), or if we're making a selection having previously
        // disabled all tracks.
        var seekRequired =
            if (seenFirstTrackSelection) oldEnabledTrackCount == 0 else positionUs != 0L && !isSingleSample
        var hasPreroll = false
        // Select new tracks.
        for (i in selections.indices) {
            if (streams[i] == null && selections[i] != null) {
                val selection = selections[i]
                Preconditions.checkState(selection!!.length() == 1)
                Preconditions.checkState(selection.getIndexInTrackGroup(0) == 0)
                val track = tracks.indexOf(selection.trackGroup)
                Preconditions.checkState(!trackEnabledStates[track])
                enabledTrackCount++
                trackEnabledStates[track] = true
                hasPreroll = hasPreroll or selection.selectedFormat.hasPrerollSamples
                var stream: SampleStream = SampleStreamImpl(
                    track,
                    selection.selectedFormat.hasPrerollSamples
                )
                if (experimentalEnableHagcPlayback
                    && Build.VERSION.SDK_INT >= 37 && !isT35TrackExplicitlySelected && isVideo(
                        selection.selectedFormat.sampleMimeType
                    )
                ) {
                    // TODO: b/388762778 - The MP4 container has extra information (e.g. cdsc box) specifying
                    // which HAGC track describes which video track. We should use this information to create
                    // the correct mergingSampleStreams when multiple video/HAGC tracks exist.
                    for (j in 0..<tracks.length) {
                        val format = tracks.get(j).getFormat(0)
                        if (format.sampleMimeType == MimeTypes.APPLICATION_ITUT_T35) {
                            var isHagc = false
                            if (!format.initializationData.isEmpty()) {
                                val initData = format.initializationData[0]
                                if (initData.size >= 5 && initData[0] == 0xB5.toByte() && initData[1] == 0x00.toByte() && initData[2] == 0x90.toByte() && initData[3] == 0x00.toByte() && initData[4] == 0x01.toByte()) {
                                    isHagc = true
                                }
                            }
                            if (isHagc) {
                                Preconditions.checkState(!trackEnabledStates[j])
                                enabledTrackCount++
                                trackEnabledStates[j] = true
                                if (loadOnlySelectedTracks) {
                                    controlledTrackOutputs[j].updateSelectionState(true)
                                }
                                // HAGC it35 metadata samples are standalone and do not depend on
                                // previous samples, hence hasPreroll is not relevant and always false.
                                stream =
                                    MergingMetadataSampleStream(
                                        stream,
                                        SampleStreamImpl(
                                            j,  
                                            false
                                        ),
                                        selection.selectedFormat
                                    )
                                mergingSampleStreams.add(stream)
                                break
                            }
                        }
                    }
                }
                streams[i] = stream
                streamResetFlags[i] = true

                if (loadOnlySelectedTracks) {
                    // If we reenable a track, it needs to be seeked since it was not previously loaded.
                    seekRequired = seekRequired or seenFirstTrackSelection
                    continue
                }

                // If there's still a chance of avoiding a seek, try and seek within the sample queue.
                if (!seekRequired) {
                    val sampleQueue = sampleQueues[track]
                    // A seek can be avoided if we haven't read any samples yet (e.g. for the first track
                    // selection) or we are able to seek to the current playback position in the sample queue.
                    // In all other cases a seek is required.
                    seekRequired =
                        sampleQueue.readIndex != 0
                                && !sampleQueue.seekTo(
                            positionUs,  
                            true
                        )
                }
            }
        }

        if (pendingInitialDiscontinuity || !seenFirstTrackSelection) {
            pendingInitialDiscontinuity = hasPreroll
        }

        if (loadOnlySelectedTracks) {
            for (i in controlledTrackOutputs.indices) {
                controlledTrackOutputs[i].updateSelectionState(trackEnabledStates[i])
            }
        }

        if (enabledTrackCount == 0) {
            pendingDeferredRetry = false
            notifyDiscontinuity = false
            pendingInitialDiscontinuity = false
            if (loader.isLoading) {
                // Discard as much as we can synchronously.
                for (sampleQueue in sampleQueues) {
                    sampleQueue.discardToEnd()
                }
                loader.cancelLoading()
            } else {
                loadingFinished = false
                for (sampleQueue in sampleQueues) {
                    sampleQueue.reset()
                }
            }
        } else if (seekRequired) {
            positionUs = seekToUs(positionUs)
            // We'll need to reset renderers consuming from all streams due to the seek.
            for (i in streams.indices) {
                if (streams[i] != null) {
                    streamResetFlags[i] = true
                    if (streams[i] is MergingMetadataSampleStream) {
                        (streams[i] as MergingMetadataSampleStream).reset()
                    }
                }
            }
        }
        seenFirstTrackSelection = true
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        if (isSingleSample) {
            // Optimize by not discarding buffers.
            return
        }
        assertPrepared()
        if (this.isPendingReset) {
            return
        }
        val trackEnabledStates = trackState!!.trackEnabledStates
        val trackCount = sampleQueues.size
        for (i in 0..<trackCount) {
            sampleQueues[i].discardTo(positionUs, toKeyframe, trackEnabledStates[i])
        }
    }

    override fun reevaluateBuffer(positionUs: Long) {
        if (enabledTrackCount > 0 && !this.isPendingReset && haveSampleQueuesReachedEndTimeUs()) {
            loadingFinished = true
            // TODO: b/474538573 - Actually cancel ongoing load and restart later if required.
        }
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        if (loadingFinished
            || loader.hasFatalError()
            || pendingDeferredRetry
            || ((prepared || singleTrackFormat != null) && enabledTrackCount == 0)
        ) {
            return false
        }
        var continuedLoading = loadCondition.open()
        if (!loader.isLoading) {
            startLoading()
            continuedLoading = true
        }
        return continuedLoading
    }

    override val isLoading: Boolean
        get() = !loadingFinished && loader.isLoading && loadCondition.isOpen

    override val nextLoadPositionUs: Long
        get() = bufferedPositionUs

    override fun setUsesStreamPrerollFlags() {
        this.usesStreamPrerollFlags = true
    }

    override fun readDiscontinuity(): Long {
        if (!usesStreamPrerollFlags && pendingInitialDiscontinuity) {
            pendingInitialDiscontinuity = false
            return lastSeekPositionUs
        }

        if (notifyDiscontinuity
            && (loadingFinished || this.extractedSamplesCount > extractedSamplesCountAtStartOfLoad)
        ) {
            notifyDiscontinuity = false
            return lastSeekPositionUs
        }
        return C.TIME_UNSET
    }

    override val bufferedPositionUs: Long
        get() {
            assertPrepared()
            if (loadingFinished || enabledTrackCount == 0) {
                return C.TIME_END_OF_SOURCE
            } else if (this.isPendingReset) {
                return pendingResetPositionUs
            }
            var largestQueuedTimestampUs = Long.MAX_VALUE
            if (haveAudioVideoTracks) {
                // Ignore non-AV tracks, which may be sparse or poorly interleaved.
                val trackCount = sampleQueues.size
                for (i in 0..<trackCount) {
                    if (trackState!!.trackIsAudioVideoFlags[i]
                        && trackState!!.trackEnabledStates[i]
                        && !sampleQueues[i].isLastSampleQueued
                    ) {
                        largestQueuedTimestampUs =
                            min(largestQueuedTimestampUs, sampleQueues[i].largestQueuedTimestampUs)
                    }
                }
            }
            if (largestQueuedTimestampUs == Long.MAX_VALUE) {
                largestQueuedTimestampUs =
                    getLargestQueuedTimestampUs( false)
            }
            return if (largestQueuedTimestampUs == Long.MIN_VALUE)
                lastSeekPositionUs
            else
                largestQueuedTimestampUs
        }

    override fun seekToUs(positionUs: Long): Long {
        var positionUs = positionUs
        for (stream in mergingSampleStreams) {
            stream.reset()
        }
        assertPrepared()
        val trackIsAudioVideoFlags = trackState!!.trackIsAudioVideoFlags
        // Treat all seeks into non-seekable media as being to t=0.
        positionUs = if (seekMap!!.isSeekable) positionUs else 0

        notifyDiscontinuity = false
        val isSameAsLastSeekPosition = lastSeekPositionUs == positionUs
        lastSeekPositionUs = positionUs
        if (this.isPendingReset) {
            // A reset is already pending. We only need to update its position.
            pendingResetPositionUs = positionUs
            return positionUs
        }

        // If we're not playing a live stream, and when loading will continue (or has finished), try
        // and seek within the existing buffer instead of restarting the load.
        if (dataType != C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE && (loadingFinished || loader.isLoading)
            && seekInsideBufferUs(trackIsAudioVideoFlags, positionUs, isSameAsLastSeekPosition)
        ) {
            return positionUs
        }

        // We can't seek inside the buffer, and so need to reset.
        pendingDeferredRetry = false
        pendingResetPositionUs = positionUs
        loadingFinished = false
        pendingInitialDiscontinuity = false
        if (loader.isLoading) {
            // Discard as much as we can synchronously.
            for (sampleQueue in sampleQueues) {
                sampleQueue.discardToEnd()
            }
            loader.cancelLoading()
        } else {
            loader.clearFatalError()
            for (sampleQueue in sampleQueues) {
                sampleQueue.reset()
            }
        }
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        assertPrepared()
        if (!seekMap!!.isSeekable) {
            // Treat all seeks into non-seekable media as being to t=0.
            return 0
        }
        val seekPoints = seekMap!!.getSeekPoints(positionUs)
        return seekParameters.resolveSeekPositionUs(
            positionUs, seekPoints!!.first.timeUs, seekPoints.second.timeUs
        )
    }

    override fun setEndPositionUs(endPositionUs: Long): Long {
        this.endPositionUs = endPositionUs
        for (sampleQueue in sampleQueues) {
            sampleQueue.setReadEndTimeUs(endPositionUs)
        }
        return endPositionUs
    }

    // SampleStream methods.

    fun isReady(track: Int): Boolean {
        return !suppressRead() && sampleQueues[track].isReady(loadingFinished)
    }

    @Throws(IOException::class)
    fun maybeThrowError(sampleQueueIndex: Int) {
        sampleQueues[sampleQueueIndex].maybeThrowError()
        maybeThrowError()
    }

    @Throws(IOException::class)
    fun maybeThrowError() {
        loader.maybeThrowError(loadErrorHandlingPolicy.getMinimumLoadableRetryCount(dataType))
    }

    fun readData(
        sampleQueueIndex: Int,
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: @ReadFlags Int
    ): Int {
        if (suppressRead()) {
            return C.RESULT_NOTHING_READ
        }
        maybeNotifyDownstreamFormat(sampleQueueIndex)
        val result =
            sampleQueues[sampleQueueIndex].read(formatHolder, buffer, readFlags, loadingFinished)
        if (result == C.RESULT_NOTHING_READ) {
            maybeStartDeferredRetry(sampleQueueIndex)
        }
        return result
    }

    fun skipData(track: Int, positionUs: Long): Int {
        if (suppressRead()) {
            return 0
        }
        maybeNotifyDownstreamFormat(track)
        val sampleQueue = sampleQueues[track]
        val skipCount = sampleQueue.getSkipCount(positionUs, loadingFinished)
        sampleQueue.skip(skipCount)
        if (skipCount == 0) {
            maybeStartDeferredRetry(track)
        }
        return skipCount
    }

    private fun haveSampleQueuesReachedEndTimeUs(): Boolean {
        if (endPositionUs == C.TIME_END_OF_SOURCE) {
            return false
        }
        assertPrepared()
        var endPositionReached = true
        for (i in sampleQueues.indices) {
            // Ignore non-AV tracks, which may be sparse or poorly interleaved.
            if (trackState!!.trackEnabledStates[i]
                && (trackState!!.trackIsAudioVideoFlags[i] || !haveAudioVideoTracks)
            ) {
                endPositionReached =
                    endPositionReached and sampleQueues[i].hasQueuedTimestampsUpToReadEndTimeUs()
            }
        }
        return endPositionReached
    }

    private fun maybeNotifyDownstreamFormat(track: Int) {
        assertPrepared()
        val trackNotifiedDownstreamFormats = trackState!!.trackNotifiedDownstreamFormats
        if (!trackNotifiedDownstreamFormats[track]) {
            val trackFormat = trackState!!.tracks.get(track).getFormat( 0)
            mediaSourceEventDispatcher.downstreamFormatChanged(
                getTrackType(trackFormat.sampleMimeType),
                trackFormat,
                C.SELECTION_REASON_UNKNOWN,  
                null,
                lastSeekPositionUs
            )
            trackNotifiedDownstreamFormats[track] = true
        }
    }

    private fun maybeStartDeferredRetry(track: Int) {
        assertPrepared()
        if (!pendingDeferredRetry || (haveAudioVideoTracks && !trackState!!.trackIsAudioVideoFlags[track])
            || sampleQueues[track].isReady( false)
        ) {
            return
        }
        pendingResetPositionUs = 0
        pendingDeferredRetry = false
        notifyDiscontinuity = true
        lastSeekPositionUs = 0
        extractedSamplesCountAtStartOfLoad = 0
        for (sampleQueue in sampleQueues) {
            sampleQueue.reset()
        }
        callback!!.onContinueLoadingRequested(this)
    }

    private fun suppressRead(): Boolean {
        return notifyDiscontinuity || this.isPendingReset
    }

    // Loader.Callback implementation.
    override fun onLoadStarted(
        loadable: ExtractingLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long, retryCount: Int
    ) {
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
        if (retryCount != 0) {
            loadEventInfo
                .setUri(dataSource.lastOpenedUri!!)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(dataSource.bytesRead)
        }
        mediaSourceEventDispatcher.loadStarted(
            loadEventInfo.build(),
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,  
            null,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            loadable.seekTimeUs,
            durationUs,
            retryCount
        )
    }

    override fun onLoadCompleted(
        loadable: ExtractingLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long
    ) {
        val seekMap = seekMap
        if (durationUs == C.TIME_UNSET && seekMap != null) {
            val largestQueuedTimestampUs =
                getLargestQueuedTimestampUs( true)
            durationUs =
                if (largestQueuedTimestampUs == Long.MIN_VALUE)
                    0
                else
                    largestQueuedTimestampUs + DEFAULT_LAST_SAMPLE_DURATION_US
            listener.onSourceInfoRefreshed(durationUs, seekMap, isLive)
        }
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(dataSource.lastOpenedUri!!)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(dataSource.bytesRead)
                .build()
        loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        mediaSourceEventDispatcher.loadCompleted(
            loadEventInfo,
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,  
            null,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            loadable.seekTimeUs,
            durationUs
        )
        loadingFinished = true
        callback!!.onContinueLoadingRequested(this)
    }

    override fun onLoadCanceled(
        loadable: ExtractingLoadable,
        elapsedRealtimeMs: Long,
        loadDurationMs: Long,
        released: Boolean
    ) {
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(dataSource.lastOpenedUri!!)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(dataSource.bytesRead)
                .build()
        loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        mediaSourceEventDispatcher.loadCanceled(
            loadEventInfo,
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,  
            null,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            loadable.seekTimeUs,
            durationUs
        )
        if (!released) {
            for (sampleQueue in sampleQueues) {
                sampleQueue.reset()
            }
            if (enabledTrackCount > 0) {
                callback!!.onContinueLoadingRequested(this)
            }
        }
    }

    override fun onLoadError(
        loadable: ExtractingLoadable,
        elapsedRealtimeMs: Long,
        loadDurationMs: Long,
        error: IOException,
        errorCount: Int
    ): LoadErrorAction {
        val dataSource = loadable.dataSource
        val loadEventInfo =
            LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(dataSource.lastOpenedUri!!)
                .setResponseHeaders(dataSource.lastResponseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(dataSource.bytesRead)
                .build()
        val mediaLoadData =
            MediaLoadData(
                C.DATA_TYPE_MEDIA,
                C.TRACK_TYPE_UNKNOWN,  
                null,
                C.SELECTION_REASON_UNKNOWN,  
                null,  
                usToMs(loadable.seekTimeUs),
                usToMs(durationUs)
            )
        val loadErrorAction: LoadErrorAction
        val retryDelayMs =
            loadErrorHandlingPolicy.getRetryDelayMsFor(
                LoadErrorInfo(loadEventInfo, mediaLoadData, error, errorCount)
            )
        if (retryDelayMs == C.TIME_UNSET) {
            loadErrorAction = Loader.DONT_RETRY_FATAL
        } else   {
            val extractedSamplesCount = this.extractedSamplesCount
            val madeProgress = extractedSamplesCount > extractedSamplesCountAtStartOfLoad
            loadErrorAction =
                if (configureRetry(loadable, extractedSamplesCount))
                    Loader.createRetryAction( madeProgress, retryDelayMs)
                else
                    Loader.DONT_RETRY
        }

        val wasCanceled = !loadErrorAction.isRetry
        mediaSourceEventDispatcher.loadError(
            loadEventInfo,
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,  
            null,
            C.SELECTION_REASON_UNKNOWN,  
            null,  
            loadable.seekTimeUs,
            durationUs,
            error,
            wasCanceled
        )
        if (wasCanceled) {
            loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId)
        }
        return loadErrorAction
    }

    // ExtractorOutput implementation. Called by the loading thread.
    override fun track(id: Int, type: Int): TrackOutput {
        return prepareTrackOutput(TrackId(id,  false))
    }

    override fun endTracks() {
        sampleQueuesBuilt = true
        handler.post(maybeFinishPrepareRunnable)
    }

    override fun seekMap(seekMap: SeekMap) {
        handler.post(Runnable { setSeekMap(seekMap) })
    }

    // Icy metadata. Called by the loading thread.

    fun icyTrack(): TrackOutput {
        return prepareTrackOutput(TrackId(0,  true))
    }

    // UpstreamFormatChangedListener implementation. Called by the loading thread.
    override fun onUpstreamFormatChanged(format: Format) {
        handler.post(maybeFinishPrepareRunnable)
    }

    // Internal methods.
    private fun onLengthKnown() {
        handler.post(Runnable { isLengthKnown = true })
    }

    private fun prepareTrackOutput(id: TrackId): TrackOutput {
        val trackCount = sampleQueues.size
        for (i in 0..<trackCount) {
            if (id == sampleQueueTrackIds[i]) {
                return sampleQueues[i]
            }
        }
        if (sampleQueuesBuilt) {
            w(TAG, "Extractor added new track (id=" + id.id + ") after finishing tracks.")
            return DiscardingTrackOutput()
        }
        val sampleQueue: SampleQueue =
            SampleQueue.createWithDrm(allocator, drmSessionManager, drmEventDispatcher)
        val trackOutput = ControlledTrackOutput(sampleQueue)
        sampleQueue.setUpstreamFormatChangeListener(this)
        val sampleQueueTrackIds: Array<TrackId?> = sampleQueueTrackIds.copyOf(trackCount + 1)
        sampleQueueTrackIds[trackCount] = id
        this.sampleQueueTrackIds = sampleQueueTrackIds as Array<TrackId>

        val sampleQueues = sampleQueues.copyOf<SampleQueue>(trackCount + 1)
        sampleQueues[trackCount] = sampleQueue
        this.sampleQueues = sampleQueues as Array<SampleQueue>

        val controlledTrackOutputs: Array<ControlledTrackOutput?> =
            controlledTrackOutputs.copyOf<ControlledTrackOutput>(trackCount + 1)
        controlledTrackOutputs[trackCount] = trackOutput
        this.controlledTrackOutputs = controlledTrackOutputs as Array<ControlledTrackOutput>
        return trackOutput
    }

    private fun setSeekMap(seekMap: SeekMap) {
        this.seekMap =
            if (icyHeaders == null) seekMap else SeekMap.Unseekable( C.TIME_UNSET)
        durationUs = seekMap.durationUs
        isLive = !isLengthKnown && seekMap.durationUs == C.TIME_UNSET
        dataType = if (isLive) C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE else C.DATA_TYPE_MEDIA
        if (prepared) {
            listener.onSourceInfoRefreshed(durationUs, seekMap, isLive)
        } else {
            maybeFinishPrepare()
        }
    }

    private fun maybeFinishPrepare() {
        val seekMap = seekMap
        if (released || prepared || !sampleQueuesBuilt || seekMap == null) {
            return
        }
        for (sampleQueue in sampleQueues) {
            if (sampleQueue.getUpstreamFormat() == null) {
                return
            }
        }
        loadCondition.close()
        val trackCount = sampleQueues.size
        var primaryTrackIndex = 0
        var primaryTrackIndexType: @C.TrackType Int = C.TRACK_TYPE_UNKNOWN
        for (i in 0..<trackCount) {
            val trackType: @C.TrackType Int =
                getTrackType(sampleQueues[i].getUpstreamFormat()!!.sampleMimeType)
            if (getTrackTypePriority(trackType) > getTrackTypePriority(primaryTrackIndexType)) {
                primaryTrackIndex = i
                primaryTrackIndexType = trackType
            }
        }
        val trackArray = arrayOfNulls<TrackGroup>(trackCount)
        val trackIsAudioVideoFlags = BooleanArray(trackCount)
        for (i in 0..<trackCount) {
            var trackFormat = sampleQueues[i].getUpstreamFormat()!!
            val mimeType = trackFormat.sampleMimeType
            val isAudio = isAudio(mimeType)
            val isAudioVideo = isAudio || isVideo(mimeType)
            trackIsAudioVideoFlags[i] = isAudioVideo
            haveAudioVideoTracks = haveAudioVideoTracks or isAudioVideo
            val isImage = isImage(mimeType)
            isSingleSample = singleSampleDurationUs != C.TIME_UNSET && trackCount == 1 && isImage
            val icyHeaders = this.icyHeaders
            if (icyHeaders != null) {
                if (isAudio || sampleQueueTrackIds[i].isIcyTrack) {
                    var metadata = trackFormat.metadata
                    if (metadata == null) {
                        metadata = Metadata(listOf(icyHeaders))
                    } else {
                        metadata = metadata.copyWithAppendedEntries(listOf(icyHeaders))
                    }
                    trackFormat = trackFormat.buildUpon().setMetadata(metadata).build()
                }
                // Update the track format with the bitrate from the ICY header only if it declares neither
                // an average or peak bitrate of its own.
                if (isAudio
                    && trackFormat.averageBitrate == Format.NO_VALUE && trackFormat.peakBitrate == Format.NO_VALUE && icyHeaders.bitrate != Format.NO_VALUE
                ) {
                    trackFormat =
                        trackFormat.buildUpon().setAverageBitrate(icyHeaders.bitrate).build()
                }
            }
            trackFormat =
                trackFormat.copyWithCryptoType(drmSessionManager.getCryptoType(trackFormat))
            if (i != primaryTrackIndex) {
                trackFormat =
                    trackFormat
                        .buildUpon()
                        .setPrimaryTrackGroupId(primaryTrackIndex.toString())
                        .build()
            }
            trackArray[i] = TrackGroup( i.toString(), listOf(trackFormat))
            sampleQueues[i].setReadEndTimeUs(endPositionUs)
        }
        trackState =
            TrackState(TrackGroupArray(trackArray as Array<TrackGroup>), trackIsAudioVideoFlags)
        if (isSingleSample && durationUs == C.TIME_UNSET) {
            durationUs = singleSampleDurationUs
            this.seekMap = object : ForwardingSeekMap(seekMap) {
                override val durationUs: Long
                    get() = this@ProgressiveMediaPeriod.durationUs
            }
        }
        listener.onSourceInfoRefreshed(durationUs, seekMap, isLive)
        prepared = true
        callback!!.onPrepared(this)
    }

    private fun startLoading() {
        val loadable =
            ExtractingLoadable(
                uri,
                dataSource,
                progressiveMediaExtractor,  
                this,
                loadCondition
            )
        if (prepared) {
            Preconditions.checkState(this.isPendingReset)
            val maxLoadPositionUs =
                if (endPositionUs != C.TIME_END_OF_SOURCE) endPositionUs else durationUs
            if (maxLoadPositionUs != C.TIME_UNSET && pendingResetPositionUs > maxLoadPositionUs) {
                loadingFinished = true
                pendingResetPositionUs = C.TIME_UNSET
                return
            }
            loadable.setLoadPosition(
                seekMap!!.getSeekPoints(pendingResetPositionUs).first.position,
                pendingResetPositionUs
            )
            for (sampleQueue in sampleQueues) {
                sampleQueue.setStartTimeUs(pendingResetPositionUs)
            }
            pendingResetPositionUs = C.TIME_UNSET
        }
        extractedSamplesCountAtStartOfLoad = this.extractedSamplesCount
        loader.startLoading(
            loadable, this, loadErrorHandlingPolicy.getMinimumLoadableRetryCount(dataType)
        )
    }

    private fun configureRetry(
        loadable: ExtractingLoadable,
        currentExtractedSampleCount: Int
    ): Boolean {
        if (isLengthKnown || (seekMap != null && seekMap!!.durationUs != C.TIME_UNSET)) {
            // We're playing an on-demand stream. Resume the current loadable, which will
            // request data starting from the point it left off.
            extractedSamplesCountAtStartOfLoad = currentExtractedSampleCount
            return true
        } else if (prepared && !suppressRead()) {
            // We're playing a stream of unknown length and duration. Assume it's live, and therefore that
            // the data at the uri is a continuously shifting window of the latest available media. For
            // this case there's no way to continue loading from where a previous load finished, so it's
            // necessary to load from the start whenever commencing a new load. Deferring the retry until
            // we run out of buffered data makes for a much better user experience. See:
            // https://github.com/google/ExoPlayer/issues/1606.
            // Note that the suppressRead() check means only a single deferred retry can occur without
            // progress being made. Any subsequent failures without progress will go through the else
            // block below.
            pendingDeferredRetry = true
            return false
        } else {
            // This is the same case as above, except in this case there's no value in deferring the retry
            // because there's no buffered data to be read. This case also covers an on-demand stream with
            // unknown length that has yet to be prepared. This case cannot be disambiguated from the live
            // stream case, so we have no option but to load from the start.
            notifyDiscontinuity = prepared
            lastSeekPositionUs = 0
            extractedSamplesCountAtStartOfLoad = 0
            for (sampleQueue in sampleQueues) {
                sampleQueue.reset()
            }
            loadable.setLoadPosition(0, 0)
            return true
        }
    }

    private fun seekInsideBufferUs(
        trackIsAudioVideoFlags: BooleanArray, positionUs: Long, isSameAsLastSeekPosition: Boolean
    ): Boolean {
        val trackCount = sampleQueues.size
        for (i in 0..<trackCount) {
            val sampleQueue = sampleQueues[i]
            // For the tracks that do not get buffered, we don't need to seek.
            if (!controlledTrackOutputs[i]!!.isSelected) {
                continue
            }

            if (sampleQueue.readIndex == 0 && isSameAsLastSeekPosition) {
                continue
            }
            val seekInsideQueue =
                if (isSingleSample)
                    sampleQueue.seekTo(sampleQueue.firstIndex)
                else
                    sampleQueue.seekTo(positionUs,  loadingFinished)
            // If we have AV tracks then an in-buffer seek is successful if the seek into every AV queue
            // is successful. We ignore whether seeks within non-AV queues are successful in this case, as
            // they may be sparse or poorly interleaved. If we only have non-AV tracks then a seek is
            // successful only if the seek into every queue succeeds.
            if (!seekInsideQueue && (trackIsAudioVideoFlags[i] || !haveAudioVideoTracks)) {
                return false
            }
        }
        return true
    }

    private val extractedSamplesCount: Int
        get() {
            var extractedSamplesCount = 0
            for (sampleQueue in sampleQueues) {
                extractedSamplesCount += sampleQueue.writeIndex
            }
            return extractedSamplesCount
        }

    private fun getLargestQueuedTimestampUs(includeDisabledTracks: Boolean): Long {
        var largestQueuedTimestampUs = Long.MIN_VALUE
        for (i in sampleQueues.indices) {
            if (includeDisabledTracks || trackState!!.trackEnabledStates[i]) {
                largestQueuedTimestampUs =
                    max(largestQueuedTimestampUs, sampleQueues[i].largestQueuedTimestampUs)
            }
        }
        return largestQueuedTimestampUs
    }

    private val isPendingReset: Boolean
        get() = pendingResetPositionUs != C.TIME_UNSET

    private fun assertPrepared() {
        Preconditions.checkState(prepared)
        trackState!!
        seekMap!!
    }

    inner class SampleStreamImpl(val track: Int, val hasPreroll: Boolean) :
        SampleStream {
        override val isReady: Boolean
            get() = this@ProgressiveMediaPeriod.isReady(track)

        @Throws(IOException::class)
        override fun maybeThrowError() {
            this@ProgressiveMediaPeriod.maybeThrowError(track)
        }

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): Int {
            return this@ProgressiveMediaPeriod.readData(track, formatHolder, buffer, readFlags)
        }

        override fun skipData(positionUs: Long): Int {
            return this@ProgressiveMediaPeriod.skipData(track, positionUs)
        }

        override val flags: @SampleStream.Flags Int
            get() = if (hasPreroll) SampleStream.FLAG_HAS_PREROLL else 0

    }

    internal inner class ExtractingLoadable(
        val uri: Uri,
        dataSource: DataSource,
        val progressiveMediaExtractor: ProgressiveMediaExtractor,
        val extractorOutput: ExtractorOutput,
        val loadCondition: ConditionVariable
    ) : Loadable, IcyDataSource.Listener {
        val loadTaskId: Long = LoadEventInfo.newId
        val dataSource: StatsDataSource = StatsDataSource(dataSource)
        val positionHolder: PositionHolder = PositionHolder()

        @Volatile
        var loadCanceled = false

        var pendingExtractorSeek: Boolean = true
        var seekTimeUs: Long = 0
        var dataSpec: DataSpec
        var icyTrackOutput: TrackOutput? = null
        var seenIcyMetadata = false

        init {
            dataSpec = buildDataSpec( 0,  null)
        }

        // Loadable implementation.
        override fun cancelLoad() {
            loadCanceled = true
        }

        @Throws(IOException::class)
        override fun load() {
            var result = Extractor.RESULT_CONTINUE
            var etag: String? = null
            while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
                try {
                    var position = positionHolder.position
                    dataSpec = buildDataSpec(position, etag)
                    var length = dataSource.open(dataSpec)
                    if (loadCanceled) {
                        break
                    }
                    val etags: List<String>? = dataSource.responseHeaders[HttpHeaders.ETAG]
                    etag = if (!etags.isNullOrEmpty()) etags[0] else null
                    if (length != C.LENGTH_UNSET.toLong()) {
                        length += position
                        onLengthKnown()
                    }
                    icyHeaders = IcyHeaders.parse(dataSource.responseHeaders)
                    var extractorDataSource: DataSource = dataSource
                    if (icyHeaders != null && icyHeaders!!.metadataInterval != C.LENGTH_UNSET) {
                        extractorDataSource =
                            IcyDataSource(dataSource, icyHeaders!!.metadataInterval, this)
                        icyTrackOutput = icyTrack()
                        icyTrackOutput!!.format(ICY_FORMAT)
                    }
                    progressiveMediaExtractor.init(
                        extractorDataSource,
                        uri,
                        dataSource.responseHeaders,
                        position,
                        length,
                        extractorOutput
                    )

                    if (icyHeaders != null) {
                        progressiveMediaExtractor.disableSeekingOnMp3Streams()
                    }

                    if (pendingExtractorSeek) {
                        progressiveMediaExtractor.seek(position, seekTimeUs)
                        pendingExtractorSeek = false
                    }
                    while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
                        try {
                            loadCondition.block()
                        } catch (e: InterruptedException) {
                            throw InterruptedIOException()
                        }
                        result = progressiveMediaExtractor.read(positionHolder)
                        val currentInputPosition =
                            progressiveMediaExtractor.currentInputPosition
                        if (currentInputPosition > position + continueLoadingCheckIntervalBytes) {
                            position = currentInputPosition
                            loadCondition.close()
                            handler.post(onContinueLoadingRequestedRunnable)
                        }
                    }
                } finally {
                    if (result == Extractor.RESULT_SEEK) {
                        result = Extractor.RESULT_CONTINUE
                    } else if (progressiveMediaExtractor.currentInputPosition != C.INDEX_UNSET.toLong()) {
                        positionHolder.position =
                            progressiveMediaExtractor.currentInputPosition
                    }
                    closeQuietly(dataSource)
                }
            }
        }

        // IcyDataSource.Listener
        override fun onIcyMetadata(metadata: ParsableByteArray) {
            // Always output the first ICY metadata at the start time. This helps minimize any delay
            // between the start of playback and the first ICY metadata event.
            val timeUs =
                if (!seenIcyMetadata)
                    seekTimeUs
                else
                    max(getLargestQueuedTimestampUs( true), seekTimeUs)
            val length = metadata.bytesLeft()
            val icyTrackOutput = this.icyTrackOutput!!
            icyTrackOutput.sampleData(metadata, length)
            icyTrackOutput.sampleMetadata(
                timeUs, C.BUFFER_FLAG_KEY_FRAME, length,  0,  null
            )
            seenIcyMetadata = true
        }

        // Internal methods.
        private fun buildDataSpec(position: Long, etag: String?): DataSpec {
            var requestHeaders: MutableMap<String, String> = ICY_METADATA_HEADERS
            if (etag != null && !etag.startsWith("W/")) {
                requestHeaders = mutableMapOf<String, String>().apply {
                    putAll(requestHeaders)
                    put(HttpHeaders.IF_RANGE, etag)
                }
            }
            return DataSpec.Builder()
                .setUri(uri)
                .setPosition(position)
                .setKey(customCacheKey) // Disable caching if the content length cannot be resolved, since this is indicative of a
                // progressive live stream.
                .setFlags(
                    DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION
                )
                .setHttpRequestHeaders(requestHeaders)
                .build()
        }

        fun setLoadPosition(position: Long, timeUs: Long) {
            positionHolder.position = position
            seekTimeUs = timeUs
            pendingExtractorSeek = true
            seenIcyMetadata = false
        }
    }

    private class TrackState(
        val tracks: TrackGroupArray,
        val trackIsAudioVideoFlags: BooleanArray
    ) {
        val trackEnabledStates: BooleanArray = BooleanArray(tracks.length)
        val trackNotifiedDownstreamFormats: BooleanArray = BooleanArray(tracks.length)
    }

    private class TrackId(val id: Int, val isIcyTrack: Boolean) {
        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj == null || javaClass != obj.javaClass) {
                return false
            }
            val other: TrackId = obj as TrackId
            return id == other.id && isIcyTrack == other.isIcyTrack
        }

        override fun hashCode(): Int {
            return 31 * id + (if (isIcyTrack) 1 else 0)
        }
    }

    private class ControlledTrackOutput(private val sampleQueue: SampleQueue) :
        ForwardingTrackOutput(sampleQueue) {
        private val discardingTrackOutput: DiscardingTrackOutput = DiscardingTrackOutput()
        private val outputMode: AtomicReference<OutputMode> =
            AtomicReference<OutputMode>(OutputMode.PASS_THROUGH)

        enum class OutputMode {
            PASS_THROUGH,
            DISCARD_AFTER_NEXT_SAMPLE_METADATA,
            DISCARDING,
        }

        @Throws(IOException::class)
        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean
        ): Int {
            return this.currentOutput.sampleData(input, length, allowEndOfInput)
        }

        @Throws(IOException::class)
        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: @TrackOutput.SampleDataPart Int
        ): Int {
            return this.currentOutput.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }

        override fun sampleData(data: ParsableByteArray, length: Int) {
            this.currentOutput.sampleData(data, length)
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: @TrackOutput.SampleDataPart Int
        ) {
            this.currentOutput.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: @C.BufferFlags Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            this.currentOutput.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            if (outputMode.get() == OutputMode.DISCARD_AFTER_NEXT_SAMPLE_METADATA) {
                sampleQueue.reset()
                outputMode.set(OutputMode.DISCARDING)
            }
        }

        fun updateSelectionState(selected: Boolean) {
            // Since there could still be some samples within the internal SampleDataQueue, we will be
            // confident about releasing them all after the next sample metadata is received.
            outputMode.set(
                if (selected) OutputMode.PASS_THROUGH else OutputMode.DISCARD_AFTER_NEXT_SAMPLE_METADATA
            )
            // In case the existing samples are taking too much memory, preventing further load, release
            // them optimistically.
            if (!selected) {
                sampleQueue.discardToEnd()
            }
        }

        val isSelected: Boolean
            get() = outputMode.get() == OutputMode.PASS_THROUGH

        val currentOutput: TrackOutput
            get() = if (outputMode.get() == OutputMode.DISCARDING) discardingTrackOutput else sampleQueue
    }

    companion object {
        private const val TAG = "ProgressiveMediaPeriod"
        private const val DEFAULT_LAST_SAMPLE_DURATION_US: Long = 10000
        private val ICY_METADATA_HEADERS: MutableMap<String, String> = createIcyMetadataHeaders()
        private val ICY_FORMAT =
            Format.Builder().setId("icy").setSampleMimeType(MimeTypes.APPLICATION_ICY).build()

        private fun createIcyMetadataHeaders(): MutableMap<String, String> {
            val headers: MutableMap<String, String> = HashMap()
            headers[IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_NAME] =
                IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_VALUE
            return Collections.unmodifiableMap(headers)
        }

        private fun getTrackTypePriority(trackType: @C.TrackType Int): Int {
            return when (trackType) {
                C.TRACK_TYPE_VIDEO -> 4
                C.TRACK_TYPE_AUDIO -> 3
                C.TRACK_TYPE_IMAGE -> 2
                C.TRACK_TYPE_TEXT -> 1
                else -> 0
            }
        }
    }
}
