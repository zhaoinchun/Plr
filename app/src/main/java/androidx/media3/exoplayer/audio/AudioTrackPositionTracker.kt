package androidx.media3.exoplayer.audio

import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.durationUsToSampleCount
import androidx.media3.common.util.Util.getMediaDurationForPlayoutDuration
import androidx.media3.common.util.Util.getPlayoutDurationForMediaDuration
import androidx.media3.common.util.Util.isEncodingLinearPcm
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.sampleCountToDurationUs
import androidx.media3.common.util.Util.usToMs
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class AudioTrackPositionTracker(
    private val listener: Listener,
    private val clock: Clock,
    private val audioTrack: AudioTrack,
    outputEncoding: @C.Encoding Int,
    outputPcmFrameSize: Int,
    bufferSize: Int
) {
    interface Listener {
        fun onPositionAdvancing(playoutStartSystemTimeMs: Long)
        fun onPositionFramesMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        )

        fun onSystemTimeUsMismatch(
            audioTimestampPositionFrames: Long,
            audioTimestampSystemTimeUs: Long,
            systemTimeUs: Long,
            playbackPositionUs: Long
        )

        fun onInvalidLatency(latencyUs: Long)
    }

    private val playheadOffsets = LongArray(MAX_PLAYHEAD_OFFSET_COUNT)
    private val outputSampleRate: Int = audioTrack.sampleRate
    private val bufferSizeUs: Long
    private val isOutputPcm: Boolean
    private var audioTimestampPoller = AudioTimestampPoller(audioTrack, listener)
    private var audioTrackPlaybackSpeed = 1f
    private var onPositionAdvancingFromPositionUs = C.TIME_UNSET
    private var smoothedPlayheadOffsetUs: Long = 0
    private var lastPlayheadSampleTimeUs: Long = 0
    private var getLatencyMethod: Method? = null
    private var latencyUs: Long = 0

    private var lastLatencySampleTimeUs: Long = 0
    private var lastRawPlaybackHeadPositionSampleTimeMs: Long = 0
    private var rawPlaybackHeadPosition: Long = 0
    private var rawPlaybackHeadWrapCount: Long = 0
    private var nextPlayheadOffsetIndex = 0
    private var playheadOffsetCount = 0
    private var stopTimestampUs = C.TIME_UNSET
    private var forceResetWorkaroundTimeMs = C.TIME_UNSET
    private var stopPlaybackHeadPosition: Long = 0
    private var endPlaybackHeadPosition: Long = 0

    // Results from the previous call to getCurrentPositionUs.
    private var lastPositionUs = C.TIME_UNSET
    private var lastSystemTimeUs = C.TIME_UNSET
    private var expectRawPlaybackHeadReset = false
    private var sumRawPlaybackHeadPosition: Long = 0

    init {
        try {
            getLatencyMethod =
                AudioTrack::class.java.getMethod("getLatency", null as Class<*>?)
        } catch (e: NoSuchMethodException) {
            // There's no guarantee this method exists. Do nothing.
        }
        isOutputPcm = isEncodingLinearPcm(outputEncoding)
        bufferSizeUs =
            if (isOutputPcm)
                sampleCountToDurationUs(bufferSize / outputPcmFrameSize.toLong(), outputSampleRate)
            else
                C.TIME_UNSET
    }

    fun setAudioTrackPlaybackSpeed(audioTrackPlaybackSpeed: Float) {
        this.audioTrackPlaybackSpeed = audioTrackPlaybackSpeed
        // Extrapolation from the last audio timestamp relies on the audio rate being constant, so we
        // reset audio timestamp tracking and wait for a new timestamp.
        audioTimestampPoller.reset()
        resetSyncParams()
    }

    fun getCurrentPositionUs(writtenFrames: Long): Long {
        val audioTrack = this.audioTrack
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
            maybeSampleSyncParams()
        }

        // If the device supports it, use the playback timestamp from AudioTrack.getTimestamp.
        // Otherwise, derive a smoothed position by sampling the track's frame position.
        val systemTimeUs = clock.nanoTime() / 1000
        val useGetTimestampMode = audioTimestampPoller.hasAdvancingTimestamp()
        var positionUs =
            if (useGetTimestampMode)
                audioTimestampPoller.getTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed)
            else
                getPlaybackHeadPositionEstimateUs(systemTimeUs)

        val maxPositionUs = sampleCountToDurationUs(writtenFrames, outputSampleRate)
        if (positionUs >= maxPositionUs) {
            positionUs = maxPositionUs
            resetSyncParams()
            audioTimestampPoller.reset()
            return positionUs
        }

        val audioTrackPlayState = audioTrack.playState
        if (audioTrackPlayState == AudioTrack.PLAYSTATE_PLAYING) {
            if (useGetTimestampMode || !audioTimestampPoller.isWaitingForAdvancingTimestamp) {
                // Assume the new position is reliable to estimate the playout start time once we have an
                // advancing timestamp from the AudioTimestampPoller, or we stopped waiting for it.
                maybeTriggerOnPositionAdvancingCallback(positionUs)
            }

            if (lastSystemTimeUs != C.TIME_UNSET) {
                // Only try to smooth if actively playing and having a previous sample to compare with.
                val elapsedSystemTimeUs = systemTimeUs - lastSystemTimeUs
                val positionDiffUs = positionUs - lastPositionUs
                val expectedPositionDiffUs =
                    getMediaDurationForPlayoutDuration(elapsedSystemTimeUs, audioTrackPlaybackSpeed)
                val expectedPositionUs = lastPositionUs + expectedPositionDiffUs
                val positionDriftUs = abs(expectedPositionUs - positionUs)
                if (positionDiffUs != 0L && positionDriftUs < MAX_POSITION_DRIFT_FOR_SMOOTHING_US) {
                    // Ignore updates without moving position (e.g. stuck audio, not yet started audio). Also
                    // ignore updates where the smoothing would take too long and it's preferable to jump to
                    // the new timestamp immediately.
                    val maxAllowedDriftUs: Long =
                        expectedPositionDiffUs * MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT / 100
                    positionUs =
                        constrainValue(
                            positionUs,
                            expectedPositionUs - maxAllowedDriftUs,
                            expectedPositionUs + maxAllowedDriftUs
                        )
                }
            }

            lastSystemTimeUs = systemTimeUs
            lastPositionUs = positionUs
        } else if (audioTrackPlayState == AudioTrack.PLAYSTATE_STOPPED) {
            // Once stopped, the position is simulated anyway and we don't need to wait for the timestamp
            // poller to produce reliable data.
            maybeTriggerOnPositionAdvancingCallback(positionUs)
        }

        return positionUs
    }

    fun start() {
        if (stopTimestampUs != C.TIME_UNSET) {
            stopTimestampUs = msToUs(clock.elapsedRealtime())
        }
        onPositionAdvancingFromPositionUs = this.playbackHeadPositionUs
        audioTimestampPoller.reset()
    }

    val isPlaying: Boolean
        get() = audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING

    fun isStalled(writtenFrames: Long): Boolean {
        return forceResetWorkaroundTimeMs != C.TIME_UNSET && writtenFrames > 0 && (clock.elapsedRealtime() - forceResetWorkaroundTimeMs
                >= FORCE_RESET_WORKAROUND_TIMEOUT_MS)
    }

    fun handleEndOfStream(writtenFrames: Long) {
        stopPlaybackHeadPosition = this.playbackHeadPosition
        stopTimestampUs = msToUs(clock.elapsedRealtime())
        endPlaybackHeadPosition = writtenFrames
    }

    fun pause() {
        resetSyncParams()
        if (stopTimestampUs == C.TIME_UNSET) {
            // The audio track is going to be paused, so reset the timestamp poller to ensure it doesn't
            // supply an advancing position.
            audioTimestampPoller.reset()
        }
        stopPlaybackHeadPosition = this.playbackHeadPosition
    }

    fun expectRawPlaybackHeadReset() {
        expectRawPlaybackHeadReset = true
        audioTimestampPoller.expectTimestampFramePositionReset()
    }

    fun reset() {
        resetSyncParams()
        audioTimestampPoller = AudioTimestampPoller(audioTrack, listener)
        rawPlaybackHeadPosition = 0
        rawPlaybackHeadWrapCount = 0
        expectRawPlaybackHeadReset = false
        sumRawPlaybackHeadPosition = 0
        stopTimestampUs = C.TIME_UNSET
        forceResetWorkaroundTimeMs = C.TIME_UNSET
        lastLatencySampleTimeUs = 0
        latencyUs = 0
        audioTrackPlaybackSpeed = 1f
        onPositionAdvancingFromPositionUs = C.TIME_UNSET
    }

    private fun maybeTriggerOnPositionAdvancingCallback(positionUs: Long) {
        if (onPositionAdvancingFromPositionUs == C.TIME_UNSET
            || positionUs < onPositionAdvancingFromPositionUs
        ) {
            return
        }
        val mediaDurationSinceResumeUs = positionUs - onPositionAdvancingFromPositionUs
        val playoutDurationSinceLastPositionUs =
            getPlayoutDurationForMediaDuration(
                mediaDurationSinceResumeUs, audioTrackPlaybackSpeed
            )
        val playoutStartSystemTimeMs =
            clock.currentTimeMillis() - usToMs(playoutDurationSinceLastPositionUs)
        onPositionAdvancingFromPositionUs = C.TIME_UNSET
        listener.onPositionAdvancing(playoutStartSystemTimeMs)
    }

    private fun maybeSampleSyncParams() {
        val systemTimeUs = clock.nanoTime() / 1000
        if (systemTimeUs - lastPlayheadSampleTimeUs >= MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US) {
            val playbackPositionUs = this.playbackHeadPositionUs
            if (playbackPositionUs == 0L) {
                // The AudioTrack hasn't output anything yet.
                return
            }
            // Take a new sample and update the smoothed offset between the system clock and the playhead.
            playheadOffsets[nextPlayheadOffsetIndex] =
                (getPlayoutDurationForMediaDuration(playbackPositionUs, audioTrackPlaybackSpeed)
                        - systemTimeUs)
            nextPlayheadOffsetIndex = (nextPlayheadOffsetIndex + 1) % MAX_PLAYHEAD_OFFSET_COUNT
            if (playheadOffsetCount < MAX_PLAYHEAD_OFFSET_COUNT) {
                playheadOffsetCount++
            }
            lastPlayheadSampleTimeUs = systemTimeUs
            smoothedPlayheadOffsetUs = 0
            for (i in 0..<playheadOffsetCount) {
                smoothedPlayheadOffsetUs += playheadOffsets[i] / playheadOffsetCount
            }
        }

        val latencyUpdated = maybeUpdateLatency(systemTimeUs)

        audioTimestampPoller.maybePollTimestamp(
            systemTimeUs,
            audioTrackPlaybackSpeed,
            getPlaybackHeadPositionEstimateUs(systemTimeUs),  
            latencyUpdated
        )
    }

    private fun maybeUpdateLatency(systemTimeUs: Long): Boolean {
        val previousLatencyUs = latencyUs
        if (isOutputPcm && getLatencyMethod != null
            && systemTimeUs - lastLatencySampleTimeUs >= MIN_LATENCY_SAMPLE_INTERVAL_US
        ) {
            try {
                // Compute the audio track latency, excluding the latency due to the buffer (leaving
                // latency due to the mixer and audio hardware driver).
                latencyUs =
                    getLatencyMethod!!.invoke(audioTrack) as Int * 1000L - bufferSizeUs
                // Check that the latency is non-negative.
                latencyUs = max(latencyUs, 0)
                // Check that the latency isn't too large.
                if (latencyUs > MAX_LATENCY_US) {
                    listener.onInvalidLatency(latencyUs)
                    latencyUs = 0
                }
            } catch (e: Exception) {
                // The method existed, but doesn't work. Don't try again.
                getLatencyMethod = null
            }
            lastLatencySampleTimeUs = systemTimeUs
        }
        return previousLatencyUs != latencyUs
    }

    private fun getPlaybackHeadPositionEstimateUs(systemTimeUs: Long): Long {
        var positionUs: Long
        if (playheadOffsetCount == 0) {
            // The AudioTrack has started, but we don't have any samples to compute a smoothed position.
            positionUs =
                if (stopTimestampUs != C.TIME_UNSET)
                    sampleCountToDurationUs(
                        this.simulatedPlaybackHeadPositionAfterStop, outputSampleRate
                    )
                else
                    this.playbackHeadPositionUs
        } else {
            // getPlaybackHeadPositionUs() only has a granularity of ~20 ms, so we base the position off
            // the system clock (and a smoothed offset between it and the playhead position) so as to
            // prevent jitter in the reported positions.
            positionUs =
                getMediaDurationForPlayoutDuration(
                    systemTimeUs + smoothedPlayheadOffsetUs, audioTrackPlaybackSpeed
                )
        }

        positionUs = max(0, positionUs - latencyUs)
        if (stopTimestampUs != C.TIME_UNSET) {
            positionUs =
                min(sampleCountToDurationUs(endPlaybackHeadPosition, outputSampleRate), positionUs)
        }
        return positionUs
    }

    private fun resetSyncParams() {
        smoothedPlayheadOffsetUs = 0
        playheadOffsetCount = 0
        nextPlayheadOffsetIndex = 0
        lastPlayheadSampleTimeUs = 0
        lastPositionUs = C.TIME_UNSET
        lastSystemTimeUs = C.TIME_UNSET
    }

    private val playbackHeadPositionUs: Long
        get() = sampleCountToDurationUs(this.playbackHeadPosition, outputSampleRate)

    private val playbackHeadPosition: Long
        get() {
            if (stopTimestampUs != C.TIME_UNSET) {
                val simulatedPlaybackHeadPositionAfterStop =
                    this.simulatedPlaybackHeadPositionAfterStop
                return min(
                    endPlaybackHeadPosition,
                    simulatedPlaybackHeadPositionAfterStop
                )
            }
            val currentTimeMs = clock.elapsedRealtime()
            if (currentTimeMs - lastRawPlaybackHeadPositionSampleTimeMs
                >= RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS
            ) {
                updateRawPlaybackHeadPosition(currentTimeMs)
                lastRawPlaybackHeadPositionSampleTimeMs = currentTimeMs
            }
            return rawPlaybackHeadPosition + sumRawPlaybackHeadPosition + (rawPlaybackHeadWrapCount shl 32)
        }

    private val simulatedPlaybackHeadPositionAfterStop: Long
        get() {
            if (this.audioTrack.playState == AudioTrack.PLAYSTATE_PAUSED) {
                // If AudioTrack is paused while stopping, then return cached playback head position.
                return stopPlaybackHeadPosition
            }
            // Simulate the playback head position up to the total number of frames submitted.
            val elapsedTimeSinceStopUs: Long =
                msToUs(clock.elapsedRealtime()) - stopTimestampUs
            val mediaTimeSinceStopUs =
                getMediaDurationForPlayoutDuration(
                    elapsedTimeSinceStopUs,
                    audioTrackPlaybackSpeed
                )
            val framesSinceStop =
                durationUsToSampleCount(mediaTimeSinceStopUs, outputSampleRate)
            return stopPlaybackHeadPosition + framesSinceStop
        }

    private fun updateRawPlaybackHeadPosition(currentTimeMs: Long) {
        val audioTrack = this.audioTrack
        val state = audioTrack.playState
        if (state == AudioTrack.PLAYSTATE_STOPPED) {
            // The audio track hasn't been started. Keep initial zero timestamp.
            return
        }
        val rawPlaybackHeadPosition = 0xFFFFFFFFL and audioTrack.playbackHeadPosition.toLong()
        if (Build.VERSION.SDK_INT <= 29) {
            if (rawPlaybackHeadPosition == 0L && this.rawPlaybackHeadPosition > 0 && state == AudioTrack.PLAYSTATE_PLAYING) {
                // If connecting a Bluetooth audio device fails, the AudioTrack may be left in a state
                // where its Java API is in the playing state, but the native track is stopped. When this
                // happens the playback head position gets stuck at zero. In this case, return the old
                // playback head position and force the track to be reset after
                // {@link #FORCE_RESET_WORKAROUND_TIMEOUT_MS} has elapsed.
                if (forceResetWorkaroundTimeMs == C.TIME_UNSET) {
                    forceResetWorkaroundTimeMs = currentTimeMs
                }
                return
            } else {
                forceResetWorkaroundTimeMs = C.TIME_UNSET
            }
        }

        if (this.rawPlaybackHeadPosition > rawPlaybackHeadPosition) {
            if (expectRawPlaybackHeadReset) {
                sumRawPlaybackHeadPosition += this.rawPlaybackHeadPosition
                expectRawPlaybackHeadReset = false
            } else {
                // The value must have wrapped around.
                rawPlaybackHeadWrapCount++
            }
        }
        this.rawPlaybackHeadPosition = rawPlaybackHeadPosition
    }

    companion object {
        private const val MAX_LATENCY_US = 10 * C.MICROS_PER_SECOND
        private const val MAX_POSITION_DRIFT_FOR_SMOOTHING_US = C.MICROS_PER_SECOND
        private const val MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT = 10
        private const val RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS: Long = 5
        private const val FORCE_RESET_WORKAROUND_TIMEOUT_MS: Long = 200
        private const val MAX_PLAYHEAD_OFFSET_COUNT = 10
        private const val MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US = 30000
        private const val MIN_LATENCY_SAMPLE_INTERVAL_US = 500000
    }
}
