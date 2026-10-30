package androidx.media3.exoplayer.video

import androidx.media3.common.C
import androidx.media3.common.Format
import java.util.Arrays
import kotlin.math.abs

class FixedFrameRateEstimator(private val listener: Listener) {
    fun interface Listener {
        fun onFrameRateEstimateChanged(frameRate: Float)
    }

    private var currentMatcher: Matcher = Matcher()
    private var candidateMatcher: Matcher = Matcher()
    private var candidateMatcherActive = false
    private var lastFramePresentationTimeNs: Long = C.TIME_UNSET
    private var framesWithoutSyncCount = 0
    private var formatFrameRate: Float= Format.NO_VALUE.toFloat()
    private var frameRateEstimate: Float= Format.NO_VALUE.toFloat()
    var frameIndex: Long = 0
        private set

    fun onFormatChanged(formatFrameRate: Float) {
        this.formatFrameRate = formatFrameRate
        currentMatcher.reset()
        candidateMatcher.reset()
        candidateMatcherActive = false
        lastFramePresentationTimeNs = C.TIME_UNSET
        framesWithoutSyncCount = 0
        updateReportedFrameRate()
    }

    private fun updateReportedFrameRate() {
        val synced = currentMatcher.isSynced
        val candidateFrameRate =
            if (synced) (C.NANOS_PER_SECOND.toDouble() / currentMatcher.frameDurationNs).toFloat() else
                formatFrameRate
        if (candidateFrameRate == frameRateEstimate) {
            return
        }

        val shouldUpdate: Boolean
        if (candidateFrameRate != Format.NO_VALUE.toFloat() && frameRateEstimate != Format.NO_VALUE.toFloat()) {
            val candidateIsHighConfidence =
                synced
                        && (currentMatcher.matchingFrameDurationSumNs
                        >= MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS)
            val minimumChangeForUpdate: Float =
                if (candidateIsHighConfidence)
                    MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE
                else
                    MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE
            shouldUpdate = abs(candidateFrameRate - frameRateEstimate) >= minimumChangeForUpdate
        } else if (candidateFrameRate != Format.NO_VALUE.toFloat()) {
            shouldUpdate = true
        } else {
            shouldUpdate =
                framesWithoutSyncCount >= MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_FRAME_RATE_ESTIMATE
        }

        if (shouldUpdate) {
            frameRateEstimate = candidateFrameRate
            listener.onFrameRateEstimateChanged(frameRateEstimate)
        }
    }

    fun onNextFrame(framePresentationTimeNs: Long) {
        if (framePresentationTimeNs == lastFramePresentationTimeNs) {
            return
        }
        frameIndex++
        currentMatcher.onNextFrame(framePresentationTimeNs)
        if (currentMatcher.isSynced) {
            candidateMatcherActive = false
        } else if (lastFramePresentationTimeNs != C.TIME_UNSET) {
            if (!candidateMatcherActive || candidateMatcher.isLastFrameOutlier) {
                // Reset the candidate with the last and current frame presentation timestamps, so that it
                // will try and match against the duration of the previous frame.
                candidateMatcher.reset()
                candidateMatcher.onNextFrame(lastFramePresentationTimeNs)
            }
            candidateMatcherActive = true
            candidateMatcher.onNextFrame(framePresentationTimeNs)
        }
        if (candidateMatcherActive && candidateMatcher.isSynced) {
            // The candidate matcher should be promoted to be the current matcher. The current matcher
            // can be re-used as the next candidate matcher.
            val previousMatcher = currentMatcher
            currentMatcher = candidateMatcher
            candidateMatcher = previousMatcher
            candidateMatcherActive = false
        }
        lastFramePresentationTimeNs = framePresentationTimeNs
        framesWithoutSyncCount = if (currentMatcher.isSynced) 0 else framesWithoutSyncCount + 1
        updateReportedFrameRate()
    }

    val frameDurationNs: Long
        get() = if (currentMatcher.isSynced) currentMatcher.frameDurationNs else C.TIME_UNSET

    private class Matcher {
        private var firstFramePresentationTimeNs: Long = 0
        private var firstFrameDurationNs: Long = 0
        private var lastFramePresentationTimeNs: Long = 0
        private var frameCount: Long = 0
        private var matchingFrameCount: Long = 0
        var matchingFrameDurationSumNs: Long = 0
            private set
        private val recentFrameOutlierFlags= BooleanArray(CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC)
        private var recentFrameOutlierCount = 0

        fun reset() {
            frameCount = 0
            matchingFrameCount = 0
            matchingFrameDurationSumNs = 0
            recentFrameOutlierCount = 0
            Arrays.fill(recentFrameOutlierFlags, false)
        }

        val isSynced: Boolean
            get() = frameCount > CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC
                    && recentFrameOutlierCount == 0

        val isLastFrameOutlier: Boolean
            get() {
                if (frameCount == 0L) {
                    return false
                }
                return recentFrameOutlierFlags[getRecentFrameOutlierIndex(
                    frameCount - 1
                )]
            }

        val frameDurationNs: Long
            get() = if (matchingFrameCount == 0L) 0 else (matchingFrameDurationSumNs / matchingFrameCount)

        fun onNextFrame(framePresentationTimeNs: Long) {
            if (frameCount == 0L) {
                firstFramePresentationTimeNs = framePresentationTimeNs
            } else if (frameCount == 1L) {
                // This is the frame duration that the tracker will match against.
                firstFrameDurationNs = framePresentationTimeNs - firstFramePresentationTimeNs
                matchingFrameDurationSumNs = firstFrameDurationNs
                matchingFrameCount = 1
            } else {
                val lastFrameDurationNs = framePresentationTimeNs - lastFramePresentationTimeNs
                val recentFrameOutlierIndex: Int = getRecentFrameOutlierIndex(frameCount)
                if (abs(lastFrameDurationNs - firstFrameDurationNs) <= MAX_MATCHING_FRAME_DIFFERENCE_NS) {
                    matchingFrameCount++
                    matchingFrameDurationSumNs += lastFrameDurationNs
                    if (recentFrameOutlierFlags[recentFrameOutlierIndex]) {
                        recentFrameOutlierFlags[recentFrameOutlierIndex] = false
                        recentFrameOutlierCount--
                    }
                } else {
                    if (!recentFrameOutlierFlags[recentFrameOutlierIndex]) {
                        recentFrameOutlierFlags[recentFrameOutlierIndex] = true
                        recentFrameOutlierCount++
                    }
                }
            }

            frameCount++
            lastFramePresentationTimeNs = framePresentationTimeNs
        }

        companion object {
            private fun getRecentFrameOutlierIndex(frameCount: Long): Int {
                return (frameCount % CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC).toInt()
            }
        }
    }

    companion object {
        const val CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC: Int = 15
        const val MAX_MATCHING_FRAME_DIFFERENCE_NS: Long = 1000000
        private const val MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS = 5000000000L
        private const val MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE = 0.1f
        private const val MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE = 1f
        private const val MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_FRAME_RATE_ESTIMATE: Int =
            2 * CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC
    }
}
