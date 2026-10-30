package androidx.media3.exoplayer.analytics

import android.annotation.SuppressLint
import android.content.Context
import android.media.DeniedByServerException
import android.media.MediaCodec
import android.media.MediaDrm.MediaDrmStateException
import android.media.MediaDrmResetException
import android.media.NotProvisionedException
import android.media.metrics.LogSessionId
import android.media.metrics.MediaMetricsManager
import android.media.metrics.NetworkEvent
import android.media.metrics.PlaybackErrorEvent
import android.media.metrics.PlaybackMetrics
import android.media.metrics.PlaybackSession
import android.media.metrics.PlaybackStateEvent
import android.media.metrics.TrackChangeEvent
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Pair
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.BackgroundExecutor
import androidx.media3.common.util.NetworkTypeObserver
import androidx.media3.common.util.Util.getErrorCodeForMediaDrmErrorCode
import androidx.media3.common.util.Util.getErrorCodeFromPlatformDiagnosticsInfo
import androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType
import androidx.media3.common.util.Util.split
import androidx.media3.datasource.FileDataSource.FileDataSourceException
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidContentTypeException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.UdpDataSource.UdpDataSourceException
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventFlags
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager.MissingSchemeDataException
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Preconditions
import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RequiresApi(31)
class MediaMetricsListener
private constructor(context: Context, private val playbackSession: PlaybackSession) :
    AnalyticsListener,
    PlaybackSessionManager.Listener {
    private val context: Context = context.applicationContext
    private val backgroundExecutor = BackgroundExecutor.get()
    private val sessionManager = DefaultPlaybackSessionManager()
    private val startTimeMs = SystemClock.elapsedRealtime()
    private val window = Timeline.Window()
    private val period = Timeline.Period()
    private val bandwidthTimeMs = mutableMapOf<String, Long>()
    private val bandwidthBytes = mutableMapOf<String, Long>()
    private var activeSessionId: String? = null
    private var metricsBuilder: PlaybackMetrics.Builder? = null

    @Player.DiscontinuityReason
    private var discontinuityReason: @Player.DiscontinuityReason Int = 0
    private var currentPlaybackState = PlaybackStateEvent.STATE_NOT_STARTED
    private var currentNetworkType = NetworkEvent.NETWORK_TYPE_UNKNOWN
    private var pendingPlayerError: PlaybackException? = null
    private var pendingVideoFormat: PendingFormatUpdate? = null
    private var pendingAudioFormat: PendingFormatUpdate? = null
    private var pendingTextFormat: PendingFormatUpdate? = null
    private var currentVideoFormat: Format? = null
    private var currentAudioFormat: Format? = null
    private var currentTextFormat: Format? = null
    private var isSeeking = false
    private var ioErrorType = 0
    private var hasFatalError = false
    private var droppedFrames = 0
    private var playedFrames = 0
    private var audioUnderruns = 0
    private var reportedEventsForCurrentSession = false

    init {
        sessionManager.setListener(this)
    }

    val logSessionId: LogSessionId
        get() = playbackSession.sessionId

    // PlaybackSessionManager.Listener implementation.
    override fun onSessionCreated(eventTime: EventTime, sessionId: String) {}

    override fun onSessionActive(eventTime: EventTime, sessionId: String) {
        if (eventTime.mediaPeriodId != null && eventTime.mediaPeriodId.isAd) {
            // Ignore ad sessions.
            return
        }
        finishCurrentSession()
        activeSessionId = sessionId
        metricsBuilder =
            PlaybackMetrics.Builder()
                .setPlayerName(MediaLibraryInfo.TAG)
                .setPlayerVersion(MediaLibraryInfo.VERSION)
        maybeUpdateTimelineMetadata(eventTime.timeline, eventTime.mediaPeriodId)
    }

    override fun onAdPlaybackStarted(
        eventTime: EventTime, contentSessionId: String, adSessionId: String
    ) {
    }

    override fun onSessionFinished(
        eventTime: EventTime, sessionId: String, automaticTransitionToNextPlayback: Boolean
    ) {
        if ((eventTime.mediaPeriodId != null && eventTime.mediaPeriodId.isAd)
            || sessionId != activeSessionId
        ) {
            // Ignore ad sessions and other sessions that are finished before becoming active.
        } else {
            finishCurrentSession()
        }
        bandwidthTimeMs.remove(sessionId)
        bandwidthBytes.remove(sessionId)
    }

    // AnalyticsListener implementation.
    override fun onPositionDiscontinuity(
        eventTime: EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            isSeeking = true
        }
        discontinuityReason = reason
    }

    override fun onVideoDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        // TODO(b/181122234): DecoderCounters are not re-reported at period boundaries.
        droppedFrames += decoderCounters.droppedBufferCount
        playedFrames += decoderCounters.renderedOutputBufferCount
    }

    override fun onBandwidthEstimate(
        eventTime: EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long
    ) {
        if (eventTime.mediaPeriodId != null) {
            val sessionId =
                sessionManager.getSessionForMediaPeriodId(
                    eventTime.timeline,
                    eventTime.mediaPeriodId
                )
            val prevBandwidthBytes = bandwidthBytes[sessionId]
            val prevBandwidthTimeMs = bandwidthTimeMs[sessionId]
            bandwidthBytes[sessionId] = (prevBandwidthBytes ?: 0) + totalBytesLoaded
            bandwidthTimeMs[sessionId] = (prevBandwidthTimeMs ?: 0) + totalLoadTimeMs
        }
    }

    override fun onDownstreamFormatChanged(eventTime: EventTime, mediaLoadData: MediaLoadData) {
        if (eventTime.mediaPeriodId == null) {
            // This event arrived after the media has been removed from the playlist or a custom
            // MediaSource forgot to set the right id. Ignore the track change in these cases.
            return
        }
        val update =
            PendingFormatUpdate(
                mediaLoadData.trackFormat!!,
                mediaLoadData.trackSelectionReason,
                sessionManager.getSessionForMediaPeriodId(
                    eventTime.timeline,
                    eventTime.mediaPeriodId
                )
            )
        when (mediaLoadData.trackType) {
            C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_DEFAULT -> pendingVideoFormat = update
            C.TRACK_TYPE_AUDIO -> pendingAudioFormat = update
            C.TRACK_TYPE_TEXT -> pendingTextFormat = update
            else -> {}
        }
    }

    override fun onVideoSizeChanged(eventTime: EventTime, videoSize: VideoSize) {
        val pendingVideoFormat = this.pendingVideoFormat
        if (pendingVideoFormat != null && pendingVideoFormat.format.height == Format.NO_VALUE) {
            val formatWithHeightAndWidth =
                pendingVideoFormat
                    .format
                    .buildUpon()
                    .setWidth(videoSize.width)
                    .setHeight(videoSize.height)
                    .build()
            this.pendingVideoFormat =
                PendingFormatUpdate(
                    formatWithHeightAndWidth,
                    pendingVideoFormat.selectionReason,
                    pendingVideoFormat.sessionId
                )
        }
    }

    override fun onLoadError(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        ioErrorType = mediaLoadData.dataType
    }

    override fun onPlayerError(eventTime: EventTime, error: PlaybackException) {
        pendingPlayerError = error
    }

    override fun onEvents(player: Player, events: AnalyticsListener.Events) {
        if (events.size() == 0) {
            return
        }
        maybeAddSessions(events)

        val realtimeMs = SystemClock.elapsedRealtime()
        maybeUpdateMetricsBuilderValues(player, events)
        maybeReportPlaybackError(realtimeMs)
        maybeReportTrackChanges(player, events, realtimeMs)
        maybeReportNetworkChange(realtimeMs)
        maybeReportPlaybackStateChange(player, events, realtimeMs)

        if (events.contains(AnalyticsListener.EVENT_PLAYER_RELEASED)) {
            sessionManager.finishAllSessions(events.getEventTime(AnalyticsListener.EVENT_PLAYER_RELEASED))
        }
    }

    private fun maybeAddSessions(events: AnalyticsListener.Events) {
        for (i in 0..<events.size()) {
            @EventFlags val event: @EventFlags Int = events.get(i)
            val eventTime = events.getEventTime(event)
            when (event) {
                AnalyticsListener.EVENT_TIMELINE_CHANGED -> {
                    sessionManager.updateSessionsWithTimelineChange(eventTime)
                }

                AnalyticsListener.EVENT_POSITION_DISCONTINUITY -> {
                    sessionManager.updateSessionsWithDiscontinuity(eventTime, discontinuityReason)
                }

                else -> {
                    sessionManager.updateSessions(eventTime)
                }
            }
        }
    }

    private fun maybeUpdateMetricsBuilderValues(player: Player, events: AnalyticsListener.Events) {
        if (events.contains(AnalyticsListener.EVENT_TIMELINE_CHANGED)) {
            val eventTime = events.getEventTime(AnalyticsListener.EVENT_TIMELINE_CHANGED)
            if (metricsBuilder != null) {
                maybeUpdateTimelineMetadata(eventTime.timeline, eventTime.mediaPeriodId)
            }
        }
        if (events.contains(AnalyticsListener.EVENT_TRACKS_CHANGED) && metricsBuilder != null) {
            val drmInitData: DrmInitData? = getDrmInitData(player.currentTracks!!.groups)
            if (drmInitData != null) {
                metricsBuilder!!.setDrmType(getDrmType(drmInitData))
            }
        }
        if (events.contains(AnalyticsListener.EVENT_AUDIO_UNDERRUN)) {
            audioUnderruns++
        }
    }

    private fun maybeReportPlaybackError(realtimeMs: Long) {
        val error = pendingPlayerError ?: return

        val errorInfo: ErrorInfo =
            getErrorInfo(error, context, ioErrorType == C.DATA_TYPE_MANIFEST)
        val playbackErrorEvent = PlaybackErrorEvent.Builder()
            .setTimeSinceCreatedMillis(realtimeMs - startTimeMs)
            .setErrorCode(errorInfo.errorCode)
            .setSubErrorCode(errorInfo.subErrorCode)
            .setException(error)
            .build()
        backgroundExecutor.execute {
            playbackSession.reportPlaybackErrorEvent(
                playbackErrorEvent
            )
        }
        reportedEventsForCurrentSession = true
        pendingPlayerError = null
    }

    private fun maybeReportTrackChanges(
        player: Player,
        events: AnalyticsListener.Events,
        realtimeMs: Long
    ) {
        if (events.contains(AnalyticsListener.EVENT_TRACKS_CHANGED)) {
            val tracks = player.currentTracks!!
            val isVideoSelected = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            val isAudioSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
            val isTextSelected = tracks.isTypeSelected(C.TRACK_TYPE_TEXT)
            if (isVideoSelected || isAudioSelected || isTextSelected) {
                // Ignore updates with insufficient information where no tracks are selected.
                if (!isVideoSelected) {
                    maybeUpdateVideoFormat(
                        realtimeMs,  
                        null,
                        C.SELECTION_REASON_UNKNOWN
                    )
                }
                if (!isAudioSelected) {
                    maybeUpdateAudioFormat(
                        realtimeMs,  
                        null,
                        C.SELECTION_REASON_UNKNOWN
                    )
                }
                if (!isTextSelected) {
                    maybeUpdateTextFormat(
                        realtimeMs,  
                        null,
                        C.SELECTION_REASON_UNKNOWN
                    )
                }
            }
        }
        if (canReportPendingFormatUpdate(pendingVideoFormat)
            && pendingVideoFormat!!.format.height != Format.NO_VALUE
        ) {
            maybeUpdateVideoFormat(
                realtimeMs, pendingVideoFormat!!.format, pendingVideoFormat!!.selectionReason
            )
            pendingVideoFormat = null
        }
        if (canReportPendingFormatUpdate(pendingAudioFormat)) {
            maybeUpdateAudioFormat(
                realtimeMs, pendingAudioFormat!!.format, pendingAudioFormat!!.selectionReason
            )
            pendingAudioFormat = null
        }
        if (canReportPendingFormatUpdate(pendingTextFormat)) {
            maybeUpdateTextFormat(
                realtimeMs, pendingTextFormat!!.format, pendingTextFormat!!.selectionReason
            )
            pendingTextFormat = null
        }
    }

    private fun canReportPendingFormatUpdate(pendingFormatUpdate: PendingFormatUpdate?): Boolean {
        return pendingFormatUpdate != null
                && pendingFormatUpdate.sessionId == sessionManager.activeSessionId
    }

    private fun maybeReportNetworkChange(realtimeMs: Long) {
        val networkType: Int = getNetworkType(context)
        if (networkType != currentNetworkType) {
            currentNetworkType = networkType
            val networkEvent =
                NetworkEvent.Builder()
                    .setNetworkType(networkType)
                    .setTimeSinceCreatedMillis(realtimeMs - startTimeMs)
                    .build()
            backgroundExecutor.execute { playbackSession.reportNetworkEvent(networkEvent) }
        }
    }

    private fun maybeReportPlaybackStateChange(
        player: Player,
        events: AnalyticsListener.Events,
        realtimeMs: Long
    ) {
        if (player.playbackState != Player.STATE_BUFFERING) {
            isSeeking = false
        }
        if (player.playerError == null) {
            hasFatalError = false
        } else if (events.contains(AnalyticsListener.EVENT_PLAYER_ERROR)) {
            hasFatalError = true
        }
        val newPlaybackState = resolveNewPlaybackState(player)
        if (currentPlaybackState != newPlaybackState) {
            currentPlaybackState = newPlaybackState
            reportedEventsForCurrentSession = true
            val playbackStateEvent =
                PlaybackStateEvent.Builder()
                    .setState(currentPlaybackState)
                    .setTimeSinceCreatedMillis(realtimeMs - startTimeMs)
                    .build()
            backgroundExecutor.execute {
                playbackSession.reportPlaybackStateEvent(
                    playbackStateEvent
                )
            }
        }
    }

    private fun resolveNewPlaybackState(player: Player): Int {
        @Player.State val playerPlaybackState: @Player.State Int = player.playbackState
        if (isSeeking) {
            // Seeking takes precedence over errors such that we report a seek while in error state.
            return PlaybackStateEvent.STATE_SEEKING
        } else if (hasFatalError) {
            return PlaybackStateEvent.STATE_FAILED
        } else if (playerPlaybackState == Player.STATE_ENDED) {
            return PlaybackStateEvent.STATE_ENDED
        } else if (playerPlaybackState == Player.STATE_BUFFERING) {
            if (currentPlaybackState == PlaybackStateEvent.STATE_NOT_STARTED || currentPlaybackState == PlaybackStateEvent.STATE_JOINING_FOREGROUND || currentPlaybackState == PlaybackStateEvent.STATE_STOPPED) {
                return PlaybackStateEvent.STATE_JOINING_FOREGROUND
            }
            if (!player.playWhenReady) {
                return PlaybackStateEvent.STATE_PAUSED_BUFFERING
            }
            return if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                PlaybackStateEvent.STATE_SUPPRESSED_BUFFERING
            else
                PlaybackStateEvent.STATE_BUFFERING
        } else if (playerPlaybackState == Player.STATE_READY) {
            if (!player.playWhenReady) {
                return PlaybackStateEvent.STATE_PAUSED
            }
            return if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                PlaybackStateEvent.STATE_SUPPRESSED
            else
                PlaybackStateEvent.STATE_PLAYING
        } else if (playerPlaybackState == Player.STATE_IDLE
            && currentPlaybackState != PlaybackStateEvent.STATE_NOT_STARTED
        ) {
            // This case only applies for calls to player.stop(). All other IDLE cases are handled by
            // !isForeground, hasFatalError or isSuspended. NOT_STARTED is deliberately ignored.
            return PlaybackStateEvent.STATE_STOPPED
        }
        return currentPlaybackState
    }

    private fun maybeUpdateVideoFormat(
        realtimeMs: Long, videoFormat: Format?, trackSelectionReason: @C.SelectionReason Int
    ) {
        var trackSelectionReason = trackSelectionReason
        if (currentVideoFormat == videoFormat) {
            return
        }
        if (currentVideoFormat == null && trackSelectionReason == C.SELECTION_REASON_UNKNOWN) {
            trackSelectionReason = C.SELECTION_REASON_INITIAL
        }
        currentVideoFormat = videoFormat
        reportTrackChangeEvent(
            TrackChangeEvent.TRACK_TYPE_VIDEO, realtimeMs, videoFormat, trackSelectionReason
        )
    }

    private fun maybeUpdateAudioFormat(
        realtimeMs: Long, audioFormat: Format?, trackSelectionReason: @C.SelectionReason Int
    ) {
        var trackSelectionReason = trackSelectionReason
        if (currentAudioFormat == audioFormat) {
            return
        }
        if (currentAudioFormat == null && trackSelectionReason == C.SELECTION_REASON_UNKNOWN) {
            trackSelectionReason = C.SELECTION_REASON_INITIAL
        }
        currentAudioFormat = audioFormat
        reportTrackChangeEvent(
            TrackChangeEvent.TRACK_TYPE_AUDIO, realtimeMs, audioFormat, trackSelectionReason
        )
    }

    private fun maybeUpdateTextFormat(
        realtimeMs: Long, textFormat: Format?, trackSelectionReason: @C.SelectionReason Int
    ) {
        var trackSelectionReason = trackSelectionReason
        if (currentTextFormat == textFormat) {
            return
        }
        if (currentTextFormat == null && trackSelectionReason == C.SELECTION_REASON_UNKNOWN) {
            trackSelectionReason = C.SELECTION_REASON_INITIAL
        }
        currentTextFormat = textFormat
        reportTrackChangeEvent(
            TrackChangeEvent.TRACK_TYPE_TEXT, realtimeMs, textFormat, trackSelectionReason
        )
    }

    private fun reportTrackChangeEvent(
        type: Int,
        realtimeMs: Long,
        format: Format?,
        trackSelectionReason: @C.SelectionReason Int
    ) {
        val builder =
            TrackChangeEvent.Builder(type).setTimeSinceCreatedMillis(realtimeMs - startTimeMs)
        if (format != null) {
            builder.setTrackState(TrackChangeEvent.TRACK_STATE_ON)
            builder.setTrackChangeReason(getTrackChangeReason(trackSelectionReason))
            if (format.containerMimeType != null) {
                builder.setContainerMimeType(format.containerMimeType)
            }
            if (format.sampleMimeType != null) {
                builder.setSampleMimeType(format.sampleMimeType)
            }
            if (format.codecs != null) {
                builder.setCodecName(format.codecs)
            }
            if (format.bitrate != Format.NO_VALUE) {
                builder.setBitrate(format.bitrate)
            }
            if (format.width != Format.NO_VALUE) {
                builder.setWidth(format.width)
            }
            if (format.height != Format.NO_VALUE) {
                builder.setHeight(format.height)
            }
            if (format.channelCount != Format.NO_VALUE) {
                builder.setChannelCount(format.channelCount)
            }
            if (format.sampleRate != Format.NO_VALUE) {
                builder.setAudioSampleRate(format.sampleRate)
            }
            if (format.language != null) {
                val languageAndRegion: Pair<String?, String?> =
                    getLanguageAndRegion(format.language)
                builder.setLanguage(languageAndRegion.first!!)
                if (languageAndRegion.second != null) {
                    builder.setLanguageRegion(languageAndRegion.second!!)
                }
            }
            if (format.frameRate != Format.NO_VALUE.toFloat()) {
                builder.setVideoFrameRate(format.frameRate)
            }
        } else {
            builder.setTrackState(TrackChangeEvent.TRACK_STATE_OFF)
        }
        reportedEventsForCurrentSession = true
        val trackChangeEvent = builder.build()
        backgroundExecutor.execute {
            playbackSession.reportTrackChangeEvent(
                trackChangeEvent
            )
        }
    }

    @RequiresNonNull("metricsBuilder")
    private fun maybeUpdateTimelineMetadata(
        timeline: Timeline, mediaPeriodId: MediaPeriodId?
    ) {
        val metricsBuilder = this.metricsBuilder
        if (mediaPeriodId == null) {
            return
        }
        val periodIndex = timeline.getIndexOfPeriod(mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) {
            return
        }
        timeline.getPeriod(periodIndex, period)
        timeline.getWindow(period.windowIndex, window)
        metricsBuilder!!.setStreamType(getStreamType(window.mediaItem))
        if (window.durationUs != C.TIME_UNSET && !window.isPlaceholder && !window.isDynamic && !window.isLive) {
            metricsBuilder.setMediaDurationMillis(window.durationMs)
        }
        metricsBuilder.setPlaybackType(
            if (window.isLive) PlaybackMetrics.PLAYBACK_TYPE_LIVE else PlaybackMetrics.PLAYBACK_TYPE_VOD
        )
        reportedEventsForCurrentSession = true
    }

    private fun finishCurrentSession() {
        val metricsBuilder = metricsBuilder
        if (metricsBuilder != null && reportedEventsForCurrentSession) {
            metricsBuilder.setAudioUnderrunCount(audioUnderruns)
            metricsBuilder.setVideoFramesDropped(droppedFrames)
            metricsBuilder.setVideoFramesPlayed(playedFrames)
            val networkTimeMs = bandwidthTimeMs[activeSessionId]
            metricsBuilder.setNetworkTransferDurationMillis(networkTimeMs ?: 0)
            val networkBytes = bandwidthBytes[activeSessionId]
            metricsBuilder.setNetworkBytesRead(networkBytes ?: 0)
            metricsBuilder.setStreamSource(
                if (networkBytes != null && networkBytes > 0)
                    PlaybackMetrics.STREAM_SOURCE_NETWORK
                else
                    PlaybackMetrics.STREAM_SOURCE_UNKNOWN
            )
            val playbackMetrics = metricsBuilder.build()
            backgroundExecutor.execute {
                playbackSession.reportPlaybackMetrics(playbackMetrics)
            }
        }
        this.metricsBuilder = null
        activeSessionId = null
        audioUnderruns = 0
        droppedFrames = 0
        playedFrames = 0
        currentVideoFormat = null
        currentAudioFormat = null
        currentTextFormat = null
        reportedEventsForCurrentSession = false
    }

    private class ErrorInfo(val errorCode: Int, val subErrorCode: Int)

    private class PendingFormatUpdate(
        val format: Format,
        val selectionReason: @C.SelectionReason Int,
        val sessionId: String
    )

    companion object {
        fun create(context: Context): MediaMetricsListener? {
            val mediaMetricsManager =
                context.getSystemService(Context.MEDIA_METRICS_SERVICE) as MediaMetricsManager?
            return if (mediaMetricsManager == null)
                null
            else
                MediaMetricsListener(context, mediaMetricsManager.createPlaybackSession())
        }

        private fun getTrackChangeReason(trackSelectionReason: @C.SelectionReason Int): Int {
            return when (trackSelectionReason) {
                C.SELECTION_REASON_INITIAL -> TrackChangeEvent.TRACK_CHANGE_REASON_INITIAL
                C.SELECTION_REASON_ADAPTIVE -> TrackChangeEvent.TRACK_CHANGE_REASON_ADAPTIVE
                C.SELECTION_REASON_MANUAL -> TrackChangeEvent.TRACK_CHANGE_REASON_MANUAL
                C.SELECTION_REASON_TRICK_PLAY, C.SELECTION_REASON_UNKNOWN -> TrackChangeEvent.TRACK_CHANGE_REASON_OTHER
                else -> TrackChangeEvent.TRACK_CHANGE_REASON_OTHER
            }
        }

        private fun getLanguageAndRegion(languageCode: String): Pair<String?, String?> {
            val parts: Array<String> = split(languageCode, "-")
            return Pair.create<String?, String?>(
                parts[0],
                if (parts.size >= 2) parts[1] else null
            )
        }

        private fun getNetworkType(context: Context): Int {
            return when (NetworkTypeObserver.getInstance(context).getNetworkType()) {
                C.NETWORK_TYPE_WIFI -> NetworkEvent.NETWORK_TYPE_WIFI
                C.NETWORK_TYPE_2G -> NetworkEvent.NETWORK_TYPE_2G
                C.NETWORK_TYPE_3G -> NetworkEvent.NETWORK_TYPE_3G
                C.NETWORK_TYPE_4G -> NetworkEvent.NETWORK_TYPE_4G
                C.NETWORK_TYPE_5G_SA -> NetworkEvent.NETWORK_TYPE_5G_SA
                C.NETWORK_TYPE_5G_NSA -> NetworkEvent.NETWORK_TYPE_5G_NSA
                C.NETWORK_TYPE_ETHERNET -> NetworkEvent.NETWORK_TYPE_ETHERNET
                C.NETWORK_TYPE_OFFLINE -> NetworkEvent.NETWORK_TYPE_OFFLINE
                C.NETWORK_TYPE_UNKNOWN -> NetworkEvent.NETWORK_TYPE_UNKNOWN
                else -> NetworkEvent.NETWORK_TYPE_OTHER
            }
        }

        private fun getStreamType(mediaItem: MediaItem): Int {
            if (mediaItem.localConfiguration == null) {
                return PlaybackMetrics.STREAM_TYPE_UNKNOWN
            }
            @C.ContentType val contentType: @C.ContentType Int =
                inferContentTypeForUriAndMimeType(
                    mediaItem.localConfiguration.uri, mediaItem.localConfiguration.mimeType
                )
            return when (contentType) {
                C.CONTENT_TYPE_HLS -> PlaybackMetrics.STREAM_TYPE_HLS
                C.CONTENT_TYPE_DASH -> PlaybackMetrics.STREAM_TYPE_DASH
                C.CONTENT_TYPE_SS -> PlaybackMetrics.STREAM_TYPE_SS
                C.CONTENT_TYPE_RTSP -> PlaybackMetrics.STREAM_TYPE_OTHER
                else -> PlaybackMetrics.STREAM_TYPE_OTHER
            }
        }

        private fun getErrorInfo(
            error: PlaybackException, context: Context, lastIoErrorForManifest: Boolean
        ): ErrorInfo {
            if (error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR) {
                return ErrorInfo(PlaybackErrorEvent.ERROR_PLAYER_REMOTE, 0)
            }
            // Unpack the PlaybackException.
            var isRendererExoPlaybackException = false
            var rendererFormatSupport = C.FORMAT_UNSUPPORTED_TYPE
            if (error is ExoPlaybackException) {
                isRendererExoPlaybackException = error.type == ExoPlaybackException.TYPE_RENDERER
                rendererFormatSupport = error.rendererFormatSupport
            }
            var cause = error.cause!!
            if (cause is IOException) {
                if (cause is InvalidResponseCodeException) {
                    val responseCode = cause.responseCode
                    return ErrorInfo(PlaybackErrorEvent.ERROR_IO_BAD_HTTP_STATUS, responseCode)
                } else if (cause is InvalidContentTypeException || cause is ParserException) {
                    val code = if (lastIoErrorForManifest)
                        PlaybackErrorEvent.ERROR_PARSING_MANIFEST_MALFORMED
                    else
                        PlaybackErrorEvent.ERROR_PARSING_CONTAINER_MALFORMED
                    return ErrorInfo(code, 0)
                } else if (cause is HttpDataSourceException || cause is UdpDataSourceException) {
                    if (NetworkTypeObserver.getInstance(context)
                            .getNetworkType() == C.NETWORK_TYPE_OFFLINE
                    ) {
                        return ErrorInfo(
                            PlaybackErrorEvent.ERROR_IO_NETWORK_UNAVAILABLE, 0
                        )
                    } else {
                        val detailedCause = cause.cause
                        if (detailedCause is UnknownHostException) {
                            return ErrorInfo(PlaybackErrorEvent.ERROR_IO_DNS_FAILED, 0)
                        } else if (detailedCause is SocketTimeoutException) {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_IO_CONNECTION_TIMEOUT,
                                0
                            )
                        } else if (cause is HttpDataSourceException
                            && (cause.type == HttpDataSourceException.TYPE_OPEN)
                        ) {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_IO_NETWORK_CONNECTION_FAILED,
                                0
                            )
                        } else {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_IO_CONNECTION_CLOSED, 0
                            )
                        }
                    }
                } else if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    return ErrorInfo(
                        PlaybackErrorEvent.ERROR_PLAYER_BEHIND_LIVE_WINDOW, 0
                    )
                } else if (cause is DrmSessionException) {
                    // Unpack DrmSessionException.
                    cause = cause.cause!!
                    when (cause) {
                        is MediaDrmStateException -> {
                            val diagnosticsInfo = cause.diagnosticInfo
                            val subErrorCode =
                                getErrorCodeFromPlatformDiagnosticsInfo(diagnosticsInfo)
                            val errorCode: Int = getDrmErrorCode(subErrorCode)
                            return ErrorInfo(errorCode, subErrorCode)
                        }

                        is MediaDrmResetException -> {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_DRM_SYSTEM_ERROR,
                                0
                            )
                        }

                        is NotProvisionedException -> {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_DRM_PROVISIONING_FAILED, 0
                            )
                        }

                        is DeniedByServerException -> {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_DRM_DEVICE_REVOKED,
                                0
                            )
                        }

                        is UnsupportedDrmException -> {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_DRM_SCHEME_UNSUPPORTED, 0
                            )
                        }

                        is MissingSchemeDataException -> {
                            return ErrorInfo(
                                PlaybackErrorEvent.ERROR_DRM_CONTENT_ERROR,
                                0
                            )
                        }

                        else -> {
                            return ErrorInfo(PlaybackErrorEvent.ERROR_DRM_OTHER, 0)
                        }
                    }
                } else if (cause is FileDataSourceException && cause.cause is FileNotFoundException) {
                    val notFoundCause = cause.cause!!.cause
                    return if (notFoundCause is ErrnoException && notFoundCause.errno == OsConstants.EACCES) {
                        ErrorInfo(PlaybackErrorEvent.ERROR_IO_NO_PERMISSION, 0)
                    } else {
                        ErrorInfo(PlaybackErrorEvent.ERROR_IO_FILE_NOT_FOUND, 0)
                    }
                } else {
                    return ErrorInfo(PlaybackErrorEvent.ERROR_IO_OTHER, 0)
                }
            } else if (isRendererExoPlaybackException
                && (rendererFormatSupport == C.FORMAT_UNSUPPORTED_TYPE
                        || rendererFormatSupport == C.FORMAT_UNSUPPORTED_SUBTYPE)
            ) {
                return ErrorInfo(
                    PlaybackErrorEvent.ERROR_DECODING_FORMAT_UNSUPPORTED, 0
                )
            } else if (isRendererExoPlaybackException
                && rendererFormatSupport == C.FORMAT_EXCEEDS_CAPABILITIES
            ) {
                return ErrorInfo(
                    PlaybackErrorEvent.ERROR_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    0
                )
            } else if (isRendererExoPlaybackException && rendererFormatSupport == C.FORMAT_UNSUPPORTED_DRM) {
                return ErrorInfo(PlaybackErrorEvent.ERROR_DRM_SCHEME_UNSUPPORTED, 0)
            } else if (cause is DecoderInitializationException) {
                val diagnosticsInfo =
                    cause.diagnosticInfo
                val subErrorCode = getErrorCodeFromPlatformDiagnosticsInfo(diagnosticsInfo)
                return ErrorInfo(PlaybackErrorEvent.ERROR_DECODER_INIT_FAILED, subErrorCode)
            } else if (cause is MediaCodecDecoderException) {
                val subErrorCode = cause.errorCode
                return ErrorInfo(PlaybackErrorEvent.ERROR_DECODING_FAILED, subErrorCode)
            } else if (cause is OutOfMemoryError) {
                return ErrorInfo(PlaybackErrorEvent.ERROR_DECODING_FAILED, 0)
            } else if (cause is AudioSink.InitializationException) {
                val subErrorCode = cause.audioTrackState
                return ErrorInfo(PlaybackErrorEvent.ERROR_AUDIO_TRACK_INIT_FAILED, subErrorCode)
            } else if (cause is AudioSink.WriteException) {
                val subErrorCode = cause.errorCode
                return ErrorInfo(PlaybackErrorEvent.ERROR_AUDIO_TRACK_WRITE_FAILED, subErrorCode)
            } else if (cause is MediaCodec.CryptoException) {
                val subErrorCode = cause.errorCode
                val errorCode: Int = getDrmErrorCode(subErrorCode)
                return ErrorInfo(errorCode, subErrorCode)
            } else {
                return ErrorInfo(PlaybackErrorEvent.ERROR_PLAYER_OTHER, 0)
            }
        }

        private fun getDrmInitData(trackGroups: List<Tracks.Group>): DrmInitData? {
            for (trackGroup in trackGroups) {
                for (trackIndex in 0..<trackGroup.length) {
                    if (trackGroup.isTrackSelected(trackIndex)) {
                        val drmInitData = trackGroup.getTrackFormat(trackIndex).drmInitData
                        if (drmInitData != null) {
                            return drmInitData
                        }
                    }
                }
            }
            return null
        }

        private fun getDrmType(drmInitData: DrmInitData): Int {
            for (i in 0..<drmInitData.schemeDataCount) {
                val uuid = drmInitData.get(i)!!.uuid
                if (uuid == C.WIDEVINE_UUID) {
                    return PlaybackMetrics.DRM_TYPE_WIDEVINE_L1
                }
                if (uuid == C.PLAYREADY_UUID) {
                    return PlaybackMetrics.DRM_TYPE_PLAY_READY
                }
                if (uuid == C.CLEARKEY_UUID) {
                    return PlaybackMetrics.DRM_TYPE_CLEARKEY
                }
            }
            return PlaybackMetrics.DRM_TYPE_OTHER
        }

        @SuppressLint("SwitchIntDef") // Only DRM error codes are relevant here.
        private fun getDrmErrorCode(mediaDrmErrorCode: Int): Int {
            return when (getErrorCodeForMediaDrmErrorCode(mediaDrmErrorCode)) {
                PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> PlaybackErrorEvent.ERROR_DRM_PROVISIONING_FAILED
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> PlaybackErrorEvent.ERROR_DRM_LICENSE_ACQUISITION_FAILED
                PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> PlaybackErrorEvent.ERROR_DRM_DISALLOWED_OPERATION
                PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR -> PlaybackErrorEvent.ERROR_DRM_CONTENT_ERROR
                PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR -> PlaybackErrorEvent.ERROR_DRM_SYSTEM_ERROR
                else -> PlaybackErrorEvent.ERROR_DRM_SYSTEM_ERROR
            }
        }
    }
}
