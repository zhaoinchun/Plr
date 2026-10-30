package androidx.media3.exoplayer.audio

import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.util.Util.getMediaDurationForPlayoutDuration
import androidx.media3.common.util.Util.sampleCountToDurationUs
import kotlin.math.abs

internal class AudioTimestampPoller(
    audioTrack: AudioTrack,
    private val errorListener: AudioTrackPositionTracker.Listener
) {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_INITIALIZING, STATE_TIMESTAMP, STATE_TIMESTAMP_ADVANCING, STATE_NO_TIMESTAMP, STATE_ERROR]
    )
    private annotation class State

    private val audioTimestamp: AudioTimestampWrapper = AudioTimestampWrapper(audioTrack)
    private val sampleRate: Int= audioTrack.sampleRate

    private var state: @State Int = 0
    private var initializeSystemTimeUs: Long = 0
    private var sampleIntervalUs: Long = 0
    private var lastTimestampSampleTimeUs: Long = 0
    private var initialTimestampPositionFrames: Long = 0
    private var initialTimestampSystemTimeUs: Long = 0

    init {
        reset()
    }

    fun maybePollTimestamp(
        systemTimeUs: Long,
        audioTrackPlaybackSpeed: Float,
        playbackHeadPositionEstimateUs: Long,
        forceUpdate: Boolean
    ) {
        if (!forceUpdate && (systemTimeUs - lastTimestampSampleTimeUs) < sampleIntervalUs) {
            return
        }
        lastTimestampSampleTimeUs = systemTimeUs
        val updatedTimestamp = audioTimestamp.maybeUpdateTimestamp()
        if (updatedTimestamp) {
            checkTimestampIsPlausibleAndUpdateErrorState(
                systemTimeUs, audioTrackPlaybackSpeed, playbackHeadPositionEstimateUs
            )
        }
        when (state) {
            STATE_INITIALIZING -> if (updatedTimestamp) {
                if (audioTimestamp.timestampSystemTimeUs >= initializeSystemTimeUs) {
                    // We have an initial timestamp, but don't know if it's advancing yet.
                    initialTimestampPositionFrames = audioTimestamp.timestampPositionFrames
                    initialTimestampSystemTimeUs = audioTimestamp.timestampSystemTimeUs
                    updateState(STATE_TIMESTAMP)
                }
            } else if (systemTimeUs - initializeSystemTimeUs > INITIALIZING_DURATION_US) {
                // We haven't received a timestamp for a while, so they probably aren't available for the
                // current audio route. Poll infrequently in case the route changes later.
                updateState(STATE_NO_TIMESTAMP)
            }

            STATE_TIMESTAMP -> if (updatedTimestamp) {
                if (isTimestampAdvancingFromInitialTimestamp(
                        systemTimeUs, audioTrackPlaybackSpeed
                    )
                ) {
                    updateState(STATE_TIMESTAMP_ADVANCING)
                } else if (systemTimeUs - initializeSystemTimeUs > WAIT_FOR_ADVANCE_DURATION_US) {
                    // Failed to find a correctly advancing timestamp. Only try again later after waiting
                    // for SLOW_POLL_INTERVAL_US.
                    updateState(STATE_NO_TIMESTAMP)
                } else {
                    // Not yet advancing, try again with the latest timestamp as the initial one.
                    initialTimestampPositionFrames = audioTimestamp.timestampPositionFrames
                    initialTimestampSystemTimeUs = audioTimestamp.timestampSystemTimeUs
                }
            } else {
                reset()
            }

            STATE_TIMESTAMP_ADVANCING -> if (!updatedTimestamp) {
                // The audio route may have changed, so reset polling.
                reset()
            }

            STATE_NO_TIMESTAMP -> if (updatedTimestamp) {
                // The audio route may have changed, so reset polling.
                reset()
            }

            STATE_ERROR -> {}
            else -> throw IllegalStateException()
        }
    }

    fun hasAdvancingTimestamp(): Boolean {
        return state == STATE_TIMESTAMP_ADVANCING
    }

    val isWaitingForAdvancingTimestamp: Boolean
        get() = state == STATE_INITIALIZING || state == STATE_TIMESTAMP

    fun reset() {
        updateState(STATE_INITIALIZING)
    }

    fun getTimestampPositionUs(systemTimeUs: Long, audioTrackPlaybackSpeed: Float): Long {
        return computeTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed)
    }

    fun expectTimestampFramePositionReset() {
        audioTimestamp.expectTimestampFramePositionReset()
    }

    private fun updateState(state: @State Int) {
        this.state = state
        when (state) {
            STATE_INITIALIZING -> {
                // Force polling a timestamp immediately, and poll quickly.
                lastTimestampSampleTimeUs = 0
                initialTimestampPositionFrames = C.INDEX_UNSET.toLong()
                initialTimestampSystemTimeUs = C.TIME_UNSET
                initializeSystemTimeUs = System.nanoTime() / 1000
                sampleIntervalUs = FAST_POLL_INTERVAL_US.toLong()
            }

            STATE_TIMESTAMP -> sampleIntervalUs = FAST_POLL_INTERVAL_US.toLong()
            STATE_TIMESTAMP_ADVANCING, STATE_NO_TIMESTAMP -> sampleIntervalUs =
                SLOW_POLL_INTERVAL_US.toLong()

            STATE_ERROR -> sampleIntervalUs = ERROR_POLL_INTERVAL_US.toLong()
            else -> throw IllegalStateException()
        }
    }

    private fun isTimestampAdvancingFromInitialTimestamp(
        systemTimeUs: Long, audioTrackPlaybackSpeed: Float
    ): Boolean {
        if (audioTimestamp.timestampPositionFrames <= initialTimestampPositionFrames) {
            // Reported timestamp hasn't been updated.
            return false
        }
        val positionEstimateUsingInitialTimestampUs =
            computeTimestampPositionUs(
                initialTimestampPositionFrames,
                initialTimestampSystemTimeUs,
                systemTimeUs,
                audioTrackPlaybackSpeed
            )
        val positionEstimateUsingCurrentTimestampUs =
            computeTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed)
        val positionDriftUs =
            abs(positionEstimateUsingCurrentTimestampUs - positionEstimateUsingInitialTimestampUs)
        return positionDriftUs < MAX_POSITION_DRIFT_ADVANCING_TIMESTAMP_US
    }

    private fun computeTimestampPositionUs(
        systemTimeUs: Long, audioTrackPlaybackSpeed: Float
    ): Long {
        return computeTimestampPositionUs(
            audioTimestamp.timestampPositionFrames,
            audioTimestamp.timestampSystemTimeUs,
            systemTimeUs,
            audioTrackPlaybackSpeed
        )
    }

    private fun computeTimestampPositionUs(
        timestampPositionFrames: Long,
        timestampSystemTimeUs: Long,
        systemTimeUs: Long,
        audioTrackPlaybackSpeed: Float
    ): Long {
        val timestampPositionUs = sampleCountToDurationUs(timestampPositionFrames, sampleRate)
        var elapsedSinceTimestampUs = systemTimeUs - timestampSystemTimeUs
        elapsedSinceTimestampUs =
            getMediaDurationForPlayoutDuration(elapsedSinceTimestampUs, audioTrackPlaybackSpeed)
        return timestampPositionUs + elapsedSinceTimestampUs
    }

    private fun checkTimestampIsPlausibleAndUpdateErrorState(
        systemTimeUs: Long, audioTrackPlaybackSpeed: Float, playbackHeadPositionEstimateUs: Long
    ) {
        val timestampSystemTimeUs = audioTimestamp.timestampSystemTimeUs
        val timestampPositionUs = computeTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed)
        if (abs(timestampSystemTimeUs - systemTimeUs) > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
            errorListener.onSystemTimeUsMismatch(
                audioTimestamp.timestampPositionFrames,
                timestampSystemTimeUs,
                systemTimeUs,
                playbackHeadPositionEstimateUs
            )
            updateState(STATE_ERROR)
        } else if (abs(timestampPositionUs - playbackHeadPositionEstimateUs) > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
            errorListener.onPositionFramesMismatch(
                audioTimestamp.timestampPositionFrames,
                timestampSystemTimeUs,
                systemTimeUs,
                playbackHeadPositionEstimateUs
            )
            updateState(STATE_ERROR)
        } else if (state == STATE_ERROR) {
            reset()
        }
    }

    private class AudioTimestampWrapper(private val audioTrack: AudioTrack) {
        private val audioTimestamp: AudioTimestamp = AudioTimestamp()

        private var rawTimestampFramePositionWrapCount: Long = 0
        private var lastTimestampRawPositionFrames: Long = 0
        var timestampPositionFrames: Long = 0
            private set
        private var expectTimestampFramePositionReset = false
        private var accumulatedRawTimestampFramePosition: Long = 0

        fun maybeUpdateTimestamp(): Boolean {
            val updated = audioTrack.getTimestamp(audioTimestamp)
            if (updated) {
                val rawPositionFrames = audioTimestamp.framePosition
                if (lastTimestampRawPositionFrames > rawPositionFrames) {
                    if (expectTimestampFramePositionReset) {
                        accumulatedRawTimestampFramePosition += lastTimestampRawPositionFrames
                        expectTimestampFramePositionReset = false
                    } else {
                        // The value must have wrapped around.
                        rawTimestampFramePositionWrapCount++
                    }
                }
                lastTimestampRawPositionFrames = rawPositionFrames
                this.timestampPositionFrames =
                    (rawPositionFrames
                            + accumulatedRawTimestampFramePosition
                            + (rawTimestampFramePositionWrapCount shl 32))
            }
            return updated
        }

        val timestampSystemTimeUs: Long
            get() = audioTimestamp.nanoTime / 1000

        fun expectTimestampFramePositionReset() {
            expectTimestampFramePositionReset = true
        }
    }

    companion object {
        private const val STATE_INITIALIZING = 0
        private const val STATE_TIMESTAMP = 1
        private const val STATE_TIMESTAMP_ADVANCING = 2
        private const val STATE_NO_TIMESTAMP = 3
        private const val STATE_ERROR = 4
        private const val FAST_POLL_INTERVAL_US = 10000
        private const val SLOW_POLL_INTERVAL_US = 10000000
        private const val ERROR_POLL_INTERVAL_US = 500000
        private const val INITIALIZING_DURATION_US = 500000
        private const val MAX_POSITION_DRIFT_ADVANCING_TIMESTAMP_US: Long = 1000
        private const val WAIT_FOR_ADVANCE_DURATION_US = 2000000
        private const val MAX_AUDIO_TIMESTAMP_OFFSET_US = 5 * C.MICROS_PER_SECOND
    }
}
