package androidx.media3.exoplayer

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.usToMs
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import kotlin.concurrent.Volatile

data class PlaybackInfo(
    val timeline: Timeline,
    val periodId: MediaPeriodId,
    val requestedContentPositionUs: Long,
    val discontinuityStartPositionUs: Long,
    val playbackState: @Player.State Int,
    val playbackError: ExoPlaybackException?,
    val isLoading: Boolean,
    val trackGroups: TrackGroupArray?,
    val trackSelectorResult: TrackSelectorResult?,
    val staticMetadata: List<Metadata>?,
    val loadingMediaPeriodId: MediaPeriodId,
    val playWhenReady: Boolean,
    val playWhenReadyChangeReason: @Player.PlayWhenReadyChangeReason Int,
    val playbackSuppressionReason: @Player.PlaybackSuppressionReason Int,
    val playbackParameters: PlaybackParameters,
    @field:Volatile var bufferedPositionUs: Long,
    @field:Volatile var totalBufferedDurationUs: Long,
    @field:Volatile var positionUs: Long,
    @field:Volatile var positionUpdateTimeMs: Long,
    val sleepingForOffload: Boolean
) {
    fun copyWithNewPosition(
        periodId: MediaPeriodId,
        positionUs: Long,
        requestedContentPositionUs: Long,
        discontinuityStartPositionUs: Long,
        totalBufferedDurationUs: Long,
        trackGroups: TrackGroupArray?,
        trackSelectorResult: TrackSelectorResult?,
        staticMetadata: List<Metadata>?
    ): PlaybackInfo {
        return copy(
            periodId = periodId,
            positionUs = positionUs,
            requestedContentPositionUs = requestedContentPositionUs,
            discontinuityStartPositionUs = discontinuityStartPositionUs,
            totalBufferedDurationUs = totalBufferedDurationUs,
            trackGroups = trackGroups,
            trackSelectorResult = trackSelectorResult,
            staticMetadata = staticMetadata,
            positionUpdateTimeMs = SystemClock.elapsedRealtime()
        )
    }

    fun copyWithTimeline(timeline: Timeline): PlaybackInfo {
        return copy(timeline = timeline)
    }

    fun copyWithPlaybackState(playbackState: Int): PlaybackInfo {
        return copy(playbackState = playbackState)
    }

    fun copyWithPlaybackError(playbackError: ExoPlaybackException?): PlaybackInfo {
        return copy(playbackError = playbackError)
    }

    fun copyWithIsLoading(isLoading: Boolean): PlaybackInfo {
        return copy(isLoading = isLoading)
    }

    fun copyWithLoadingMediaPeriodId(loadingMediaPeriodId: MediaPeriodId): PlaybackInfo {
        return copy(loadingMediaPeriodId = loadingMediaPeriodId)
    }

    fun copyWithPlayWhenReady(
        playWhenReady: Boolean,
        playWhenReadyChangeReason: @Player.PlayWhenReadyChangeReason Int,
        playbackSuppressionReason: @Player.PlaybackSuppressionReason Int
    ): PlaybackInfo {
        return copy(
            playWhenReady = playWhenReady,
            playWhenReadyChangeReason = playWhenReadyChangeReason,
            playbackSuppressionReason = playbackSuppressionReason
        )
    }

    fun copyWithPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackInfo {
        return copy(playbackParameters = playbackParameters)
    }

    fun copyWithSleepingForOffload(sleepingForOffload: Boolean): PlaybackInfo {
        return copy(sleepingForOffload = sleepingForOffload)
    }

    fun copyWithEstimatedPosition(): PlaybackInfo {
        return copy(positionUpdateTimeMs = SystemClock.elapsedRealtime())
    }

    fun updatePositionUs(positionUs: Long) {
        // Write order of positionUs then positionUpdateTimeMs in order to be reverse of
        // retrieval in getExtrapolatedPositionUs().
        this.positionUs = positionUs
        this.positionUpdateTimeMs = SystemClock.elapsedRealtime()
    }

    val estimatedPositionUs: Long
        get() {
            if (!this.isPlaying) {
                return this.positionUs
            }

            // Snapshot of volatile position info
            var positionUs: Long
            var positionUpdateTimeMs: Long
            do {
                // Read order of positionUpdateTimeMs then positionUs to be reverse of updatePositionUs write.
                positionUpdateTimeMs = this.positionUpdateTimeMs
                positionUs = this.positionUs
            } while (positionUpdateTimeMs != this.positionUpdateTimeMs)

            val elapsedTimeMs =
                SystemClock.elapsedRealtime() - positionUpdateTimeMs
            val estimatedPositionMs =
                usToMs(positionUs) + (elapsedTimeMs * playbackParameters.speed).toLong()
            return msToUs(estimatedPositionMs)
        }

    val isPlaying: Boolean
        get() = playbackState == Player.STATE_READY && playWhenReady
                && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    override fun equals(other: Any?): Boolean {
        return super.equals(other)
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }

    companion object {
        val dummyPeriodForEmptyTimeline: MediaPeriodId = MediaPeriodId(Any())

        fun createDummy(emptyTrackSelectorResult: TrackSelectorResult?): PlaybackInfo {
            return PlaybackInfo(
                Timeline.EMPTY,
                dummyPeriodForEmptyTimeline,
                C.TIME_UNSET,
                0,
                Player.STATE_IDLE,
                null,
                false,
                TrackGroupArray.EMPTY,
                emptyTrackSelectorResult,
                listOf(),
                dummyPeriodForEmptyTimeline,
                false,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                PlaybackParameters.DEFAULT,
                0,
                0,
                0,
                0,
                false
            )
        }
    }
}
