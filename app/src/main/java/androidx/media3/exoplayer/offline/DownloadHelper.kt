package androidx.media3.exoplayer.offline

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.SparseIntArray
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererCapabilitiesList
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.BaseTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.SelectionOverride
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.trackselection.TrackSelectionUtil
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import com.google.common.base.Preconditions
import com.google.common.base.Supplier

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import java.util.Collections
import kotlin.math.min

class DownloadHelper(
    mediaItem: MediaItem,
    private val mediaSource: MediaSource?,
    trackSelectionParameters: TrackSelectionParameters,
    private val rendererCapabilities: RendererCapabilitiesList,
    private val debugLoggingEnabled: Boolean = false
) {
    class Factory {
        private var dataSourceFactory: DataSource.Factory? = null
        private var renderersFactory: RenderersFactory? = null
        private var trackSelectionParameters: TrackSelectionParameters? =
            DEFAULT_TRACK_SELECTOR_PARAMETERS
        private var drmSessionManager: DrmSessionManager? = null
        private var loadExecutorSupplier: Supplier<ReleasableExecutor>? =
            null
        private var debugLoggingEnabled = false

        fun setDataSourceFactory(dataSourceFactory: DataSource.Factory?): Factory {
            this.dataSourceFactory = dataSourceFactory
            return this
        }

        fun setRenderersFactory(renderersFactory: RenderersFactory?): Factory {
            this.renderersFactory = renderersFactory
            return this
        }

        fun setTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters?): Factory {
            this.trackSelectionParameters = trackSelectionParameters
            return this
        }

        fun setDrmSessionManager(drmSessionManager: DrmSessionManager?): Factory {
            this.drmSessionManager = drmSessionManager
            return this
        }

        fun setLoadExecutor(loadExecutor: Supplier<ReleasableExecutor>?): Factory {
            this.loadExecutorSupplier = loadExecutor
            return this
        }

        fun setDebugLoggingEnabled(debugLoggingEnabled: Boolean): Factory {
            this.debugLoggingEnabled = debugLoggingEnabled
            return this
        }

        fun create(mediaItem: MediaItem): DownloadHelper {
            val isProgressive: Boolean =
                isProgressive(mediaItem.localConfiguration!!)
            Preconditions.checkArgument(isProgressive || dataSourceFactory != null)
            return DownloadHelper(
                mediaItem,
                if (isProgressive && dataSourceFactory == null)
                    null
                else
                    createMediaSourceInternal(
                        mediaItem,
                        dataSourceFactory!!,
                        drmSessionManager,
                        loadExecutorSupplier
                    ),
                trackSelectionParameters!!,
                if (renderersFactory != null)
                    DefaultRendererCapabilitiesList.Factory(renderersFactory!!)
                        .createRendererCapabilitiesList()
                else
                    UnreleaseableRendererCapabilitiesList(arrayOf()),
                debugLoggingEnabled
            )
        }

        fun create(mediaSource: MediaSource): DownloadHelper {
            return DownloadHelper(
                mediaSource.mediaItem,
                mediaSource,
                trackSelectionParameters!!,
                if (renderersFactory != null)
                    DefaultRendererCapabilitiesList.Factory(renderersFactory!!)
                        .createRendererCapabilitiesList()
                else
                    UnreleaseableRendererCapabilitiesList(arrayOf()),
                debugLoggingEnabled
            )
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [MODE_NOT_PREPARE, MODE_PREPARE_PROGRESSIVE_SOURCE, MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS]
    )
    private annotation class Mode

    interface Callback {
        fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean)
        fun onPrepareError(helper: DownloadHelper, e: IOException)
    }

    class LiveContentUnsupportedException : IOException()

    private val localConfiguration = mediaItem.localConfiguration!!
    private val mode: @Mode Int = when (mediaSource) {
        null -> MODE_NOT_PREPARE
        is ProgressiveMediaSource -> MODE_PREPARE_PROGRESSIVE_SOURCE
        else -> MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS
    }
    private val trackSelector =
        DefaultTrackSelector(trackSelectionParameters, DownloadTrackSelection.Factory())
    private val scratchSet = SparseIntArray()
    private val callbackHandler = Util.createHandlerForCurrentOrMainLooper()
    private val window = Timeline.Window()

    private var isPreparedWithMedia = false
    private var areTracksSelected = false
    private var callback: Callback? = null
    private var mediaPreparer: MediaPreparer? = null
    private var trackGroupArrays: Array<TrackGroupArray>? = null
    private var mappedTrackInfos: Array<MappedTrackInfo>? = null
    private var trackSelectionsByPeriodAndRenderer: Array<Array<MutableList<ExoTrackSelection>>?>? =
        null
    private var immutableTrackSelectionsByPeriodAndRenderer: Array<Array<MutableList<ExoTrackSelection>>>? =
        null

    init {
        trackSelector.init({ parameters: TrackSelectionParameters? ->
            trackSelector.onParametersActivated(
                parameters
            )
        }, BandwidthMeter.NO_OP)
    }

    fun prepare(callback: Callback) {
        Preconditions.checkState(this.callback == null)
        this.callback = callback
        if (mode != MODE_NOT_PREPARE) {
            mediaPreparer = MediaPreparer(
                mediaSource!!,
                this
            )
        } else {
            callbackHandler.post(Runnable {
                callback.onPrepared(
                    this,
                    false
                )
            })
        }
    }

    fun release() {
        mediaPreparer?.release()
        trackSelector.release()
        rendererCapabilities.release()
    }

    val manifest: Any?
        get() {
            if (mode == MODE_NOT_PREPARE) {
                return null
            }
            assertPreparedWithMedia()
            val timeLine = mediaPreparer!!.timeline!!
            return if (timeLine.windowCount > 0)
                timeLine.getWindow(0, window).manifest
            else null
        }

    val periodCount: Int
        get() {
            if (mode == MODE_NOT_PREPARE) {
                return 0
            }
            assertPreparedWithMedia()
            return mediaPreparer!!.mediaPeriods!!.size
        }

    fun getTracks(periodIndex: Int): Tracks {
        assertPreparedWithNonProgressiveSourceAndTracksSelected()
        return TrackSelectionUtil.buildTracks(
            mappedTrackInfos!![periodIndex],
            immutableTrackSelectionsByPeriodAndRenderer!![periodIndex]
        )
    }

    fun getTrackGroups(periodIndex: Int): TrackGroupArray {
        assertPreparedWithNonProgressiveSourceAndTracksSelected()
        return trackGroupArrays!![periodIndex]
    }

    fun getMappedTrackInfo(periodIndex: Int): MappedTrackInfo {
        assertPreparedWithNonProgressiveSourceAndTracksSelected()
        return mappedTrackInfos!![periodIndex]
    }

    fun getTrackSelections(periodIndex: Int, rendererIndex: Int): MutableList<ExoTrackSelection> {
        assertPreparedWithNonProgressiveSourceAndTracksSelected()
        return immutableTrackSelectionsByPeriodAndRenderer!![periodIndex][rendererIndex]
    }

    fun clearTrackSelections(periodIndex: Int) {
        assertPreparedWithNonProgressiveSourceAndTracksSelected()
        for (i in 0..<rendererCapabilities.size()) {
            trackSelectionsByPeriodAndRenderer!![periodIndex]!![i].clear()
        }
    }

    fun replaceTrackSelections(
        periodIndex: Int, trackSelectionParameters: TrackSelectionParameters
    ) {
        try {
            assertPreparedWithNonProgressiveSourceAndTracksSelected()
            clearTrackSelections(periodIndex)
            addTrackSelectionInternal(periodIndex, trackSelectionParameters)
        } catch (e: ExoPlaybackException) {
            throw IllegalStateException(e)
        }
    }

    fun addTrackSelection(
        periodIndex: Int, trackSelectionParameters: TrackSelectionParameters
    ) {
        try {
            assertPreparedWithNonProgressiveSourceAndTracksSelected()
            addTrackSelectionInternal(periodIndex, trackSelectionParameters)
        } catch (e: ExoPlaybackException) {
            throw IllegalStateException(e)
        }
    }

    fun addAudioLanguagesToSelection(vararg languages: String?) {
        try {
            assertPreparedWithNonProgressiveSourceAndTracksSelected()
            val parametersBuilder: TrackSelectionParameters.Builder =
                DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon()
            // Prefer highest supported bitrate for downloads.
            parametersBuilder.setForceHighestSupportedBitrate(true)
            // Disable all non-audio track types supported by the renderers.
            for (capabilities in rendererCapabilities.rendererCapabilities!!) {
                val trackType: @C.TrackType Int = capabilities.trackType
                parametersBuilder.setTrackTypeDisabled(
                    trackType, trackType != C.TRACK_TYPE_AUDIO
                )
            }

            // Add a track selection to each period for each of the languages.
            val periodCount = this.periodCount
            for (language in languages) {
                val parameters =
                    parametersBuilder.setPreferredAudioLanguage(language).build()
                for (periodIndex in 0..<periodCount) {
                    addTrackSelectionInternal(periodIndex, parameters)
                }
            }
        } catch (e: ExoPlaybackException) {
            throw IllegalStateException(e)
        }
    }

    fun addTextLanguagesToSelection(
        selectUndeterminedTextLanguage: Boolean, vararg languages: String?
    ) {
        try {
            assertPreparedWithNonProgressiveSourceAndTracksSelected()

            val parametersBuilder: TrackSelectionParameters.Builder =
                DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon()
            parametersBuilder.setSelectUndeterminedTextLanguage(selectUndeterminedTextLanguage)
            // Prefer highest supported bitrate for downloads.
            parametersBuilder.setForceHighestSupportedBitrate(true)
            // Disable all non-text track types supported by the renderers.
            for (capabilities in rendererCapabilities.rendererCapabilities!!) {
                val trackType: @C.TrackType Int = capabilities.trackType
                parametersBuilder.setTrackTypeDisabled(
                    trackType, trackType != C.TRACK_TYPE_TEXT
                )
            }

            // Add a track selection to each period for each of the languages.
            val periodCount = this.periodCount
            for (language in languages) {
                val parameters =
                    parametersBuilder.setPreferredTextLanguage(language).build()
                for (periodIndex in 0..<periodCount) {
                    addTrackSelectionInternal(periodIndex, parameters)
                }
            }
        } catch (e: ExoPlaybackException) {
            throw IllegalStateException(e)
        }
    }

    fun addTrackSelectionForSingleRenderer(
        periodIndex: Int,
        rendererIndex: Int,
        trackSelectorParameters: DefaultTrackSelector.Parameters,
        overrides: List<SelectionOverride?>
    ) {
        try {
            assertPreparedWithNonProgressiveSourceAndTracksSelected()
            val builder = trackSelectorParameters.buildUpon()
            for (i in 0..<mappedTrackInfos!![periodIndex].rendererCount) {
                builder.setRendererDisabled(
                    i,
                    i != rendererIndex
                )
            }
            if (overrides.isEmpty()) {
                addTrackSelectionInternal(periodIndex, builder.build())
            } else {
                val trackGroupArray =
                    mappedTrackInfos!![periodIndex].getTrackGroups(rendererIndex)
                for (i in overrides.indices) {
                    builder.setSelectionOverride(rendererIndex, trackGroupArray, overrides[i])
                    addTrackSelectionInternal(periodIndex, builder.build())
                }
            }
        } catch (e: ExoPlaybackException) {
            throw IllegalStateException(e)
        }
    }

    fun getDownloadRequest(data: ByteArray?): DownloadRequest {
        return getDownloadRequest(localConfiguration.uri.toString(), data)
    }

    fun getDownloadRequest(
        data: ByteArray?, startPositionMs: Long, durationMs: Long
    ): DownloadRequest {
        return getDownloadRequest(
            localConfiguration.uri.toString(),
            data,
            startPositionMs,
            durationMs
        )
    }

    fun getDownloadRequest(id: String, data: ByteArray?): DownloadRequest {
        return getDownloadRequestBuilder(id, data).build()
    }

    fun getDownloadRequest(
        id: String, data: ByteArray?, startPositionMs: Long, durationMs: Long
    ): DownloadRequest {
        val builder = getDownloadRequestBuilder(id, data)
        assertPreparedWithMedia()
        populateDownloadRequestBuilderWithDownloadRange(builder, startPositionMs, durationMs)
        return builder.build()
    }

    private fun getDownloadRequestBuilder(id: String, data: ByteArray?): DownloadRequest.Builder {
        val requestBuilder =
            DownloadRequest.Builder(id, localConfiguration.uri)
                .setMimeType(localConfiguration.mimeType)
                .setKeySetId(localConfiguration.drmConfiguration?.getKeySetId())
                .setCustomCacheKey(localConfiguration.customCacheKey)
                .setData(data)
        if (mode == MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS) {
            assertPreparedWithNonProgressiveSourceAndTracksSelected()
            val streamKeys: MutableList<StreamKey> = ArrayList()
            val allSelections: MutableList<ExoTrackSelection> = ArrayList()
            val periodCount = trackSelectionsByPeriodAndRenderer!!.size
            for (periodIndex in 0..<periodCount) {
                allSelections.clear()
                val rendererCount = trackSelectionsByPeriodAndRenderer!![periodIndex]!!.size
                for (rendererIndex in 0..<rendererCount) {
                    allSelections.addAll(trackSelectionsByPeriodAndRenderer!![periodIndex]!![rendererIndex])
                }
                streamKeys.addAll(
                    mediaPreparer!!.mediaPeriods!![periodIndex].getStreamKeys(
                        allSelections
                    )
                )
            }
            requestBuilder.setStreamKeys(streamKeys)
        }
        return requestBuilder
    }

    private fun populateDownloadRequestBuilderWithDownloadRange(
        requestBuilder: DownloadRequest.Builder, startPositionMs: Long, durationMs: Long
    ) {
        when (mode) {
            MODE_PREPARE_PROGRESSIVE_SOURCE -> populateDownloadRequestBuilderWithByteRange(
                requestBuilder,
                startPositionMs,
                durationMs
            )

            MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS -> populateDownloadRequestBuilderWithTimeRange(
                requestBuilder,
                startPositionMs,
                durationMs
            )

            else -> {}
        }
    }

    private fun populateDownloadRequestBuilderWithByteRange(
        requestBuilder: DownloadRequest.Builder, startPositionMs: Long, durationMs: Long
    ) {
        assertPreparedWithProgressiveSource()
        val timeline = mediaPreparer!!.timeline!!
        val window = Timeline.Window()
        val period = Timeline.Period()
        val periodStartPositionUs: Long =
            timeline.getPeriodPositionUs(
                window,
                period,
                0,
                Util.msToUs(startPositionMs)
            )
                .second!!

        var periodEndPositionUs = C.TIME_UNSET
        if (durationMs != C.TIME_UNSET) {
            periodEndPositionUs = periodStartPositionUs + Util.msToUs(durationMs)
            if (period.durationUs != C.TIME_UNSET) {
                periodEndPositionUs = min(periodEndPositionUs, period.durationUs - 1)
            }
        }

        // SeekMap should be available for prepared progressive media.
        val seekMap = mediaPreparer!!.seekMap!!
        if (seekMap.isSeekable) {
            val byteRangeStartPositionOffset =
                seekMap.getSeekPoints(periodStartPositionUs)!!.first.position
            var byteRangeLength = C.LENGTH_UNSET.toLong()
            if (periodEndPositionUs != C.TIME_UNSET) {
                val byteRangeEndPositionOffset =
                    seekMap.getSeekPoints(periodEndPositionUs)!!.second.position
                // When the start and end positions are after the last seek point, they will both have only
                // that one mapped seek point. Then we should download from that seek point to the end of
                // the media, otherwise nothing will be downloaded as the resolved length is 0.
                val areStartAndEndPositionsAfterTheLastSeekPoint =
                    periodStartPositionUs != periodEndPositionUs
                            && byteRangeStartPositionOffset == byteRangeEndPositionOffset
                byteRangeLength =
                    if (!areStartAndEndPositionsAfterTheLastSeekPoint)
                        byteRangeEndPositionOffset - byteRangeStartPositionOffset
                    else
                        C.LENGTH_UNSET.toLong()
            }
            requestBuilder.setByteRange(byteRangeStartPositionOffset, byteRangeLength)
        } else {
            w(TAG, "Cannot set download byte range for progressive stream that is unseekable")
        }
    }

    private fun populateDownloadRequestBuilderWithTimeRange(
        requestBuilder: DownloadRequest.Builder, startPositionMs: Long, durationMs: Long
    ) {
        assertPreparedWithNonProgressiveSourceAndTracksSelected()
        val timeline = mediaPreparer!!.timeline
        val window = timeline!!.getWindow(0, Timeline.Window())

        var startPositionUs =
            if (startPositionMs == C.TIME_UNSET)
                window.defaultPositionUs
            else
                Util.msToUs(startPositionMs)
        val windowDurationUs = window.durationUs
        var durationUs =
            if (durationMs == C.TIME_UNSET) windowDurationUs else Util.msToUs(durationMs)
        if (windowDurationUs != C.TIME_UNSET) {
            startPositionUs = min(startPositionUs, windowDurationUs)
            durationUs = min(durationUs, windowDurationUs - startPositionUs)
        }
        requestBuilder.setTimeRange(startPositionUs, durationUs)
    }

    @RequiresNonNull(
        "trackGroupArrays",
        "trackSelectionsByPeriodAndRenderer",
        "mediaPreparer",
        "mediaPreparer.timeline"
    )
    @Throws(ExoPlaybackException::class)
    private fun addTrackSelectionInternal(
        periodIndex: Int, trackSelectionParameters: TrackSelectionParameters
    ) {
        trackSelector.parameters = (trackSelectionParameters)
        runTrackSelection(periodIndex)
        // TrackSelectionParameters can contain multiple overrides for each track type. The track
        // selector will only use one of them (because it's designed for playback), but for downloads we
        // want to use all of them. Run selection again with each override being the only one of its
        // type, to ensure that all of the desired tracks are included.
        for (override in trackSelectionParameters.overrides.values) {
            trackSelector.parameters = (
                    trackSelectionParameters.buildUpon().setOverrideForType(override).build()
                    )
            runTrackSelection(periodIndex)
        }
    }

    @Throws(ExoPlaybackException::class)  // Initialization of array of Lists.
    private fun onMediaPrepared() {
        mediaPreparer!!
        mediaPreparer!!.mediaPeriods!!
        mediaPreparer!!.timeline
        val tracksInfoAvailable: Boolean
        if (mode == MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS) {
            val periodCount = mediaPreparer!!.mediaPeriods!!.size
            val rendererCount = rendererCapabilities.size()
            trackSelectionsByPeriodAndRenderer =
                Array<Array<MutableList<*>?>?>(periodCount) {
                    arrayOfNulls<MutableList<*>>(
                        rendererCount
                    )
                } as Array<Array<MutableList<ExoTrackSelection>>?>
            immutableTrackSelectionsByPeriodAndRenderer =
                Array<Array<MutableList<*>?>?>(periodCount) {
                    arrayOfNulls<MutableList<*>>(
                        rendererCount
                    )
                } as Array<Array<MutableList<ExoTrackSelection>>>
            for (i in 0..<periodCount) {
                for (j in 0..<rendererCount) {
                    trackSelectionsByPeriodAndRenderer!![i]!![j] = ArrayList<ExoTrackSelection>()
                    immutableTrackSelectionsByPeriodAndRenderer!![i]!![j] =
                        Collections.unmodifiableList<ExoTrackSelection?>(
                            trackSelectionsByPeriodAndRenderer!![i]!![j]
                        )
                }
            }
            val trackGroupArrays = arrayOfNulls<TrackGroupArray>(periodCount)
            val mappedTrackInfos = arrayOfNulls<MappedTrackInfo>(periodCount)
            for (i in 0..<periodCount) {
                trackGroupArrays[i] = mediaPreparer!!.mediaPeriods!![i].trackGroups
                val trackSelectorResult = runTrackSelection(i)
                trackSelector.onSelectionActivated(trackSelectorResult.info)
                mappedTrackInfos[i] = trackSelector.currentMappedTrackInfo!!
            }
            this.trackGroupArrays = trackGroupArrays as Array<TrackGroupArray>
            this.mappedTrackInfos = mappedTrackInfos as Array<MappedTrackInfo>
            tracksInfoAvailable = true
            setPreparedWithNonProgressiveSourceAndTracksSelected()
        } else {
            Preconditions.checkState(mode == MODE_PREPARE_PROGRESSIVE_SOURCE)
            mediaPreparer!!.seekMap!!
            tracksInfoAvailable = false
            setPreparedWithProgressiveSource()
        }
        callbackHandler.post(Runnable {
            callback!!.onPrepared(this, tracksInfoAvailable)
        })
    }

    private fun onMediaPreparationFailed(error: IOException) {
        callbackHandler!!.post(Runnable {
            callback!!.onPrepareError(this, error)
        })
    }

    @RequiresNonNull(
        "trackGroupArrays",
        "mappedTrackInfos",
        "trackSelectionsByPeriodAndRenderer",
        "immutableTrackSelectionsByPeriodAndRenderer",
        "mediaPreparer",
        "mediaPreparer.timeline",
        "mediaPreparer.mediaPeriods"
    )
    private fun setPreparedWithNonProgressiveSourceAndTracksSelected() {
        isPreparedWithMedia = true
        areTracksSelected = true
    }

    @RequiresNonNull(
        "mediaPreparer",
        "mediaPreparer.timeline",
        "mediaPreparer.seekMap",
        "mediaPreparer.mediaPeriods"
    )
    private fun setPreparedWithProgressiveSource() {
        isPreparedWithMedia = true
    }

    private fun assertPreparedWithMedia() {
        Preconditions.checkState(mode != MODE_NOT_PREPARE)
        Preconditions.checkState(isPreparedWithMedia)
    }

    private fun assertPreparedWithNonProgressiveSourceAndTracksSelected() {
        Preconditions.checkState(mode == MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS)
        Preconditions.checkState(isPreparedWithMedia)
        Preconditions.checkState(areTracksSelected)
    }

    private fun assertPreparedWithProgressiveSource() {
        Preconditions.checkState(mode == MODE_PREPARE_PROGRESSIVE_SOURCE)
        Preconditions.checkState(isPreparedWithMedia)
    }

    @RequiresNonNull(
        "trackGroupArrays",
        "trackSelectionsByPeriodAndRenderer",
        "mediaPreparer",
        "mediaPreparer.timeline"
    )
    @Throws(ExoPlaybackException::class)
    private fun runTrackSelection(periodIndex: Int): TrackSelectorResult {
        val trackSelectorResult =
            trackSelector.selectTracks(
                rendererCapabilities.rendererCapabilities!!,
                trackGroupArrays!![periodIndex],
                MediaPeriodId(mediaPreparer!!.timeline!!.getUidOfPeriod(periodIndex)),
                mediaPreparer!!.timeline!!
            )
        for (i in 0..<trackSelectorResult.length) {
            val newSelection = trackSelectorResult.selections[i]
            if (newSelection == null) {
                continue
            }
            val existingSelectionList =
                trackSelectionsByPeriodAndRenderer!![periodIndex]!![i]
            var mergedWithExistingSelection = false
            for (j in existingSelectionList.indices) {
                val existingSelection = existingSelectionList.get(j)
                if (existingSelection.trackGroup!!.equals(newSelection.trackGroup)) {
                    // Merge with existing selection.
                    scratchSet.clear()
                    for (k in 0..<existingSelection.length()) {
                        scratchSet.put(existingSelection.getIndexInTrackGroup(k), 0)
                    }
                    for (k in 0..<newSelection.length()) {
                        scratchSet.put(newSelection.getIndexInTrackGroup(k), 0)
                    }
                    val mergedTracks = IntArray(scratchSet.size())
                    for (k in 0..<scratchSet.size()) {
                        mergedTracks[k] = scratchSet.keyAt(k)
                    }
                    existingSelectionList.set(
                        j, DownloadTrackSelection(existingSelection.trackGroup!!, mergedTracks)
                    )
                    mergedWithExistingSelection = true
                    break
                }
            }
            if (!mergedWithExistingSelection) {
                existingSelectionList.add(newSelection)
            }
        }
        if (debugLoggingEnabled) {
            logTrackSelectorResult(periodIndex, trackSelectorResult)
        }
        return trackSelectorResult
    }

    private class MediaPreparer
        (private val mediaSource: MediaSource, private val downloadHelper: DownloadHelper) :
        MediaSourceCaller, ProgressiveMediaSource.Listener, MediaPeriod.Callback, Handler.Callback {
        private val allocator: Allocator
        private val pendingMediaPeriods: ArrayList<MediaPeriod?>
        private val downloadHelperHandler: Handler
        private val mediaSourceThread: HandlerThread
        private val mediaSourceHandler: Handler

        var timeline: Timeline? = null
        var seekMap: SeekMap? = null
        var mediaPeriods: Array<MediaPeriod>? = null

        private var released = false

        init {
            allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
            pendingMediaPeriods = ArrayList<MediaPeriod?>()
            val downloadThreadHandler =
                Util.createHandlerForCurrentOrMainLooper(Handler.Callback { msg: Message? ->
                    this.handleDownloadHelperCallbackMessage(
                        msg!!
                    )
                })
            this.downloadHelperHandler = downloadThreadHandler
            mediaSourceThread = HandlerThread("ExoPlayer:DownloadHelper")
            mediaSourceThread.start()
            mediaSourceHandler = Util.createHandler(mediaSourceThread.getLooper(), this)
            mediaSourceHandler.sendEmptyMessage(MESSAGE_PREPARE_SOURCE)
        }

        fun release() {
            if (released) {
                return
            }
            released = true
            mediaSourceHandler.sendEmptyMessage(MESSAGE_RELEASE)
        }

        // Handler.Callback
        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                MESSAGE_PREPARE_SOURCE -> {
                    if (mediaSource is ProgressiveMediaSource) {
                        mediaSource.setListener(this)
                    }
                    mediaSource.prepareSource(
                        this,
                        PlayerId.UNSET,
                        BandwidthMeter.NO_OP
                    )
                    mediaSourceHandler.sendEmptyMessage(MESSAGE_CHECK_FOR_FAILURE)
                    return true
                }

                MESSAGE_CHECK_FOR_FAILURE -> {
                    try {
                        if (mediaPeriods == null) {
                            mediaSource.maybeThrowSourceInfoRefreshError()
                        } else {
                            var i = 0
                            while (i < pendingMediaPeriods.size) {
                                pendingMediaPeriods[i]!!.maybeThrowPrepareError()
                                i++
                            }
                        }
                        mediaSourceHandler.sendEmptyMessageDelayed(
                            MESSAGE_CHECK_FOR_FAILURE, 100
                        )
                    } catch (e: IOException) {
                        downloadHelperHandler
                            .obtainMessage(DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED, e)
                            .sendToTarget()
                    }
                    return true
                }

                MESSAGE_CONTINUE_LOADING -> {
                    val mediaPeriod = msg.obj as MediaPeriod
                    if (pendingMediaPeriods.contains(mediaPeriod)) {
                        mediaPeriod.continueLoading(
                            LoadingInfo.Builder().setPlaybackPositionUs(0).build()
                        )
                    }
                    return true
                }

                MESSAGE_RELEASE -> {
                    if (mediaPeriods != null) {
                        for (period in mediaPeriods) {
                            mediaSource.releasePeriod(period)
                        }
                    }
                    if (mediaSource is ProgressiveMediaSource) {
                        mediaSource.clearListener()
                    }
                    mediaSource.releaseSource(this)
                    mediaSourceHandler.removeCallbacksAndMessages(null)
                    mediaSourceThread.quit()
                    return true
                }

                else -> return false
            }
        }

        // MediaSource.MediaSourceCaller implementation.
        override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) {
            if (this.timeline != null) {
                // Ignore dynamic updates.
                return
            }
            if (timeline.getWindow(0, Timeline.Window()).isLive) {
                downloadHelperHandler
                    .obtainMessage(
                        DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED,
                        LiveContentUnsupportedException()
                    )
                    .sendToTarget()
                return
            }
            this.timeline = timeline
            val mediaPeriods = arrayOfNulls<MediaPeriod>(timeline.periodCount)
            for (i in mediaPeriods!!.indices) {
                val mediaPeriod =
                    mediaSource.createPeriod(
                        MediaPeriodId(timeline.getUidOfPeriod(i)),
                        allocator,
                        0
                    )
                mediaPeriods!![i] = mediaPeriod!!
                pendingMediaPeriods.add(mediaPeriod)
            }
            this.mediaPeriods = mediaPeriods as Array<MediaPeriod>
            for (mediaPeriod in mediaPeriods!!) {
                mediaPeriod.prepare(this, 0)
            }
        }

        // ProgressiveMediaSource.Listener implementation.
        override fun onSeekMap(source: MediaSource, seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        // MediaPeriod.Callback implementation.
        override fun onPrepared(mediaPeriod: MediaPeriod) {
            pendingMediaPeriods.remove(mediaPeriod)
            if (pendingMediaPeriods.isEmpty()) {
                mediaSourceHandler.removeMessages(MESSAGE_CHECK_FOR_FAILURE)
                downloadHelperHandler.sendEmptyMessage(DOWNLOAD_HELPER_CALLBACK_MESSAGE_PREPARED)
            }
        }

        override fun onContinueLoadingRequested(mediaPeriod: MediaPeriod) {
            if (pendingMediaPeriods.contains(mediaPeriod)) {
                mediaSourceHandler.obtainMessage(MESSAGE_CONTINUE_LOADING, mediaPeriod)
                    .sendToTarget()
            }
        }

        fun handleDownloadHelperCallbackMessage(msg: Message): Boolean {
            if (released) {
                // Stale message.
                return false
            }
            when (msg.what) {
                DOWNLOAD_HELPER_CALLBACK_MESSAGE_PREPARED -> {
                    try {
                        downloadHelper.onMediaPrepared()
                    } catch (e: ExoPlaybackException) {
                        downloadHelperHandler
                            .obtainMessage(
                                DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED, IOException(e)
                            )
                            .sendToTarget()
                    }
                    return true
                }

                DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED -> {
                    release()
                    downloadHelper.onMediaPreparationFailed(msg.obj!! as IOException)
                    return true
                }

                else -> return false
            }
        }

        companion object {
            private const val MESSAGE_PREPARE_SOURCE = 1
            private const val MESSAGE_CHECK_FOR_FAILURE = 2
            private const val MESSAGE_CONTINUE_LOADING = 3
            private const val MESSAGE_RELEASE = 4
            private const val DOWNLOAD_HELPER_CALLBACK_MESSAGE_PREPARED = 1
            private const val DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED = 2
        }
    }

    private class DownloadTrackSelection(trackGroup: TrackGroup, tracks: IntArray) :
        BaseTrackSelection(trackGroup, tracks) {
        class Factory : ExoTrackSelection.Factory {
            override fun createTrackSelections(
                definitions: Array<ExoTrackSelection.Definition?>,
                bandwidthMeter: BandwidthMeter,
                mediaPeriodId: MediaPeriodId,
                timeline: Timeline
            ): Array<ExoTrackSelection?> {
                val selections = arrayOfNulls<ExoTrackSelection>(definitions.size)
                for (i in definitions.indices) {
                    selections[i] =
                        if (definitions[i] == null)
                            null
                        else
                            DownloadTrackSelection(
                                definitions[i]!!.group,
                                definitions[i]!!.tracks
                            )
                }
                return selections
            }
        }

        override val selectedIndex: Int
            get() = 0

        override val selectionReason: @C.SelectionReason Int
            get() = C.SELECTION_REASON_UNKNOWN

        override val selectionData: Any?
            get() = null

        override fun updateSelectedTrack(
            playbackPositionUs: Long,
            bufferedDurationUs: Long,
            availableDurationUs: Long,
            queue: List<MediaChunk>,
            mediaChunkIterators: Array<MediaChunkIterator>
        ) {
            // Do nothing.
        }
    }

    private class UnreleaseableRendererCapabilitiesList
        (override val rendererCapabilities: Array<RendererCapabilities>) :
        RendererCapabilitiesList {

        override fun size(): Int {
            return rendererCapabilities.size
        }

        override fun release() {}
    }

    companion object {
        val DEFAULT_TRACK_SELECTOR_PARAMETERS: DefaultTrackSelector.Parameters =
            DefaultTrackSelector.Parameters.DEFAULT
                .buildUpon()
                .apply {
                    forceHighestSupportedBitrate = true
                }.setConstrainAudioChannelCountToDeviceCapabilities(false)
                .build()

        @Deprecated("Use {@link #DEFAULT_TRACK_SELECTOR_PARAMETERS} instead.")
        val DEFAULT_TRACK_SELECTOR_PARAMETERS_WITHOUT_CONTEXT: DefaultTrackSelector.Parameters =
            DEFAULT_TRACK_SELECTOR_PARAMETERS

        @Deprecated("Use {@link #DEFAULT_TRACK_SELECTOR_PARAMETERS} instead.")
        fun getDefaultTrackSelectorParameters(context: Context?): DefaultTrackSelector.Parameters {
            return DEFAULT_TRACK_SELECTOR_PARAMETERS
        }

        private const val MODE_NOT_PREPARE = 0
        private const val MODE_PREPARE_PROGRESSIVE_SOURCE = 1
        private const val MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS = 2

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(context: Context?, mediaItem: MediaItem): DownloadHelper {
            Preconditions.checkArgument(
                isProgressive(
                    mediaItem.localConfiguration!!
                )
            )
            return Factory().create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            context: Context?, mediaItem: MediaItem, dataSourceFactory: DataSource.Factory?
        ): DownloadHelper {
            return Factory().setDataSourceFactory(dataSourceFactory).create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            context: Context?,
            mediaItem: MediaItem,
            dataSourceFactory: DataSource.Factory?,
            debugLoggingEnabled: Boolean
        ): DownloadHelper {
            return Factory()
                .setDataSourceFactory(dataSourceFactory)
                .setDebugLoggingEnabled(debugLoggingEnabled)
                .create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            context: Context?,
            mediaItem: MediaItem,
            renderersFactory: RenderersFactory?,
            dataSourceFactory: DataSource.Factory?
        ): DownloadHelper {
            return Factory()
                .setDataSourceFactory(dataSourceFactory)
                .setRenderersFactory(renderersFactory)
                .create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            context: Context?,
            mediaItem: MediaItem,
            renderersFactory: RenderersFactory?,
            dataSourceFactory: DataSource.Factory?,
            debugLoggingEnabled: Boolean
        ): DownloadHelper {
            return Factory()
                .setDataSourceFactory(dataSourceFactory)
                .setRenderersFactory(renderersFactory)
                .setDebugLoggingEnabled(debugLoggingEnabled)
                .create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            mediaItem: MediaItem,
            trackSelectionParameters: TrackSelectionParameters?,
            renderersFactory: RenderersFactory?,
            dataSourceFactory: DataSource.Factory?
        ): DownloadHelper {
            return Factory()
                .setDataSourceFactory(dataSourceFactory)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setRenderersFactory(renderersFactory)
                .create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            mediaItem: MediaItem,
            trackSelectionParameters: TrackSelectionParameters?,
            renderersFactory: RenderersFactory?,
            dataSourceFactory: DataSource.Factory?,
            debugLoggingEnabled: Boolean
        ): DownloadHelper {
            return Factory()
                .setDataSourceFactory(dataSourceFactory)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setRenderersFactory(renderersFactory)
                .setDebugLoggingEnabled(debugLoggingEnabled)
                .create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            mediaItem: MediaItem,
            trackSelectionParameters: TrackSelectionParameters?,
            renderersFactory: RenderersFactory?,
            dataSourceFactory: DataSource.Factory?,
            drmSessionManager: DrmSessionManager?
        ): DownloadHelper {
            return Factory()
                .setDataSourceFactory(dataSourceFactory)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setRenderersFactory(renderersFactory)
                .setDrmSessionManager(drmSessionManager)
                .create(mediaItem)
        }

        @Deprecated("Use {@link Factory#create(MediaItem)} instead.")
        fun forMediaItem(
            mediaItem: MediaItem,
            trackSelectionParameters: TrackSelectionParameters?,
            renderersFactory: RenderersFactory?,
            dataSourceFactory: DataSource.Factory?,
            drmSessionManager: DrmSessionManager?,
            debugLoggingEnabled: Boolean
        ): DownloadHelper {
            return Factory()
                .setDataSourceFactory(dataSourceFactory)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setRenderersFactory(renderersFactory)
                .setDrmSessionManager(drmSessionManager)
                .setDebugLoggingEnabled(debugLoggingEnabled)
                .create(mediaItem)
        }

        fun createMediaSource(
            downloadRequest: DownloadRequest,
            dataSourceFactory: DataSource.Factory,
            drmSessionManager: DrmSessionManager? = null
        ): MediaSource? {
            return createMediaSourceInternal(
                downloadRequest.toMediaItem(),
                dataSourceFactory,
                drmSessionManager,
                null
            )
        }

        private const val TAG = "DownloadHelper"

        private fun createMediaSourceInternal(
            mediaItem: MediaItem,
            dataSourceFactory: DataSource.Factory,
            drmSessionManager: DrmSessionManager?,
            loadExecutorSupplier: Supplier<ReleasableExecutor>?
        ): MediaSource? {
            val mediaSourceFactory: MediaSource.Factory =
                if (isProgressive(
                        mediaItem.localConfiguration!!
                    )
                )
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                else
                    DefaultMediaSourceFactory(dataSourceFactory, ExtractorsFactory.EMPTY)
            if (loadExecutorSupplier != null) {
                mediaSourceFactory.setDownloadExecutor(loadExecutorSupplier)
            }
            if (drmSessionManager != null) {
                mediaSourceFactory.setDrmSessionManagerProvider(DrmSessionManagerProvider { unusedMediaItem: MediaItem? -> drmSessionManager })
            }
            return mediaSourceFactory.createMediaSource(mediaItem)
        }

        private fun isProgressive(localConfiguration: MediaItem.LocalConfiguration): Boolean {
            return (Util.inferContentTypeForUriAndMimeType(
                localConfiguration.uri, localConfiguration.mimeType
            )
                    == C.CONTENT_TYPE_OTHER)
        }

        private fun logTrackSelectorResult(
            periodIndex: Int, trackSelectorResult: TrackSelectorResult
        ) {
            d(TAG, "Track selections changed, period index: $periodIndex, tracks [")
            val trackGroups: List<Tracks.Group> = trackSelectorResult.tracks.groups
            for (groupIndex in trackGroups.indices) {
                val trackGroup = trackGroups[groupIndex]
                d(TAG, "  group [")
                for (trackIndex in 0..<trackGroup.length) {
                    val status = if (trackGroup.isTrackSelected(trackIndex)) "[X]" else "[ ]"
                    val formatSupport: String =
                        Util.getFormatSupportString(trackGroup.getTrackSupport(trackIndex))
                    d(
                        TAG,
                        ("    "
                                + status
                                + " Track:"
                                + trackIndex
                                + ", "
                                + Format.toLogString(trackGroup.getTrackFormat(trackIndex))
                                + ", supported="
                                + formatSupport)
                    )
                }
                d(TAG, "  ]")
            }
            d(TAG, "]")
        }
    }
}
