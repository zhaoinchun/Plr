package androidx.media3.exoplayer.upstream.experimental

import android.os.Handler
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.Clock

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue

class CombinedParallelSampleBandwidthEstimator private constructor(builder: Builder) :
    BandwidthEstimator {
    class Builder {
        var bandwidthStatistic: BandwidthStatistic = SlidingWeightedAverageBandwidthStatistic()
        var minSamples = 0
        var minBytesTransferred: Long = 0
        var clock = Clock.DEFAULT

        fun setBandwidthStatistic(bandwidthStatistic: BandwidthStatistic): Builder {
            this.bandwidthStatistic = bandwidthStatistic
            return this
        }

        fun setMinSamples(minSamples: Int): Builder {
            Preconditions.checkArgument(minSamples >= 0)
            this.minSamples = minSamples
            return this
        }

        fun setMinBytesTransferred(minBytesTransferred: Long): Builder {
            Preconditions.checkArgument(minBytesTransferred >= 0)
            this.minBytesTransferred = minBytesTransferred
            return this
        }

        fun setClock(clock: Clock): Builder {
            this.clock = clock
            return this
        }

        fun build(): CombinedParallelSampleBandwidthEstimator {
            return CombinedParallelSampleBandwidthEstimator(this)
        }
    }

    private val bandwidthStatistic: BandwidthStatistic
    private val minSamples: Int
    private val minBytesTransferred: Long
    private val eventDispatcher: BandwidthMeter.EventListener.EventDispatcher
    private val clock: Clock

    private var streamCount = 0
    private var sampleStartTimeMs: Long = 0
    private var sampleBytesTransferred: Long = 0
    override var bandwidthEstimate: Long
    private var lastReportedBandwidthEstimate: Long
    private var totalSamplesAdded = 0
    private var totalBytesTransferred: Long = 0

    init {
        this.bandwidthStatistic = builder.bandwidthStatistic
        this.minSamples = builder.minSamples
        this.minBytesTransferred = builder.minBytesTransferred
        this.clock = builder.clock
        eventDispatcher = BandwidthMeter.EventListener.EventDispatcher()
        bandwidthEstimate = BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
        lastReportedBandwidthEstimate = BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
    }

    override fun addEventListener(
        eventHandler: Handler,
        eventListener: BandwidthMeter.EventListener
    ) {
        eventDispatcher.addListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        eventDispatcher.removeListener(eventListener)
    }

    override fun onTransferInitializing(source: DataSource) {}

    override fun onTransferStart(source: DataSource) {
        if (streamCount == 0) {
            sampleStartTimeMs = clock.elapsedRealtime()
        }
        streamCount++
    }

    override fun onBytesTransferred(source: DataSource, bytesTransferred: Int) {
        sampleBytesTransferred += bytesTransferred.toLong()
        totalBytesTransferred += bytesTransferred.toLong()
    }

    override fun onTransferEnd(source: DataSource) {
        Preconditions.checkState(streamCount > 0)
        streamCount--
        if (streamCount > 0) {
            return
        }
        val nowMs = clock.elapsedRealtime()
        val sampleElapsedTimeMs = (nowMs - sampleStartTimeMs).toInt().toLong()
        if (sampleElapsedTimeMs > 0) {
            bandwidthStatistic.addSample(sampleBytesTransferred, sampleElapsedTimeMs * 1000)
            totalSamplesAdded++
            if (totalSamplesAdded > minSamples && totalBytesTransferred > minBytesTransferred) {
                bandwidthEstimate = bandwidthStatistic.bandwidthEstimate
            }
            maybeNotifyBandwidthSample(
                sampleElapsedTimeMs.toInt(), sampleBytesTransferred, bandwidthEstimate
            )
            sampleBytesTransferred = 0
        }
    }

    override fun onNetworkTypeChange(newBandwidthEstimate: Long) {
        val nowMs = clock.elapsedRealtime()
        val sampleElapsedTimeMs = if (streamCount > 0) (nowMs - sampleStartTimeMs).toInt() else 0
        maybeNotifyBandwidthSample(
            sampleElapsedTimeMs,
            sampleBytesTransferred,
            newBandwidthEstimate
        )
        bandwidthStatistic.reset()
        bandwidthEstimate = BandwidthEstimator.ESTIMATE_NOT_AVAILABLE
        sampleStartTimeMs = nowMs
        sampleBytesTransferred = 0
        totalSamplesAdded = 0
        totalBytesTransferred = 0
    }

    private fun maybeNotifyBandwidthSample(
        elapsedMs: Int, bytesTransferred: Long, bandwidthEstimate: Long
    ) {
        if ((bandwidthEstimate == BandwidthEstimator.ESTIMATE_NOT_AVAILABLE)
            || (elapsedMs == 0 && bytesTransferred == 0L && bandwidthEstimate == lastReportedBandwidthEstimate)
        ) {
            return
        }
        lastReportedBandwidthEstimate = bandwidthEstimate
        eventDispatcher.bandwidthSample(elapsedMs, bytesTransferred, bandwidthEstimate)
    }
}
