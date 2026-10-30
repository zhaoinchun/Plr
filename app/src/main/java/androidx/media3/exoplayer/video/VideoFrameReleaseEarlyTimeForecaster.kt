package androidx.media3.exoplayer.video

import android.util.Range
import androidx.annotation.FloatRange
import androidx.media3.common.C
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameReleaseInfo
import com.google.common.base.Preconditions

 class VideoFrameReleaseEarlyTimeForecaster(
    @FloatRange(
        from = 0.0,
        fromInclusive = false
    ) playbackSpeed: Float
) {
    private var lastFramePresentationTimeUs: Long
    private var lastFrameEarlyUs: Long
    private var derivativeOfEarlyTime: Double
    private var derivativeOfEarlyTimeRange: Range<Double>

    init {
        Preconditions.checkArgument(playbackSpeed > 0)
        derivativeOfEarlyTimeRange =
            Range<Double>( 0.0,  1.0 / playbackSpeed)
        derivativeOfEarlyTime = derivativeOfEarlyTimeRange.getUpper()!!
        lastFramePresentationTimeUs = C.TIME_UNSET
        lastFrameEarlyUs = C.TIME_UNSET
    }

    fun onVideoFrameProcessed(framePresentationTimeUs: Long, earlyUs: Long) {
        Preconditions.checkArgument(framePresentationTimeUs != C.TIME_UNSET)
        Preconditions.checkArgument(earlyUs != C.TIME_UNSET)
        val derivativeFromLastFrame =
            calculateDerivativeFromLastFrame(framePresentationTimeUs, earlyUs)
        updateDerivativeWithExponentialMovingAverage(
            derivativeOfEarlyTimeRange.clamp(derivativeFromLastFrame)!!
        )
        lastFramePresentationTimeUs = framePresentationTimeUs
        lastFrameEarlyUs = earlyUs
    }

    fun predictEarlyUs(presentationTimeUs: Long): Long {
        if (lastFramePresentationTimeUs == C.TIME_UNSET) {
            return C.TIME_UNSET
        }
        return (lastFrameEarlyUs
                + (presentationTimeUs - lastFramePresentationTimeUs) * derivativeOfEarlyTime).toLong()
    }

    fun setPlaybackSpeed(@FloatRange(from = 0.0, fromInclusive = false) playbackSpeed: Float) {
        Preconditions.checkArgument(playbackSpeed > 0)
        derivativeOfEarlyTimeRange =
            Range<Double>( 0.0,  1.0 / playbackSpeed)
        reset()
    }

    fun reset() {
        derivativeOfEarlyTime = derivativeOfEarlyTimeRange.getUpper()!!
        lastFramePresentationTimeUs = C.TIME_UNSET
        lastFrameEarlyUs = C.TIME_UNSET
    }

    private fun calculateDerivativeFromLastFrame(presentationTimeUs: Long, earlyUs: Long): Double {
        if (lastFramePresentationTimeUs != C.TIME_UNSET && lastFrameEarlyUs != C.TIME_UNSET && presentationTimeUs != lastFramePresentationTimeUs) {
            return (earlyUs - lastFrameEarlyUs).toDouble() / (presentationTimeUs - lastFramePresentationTimeUs)
        }
        return derivativeOfEarlyTimeRange.getUpper()!!
    }

    private fun updateDerivativeWithExponentialMovingAverage(latestDerivative: Double) {
        derivativeOfEarlyTime =
            derivativeOfEarlyTime * (1 - SMOOTHING_FACTOR) + latestDerivative * SMOOTHING_FACTOR
    }

    companion object {
        private const val SMOOTHING_FACTOR = 0.2f
    }
}
