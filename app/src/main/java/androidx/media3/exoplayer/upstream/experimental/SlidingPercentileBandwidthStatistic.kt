package androidx.media3.exoplayer.upstream.experimental

import com.google.common.base.Preconditions
import java.util.ArrayDeque
import java.util.TreeSet
import kotlin.math.sqrt

class SlidingPercentileBandwidthStatistic constructor(
    private val maxSampleCount: Int = DEFAULT_MAX_SAMPLES_COUNT,
    private val percentile: Double = DEFAULT_PERCENTILE
) : BandwidthStatistic {
    private val samples: ArrayDeque<Sample> = ArrayDeque<Sample>()
    private val sortedSamples = TreeSet<Sample>()
    private var weightSum = 0.0
    private var bitrateEstimate = BandwidthEstimator.ESTIMATE_NOT_AVAILABLE

    init {
        Preconditions.checkArgument(percentile in 0.0..1.0)
    }

    override fun addSample(bytes: Long, durationUs: Long) {
        while (samples.size >= maxSampleCount) {
            val removedSample = samples.remove()
            sortedSamples.remove(removedSample)
            weightSum -= removedSample.weight
        }

        val weight = sqrt(bytes.toDouble())
        val bitrate = bytes * 8000000 / durationUs
        val sample = Sample(bitrate, weight)
        samples.add(sample)
        sortedSamples.add(sample)
        weightSum += weight
        bitrateEstimate = calculateBitrateEstimate()
    }

    override val bandwidthEstimate: Long
        get() = bitrateEstimate

    override fun reset() {
        samples.clear()
        sortedSamples.clear()
        weightSum = 0.0
        bitrateEstimate = BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
    }

    private fun calculateBitrateEstimate(): Long {
        if (samples.isEmpty()) {
            return BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
        }
        val targetWeightSum = weightSum * percentile
        var previousPartialWeightSum = 0.0
        var previousSampleBitrate: Long = 0
        var nextPartialWeightSum = 0.0
        for (sample in sortedSamples) {
            // The percentile position of each sample is the middle of its weight. Hence, we need to add
            // half the weight to check whether the target percentile is before or after this sample.
            nextPartialWeightSum += sample.weight / 2
            if (nextPartialWeightSum >= targetWeightSum) {
                if (previousSampleBitrate == 0L) {
                    return sample.bitrate
                }
                // Interpolate between samples to get an estimate for the target percentile.
                val partialBitrateBetweenSamples =
                    ((sample.bitrate - previousSampleBitrate)
                            * (targetWeightSum - previousPartialWeightSum)
                            / (nextPartialWeightSum - previousPartialWeightSum))
                return previousSampleBitrate + partialBitrateBetweenSamples.toLong()
            }
            previousSampleBitrate = sample.bitrate
            previousPartialWeightSum = nextPartialWeightSum
            nextPartialWeightSum += sample.weight / 2
        }
        return previousSampleBitrate
    }

    private class Sample(val bitrate: Long, val weight: Double) :
        Comparable<Sample> {
        override fun compareTo(other: Sample): Int {
            return this.bitrate.compareTo(other.bitrate)
        }
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES_COUNT: Int = 10
        const val DEFAULT_PERCENTILE: Double = 0.5
    }
}
