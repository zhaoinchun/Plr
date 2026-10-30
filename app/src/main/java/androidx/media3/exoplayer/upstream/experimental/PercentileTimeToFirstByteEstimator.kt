package androidx.media3.exoplayer.upstream.experimental

import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.msToUs
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.SlidingPercentile
import androidx.media3.exoplayer.upstream.TimeToFirstByteEstimator
import com.google.common.base.Preconditions

class PercentileTimeToFirstByteEstimator @VisibleForTesting internal constructor(
    numberOfSamples: Int,
    private val percentile: Float,
    private val clock: Clock
) : TimeToFirstByteEstimator {
    private val initializedDataSpecs: LinkedHashMap<DataSpec?, Long?> =
        FixedSizeLinkedHashMap<DataSpec?, Long?>(MAX_DATA_SPECS)
    private val slidingPercentile = SlidingPercentile(numberOfSamples)
    private var isEmpty = true

    constructor(
        numberOfSamples: Int = DEFAULT_MAX_SAMPLES_COUNT,
        percentile: Float = DEFAULT_PERCENTILE
    ) : this(numberOfSamples, percentile, Clock.DEFAULT)

    init {
        Preconditions.checkArgument(numberOfSamples > 0 && percentile > 0 && percentile <= 1)
    }

    override val timeToFirstByteEstimateUs: Long
        get() = if (!isEmpty) slidingPercentile.getPercentile(percentile)
            .toLong() else C.TIME_UNSET

    override fun reset() {
        slidingPercentile.reset()
        isEmpty = true
    }

    override fun onTransferInitializing(dataSpec: DataSpec) {
        // Remove to make sure insertion order is updated in case the key already exists.
        initializedDataSpecs.remove(dataSpec)
        initializedDataSpecs[dataSpec] = msToUs(clock.elapsedRealtime())
    }

    override fun onTransferStart(dataSpec: DataSpec) {
        val initializationStartUs = initializedDataSpecs.remove(dataSpec) ?: return
        slidingPercentile.addSample(
            1,
            (msToUs(clock.elapsedRealtime()) - initializationStartUs).toFloat()
        )
        isEmpty = false
    }

    private class FixedSizeLinkedHashMap<K, V>(private val maxSize: Int) : LinkedHashMap<K?, V?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K?, V?>?): Boolean {
            return size > maxSize
        }
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES_COUNT: Int = 10
        const val DEFAULT_PERCENTILE: Float = 0.5f
        private const val MAX_DATA_SPECS = 10
    }
}
