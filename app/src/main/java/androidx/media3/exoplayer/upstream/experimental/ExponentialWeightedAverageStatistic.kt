package androidx.media3.exoplayer.upstream.experimental

import kotlin.math.pow
import kotlin.math.sqrt

class ExponentialWeightedAverageStatistic(private val smoothingFactor: Double = DEFAULT_SMOOTHING_FACTOR) :
    BandwidthStatistic {
    private var bitrateEstimate: Long

    init {
        bitrateEstimate = BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
    }

    override fun addSample(bytes: Long, durationUs: Long) {
        val bitrate = bytes * 8000000 / durationUs
        if (bitrateEstimate == BandwidthEstimator.ESTIMATE_NOT_AVAILABLE) {
            bitrateEstimate = bitrate
            return
        }
        // Weight smoothing factor by sqrt(bytes).
        val factor = smoothingFactor.pow(sqrt(bytes.toDouble()))
        bitrateEstimate = (factor * bitrateEstimate + (1f - factor) * bitrate).toLong()
    }

    override val bandwidthEstimate: Long
        get() = bitrateEstimate

    override fun reset() {
        bitrateEstimate = BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
    }

    companion object {
        const val DEFAULT_SMOOTHING_FACTOR: Double = 0.9999
    }
}
