package androidx.media3.exoplayer.analytics

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId

interface PlaybackSessionManager {

    interface Listener {
        fun onSessionCreated(eventTime: EventTime, sessionId: String)
        fun onSessionActive(eventTime: EventTime, sessionId: String)
        fun onAdPlaybackStarted(
            eventTime: EventTime,
            contentSessionId: String,
            adSessionId: String
        )
        fun onSessionFinished(
            eventTime: EventTime, sessionId: String, automaticTransitionToNextPlayback: Boolean
        )
    }

    fun setListener(listener: Listener)
    fun getSessionForMediaPeriodId(timeline: Timeline, mediaPeriodId: MediaPeriodId): String
    fun belongsToSession(eventTime: EventTime, sessionId: String): Boolean
    fun updateSessions(eventTime: EventTime)
    fun updateSessionsWithTimelineChange(eventTime: EventTime)
    fun updateSessionsWithDiscontinuity(
        eventTime: EventTime,
        @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    )

    val activeSessionId: String?
    fun finishAllSessions(eventTime: EventTime)
}
