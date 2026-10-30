package androidx.media3.exoplayer.analytics

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class PlaybackStats internal constructor(
    val playbackCount: Int,
    private val playbackStateDurationsMs: LongArray,
    val playbackStateHistory: MutableList<EventTimeAndPlaybackState>,
    mediaTimeHistory: MutableList<LongArray>,
    val firstReportedTimeMs: Long,
    val foregroundPlaybackCount: Int,
    val abandonedBeforeReadyCount: Int,
    val endedCount: Int,
    val backgroundJoiningCount: Int,
    val totalValidJoinTimeMs: Long,
    val validJoinTimeCount: Int,
    val totalPauseCount: Int,
    val totalPauseBufferCount: Int,
    val totalSeekCount: Int,
    val totalRebufferCount: Int,
    val maxRebufferTimeMs: Long,
    val adPlaybackCount: Int,
    videoFormatHistory: MutableList<EventTimeAndFormat>,
    audioFormatHistory: MutableList<EventTimeAndFormat>,
    val totalVideoFormatHeightTimeMs: Long,
    val totalVideoFormatHeightTimeProduct: Long,
    val totalVideoFormatBitrateTimeMs: Long,
    val totalVideoFormatBitrateTimeProduct: Long,
    val totalAudioFormatTimeMs: Long,
    val totalAudioFormatBitrateTimeProduct: Long,
    val initialVideoFormatHeightCount: Int,
    val initialVideoFormatBitrateCount: Int,
    val totalInitialVideoFormatHeight: Int,
    val totalInitialVideoFormatBitrate: Long,
    val initialAudioFormatBitrateCount: Int,
    val totalInitialAudioFormatBitrate: Long,
    val totalBandwidthTimeMs: Long,
    val totalBandwidthBytes: Long,
    val totalDroppedFrames: Long,
    val totalAudioUnderruns: Long,
    val fatalErrorPlaybackCount: Int,
    val fatalErrorCount: Int,
    val nonFatalErrorCount: Int,
    fatalErrorHistory: MutableList<EventTimeAndException>,
    nonFatalErrorHistory: MutableList<EventTimeAndException>
) {
    class EventTimeAndPlaybackState(
        val eventTime: EventTime,
        val playbackState: @PlaybackState Int
    ) {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val that = o as EventTimeAndPlaybackState
            if (playbackState != that.playbackState) {
                return false
            }
            return eventTime == that.eventTime
        }

        override fun hashCode(): Int {
            var result = eventTime.hashCode()
            result = 31 * result + playbackState
            return result
        }
    }

    class EventTimeAndFormat(val eventTime: EventTime, val format: Format?) {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val that = o as EventTimeAndFormat
            if (eventTime != that.eventTime) {
                return false
            }
            return format?.equals(that.format) ?: (that.format == null)
        }

        override fun hashCode(): Int {
            var result = eventTime.hashCode()
            result = 31 * result + (format?.hashCode() ?: 0)
            return result
        }
    }

    class EventTimeAndException(val eventTime: EventTime, val exception: Exception) {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val that = o as EventTimeAndException
            if (eventTime != that.eventTime) {
                return false
            }
            return exception == that.exception
        }

        override fun hashCode(): Int {
            var result = eventTime.hashCode()
            result = 31 * result + exception.hashCode()
            return result
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [PLAYBACK_STATE_NOT_STARTED, PLAYBACK_STATE_JOINING_BACKGROUND, PLAYBACK_STATE_JOINING_FOREGROUND, PLAYBACK_STATE_PLAYING, PLAYBACK_STATE_PAUSED, PLAYBACK_STATE_SEEKING, PLAYBACK_STATE_BUFFERING, PLAYBACK_STATE_PAUSED_BUFFERING, PLAYBACK_STATE_SUPPRESSED, PLAYBACK_STATE_SUPPRESSED_BUFFERING, PLAYBACK_STATE_ENDED, PLAYBACK_STATE_STOPPED, PLAYBACK_STATE_FAILED, PLAYBACK_STATE_INTERRUPTED_BY_AD, PLAYBACK_STATE_ABANDONED]
    )
    internal annotation class PlaybackState

    val mediaTimeHistory: List<LongArray?> = mediaTimeHistory.toList()
    val videoFormatHistory: List<EventTimeAndFormat?> = videoFormatHistory.toList()
    val audioFormatHistory: List<EventTimeAndFormat?> = audioFormatHistory.toList()
    val fatalErrorHistory: List<EventTimeAndException?> = fatalErrorHistory.toList()
    val nonFatalErrorHistory: List<EventTimeAndException?> = nonFatalErrorHistory.toList()

    fun getPlaybackStateDurationMs(playbackState: @PlaybackState Int): Long {
        return playbackStateDurationsMs[playbackState]
    }

    fun getPlaybackStateAtTime(realtimeMs: Long): @PlaybackState Int {
        var state: @PlaybackState Int = PLAYBACK_STATE_NOT_STARTED
        for (timeAndState in playbackStateHistory) {
            if (timeAndState.eventTime.realtimeMs > realtimeMs) {
                break
            }
            state = timeAndState.playbackState
        }
        return state
    }

    fun getMediaTimeMsAtRealtimeMs(realtimeMs: Long): Long {
        if (mediaTimeHistory.isEmpty()) {
            return C.TIME_UNSET
        }
        var nextIndex = 0
        while (nextIndex < mediaTimeHistory.size
            && mediaTimeHistory[nextIndex]!![0] <= realtimeMs
        ) {
            nextIndex++
        }
        if (nextIndex == 0) {
            return mediaTimeHistory[0]!![1]
        }
        if (nextIndex == mediaTimeHistory.size) {
            return mediaTimeHistory[mediaTimeHistory.size - 1]!![1]
        }
        val prevRealtimeMs = mediaTimeHistory[nextIndex - 1]!![0]
        val prevMediaTimeMs = mediaTimeHistory[nextIndex - 1]!![1]
        val nextRealtimeMs = mediaTimeHistory[nextIndex]!![0]
        val nextMediaTimeMs = mediaTimeHistory[nextIndex]!![1]
        val realtimeDurationMs = nextRealtimeMs - prevRealtimeMs
        if (realtimeDurationMs == 0L) {
            return prevMediaTimeMs
        }
        val fraction = (realtimeMs - prevRealtimeMs).toFloat() / realtimeDurationMs
        return prevMediaTimeMs + ((nextMediaTimeMs - prevMediaTimeMs) * fraction).toLong()
    }

    val meanJoinTimeMs: Long
        get() = if (validJoinTimeCount == 0) C.TIME_UNSET else totalValidJoinTimeMs / validJoinTimeCount

    val totalJoinTimeMs: Long
        get() = getPlaybackStateDurationMs(PLAYBACK_STATE_JOINING_FOREGROUND)

    val totalPlayTimeMs: Long
        get() = getPlaybackStateDurationMs(PLAYBACK_STATE_PLAYING)

    val meanPlayTimeMs: Long
        get() = if (foregroundPlaybackCount == 0)
            C.TIME_UNSET
        else
            this.totalPlayTimeMs / foregroundPlaybackCount

    val totalPausedTimeMs: Long
        get() = (getPlaybackStateDurationMs(PLAYBACK_STATE_PAUSED)
                + getPlaybackStateDurationMs(PLAYBACK_STATE_PAUSED_BUFFERING))

    val meanPausedTimeMs: Long
        get() = if (foregroundPlaybackCount == 0)
            C.TIME_UNSET
        else
            this.totalPausedTimeMs / foregroundPlaybackCount

    val totalRebufferTimeMs: Long
        get() = getPlaybackStateDurationMs(PLAYBACK_STATE_BUFFERING)

    val meanRebufferTimeMs: Long
        get() = if (foregroundPlaybackCount == 0)
            C.TIME_UNSET
        else
            this.totalRebufferTimeMs / foregroundPlaybackCount

    val meanSingleRebufferTimeMs: Long
        get() = if (totalRebufferCount == 0)
            C.TIME_UNSET
        else
            ((getPlaybackStateDurationMs(PLAYBACK_STATE_BUFFERING)
                    + getPlaybackStateDurationMs(PLAYBACK_STATE_PAUSED_BUFFERING))
                    / totalRebufferCount)

    val totalSeekTimeMs: Long
        get() = getPlaybackStateDurationMs(PLAYBACK_STATE_SEEKING)

    val meanSeekTimeMs: Long
        get() = if (foregroundPlaybackCount == 0)
            C.TIME_UNSET
        else
            this.totalSeekTimeMs / foregroundPlaybackCount

    val meanSingleSeekTimeMs: Long
        get() = if (totalSeekCount == 0) C.TIME_UNSET else this.totalSeekTimeMs / totalSeekCount

    val totalWaitTimeMs: Long
        get() = (getPlaybackStateDurationMs(PLAYBACK_STATE_JOINING_FOREGROUND)
                + getPlaybackStateDurationMs(PLAYBACK_STATE_BUFFERING)
                + getPlaybackStateDurationMs(PLAYBACK_STATE_SEEKING))

    val meanWaitTimeMs: Long
        get() = if (foregroundPlaybackCount == 0)
            C.TIME_UNSET
        else
            this.totalWaitTimeMs / foregroundPlaybackCount

    val totalPlayAndWaitTimeMs: Long
        get() = this.totalPlayTimeMs + this.totalWaitTimeMs

    val meanPlayAndWaitTimeMs: Long
        get() = if (foregroundPlaybackCount == 0)
            C.TIME_UNSET
        else
            this.totalPlayAndWaitTimeMs / foregroundPlaybackCount

    val totalElapsedTimeMs: Long
        get() {
            var totalTimeMs: Long = 0
            for (i in 0..<PLAYBACK_STATE_COUNT) {
                totalTimeMs += playbackStateDurationsMs[i]
            }
            return totalTimeMs
        }

    val meanElapsedTimeMs: Long
        get() = if (playbackCount == 0) C.TIME_UNSET else this.totalElapsedTimeMs / playbackCount

    val abandonedBeforeReadyRatio: Float
        get() {
            val foregroundAbandonedBeforeReady =
                abandonedBeforeReadyCount - (playbackCount - foregroundPlaybackCount)
            return if (foregroundPlaybackCount == 0)
                0f
            else
                foregroundAbandonedBeforeReady.toFloat() / foregroundPlaybackCount
        }

    val endedRatio: Float
        get() = if (foregroundPlaybackCount == 0) 0f else endedCount.toFloat() / foregroundPlaybackCount

    val meanPauseCount: Float
        get() = if (foregroundPlaybackCount == 0) 0f else totalPauseCount.toFloat() / foregroundPlaybackCount

    val meanPauseBufferCount: Float
        get() = if (foregroundPlaybackCount == 0)
            0f
        else
            totalPauseBufferCount.toFloat() / foregroundPlaybackCount

    val meanSeekCount: Float
        get() = if (foregroundPlaybackCount == 0) 0f else totalSeekCount.toFloat() / foregroundPlaybackCount

    val meanRebufferCount: Float
        get() = if (foregroundPlaybackCount == 0) 0f else totalRebufferCount.toFloat() / foregroundPlaybackCount

    val waitTimeRatio: Float
        get() {
            val playAndWaitTimeMs = this.totalPlayAndWaitTimeMs
            return if (playAndWaitTimeMs == 0L) 0f else this.totalWaitTimeMs.toFloat() / playAndWaitTimeMs
        }

    val joinTimeRatio: Float
        get() {
            val playAndWaitTimeMs = this.totalPlayAndWaitTimeMs
            return if (playAndWaitTimeMs == 0L) 0f else this.totalJoinTimeMs.toFloat() / playAndWaitTimeMs
        }

    val rebufferTimeRatio: Float
        get() {
            val playAndWaitTimeMs = this.totalPlayAndWaitTimeMs
            return if (playAndWaitTimeMs == 0L) 0f else this.totalRebufferTimeMs.toFloat() / playAndWaitTimeMs
        }

    val seekTimeRatio: Float
        get() {
            val playAndWaitTimeMs = this.totalPlayAndWaitTimeMs
            return if (playAndWaitTimeMs == 0L) 0f else this.totalSeekTimeMs.toFloat() / playAndWaitTimeMs
        }

    val rebufferRate: Float
        get() {
            val playTimeMs = this.totalPlayTimeMs
            return if (playTimeMs == 0L) 0f else 1000f * totalRebufferCount / playTimeMs
        }

    val meanTimeBetweenRebuffers: Float
        get() = 1f / this.rebufferRate

    val meanInitialVideoFormatHeight: Int
        get() = if (initialVideoFormatHeightCount == 0)
            C.LENGTH_UNSET
        else
            totalInitialVideoFormatHeight / initialVideoFormatHeightCount

    val meanInitialVideoFormatBitrate: Int
        get() = if (initialVideoFormatBitrateCount == 0)
            C.LENGTH_UNSET
        else (totalInitialVideoFormatBitrate / initialVideoFormatBitrateCount).toInt()

    val meanInitialAudioFormatBitrate: Int
        get() = if (initialAudioFormatBitrateCount == 0)
            C.LENGTH_UNSET
        else (totalInitialAudioFormatBitrate / initialAudioFormatBitrateCount).toInt()

    val meanVideoFormatHeight: Int
        get() = if (totalVideoFormatHeightTimeMs == 0L)
            C.LENGTH_UNSET
        else (totalVideoFormatHeightTimeProduct / totalVideoFormatHeightTimeMs).toInt()

    val meanVideoFormatBitrate: Int
        get() = if (totalVideoFormatBitrateTimeMs == 0L)
            C.LENGTH_UNSET
        else (totalVideoFormatBitrateTimeProduct / totalVideoFormatBitrateTimeMs).toInt()

    val meanAudioFormatBitrate: Int
        get() = if (totalAudioFormatTimeMs == 0L)
            C.LENGTH_UNSET
        else (totalAudioFormatBitrateTimeProduct / totalAudioFormatTimeMs).toInt()

    val meanBandwidth: Int
        get() = if (totalBandwidthTimeMs == 0L)
            C.LENGTH_UNSET
        else (totalBandwidthBytes * 8000 / totalBandwidthTimeMs).toInt()

    val droppedFramesRate: Float
        get() {
            val playTimeMs = this.totalPlayTimeMs
            return if (playTimeMs == 0L) 0f else 1000f * totalDroppedFrames / playTimeMs
        }

    val audioUnderrunRate: Float
        get() {
            val playTimeMs = this.totalPlayTimeMs
            return if (playTimeMs == 0L) 0f else 1000f * totalAudioUnderruns / playTimeMs
        }

    val fatalErrorRatio: Float
        get() = if (foregroundPlaybackCount == 0)
            0f
        else
            fatalErrorPlaybackCount.toFloat() / foregroundPlaybackCount

    val fatalErrorRate: Float
        get() {
            val playTimeMs = this.totalPlayTimeMs
            return if (playTimeMs == 0L) 0f else 1000f * fatalErrorCount / playTimeMs
        }

    val meanTimeBetweenFatalErrors: Float
        get() = 1f / this.fatalErrorRate

    val meanNonFatalErrorCount: Float
        get() = if (foregroundPlaybackCount == 0) 0f else nonFatalErrorCount.toFloat() / foregroundPlaybackCount

    val nonFatalErrorRate: Float
        get() {
            val playTimeMs = this.totalPlayTimeMs
            return if (playTimeMs == 0L) 0f else 1000f * nonFatalErrorCount / playTimeMs
        }

    val meanTimeBetweenNonFatalErrors: Float
        get() = 1f / this.nonFatalErrorRate

    companion object {

        const val PLAYBACK_STATE_NOT_STARTED: Int = 0

        const val PLAYBACK_STATE_JOINING_BACKGROUND: Int = 1

        const val PLAYBACK_STATE_JOINING_FOREGROUND: Int = 2

        const val PLAYBACK_STATE_PLAYING: Int = 3

        const val PLAYBACK_STATE_PAUSED: Int = 4

        const val PLAYBACK_STATE_SEEKING: Int = 5

        const val PLAYBACK_STATE_BUFFERING: Int = 6

        const val PLAYBACK_STATE_PAUSED_BUFFERING: Int = 7

        const val PLAYBACK_STATE_SUPPRESSED: Int = 9

        const val PLAYBACK_STATE_SUPPRESSED_BUFFERING: Int = 10

        const val PLAYBACK_STATE_ENDED: Int = 11

        const val PLAYBACK_STATE_STOPPED: Int = 12

        const val PLAYBACK_STATE_FAILED: Int = 13

        const val PLAYBACK_STATE_INTERRUPTED_BY_AD: Int = 14

        const val PLAYBACK_STATE_ABANDONED: Int = 15

        const val PLAYBACK_STATE_COUNT: Int = 16

        val EMPTY: PlaybackStats = merge(arrayOf())

        fun merge(playbackStats: Array<PlaybackStats>): PlaybackStats {
            var playbackCount = 0
            val playbackStateDurationsMs = LongArray(PLAYBACK_STATE_COUNT)
            var firstReportedTimeMs = C.TIME_UNSET
            var foregroundPlaybackCount = 0
            var abandonedBeforeReadyCount = 0
            var endedCount = 0
            var backgroundJoiningCount = 0
            var totalValidJoinTimeMs = C.TIME_UNSET
            var validJoinTimeCount = 0
            var totalPauseCount = 0
            var totalPauseBufferCount = 0
            var totalSeekCount = 0
            var totalRebufferCount = 0
            var maxRebufferTimeMs = C.TIME_UNSET
            var adPlaybackCount = 0
            var totalVideoFormatHeightTimeMs: Long = 0
            var totalVideoFormatHeightTimeProduct: Long = 0
            var totalVideoFormatBitrateTimeMs: Long = 0
            var totalVideoFormatBitrateTimeProduct: Long = 0
            var totalAudioFormatTimeMs: Long = 0
            var totalAudioFormatBitrateTimeProduct: Long = 0
            var initialVideoFormatHeightCount = 0
            var initialVideoFormatBitrateCount = 0
            var totalInitialVideoFormatHeight = C.LENGTH_UNSET
            var totalInitialVideoFormatBitrate = C.LENGTH_UNSET.toLong()
            var initialAudioFormatBitrateCount = 0
            var totalInitialAudioFormatBitrate = C.LENGTH_UNSET.toLong()
            var totalBandwidthTimeMs: Long = 0
            var totalBandwidthBytes: Long = 0
            var totalDroppedFrames: Long = 0
            var totalAudioUnderruns: Long = 0
            var fatalErrorPlaybackCount = 0
            var fatalErrorCount = 0
            var nonFatalErrorCount = 0
            for (stats in playbackStats) {
                playbackCount += stats.playbackCount
                for (i in 0..<PLAYBACK_STATE_COUNT) {
                    playbackStateDurationsMs[i] += stats.playbackStateDurationsMs[i]
                }
                if (firstReportedTimeMs == C.TIME_UNSET) {
                    firstReportedTimeMs = stats.firstReportedTimeMs
                } else if (stats.firstReportedTimeMs != C.TIME_UNSET) {
                    firstReportedTimeMs = min(firstReportedTimeMs, stats.firstReportedTimeMs)
                }
                foregroundPlaybackCount += stats.foregroundPlaybackCount
                abandonedBeforeReadyCount += stats.abandonedBeforeReadyCount
                endedCount += stats.endedCount
                backgroundJoiningCount += stats.backgroundJoiningCount
                if (totalValidJoinTimeMs == C.TIME_UNSET) {
                    totalValidJoinTimeMs = stats.totalValidJoinTimeMs
                } else if (stats.totalValidJoinTimeMs != C.TIME_UNSET) {
                    totalValidJoinTimeMs += stats.totalValidJoinTimeMs
                }
                validJoinTimeCount += stats.validJoinTimeCount
                totalPauseCount += stats.totalPauseCount
                totalPauseBufferCount += stats.totalPauseBufferCount
                totalSeekCount += stats.totalSeekCount
                totalRebufferCount += stats.totalRebufferCount
                if (maxRebufferTimeMs == C.TIME_UNSET) {
                    maxRebufferTimeMs = stats.maxRebufferTimeMs
                } else if (stats.maxRebufferTimeMs != C.TIME_UNSET) {
                    maxRebufferTimeMs = max(maxRebufferTimeMs, stats.maxRebufferTimeMs)
                }
                adPlaybackCount += stats.adPlaybackCount
                totalVideoFormatHeightTimeMs += stats.totalVideoFormatHeightTimeMs
                totalVideoFormatHeightTimeProduct += stats.totalVideoFormatHeightTimeProduct
                totalVideoFormatBitrateTimeMs += stats.totalVideoFormatBitrateTimeMs
                totalVideoFormatBitrateTimeProduct += stats.totalVideoFormatBitrateTimeProduct
                totalAudioFormatTimeMs += stats.totalAudioFormatTimeMs
                totalAudioFormatBitrateTimeProduct += stats.totalAudioFormatBitrateTimeProduct
                initialVideoFormatHeightCount += stats.initialVideoFormatHeightCount
                initialVideoFormatBitrateCount += stats.initialVideoFormatBitrateCount
                if (totalInitialVideoFormatHeight == C.LENGTH_UNSET) {
                    totalInitialVideoFormatHeight = stats.totalInitialVideoFormatHeight
                } else if (stats.totalInitialVideoFormatHeight != C.LENGTH_UNSET) {
                    totalInitialVideoFormatHeight += stats.totalInitialVideoFormatHeight
                }
                if (totalInitialVideoFormatBitrate == C.LENGTH_UNSET.toLong()) {
                    totalInitialVideoFormatBitrate = stats.totalInitialVideoFormatBitrate
                } else if (stats.totalInitialVideoFormatBitrate != C.LENGTH_UNSET.toLong()) {
                    totalInitialVideoFormatBitrate += stats.totalInitialVideoFormatBitrate
                }
                initialAudioFormatBitrateCount += stats.initialAudioFormatBitrateCount
                if (totalInitialAudioFormatBitrate == C.LENGTH_UNSET.toLong()) {
                    totalInitialAudioFormatBitrate = stats.totalInitialAudioFormatBitrate
                } else if (stats.totalInitialAudioFormatBitrate != C.LENGTH_UNSET.toLong()) {
                    totalInitialAudioFormatBitrate += stats.totalInitialAudioFormatBitrate
                }
                totalBandwidthTimeMs += stats.totalBandwidthTimeMs
                totalBandwidthBytes += stats.totalBandwidthBytes
                totalDroppedFrames += stats.totalDroppedFrames
                totalAudioUnderruns += stats.totalAudioUnderruns
                fatalErrorPlaybackCount += stats.fatalErrorPlaybackCount
                fatalErrorCount += stats.fatalErrorCount
                nonFatalErrorCount += stats.nonFatalErrorCount
            }
            return PlaybackStats(
                playbackCount,
                playbackStateDurationsMs,  
                mutableListOf(),  
                mutableListOf(),
                firstReportedTimeMs,
                foregroundPlaybackCount,
                abandonedBeforeReadyCount,
                endedCount,
                backgroundJoiningCount,
                totalValidJoinTimeMs,
                validJoinTimeCount,
                totalPauseCount,
                totalPauseBufferCount,
                totalSeekCount,
                totalRebufferCount,
                maxRebufferTimeMs,
                adPlaybackCount,  
                mutableListOf(),  
                mutableListOf(),
                totalVideoFormatHeightTimeMs,
                totalVideoFormatHeightTimeProduct,
                totalVideoFormatBitrateTimeMs,
                totalVideoFormatBitrateTimeProduct,
                totalAudioFormatTimeMs,
                totalAudioFormatBitrateTimeProduct,
                initialVideoFormatHeightCount,
                initialVideoFormatBitrateCount,
                totalInitialVideoFormatHeight,
                totalInitialVideoFormatBitrate,
                initialAudioFormatBitrateCount,
                totalInitialAudioFormatBitrate,
                totalBandwidthTimeMs,
                totalBandwidthBytes,
                totalDroppedFrames,
                totalAudioUnderruns,
                fatalErrorPlaybackCount,
                fatalErrorCount,
                nonFatalErrorCount,  
                mutableListOf(),  
                mutableListOf()
            )
        }
    }
}
