package androidx.media3.exoplayer.analytics

import android.os.SystemClock
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize

import androidx.media3.common.util.Util.usToMs
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventFlags
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.analytics.PlaybackStats.EventTimeAndException
import androidx.media3.exoplayer.analytics.PlaybackStats.EventTimeAndFormat
import androidx.media3.exoplayer.analytics.PlaybackStats.EventTimeAndPlaybackState
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.max

class PlaybackStatsListener(private val keepHistory: Boolean, private val callback: Callback?) :
    AnalyticsListener, PlaybackSessionManager.Listener {

    interface Callback {
        fun onPlaybackStatsReady(eventTime: EventTime?, playbackStats: PlaybackStats?)
    }

    private val sessionManager = DefaultPlaybackSessionManager()
    private val playbackStatsTrackers: MutableMap<String, PlaybackStatsTracker> = HashMap()
    private val sessionStartEventTimes: MutableMap<String?, EventTime?> = HashMap()
    private val period: Timeline.Period = Timeline.Period()
    private var finishedPlaybackStats: PlaybackStats = PlaybackStats.EMPTY
    private var discontinuityFromSession: String? = null
    private var discontinuityFromPositionMs: Long = 0

    @Player.DiscontinuityReason
    private var discontinuityReason: @Player.DiscontinuityReason Int = 0
    private var droppedFrames = 0
    private var nonFatalException: Exception? = null
    private var bandwidthTimeMs: Long = 0
    private var bandwidthBytes: Long = 0
    private var videoFormat: Format? = null
    private var audioFormat: Format? = null
    private var videoSize: VideoSize? = VideoSize.UNKNOWN

    init {
        sessionManager.setListener(this)
    }

    val combinedPlaybackStats: PlaybackStats
        get() {
            val allPendingPlaybackStats = mutableListOf<PlaybackStats>()
            allPendingPlaybackStats.add(finishedPlaybackStats)
            for (tracker in playbackStatsTrackers.values) {
                allPendingPlaybackStats.add(tracker.build(false))
            }
            return PlaybackStats.merge(allPendingPlaybackStats.toTypedArray())
        }

    val playbackStats: PlaybackStats?
        get() {
            val activeSessionId = sessionManager.activeSessionId
            val activeStatsTracker =
                if (activeSessionId == null) null else playbackStatsTrackers[activeSessionId]
            return activeStatsTracker?.build(false)
        }

    // PlaybackSessionManager.Listener implementation.
    override fun onSessionCreated(eventTime: EventTime, sessionId: String) {
        val tracker = PlaybackStatsTracker(keepHistory, eventTime)
        playbackStatsTrackers[sessionId] = tracker
        sessionStartEventTimes[sessionId] = eventTime
    }

    override fun onSessionActive(eventTime: EventTime, sessionId: String) {
        playbackStatsTrackers[sessionId]!!.onForeground()
    }

    override fun onAdPlaybackStarted(
        eventTime: EventTime, contentSessionId: String, adSessionId: String
    ) {
        playbackStatsTrackers[contentSessionId]!!.onInterruptedByAd()
    }

    override fun onSessionFinished(
        eventTime: EventTime, sessionId: String, automaticTransitionToNextPlayback: Boolean
    ) {
        val tracker: PlaybackStatsTracker =playbackStatsTrackers.remove(sessionId)!!
        val startEventTime =sessionStartEventTimes.remove(sessionId)!!
        val discontinuityFromPositionMs =
            if (sessionId == discontinuityFromSession)
                this.discontinuityFromPositionMs
            else
                C.TIME_UNSET
        tracker.onFinished(
            eventTime,
            automaticTransitionToNextPlayback,
            discontinuityFromPositionMs
        )
        val playbackStats = tracker.build(true)
        finishedPlaybackStats = PlaybackStats.merge(arrayOf(finishedPlaybackStats, playbackStats))
        callback?.onPlaybackStatsReady(startEventTime, playbackStats)
    }

    // AnalyticsListener implementation.
    override fun onPositionDiscontinuity(
        eventTime: EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    ) {
        if (discontinuityFromSession == null) {
            discontinuityFromSession = sessionManager.activeSessionId
            discontinuityFromPositionMs = oldPosition.positionMs
        }
        discontinuityReason = reason
    }

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames = droppedFrames
    }

    override fun onLoadError(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        nonFatalException = error
    }

    override fun onDrmSessionManagerError(eventTime: EventTime, error: Exception) {
        nonFatalException = error
    }

    override fun onBandwidthEstimate(
        eventTime: EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long
    ) {
        bandwidthTimeMs = totalLoadTimeMs.toLong()
        bandwidthBytes = totalBytesLoaded
    }

    override fun onDownstreamFormatChanged(eventTime: EventTime, mediaLoadData: MediaLoadData) {
        if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO
            || mediaLoadData.trackType == C.TRACK_TYPE_DEFAULT
        ) {
            videoFormat = mediaLoadData.trackFormat
        } else if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO) {
            audioFormat = mediaLoadData.trackFormat
        }
    }

    override fun onVideoSizeChanged(eventTime: EventTime, videoSize: VideoSize) {
        this.videoSize = videoSize
    }

    override fun onEvents(player: Player, events: AnalyticsListener.Events) {
        if (events.size() == 0) {
            return
        }
        maybeAddSessions(events)
        for (session in playbackStatsTrackers.keys) {
            val eventTimeAndBelongsToPlayback = findBestEventTime(events, session)
            val tracker = playbackStatsTrackers.get(session)
            val hasDiscontinuityToPlayback =
                hasEvent(events, session, AnalyticsListener.EVENT_POSITION_DISCONTINUITY)
            val hasDroppedFrames =
                hasEvent(events, session, AnalyticsListener.EVENT_DROPPED_VIDEO_FRAMES)
            val hasAudioUnderrun =
                hasEvent(events, session, AnalyticsListener.EVENT_AUDIO_UNDERRUN)
            val startedLoading =
                hasEvent(events, session, AnalyticsListener.EVENT_LOAD_STARTED)
            val hasFatalError =
                hasEvent(events, session, AnalyticsListener.EVENT_PLAYER_ERROR)
            val hasNonFatalException =
                hasEvent(events, session, AnalyticsListener.EVENT_LOAD_ERROR)
                        || hasEvent(
                    events,
                    session,
                    AnalyticsListener.EVENT_DRM_SESSION_MANAGER_ERROR
                )
            val hasBandwidthData =
                hasEvent(events, session, AnalyticsListener.EVENT_BANDWIDTH_ESTIMATE)
            val hasFormatData = hasEvent(
                events,
                session,
                AnalyticsListener.EVENT_DOWNSTREAM_FORMAT_CHANGED
            )
            val hasVideoSize =
                hasEvent(events, session, AnalyticsListener.EVENT_VIDEO_SIZE_CHANGED)
            tracker!!.onEvents(
                player,  
                eventTimeAndBelongsToPlayback.first!!,  
                eventTimeAndBelongsToPlayback.second!!,
                if (session == discontinuityFromSession) discontinuityFromPositionMs else C.TIME_UNSET,
                hasDiscontinuityToPlayback,
                if (hasDroppedFrames) droppedFrames else 0,
                hasAudioUnderrun,
                startedLoading,
                if (hasFatalError) player.playerError else null,
                if (hasNonFatalException) nonFatalException else null,
                if (hasBandwidthData) bandwidthTimeMs else 0,
                if (hasBandwidthData) bandwidthBytes else 0,
                if (hasFormatData) videoFormat else null,
                if (hasFormatData) audioFormat else null,
                if (hasVideoSize) videoSize else null
            )
        }
        videoFormat = null
        audioFormat = null
        discontinuityFromSession = null
        if (events.contains(AnalyticsListener.EVENT_PLAYER_RELEASED)) {
            sessionManager.finishAllSessions(events.getEventTime(AnalyticsListener.EVENT_PLAYER_RELEASED))
        }
    }

    private fun maybeAddSessions(events: AnalyticsListener.Events) {
        for (i in 0..<events.size()) {
            @EventFlags val event: @EventFlags Int = events.get(i)
            val eventTime = events.getEventTime(event)
            if (event == AnalyticsListener.EVENT_TIMELINE_CHANGED) {
                sessionManager.updateSessionsWithTimelineChange(eventTime)
            } else if (event == AnalyticsListener.EVENT_POSITION_DISCONTINUITY) {
                sessionManager.updateSessionsWithDiscontinuity(eventTime, discontinuityReason)
            } else {
                sessionManager.updateSessions(eventTime)
            }
        }
    }

    private fun findBestEventTime(
        events: AnalyticsListener.Events,
        session: String
    ): Pair<EventTime?, Boolean?> {
        var eventTime: EventTime? = null
        var belongsToPlayback = false
        for (i in 0..<events.size()) {
            @EventFlags val event: @EventFlags Int = events.get(i)
            val newEventTime = events.getEventTime(event)
            val newBelongsToPlayback = sessionManager.belongsToSession(newEventTime, session)
            if (eventTime == null || (newBelongsToPlayback && !belongsToPlayback)
                || (newBelongsToPlayback == belongsToPlayback
                        && newEventTime.realtimeMs > eventTime.realtimeMs)
            ) {
                // Prefer event times for the current playback and prefer later timestamps.
                eventTime = newEventTime
                belongsToPlayback = newBelongsToPlayback
            }
        }
        if (!belongsToPlayback && eventTime!!.mediaPeriodId != null && eventTime.mediaPeriodId.isAd) {
            // Replace ad event time with content event time unless it's for the ad playback itself.
            var contentPeriodPositionUs =
                eventTime
                    .timeline
                    .getPeriodByUid(eventTime.mediaPeriodId.periodUid, period)
                    .getAdGroupTimeUs(eventTime.mediaPeriodId.adGroupIndex)
            if (contentPeriodPositionUs == C.TIME_END_OF_SOURCE) {
                contentPeriodPositionUs = period.durationUs
            }
            val contentWindowPositionUs = contentPeriodPositionUs + period.positionInWindowUs
            eventTime =
                EventTime(
                    eventTime.realtimeMs,
                    eventTime.timeline,
                    eventTime.windowIndex,
                    MediaPeriodId(
                        eventTime.mediaPeriodId.periodUid,
                        eventTime.mediaPeriodId.windowSequenceNumber,
                        eventTime.mediaPeriodId.adGroupIndex
                    ),  
                    usToMs(contentWindowPositionUs),
                    eventTime.timeline,
                    eventTime.currentWindowIndex,
                    eventTime.currentMediaPeriodId,
                    eventTime.currentPlaybackPositionMs,
                    eventTime.totalBufferedDurationMs
                )
            belongsToPlayback = sessionManager.belongsToSession(eventTime, session)
        }
        return Pair.create<EventTime?, Boolean?>(eventTime, belongsToPlayback)
    }

    private fun hasEvent(
        events: AnalyticsListener.Events,
        session: String,
        @EventFlags event: @EventFlags Int
    ): Boolean {
        return events.contains(event)
                && sessionManager.belongsToSession(events.getEventTime(event), session)
    }

    private class PlaybackStatsTracker(// Final stats.
        private val keepHistory: Boolean, startTime: EventTime
    ) {
        private val playbackStateDurationsMs: LongArray
        private val playbackStateHistory: MutableList<EventTimeAndPlaybackState>
        private val mediaTimeHistory: MutableList<LongArray>
        private val videoFormatHistory: MutableList<EventTimeAndFormat>
        private val audioFormatHistory: MutableList<EventTimeAndFormat>
        private val fatalErrorHistory: MutableList<EventTimeAndException>
        private val nonFatalErrorHistory: MutableList<EventTimeAndException>
        private val isAd: Boolean

        private var firstReportedTimeMs: Long
        private var hasBeenReady = false
        private var hasEnded = false
        private var isJoinTimeInvalid = false
        private var pauseCount = 0
        private var pauseBufferCount = 0
        private var seekCount = 0
        private var rebufferCount = 0
        private var maxRebufferTimeMs: Long
        private var initialVideoFormatHeight: Int
        private var initialVideoFormatBitrate: Long
        private var initialAudioFormatBitrate: Long
        private var videoFormatHeightTimeMs: Long = 0
        private var videoFormatHeightTimeProduct: Long = 0
        private var videoFormatBitrateTimeMs: Long = 0
        private var videoFormatBitrateTimeProduct: Long = 0
        private var audioFormatTimeMs: Long = 0
        private var audioFormatBitrateTimeProduct: Long = 0
        private var bandwidthTimeMs: Long = 0
        private var bandwidthBytes: Long = 0
        private var droppedFrames: Long = 0
        private var audioUnderruns: Long = 0
        private var fatalErrorCount = 0
        private var nonFatalErrorCount = 0

        // Current player state tracking.
        private var currentPlaybackState: @PlaybackStats.PlaybackState Int
        private var currentPlaybackStateStartTimeMs: Long
        private var isSeeking = false
        private var isForeground = false
        private var isInterruptedByAd = false
        private var hasFatalError = false
        private var startedLoading = false
        private var lastRebufferStartTimeMs: Long = 0
        private var currentVideoFormat: Format? = null
        private var currentAudioFormat: Format? = null
        private var lastVideoFormatStartTimeMs: Long = 0
        private var lastAudioFormatStartTimeMs: Long = 0
        private var currentPlaybackSpeed = 1f

        init {
            playbackStateDurationsMs = LongArray(PlaybackStats.PLAYBACK_STATE_COUNT)
            playbackStateHistory =
                if (keepHistory) ArrayList<EventTimeAndPlaybackState>() else mutableListOf<EventTimeAndPlaybackState>()
            mediaTimeHistory =
                if (keepHistory) ArrayList<LongArray>() else mutableListOf<LongArray>()
            videoFormatHistory =
                if (keepHistory) ArrayList<EventTimeAndFormat>() else mutableListOf<EventTimeAndFormat>()
            audioFormatHistory =
                if (keepHistory) ArrayList<EventTimeAndFormat>() else mutableListOf<EventTimeAndFormat>()
            fatalErrorHistory =
                if (keepHistory) ArrayList<EventTimeAndException>() else mutableListOf<EventTimeAndException>()
            nonFatalErrorHistory =
                if (keepHistory) ArrayList<EventTimeAndException>() else mutableListOf<EventTimeAndException>()
            currentPlaybackState = PlaybackStats.PLAYBACK_STATE_NOT_STARTED
            currentPlaybackStateStartTimeMs = startTime.realtimeMs
            firstReportedTimeMs = C.TIME_UNSET
            maxRebufferTimeMs = C.TIME_UNSET
            isAd = startTime.mediaPeriodId != null && startTime.mediaPeriodId.isAd
            initialAudioFormatBitrate = C.LENGTH_UNSET.toLong()
            initialVideoFormatBitrate = C.LENGTH_UNSET.toLong()
            initialVideoFormatHeight = C.LENGTH_UNSET
        }

        fun onForeground() {
            isForeground = true
        }

        fun onInterruptedByAd() {
            isInterruptedByAd = true
            isSeeking = false
        }

        fun onFinished(
            eventTime: EventTime, automaticTransition: Boolean, discontinuityFromPositionMs: Long
        ) {
            // Simulate state change to ENDED to record natural ending of playback.
            val finalPlaybackState: @PlaybackStats.PlaybackState Int =
                if (currentPlaybackState == PlaybackStats.PLAYBACK_STATE_ENDED || automaticTransition)
                    PlaybackStats.PLAYBACK_STATE_ENDED
                else
                    PlaybackStats.PLAYBACK_STATE_ABANDONED
            maybeUpdateMediaTimeHistory(eventTime.realtimeMs, discontinuityFromPositionMs)
            maybeRecordVideoFormatTime(eventTime.realtimeMs)
            maybeRecordAudioFormatTime(eventTime.realtimeMs)
            updatePlaybackState(finalPlaybackState, eventTime)
        }

        fun onEvents(
            player: Player,
            eventTime: EventTime,
            belongsToPlayback: Boolean,
            discontinuityFromPositionMs: Long,
            hasDiscontinuity: Boolean,
            droppedFrameCount: Int,
            hasAudioUnderun: Boolean,
            startedLoading: Boolean,
            fatalError: PlaybackException?,
            nonFatalException: Exception?,
            bandwidthTimeMs: Long,
            bandwidthBytes: Long,
            videoFormat: Format?,
            audioFormat: Format?,
            videoSize: VideoSize?
        ) {
            if (discontinuityFromPositionMs != C.TIME_UNSET) {
                maybeUpdateMediaTimeHistory(eventTime.realtimeMs, discontinuityFromPositionMs)
                isSeeking = true
            }
            if (player.playbackState != Player.STATE_BUFFERING) {
                isSeeking = false
            }
            val playerPlaybackState = player.playbackState
            if (playerPlaybackState == Player.STATE_IDLE || playerPlaybackState == Player.STATE_ENDED || hasDiscontinuity) {
                isInterruptedByAd = false
            }
            if (fatalError != null) {
                hasFatalError = true
                fatalErrorCount++
                if (keepHistory) {
                    fatalErrorHistory.add(EventTimeAndException(eventTime, fatalError))
                }
            } else if (player.playerError == null) {
                hasFatalError = false
            }
            if (isForeground && !isInterruptedByAd) {
                val currentTracks = player.currentTracks!!
                if (!currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    maybeUpdateVideoFormat(eventTime,  null)
                }
                if (!currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                    maybeUpdateAudioFormat(eventTime,  null)
                }
            }
            if (videoFormat != null) {
                maybeUpdateVideoFormat(eventTime, videoFormat)
            }
            if (audioFormat != null) {
                maybeUpdateAudioFormat(eventTime, audioFormat)
            }
            if (currentVideoFormat != null && currentVideoFormat!!.height == Format.NO_VALUE && videoSize != null) {
                val formatWithHeightAndWidth =
                    currentVideoFormat!!
                        .buildUpon()
                        .setWidth(videoSize.width)
                        .setHeight(videoSize.height)
                        .build()
                maybeUpdateVideoFormat(eventTime, formatWithHeightAndWidth)
            }
            if (startedLoading) {
                this.startedLoading = true
            }
            if (hasAudioUnderun) {
                audioUnderruns++
            }
            this.droppedFrames += droppedFrameCount.toLong()
            this.bandwidthTimeMs += bandwidthTimeMs
            this.bandwidthBytes += bandwidthBytes
            if (nonFatalException != null) {
                nonFatalErrorCount++
                if (keepHistory) {
                    nonFatalErrorHistory.add(EventTimeAndException(eventTime, nonFatalException))
                }
            }

            val newPlaybackState: @PlaybackStats.PlaybackState Int = resolveNewPlaybackState(player)
            val newPlaybackSpeed = player.playbackParameters!!.speed
            if (currentPlaybackState != newPlaybackState || currentPlaybackSpeed != newPlaybackSpeed) {
                maybeUpdateMediaTimeHistory(
                    eventTime.realtimeMs,
                    if (belongsToPlayback) eventTime.eventPlaybackPositionMs else C.TIME_UNSET
                )
                maybeRecordVideoFormatTime(eventTime.realtimeMs)
                maybeRecordAudioFormatTime(eventTime.realtimeMs)
            }
            currentPlaybackSpeed = newPlaybackSpeed
            if (currentPlaybackState != newPlaybackState) {
                updatePlaybackState(newPlaybackState, eventTime)
            }
        }

        fun build(isFinal: Boolean): PlaybackStats {
            var playbackStateDurationsMs = this.playbackStateDurationsMs
            var mediaTimeHistory = this.mediaTimeHistory
            if (!isFinal) {
                val buildTimeMs = SystemClock.elapsedRealtime()
                playbackStateDurationsMs =
                    this.playbackStateDurationsMs.copyOf(PlaybackStats.PLAYBACK_STATE_COUNT)
                val lastStateDurationMs = max(0, buildTimeMs - currentPlaybackStateStartTimeMs)
                playbackStateDurationsMs[currentPlaybackState] += lastStateDurationMs
                maybeUpdateMaxRebufferTimeMs(buildTimeMs)
                maybeRecordVideoFormatTime(buildTimeMs)
                maybeRecordAudioFormatTime(buildTimeMs)
                mediaTimeHistory = ArrayList<LongArray>(this.mediaTimeHistory)
                if (keepHistory && currentPlaybackState == PlaybackStats.PLAYBACK_STATE_PLAYING) {
                    mediaTimeHistory.add(guessMediaTimeBasedOnElapsedRealtime(buildTimeMs))
                }
            }
            val isJoinTimeInvalid = this.isJoinTimeInvalid || !hasBeenReady
            val validJoinTimeMs =
                if (isJoinTimeInvalid)
                    C.TIME_UNSET
                else
                    playbackStateDurationsMs[PlaybackStats.PLAYBACK_STATE_JOINING_FOREGROUND]
            val hasBackgroundJoin =
                playbackStateDurationsMs[PlaybackStats.PLAYBACK_STATE_JOINING_BACKGROUND] > 0
            val videoHistory =
                if (isFinal) videoFormatHistory else ArrayList<EventTimeAndFormat>(
                    videoFormatHistory
                )
            val audioHistory =
                if (isFinal) audioFormatHistory else ArrayList<EventTimeAndFormat>(
                    audioFormatHistory
                )
            return PlaybackStats( 
                1,
                playbackStateDurationsMs,
                if (isFinal) playbackStateHistory else ArrayList<EventTimeAndPlaybackState>(
                    playbackStateHistory
                ),
                mediaTimeHistory,
                firstReportedTimeMs,  
                if (isForeground) 1 else 0,  
                if (hasBeenReady) 0 else 1,  
                if (hasEnded) 1 else 0,  
                if (hasBackgroundJoin) 1 else 0,
                validJoinTimeMs,  
                if (isJoinTimeInvalid) 0 else 1,
                pauseCount,
                pauseBufferCount,
                seekCount,
                rebufferCount,
                maxRebufferTimeMs,  
                if (isAd) 1 else 0,
                videoHistory,
                audioHistory,
                videoFormatHeightTimeMs,
                videoFormatHeightTimeProduct,
                videoFormatBitrateTimeMs,
                videoFormatBitrateTimeProduct,
                audioFormatTimeMs,
                audioFormatBitrateTimeProduct,  
                if (initialVideoFormatHeight == C.LENGTH_UNSET) 0 else 1,  
                if (initialVideoFormatBitrate == C.LENGTH_UNSET.toLong()) 0 else 1,
                initialVideoFormatHeight,
                initialVideoFormatBitrate,  
                if (initialAudioFormatBitrate == C.LENGTH_UNSET.toLong()) 0 else 1,
                initialAudioFormatBitrate,
                bandwidthTimeMs,
                bandwidthBytes,
                droppedFrames,
                audioUnderruns,  
                if (fatalErrorCount > 0) 1 else 0,
                fatalErrorCount,
                nonFatalErrorCount,
                fatalErrorHistory,
                nonFatalErrorHistory
            )
        }

        fun updatePlaybackState(
            newPlaybackState: @PlaybackStats.PlaybackState Int,
            eventTime: EventTime
        ) {
            Preconditions.checkArgument(eventTime.realtimeMs >= currentPlaybackStateStartTimeMs)
            val stateDurationMs = eventTime.realtimeMs - currentPlaybackStateStartTimeMs
            playbackStateDurationsMs[currentPlaybackState] += stateDurationMs
            if (firstReportedTimeMs == C.TIME_UNSET) {
                firstReportedTimeMs = eventTime.realtimeMs
            }
            isJoinTimeInvalid =
                isJoinTimeInvalid or isInvalidJoinTransition(currentPlaybackState, newPlaybackState)
            hasBeenReady = hasBeenReady or isReadyState(newPlaybackState)
            hasEnded =
                hasEnded or (newPlaybackState == PlaybackStats.PLAYBACK_STATE_ENDED)
            if (!isPausedState(currentPlaybackState) && isPausedState(newPlaybackState)) {
                pauseCount++
            }
            if (newPlaybackState == PlaybackStats.PLAYBACK_STATE_SEEKING) {
                seekCount++
            }
            if (!isRebufferingState(currentPlaybackState) && isRebufferingState(newPlaybackState)) {
                rebufferCount++
                lastRebufferStartTimeMs = eventTime.realtimeMs
            }
            if (isRebufferingState(currentPlaybackState)
                && currentPlaybackState != PlaybackStats.PLAYBACK_STATE_PAUSED_BUFFERING && newPlaybackState == PlaybackStats.PLAYBACK_STATE_PAUSED_BUFFERING
            ) {
                pauseBufferCount++
            }
            maybeUpdateMaxRebufferTimeMs(eventTime.realtimeMs)

            currentPlaybackState = newPlaybackState
            currentPlaybackStateStartTimeMs = eventTime.realtimeMs
            if (keepHistory) {
                playbackStateHistory.add(EventTimeAndPlaybackState(eventTime, currentPlaybackState))
            }
        }

        fun resolveNewPlaybackState(player: Player): @PlaybackStats.PlaybackState Int {
            @Player.State val playerPlaybackState: @Player.State Int = player.playbackState
            if (isSeeking && isForeground) {
                // Seeking takes precedence over errors such that we report a seek while in error state.
                return PlaybackStats.PLAYBACK_STATE_SEEKING
            } else if (hasFatalError) {
                return PlaybackStats.PLAYBACK_STATE_FAILED
            } else if (!isForeground) {
                // Before the playback becomes foreground, only report background joining and not started.
                return if (startedLoading)
                    PlaybackStats.PLAYBACK_STATE_JOINING_BACKGROUND
                else
                    PlaybackStats.PLAYBACK_STATE_NOT_STARTED
            } else if (isInterruptedByAd) {
                return PlaybackStats.PLAYBACK_STATE_INTERRUPTED_BY_AD
            } else if (playerPlaybackState == Player.STATE_ENDED) {
                return PlaybackStats.PLAYBACK_STATE_ENDED
            } else if (playerPlaybackState == Player.STATE_BUFFERING) {
                if (currentPlaybackState == PlaybackStats.PLAYBACK_STATE_NOT_STARTED || currentPlaybackState == PlaybackStats.PLAYBACK_STATE_JOINING_BACKGROUND || currentPlaybackState == PlaybackStats.PLAYBACK_STATE_JOINING_FOREGROUND || currentPlaybackState == PlaybackStats.PLAYBACK_STATE_INTERRUPTED_BY_AD) {
                    return PlaybackStats.PLAYBACK_STATE_JOINING_FOREGROUND
                }
                if (!player.playWhenReady) {
                    return PlaybackStats.PLAYBACK_STATE_PAUSED_BUFFERING
                }
                return if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                    PlaybackStats.PLAYBACK_STATE_SUPPRESSED_BUFFERING
                else
                    PlaybackStats.PLAYBACK_STATE_BUFFERING
            } else if (playerPlaybackState == Player.STATE_READY) {
                if (!player.playWhenReady) {
                    return PlaybackStats.PLAYBACK_STATE_PAUSED
                }
                return if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                    PlaybackStats.PLAYBACK_STATE_SUPPRESSED
                else
                    PlaybackStats.PLAYBACK_STATE_PLAYING
            } else if (playerPlaybackState == Player.STATE_IDLE
                && currentPlaybackState != PlaybackStats.PLAYBACK_STATE_NOT_STARTED
            ) {
                // This case only applies for calls to player.stop(). All other IDLE cases are handled by
                // !isForeground, hasFatalError or isSuspended. NOT_STARTED is deliberately ignored.
                return PlaybackStats.PLAYBACK_STATE_STOPPED
            }
            return currentPlaybackState
        }

        fun maybeUpdateMaxRebufferTimeMs(nowMs: Long) {
            if (isRebufferingState(currentPlaybackState)) {
                val rebufferDurationMs = nowMs - lastRebufferStartTimeMs
                if (maxRebufferTimeMs == C.TIME_UNSET || rebufferDurationMs > maxRebufferTimeMs) {
                    maxRebufferTimeMs = rebufferDurationMs
                }
            }
        }

        fun maybeUpdateMediaTimeHistory(realtimeMs: Long, mediaTimeMs: Long) {
            if (!keepHistory) {
                return
            }
            if (currentPlaybackState != PlaybackStats.PLAYBACK_STATE_PLAYING) {
                if (mediaTimeMs == C.TIME_UNSET) {
                    return
                }
                if (!mediaTimeHistory.isEmpty()) {
                    val previousMediaTimeMs = mediaTimeHistory.get(mediaTimeHistory.size - 1)[1]
                    if (previousMediaTimeMs != mediaTimeMs) {
                        mediaTimeHistory.add(longArrayOf(realtimeMs, previousMediaTimeMs))
                    }
                }
            }

            if (mediaTimeMs != C.TIME_UNSET) {
                mediaTimeHistory.add(longArrayOf(realtimeMs, mediaTimeMs))
            } else if (!mediaTimeHistory.isEmpty()) {
                mediaTimeHistory.add(guessMediaTimeBasedOnElapsedRealtime(realtimeMs))
            }
        }

        fun guessMediaTimeBasedOnElapsedRealtime(realtimeMs: Long): LongArray {
            val previousKnownMediaTimeHistory = mediaTimeHistory.get(mediaTimeHistory.size - 1)
            val previousRealtimeMs = previousKnownMediaTimeHistory[0]
            val previousMediaTimeMs = previousKnownMediaTimeHistory[1]
            val elapsedMediaTimeEstimateMs =
                ((realtimeMs - previousRealtimeMs) * currentPlaybackSpeed).toLong()
            val mediaTimeEstimateMs = previousMediaTimeMs + elapsedMediaTimeEstimateMs
            return longArrayOf(realtimeMs, mediaTimeEstimateMs)
        }

        fun maybeUpdateVideoFormat(eventTime: EventTime, newFormat: Format?) {
            if (currentVideoFormat == newFormat) {
                return
            }
            maybeRecordVideoFormatTime(eventTime.realtimeMs)
            if (newFormat != null) {
                if (initialVideoFormatHeight == C.LENGTH_UNSET && newFormat.height != Format.NO_VALUE) {
                    initialVideoFormatHeight = newFormat.height
                }
                if (initialVideoFormatBitrate == C.LENGTH_UNSET.toLong() && newFormat.bitrate != Format.NO_VALUE) {
                    initialVideoFormatBitrate = newFormat.bitrate.toLong()
                }
            }
            currentVideoFormat = newFormat
            if (keepHistory) {
                videoFormatHistory.add(EventTimeAndFormat(eventTime, currentVideoFormat))
            }
        }

        fun maybeUpdateAudioFormat(eventTime: EventTime, newFormat: Format?) {
            if (currentAudioFormat == newFormat) {
                return
            }
            maybeRecordAudioFormatTime(eventTime.realtimeMs)
            if (newFormat != null && initialAudioFormatBitrate == C.LENGTH_UNSET.toLong() && newFormat.bitrate != Format.NO_VALUE) {
                initialAudioFormatBitrate = newFormat.bitrate.toLong()
            }
            currentAudioFormat = newFormat
            if (keepHistory) {
                audioFormatHistory.add(EventTimeAndFormat(eventTime, currentAudioFormat))
            }
        }

        fun maybeRecordVideoFormatTime(nowMs: Long) {
            if (currentPlaybackState == PlaybackStats.PLAYBACK_STATE_PLAYING
                && currentVideoFormat != null
            ) {
                val mediaDurationMs =
                    ((nowMs - lastVideoFormatStartTimeMs) * currentPlaybackSpeed).toLong()
                if (currentVideoFormat!!.height != Format.NO_VALUE) {
                    videoFormatHeightTimeMs += mediaDurationMs
                    videoFormatHeightTimeProduct += mediaDurationMs * currentVideoFormat!!.height
                }
                if (currentVideoFormat!!.bitrate != Format.NO_VALUE) {
                    videoFormatBitrateTimeMs += mediaDurationMs
                    videoFormatBitrateTimeProduct += mediaDurationMs * currentVideoFormat!!.bitrate
                }
            }
            lastVideoFormatStartTimeMs = nowMs
        }

        fun maybeRecordAudioFormatTime(nowMs: Long) {
            if (currentPlaybackState == PlaybackStats.PLAYBACK_STATE_PLAYING && currentAudioFormat != null && currentAudioFormat!!.bitrate != Format.NO_VALUE) {
                val mediaDurationMs =
                    ((nowMs - lastAudioFormatStartTimeMs) * currentPlaybackSpeed).toLong()
                audioFormatTimeMs += mediaDurationMs
                audioFormatBitrateTimeProduct += mediaDurationMs * currentAudioFormat!!.bitrate
            }
            lastAudioFormatStartTimeMs = nowMs
        }

        companion object {
            private fun isReadyState(state: @PlaybackStats.PlaybackState Int): Boolean {
                return state == PlaybackStats.PLAYBACK_STATE_PLAYING || state == PlaybackStats.PLAYBACK_STATE_PAUSED || state == PlaybackStats.PLAYBACK_STATE_SUPPRESSED
            }

            private fun isPausedState(state: @PlaybackStats.PlaybackState Int): Boolean {
                return state == PlaybackStats.PLAYBACK_STATE_PAUSED
                        || state == PlaybackStats.PLAYBACK_STATE_PAUSED_BUFFERING
            }

            private fun isRebufferingState(state: @PlaybackStats.PlaybackState Int): Boolean {
                return state == PlaybackStats.PLAYBACK_STATE_BUFFERING || state == PlaybackStats.PLAYBACK_STATE_PAUSED_BUFFERING || state == PlaybackStats.PLAYBACK_STATE_SUPPRESSED_BUFFERING
            }

            private fun isInvalidJoinTransition(
                oldState: @PlaybackStats.PlaybackState Int,
                newState: @PlaybackStats.PlaybackState Int
            ): Boolean {
                if (oldState != PlaybackStats.PLAYBACK_STATE_JOINING_BACKGROUND && oldState != PlaybackStats.PLAYBACK_STATE_JOINING_FOREGROUND && oldState != PlaybackStats.PLAYBACK_STATE_INTERRUPTED_BY_AD) {
                    return false
                }
                return newState != PlaybackStats.PLAYBACK_STATE_JOINING_BACKGROUND && newState != PlaybackStats.PLAYBACK_STATE_JOINING_FOREGROUND && newState != PlaybackStats.PLAYBACK_STATE_INTERRUPTED_BY_AD && newState != PlaybackStats.PLAYBACK_STATE_PLAYING && newState != PlaybackStats.PLAYBACK_STATE_PAUSED && newState != PlaybackStats.PLAYBACK_STATE_SUPPRESSED && newState != PlaybackStats.PLAYBACK_STATE_ENDED
            }
        }
    }
}
