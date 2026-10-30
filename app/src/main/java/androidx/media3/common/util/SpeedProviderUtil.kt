package androidx.media3.common.util

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.common.util.Util.durationUsToSampleCount
import androidx.media3.common.util.Util.getMediaDurationForPlayoutDuration
import androidx.media3.common.util.Util.getPlayoutDurationForMediaDuration
import androidx.media3.common.util.Util.sampleCountToDurationUs
import com.google.common.base.Preconditions
import com.google.common.primitives.Floats
import kotlin.LongArray
import kotlin.math.floor
import kotlin.math.min

object SpeedProviderUtil {
    
    fun getDurationAfterSpeedProviderApplied(
        speedProvider: SpeedProvider, durationUs: Long
    ): Long {
        var speedChangeTimeUs: Long = 0
        var outputDurationUs = 0.0
        while (speedChangeTimeUs < durationUs) {
            var nextSpeedChangeTimeUs = speedProvider.getNextSpeedChangeTimeUs(speedChangeTimeUs)
            if (nextSpeedChangeTimeUs == C.TIME_UNSET) {
                nextSpeedChangeTimeUs = Long.MAX_VALUE
            }
            Preconditions.checkState(nextSpeedChangeTimeUs > speedChangeTimeUs)
            outputDurationUs +=
                ((min(nextSpeedChangeTimeUs, durationUs) - speedChangeTimeUs)
                        / speedProvider.getSpeed(speedChangeTimeUs).toDouble())
            speedChangeTimeUs = nextSpeedChangeTimeUs
        }
        // Use floor to be consistent with Util#scaleLargeTimestamp().
        return floor(outputDurationUs).toLong()
    }

    fun getSampleAlignedSpeed(
        speedProvider: SpeedProvider, samplePosition: Long, sampleRate: Int
    ): Float {
        Preconditions.checkArgument(samplePosition >= 0)
        Preconditions.checkArgument(sampleRate > 0)

        val durationUs: Long = sampleCountToDurationUs(samplePosition, sampleRate)
        return speedProvider.getSpeed(durationUs)
    }

    fun getNextSpeedChangeSamplePosition(
        speedProvider: SpeedProvider, samplePosition: Long, sampleRate: Int
    ): Long {
        Preconditions.checkArgument(samplePosition >= 0)
        Preconditions.checkArgument(sampleRate > 0)

        val durationUs: Long = sampleCountToDurationUs(samplePosition, sampleRate)
        val nextSpeedChangeTimeUs = speedProvider.getNextSpeedChangeTimeUs(durationUs)

        if (nextSpeedChangeTimeUs == C.TIME_UNSET) {
            return C.INDEX_UNSET.toLong()
        }

        // Use RoundingMode#UP to return next closest sample if duration falls between samples.
        return durationUsToSampleCount(nextSpeedChangeTimeUs, sampleRate)
    }

    class SpeedProviderMapper(speedProvider: SpeedProvider) {
        private val outputSegmentStartTimesUs: LongArray
        private val inputSegmentStartTimesUs: LongArray
        private val speeds: FloatArray

        init {
            val outputSegmentStartTimesUs = LongArray()
            val inputSegmentStartTimesUs = LongArray()
            val speeds: MutableList<Float?> = ArrayList<Float?>()

            var lastOutputSegmentStartTimeUs: Long = 0
            var lastInputSegmentStartTimeUs: Long = 0
            var lastSpeed = speedProvider.getSpeed(lastInputSegmentStartTimeUs)
            outputSegmentStartTimesUs.add(lastOutputSegmentStartTimeUs)
            inputSegmentStartTimesUs.add(lastInputSegmentStartTimeUs)
            speeds.add(lastSpeed)
            var nextSpeedChangeTimeUs =
                speedProvider.getNextSpeedChangeTimeUs(lastInputSegmentStartTimeUs)
            Preconditions.checkState(lastSpeed > 0)

            while (nextSpeedChangeTimeUs != C.TIME_UNSET) {
                Preconditions.checkState(nextSpeedChangeTimeUs > lastInputSegmentStartTimeUs)
                Preconditions.checkState(lastSpeed > 0)
                lastOutputSegmentStartTimeUs +=
                    getPlayoutDurationForMediaDuration(
                        nextSpeedChangeTimeUs - lastInputSegmentStartTimeUs, lastSpeed
                    )
                lastInputSegmentStartTimeUs = nextSpeedChangeTimeUs
                lastSpeed = speedProvider.getSpeed(lastInputSegmentStartTimeUs)
                outputSegmentStartTimesUs.add(lastOutputSegmentStartTimeUs)
                inputSegmentStartTimesUs.add(lastInputSegmentStartTimeUs)
                speeds.add(lastSpeed)
                nextSpeedChangeTimeUs =
                    speedProvider.getNextSpeedChangeTimeUs(lastInputSegmentStartTimeUs)
            }
            this.outputSegmentStartTimesUs = outputSegmentStartTimesUs.toArray()
            this.inputSegmentStartTimesUs = inputSegmentStartTimesUs.toArray()
            this.speeds = Floats.toArray(speeds)
        }

        fun getAdjustedTimeUs(originalTimeUs: Long): Long {
            Preconditions.checkArgument(originalTimeUs != C.TIME_UNSET && originalTimeUs != C.TIME_END_OF_SOURCE)
            Preconditions.checkArgument(originalTimeUs >= 0)
            val index =
                binarySearchFloor(
                    inputSegmentStartTimesUs,
                    originalTimeUs,  
                    true,  
                    true
                )
            return (outputSegmentStartTimesUs[index]
                    + getPlayoutDurationForMediaDuration(
                originalTimeUs - inputSegmentStartTimesUs[index], speeds[index]
            ))
        }

        fun getOriginalTimeUs(adjustedTimeUs: Long): Long {
            Preconditions.checkArgument(adjustedTimeUs != C.TIME_UNSET && adjustedTimeUs != C.TIME_END_OF_SOURCE)
            Preconditions.checkArgument(adjustedTimeUs >= 0)
            val index =
                binarySearchFloor(
                    outputSegmentStartTimesUs,
                    adjustedTimeUs,  
                    true,  
                    true
                )
            return (inputSegmentStartTimesUs[index]
                    + getMediaDurationForPlayoutDuration(
                adjustedTimeUs - outputSegmentStartTimesUs[index], speeds[index]
            ))
        }
    }
}
