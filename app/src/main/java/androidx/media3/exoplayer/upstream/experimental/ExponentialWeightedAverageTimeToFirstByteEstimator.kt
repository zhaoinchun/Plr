package androidx.media3.exoplayer.upstream.experimental

import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.Clock

import androidx.media3.common.util.Util.msToUs
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.TimeToFirstByteEstimator

class ExponentialWeightedAverageTimeToFirstByteEstimator
@VisibleForTesting internal constructor(
    private val smoothingFactor: Double,
    private val clock: Clock
) : TimeToFirstByteEstimator {
    private val initializedDataSpecs: LinkedHashMap<DataSpec?, Long?> = FixedSizeLinkedHashMap<DataSpec?, Long?>(
        MAX_DATA_SPECS
    )

    override var timeToFirstByteEstimateUs = C.TIME_UNSET
        private set

    constructor() : this(DEFAULT_SMOOTHING_FACTOR, Clock.DEFAULT)

    constructor(smoothingFactor: Double) : this(smoothingFactor, Clock.DEFAULT)

    override fun reset() {
        this.timeToFirstByteEstimateUs = C.TIME_UNSET
    }

    override fun onTransferInitializing(dataSpec: DataSpec) {
        // Remove to make sure insertion order is updated in case the key already exists.
        initializedDataSpecs.remove(dataSpec)
        initializedDataSpecs.put(dataSpec, msToUs(clock.elapsedRealtime()))
    }

    override fun onTransferStart(dataSpec: DataSpec) {
        val initializationStartUs = initializedDataSpecs.remove(dataSpec)
        if (initializationStartUs == null) {
            return
        }

        val timeToStartSampleUs = msToUs(clock.elapsedRealtime()) - initializationStartUs
        if (this.timeToFirstByteEstimateUs == C.TIME_UNSET) {
            this.timeToFirstByteEstimateUs = timeToStartSampleUs
        } else {
            this.timeToFirstByteEstimateUs =
                (smoothingFactor * this.timeToFirstByteEstimateUs + (1.0 - smoothingFactor) * timeToStartSampleUs).toLong()
        }
    }

    private class FixedSizeLinkedHashMap<K, V>(private val maxSize: Int) : LinkedHashMap<K?, V?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K?, V?>?): Boolean {
            return size > maxSize
        }
    }

    companion object {
        const val DEFAULT_SMOOTHING_FACTOR: Double = 0.85
        private const val MAX_DATA_SPECS = 10
    }
}
