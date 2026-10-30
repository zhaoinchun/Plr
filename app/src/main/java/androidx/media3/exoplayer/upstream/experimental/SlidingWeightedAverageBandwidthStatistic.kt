package androidx.media3.exoplayer.upstream.experimental

import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.Clock

import java.util.ArrayDeque
import java.util.Deque
import kotlin.math.sqrt

class SlidingWeightedAverageBandwidthStatistic @VisibleForTesting internal constructor(
    private val sampleEvictionFunction: SampleEvictionFunction, private val clock: Clock
) : BandwidthStatistic {
    class Sample(
        val bitrate: Long,
        val weight: Double,
        val timeAddedMs: Long
    )

    fun interface SampleEvictionFunction {
        fun shouldEvictSample(samples: Deque<Sample>?): Boolean
    }

    private val samples = ArrayDeque<Sample>()
    private var bitrateWeightProductSum = 0.0
    private var weightSum = 0.0

    constructor(
        sampleEvictionFunction: SampleEvictionFunction = getMaxCountEvictionFunction(
            DEFAULT_MAX_SAMPLES_COUNT.toLong()
        )
    ) : this(sampleEvictionFunction, Clock.DEFAULT)

    override fun addSample(bytes: Long, durationUs: Long) {
        while (sampleEvictionFunction.shouldEvictSample(samples)) {
            val sample = samples.remove()
            bitrateWeightProductSum -= sample.bitrate * sample.weight
            weightSum -= sample.weight
        }

        val weight = sqrt(bytes.toDouble())
        val bitrate = bytes * 8000000 / durationUs
        val sample = Sample(bitrate, weight, clock.elapsedRealtime())
        samples.add(sample)
        bitrateWeightProductSum += sample.bitrate * sample.weight
        weightSum += sample.weight
    }

    override val bandwidthEstimate: Long
        get() {
            if (samples.isEmpty()) {
                return BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
            }

            return (bitrateWeightProductSum / weightSum).toLong()
        }

    override fun reset() {
        samples.clear()
        bitrateWeightProductSum = 0.0
        weightSum = 0.0
    }

    companion object {
        fun getMaxCountEvictionFunction(maxSamplesCount: Long): SampleEvictionFunction {
            return SampleEvictionFunction { samples: Deque<Sample>? -> samples!!.size >= maxSamplesCount }
        }

        fun getAgeBasedEvictionFunction(maxAgeMs: Long): SampleEvictionFunction {
            return getAgeBasedEvictionFunction(maxAgeMs, Clock.DEFAULT)
        }

        fun getAgeBasedEvictionFunction(
            maxAgeMs: Long, clock: Clock
        ): SampleEvictionFunction {
            return SampleEvictionFunction { samples: Deque<Sample>? ->
                if (samples!!.isEmpty()) {
                    return@SampleEvictionFunction false
                }
                samples.peek()!!.timeAddedMs + maxAgeMs < clock.elapsedRealtime()
            }
        }

        const val DEFAULT_MAX_SAMPLES_COUNT: Int = 10
    }
}
