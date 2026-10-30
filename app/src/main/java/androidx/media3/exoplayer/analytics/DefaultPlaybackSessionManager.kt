package androidx.media3.exoplayer.analytics

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util.usToMs
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Supplier
import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.util.Random
import kotlin.math.max

class DefaultPlaybackSessionManager(private val sessionIdGenerator: Supplier<String> = DEFAULT_SESSION_ID_GENERATOR) :
    PlaybackSessionManager {
    private val window: Timeline.Window = Timeline.Window()
    private val period: Timeline.Period = Timeline.Period()
    private val sessions: MutableMap<String, SessionDescriptor> = mutableMapOf()

    private var listener: PlaybackSessionManager.Listener? = null
    private var currentTimeline: Timeline= Timeline.EMPTY
    private var currentSessionId: String? = null
    private var lastRemovedCurrentWindowSequenceNumber: Long=1

    override fun setListener(listener: PlaybackSessionManager.Listener) {
        this.listener = listener
    }

    @Synchronized
    override fun getSessionForMediaPeriodId(
        timeline: Timeline, mediaPeriodId: MediaPeriodId
    ): String {
        val windowIndex = timeline.getPeriodByUid(mediaPeriodId.periodUid, period).windowIndex
        return getOrAddSession(windowIndex, mediaPeriodId).sessionId
    }

    @Synchronized
    override fun belongsToSession(eventTime: EventTime, sessionId: String): Boolean {
        val sessionDescriptor = sessions[sessionId] ?: return false
        sessionDescriptor.maybeSetWindowSequenceNumber(
            eventTime.windowIndex,
            eventTime.mediaPeriodId
        )
        return sessionDescriptor.belongsToSession(eventTime.windowIndex, eventTime.mediaPeriodId)
    }

    @Synchronized
    override fun updateSessions(eventTime: EventTime) {
        val listener = listener!!
        if (eventTime.timeline.isEmpty) {
            // Don't try to create new sessions for empty timelines.
            return
        }
        if (eventTime.mediaPeriodId != null) {
            if (eventTime.mediaPeriodId.windowSequenceNumber != C.INDEX_UNSET.toLong()
                && eventTime.mediaPeriodId.windowSequenceNumber < this.minWindowSequenceNumber
            ) {
                // Ignore event because it is part of a past window that has already been finished.
                return
            }
            val currentSession = sessions[currentSessionId]
            if (currentSession != null && currentSession.windowSequenceNumber == C.INDEX_UNSET.toLong() && currentSession.windowIndex != eventTime.windowIndex) {
                // Ignore events for anything other than the current window before the first media period
                // has been created.
                return
            }
        }
        val eventSession =
            getOrAddSession(eventTime.windowIndex, eventTime.mediaPeriodId)
        if (currentSessionId == null) {
            currentSessionId = eventSession.sessionId
        }
        if (eventTime.mediaPeriodId != null && eventTime.mediaPeriodId.isAd) {
            // Ensure that the content session for an ad session is created first.
            val contentMediaPeriodId =
                MediaPeriodId(
                    eventTime.mediaPeriodId.periodUid,
                    eventTime.mediaPeriodId.windowSequenceNumber,
                    eventTime.mediaPeriodId.adGroupIndex
                )
            val contentSession =
                getOrAddSession(eventTime.windowIndex, contentMediaPeriodId)
            if (!contentSession.isCreated) {
                contentSession.isCreated = true
                eventTime.timeline.getPeriodByUid(eventTime.mediaPeriodId.periodUid, period)
                var adGroupPositionMs =
                    (usToMs(period.getAdGroupTimeUs(eventTime.mediaPeriodId.adGroupIndex))
                            + period.positionInWindowMs)
                // getAdGroupTimeUs may return 0 for prerolls despite period offset.
                adGroupPositionMs = max(0, adGroupPositionMs)
                val eventTimeForContent =
                    EventTime(
                        eventTime.realtimeMs,
                        eventTime.timeline,
                        eventTime.windowIndex,
                        contentMediaPeriodId,  
                        adGroupPositionMs,
                        eventTime.currentTimeline,
                        eventTime.currentWindowIndex,
                        eventTime.currentMediaPeriodId,
                        eventTime.currentPlaybackPositionMs,
                        eventTime.totalBufferedDurationMs
                    )
                listener.onSessionCreated(eventTimeForContent, contentSession.sessionId)
            }
        }
        if (!eventSession.isCreated) {
            eventSession.isCreated = true
            listener.onSessionCreated(eventTime, eventSession.sessionId)
        }
        if (eventSession.sessionId == currentSessionId && !eventSession.isActive) {
            eventSession.isActive = true
            listener.onSessionActive(eventTime, eventSession.sessionId)
        }
    }

    @Synchronized
    override fun updateSessionsWithTimelineChange(eventTime: EventTime) {
        val listener = listener!!
        val previousTimeline = currentTimeline
        currentTimeline = eventTime.timeline
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (!session.tryResolvingToNewTimeline(previousTimeline, currentTimeline)
                || session.isFinishedAtEventTime(eventTime)
            ) {
                iterator.remove()
                if (session.sessionId == currentSessionId) {
                    clearCurrentSession(session)
                }
                if (session.isCreated) {
                    listener.onSessionFinished(
                        eventTime, session.sessionId,  false
                    )
                }
            }
        }
        updateCurrentSession(eventTime)
    }

    @Synchronized
    override fun updateSessionsWithDiscontinuity(
        eventTime: EventTime, @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    ) {
        val listener = listener!!
        val hasAutomaticTransition = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (session.isFinishedAtEventTime(eventTime)) {
                iterator.remove()
                val isRemovingCurrentSession = session.sessionId == currentSessionId
                if (isRemovingCurrentSession) {
                    clearCurrentSession(session)
                }
                if (session.isCreated) {
                    val isAutomaticTransition =
                        hasAutomaticTransition && isRemovingCurrentSession && session.isActive
                    listener.onSessionFinished(
                        eventTime,
                        session.sessionId,
                        isAutomaticTransition
                    )
                }
            }
        }
        updateCurrentSession(eventTime)
    }

    override val activeSessionId: String?
        @Synchronized
        get() = currentSessionId

    @Synchronized
    override fun finishAllSessions(eventTime: EventTime) {
        if (currentSessionId != null) {
            clearCurrentSession(sessions[currentSessionId]!!)
        }
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            iterator.remove()
            if (session.isCreated && listener != null) {
                listener!!.onSessionFinished(
                    eventTime, session.sessionId,  false
                )
            }
        }
    }

    @RequiresNonNull("listener")
    private fun updateCurrentSession(eventTime: EventTime) {
        if (eventTime.timeline.isEmpty) {
            // Clear current session if the Timeline is empty.
            if (currentSessionId != null) {
                clearCurrentSession(sessions[currentSessionId]!!)
            }
            return
        }
        val previousSessionDescriptor = sessions[currentSessionId]
        val currentSessionDescriptor =
            getOrAddSession(eventTime.windowIndex, eventTime.mediaPeriodId)
        currentSessionId = currentSessionDescriptor.sessionId
        updateSessions(eventTime)
        if (eventTime.mediaPeriodId != null && eventTime.mediaPeriodId.isAd
            && (previousSessionDescriptor == null || (previousSessionDescriptor.windowSequenceNumber
                    != eventTime.mediaPeriodId.windowSequenceNumber) || previousSessionDescriptor.adMediaPeriodId == null || (previousSessionDescriptor.adMediaPeriodId!!.adGroupIndex
                    != eventTime.mediaPeriodId.adGroupIndex) || (previousSessionDescriptor.adMediaPeriodId!!.adIndexInAdGroup
                    != eventTime.mediaPeriodId.adIndexInAdGroup))
        ) {
            // New ad playback started. Find corresponding content session and notify ad playback started.
            val contentMediaPeriodId =
                MediaPeriodId(
                    eventTime.mediaPeriodId.periodUid, eventTime.mediaPeriodId.windowSequenceNumber
                )
            val contentSession =
                getOrAddSession(eventTime.windowIndex, contentMediaPeriodId)
            listener!!.onAdPlaybackStarted(
                eventTime, contentSession.sessionId, currentSessionDescriptor.sessionId
            )
        }
    }

    private fun clearCurrentSession(currentSession: SessionDescriptor) {
        if (currentSession.windowSequenceNumber != C.INDEX_UNSET.toLong() && currentSession.isCreated) {
            lastRemovedCurrentWindowSequenceNumber = currentSession.windowSequenceNumber
        }
        currentSessionId = null
    }

     val minWindowSequenceNumber: Long
        get() {
            val currentSession = sessions[currentSessionId]
            return if (currentSession != null && currentSession.windowSequenceNumber != C.INDEX_UNSET.toLong())
                currentSession.windowSequenceNumber
            else
                lastRemovedCurrentWindowSequenceNumber + 1
        }

    private fun getOrAddSession(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?
    ): SessionDescriptor {
        // There should only be one matching session if mediaPeriodId is non-null. If mediaPeriodId is
        // null, there may be multiple matching sessions with different window sequence numbers or
        // adMediaPeriodIds. The best match is the one with the smaller window sequence number, and for
        // windows with ads, the content session is preferred over ad sessions.
        var bestMatch: SessionDescriptor? = null
        var bestMatchWindowSequenceNumber = Long.MAX_VALUE
        for (sessionDescriptor in sessions.values) {
            sessionDescriptor.maybeSetWindowSequenceNumber(windowIndex, mediaPeriodId)
            if (sessionDescriptor.belongsToSession(windowIndex, mediaPeriodId)) {
                val windowSequenceNumber = sessionDescriptor.windowSequenceNumber
                if (windowSequenceNumber == C.INDEX_UNSET.toLong()
                    || windowSequenceNumber < bestMatchWindowSequenceNumber
                ) {
                    bestMatch = sessionDescriptor
                    bestMatchWindowSequenceNumber = windowSequenceNumber
                } else if (windowSequenceNumber == bestMatchWindowSequenceNumber && bestMatch!!.adMediaPeriodId != null && sessionDescriptor.adMediaPeriodId != null
                ) {
                    bestMatch = sessionDescriptor
                }
            }
        }
        if (bestMatch == null) {
            val sessionId = sessionIdGenerator.get()
            bestMatch = SessionDescriptor(sessionId, windowIndex, mediaPeriodId)
            sessions[sessionId] = bestMatch
        }
        return bestMatch
    }

    private inner class SessionDescriptor(
         val sessionId: String,
         var windowIndex: Int,
        mediaPeriodId: MediaPeriodId?
    ) {
         var windowSequenceNumber: Long
         var adMediaPeriodId:MediaPeriodId? = null
         var isCreated = false
         var isActive = false

        init {
            this.windowSequenceNumber =
                mediaPeriodId?.windowSequenceNumber ?: C.INDEX_UNSET.toLong()
            if (mediaPeriodId != null && mediaPeriodId.isAd) {
                this.adMediaPeriodId = mediaPeriodId
            }
        }

        fun tryResolvingToNewTimeline(oldTimeline: Timeline, newTimeline: Timeline): Boolean {
            windowIndex = resolveWindowIndexToNewTimeline(oldTimeline, newTimeline, windowIndex)
            if (windowIndex == C.INDEX_UNSET) {
                return false
            }
            if (adMediaPeriodId == null) {
                return true
            }
            val newPeriodIndex = newTimeline.getIndexOfPeriod(adMediaPeriodId!!.periodUid)
            return newPeriodIndex != C.INDEX_UNSET
        }

        fun belongsToSession(
            eventWindowIndex: Int, eventMediaPeriodId: MediaPeriodId?
        ): Boolean {
            if (eventMediaPeriodId == null || eventMediaPeriodId.windowSequenceNumber == C.INDEX_UNSET.toLong()) {
                // Events without concrete media period id are for all sessions of the same window.
                return eventWindowIndex == windowIndex
            }
            if (adMediaPeriodId == null) {
                // If this is a content session, only events for content with the same window sequence
                // number belong to this session.
                return !eventMediaPeriodId.isAd
                        && eventMediaPeriodId.windowSequenceNumber == windowSequenceNumber
            }
            // If this is an ad session, only events for this ad belong to the session.
            return eventMediaPeriodId.windowSequenceNumber == adMediaPeriodId!!.windowSequenceNumber && eventMediaPeriodId.adGroupIndex == adMediaPeriodId!!.adGroupIndex && eventMediaPeriodId.adIndexInAdGroup == adMediaPeriodId!!.adIndexInAdGroup
        }

        fun maybeSetWindowSequenceNumber(
            eventWindowIndex: Int, eventMediaPeriodId: MediaPeriodId?
        ) {
            if (windowSequenceNumber == C.INDEX_UNSET.toLong() && eventWindowIndex == windowIndex && eventMediaPeriodId != null && eventMediaPeriodId.windowSequenceNumber >= minWindowSequenceNumber) {
                // Set window sequence number for this session as soon as we have one.
                windowSequenceNumber = eventMediaPeriodId.windowSequenceNumber
            }
        }

        fun isFinishedAtEventTime(eventTime: EventTime): Boolean {
            if (eventTime.mediaPeriodId == null) {
                // For event times without media period id (e.g. after seek to new window), we only keep
                // sessions of this window.
                return windowIndex != eventTime.windowIndex
            }
            if (windowSequenceNumber == C.INDEX_UNSET.toLong()) {
                // Sessions with unspecified window sequence number are kept until we know more.
                return false
            }
            if (eventTime.mediaPeriodId.windowSequenceNumber > windowSequenceNumber) {
                // All past window sequence numbers are finished.
                return true
            }
            if (adMediaPeriodId == null) {
                // Current or future content is not finished.
                return false
            }
            val eventPeriodIndex =
                eventTime.timeline.getIndexOfPeriod(eventTime.mediaPeriodId.periodUid)
            val adPeriodIndex = eventTime.timeline.getIndexOfPeriod(adMediaPeriodId!!.periodUid)
            if (eventTime.mediaPeriodId.windowSequenceNumber < adMediaPeriodId!!.windowSequenceNumber
                || eventPeriodIndex < adPeriodIndex
            ) {
                // Ads in future windows or periods are not finished.
                return false
            }
            if (eventPeriodIndex > adPeriodIndex) {
                // Ads in past periods are finished.
                return true
            }
            if (eventTime.mediaPeriodId.isAd) {
                val eventAdGroup = eventTime.mediaPeriodId.adGroupIndex
                val eventAdIndex = eventTime.mediaPeriodId.adIndexInAdGroup
                // Finished if event is for an ad after this one in the same period.
                return eventAdGroup > adMediaPeriodId!!.adGroupIndex
                        || (eventAdGroup == adMediaPeriodId!!.adGroupIndex
                        && eventAdIndex > adMediaPeriodId!!.adIndexInAdGroup)
            } else {
                // Finished if the event is for content after this ad.
                return eventTime.mediaPeriodId.nextAdGroupIndex == C.INDEX_UNSET
                        || eventTime.mediaPeriodId.nextAdGroupIndex > adMediaPeriodId!!.adGroupIndex
            }
        }

        fun resolveWindowIndexToNewTimeline(
            oldTimeline: Timeline, newTimeline: Timeline, windowIndex: Int
        ): Int {
            if (windowIndex >= oldTimeline.windowCount) {
                return if (windowIndex < newTimeline.windowCount) windowIndex else C.INDEX_UNSET
            }
            oldTimeline.getWindow(windowIndex, window)
            for (periodIndex in window.firstPeriodIndex..window.lastPeriodIndex) {
                val periodUid = oldTimeline.getUidOfPeriod(periodIndex)
                val newPeriodIndex = newTimeline.getIndexOfPeriod(periodUid)
                if (newPeriodIndex != C.INDEX_UNSET) {
                    return newTimeline.getPeriod(newPeriodIndex, period).windowIndex
                }
            }
            return C.INDEX_UNSET
        }
    }

    companion object {
        val DEFAULT_SESSION_ID_GENERATOR: Supplier<String> = Supplier { generateDefaultSessionId() }
        private val RANDOM = Random()
        private const val SESSION_ID_LENGTH = 12

        private fun generateDefaultSessionId(): String {
            val randomBytes = ByteArray(SESSION_ID_LENGTH)
            RANDOM.nextBytes(randomBytes)
            return Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP)
        }
    }
}
