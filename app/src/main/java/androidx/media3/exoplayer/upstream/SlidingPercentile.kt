package androidx.media3.exoplayer.upstream

import java.util.Collections
import kotlin.Array
import kotlin.Comparator
import kotlin.Int
import kotlin.arrayOfNulls

class SlidingPercentile(private val maxWeight: Int) {
    private val samples: ArrayList<Sample>

    private val recycledSamples: Array<Sample?>

    private var currentSortOrder: Int
    private var nextSampleIndex = 0
    private var totalWeight = 0
    private var recycledSampleCount = 0

    init {
        recycledSamples = arrayOfNulls<Sample>(MAX_RECYCLED_SAMPLES)
        samples = ArrayList<Sample>()
        currentSortOrder = SORT_ORDER_NONE
    }

    fun reset() {
        samples.clear()
        currentSortOrder = SORT_ORDER_NONE
        nextSampleIndex = 0
        totalWeight = 0
    }

    fun addSample(weight: Int, value: Float) {
        ensureSortedByIndex()

        val newSample =
            if (recycledSampleCount > 0) recycledSamples[--recycledSampleCount]!! else Sample()
        newSample.index = nextSampleIndex++
        newSample.weight = weight
        newSample.value = value
        samples.add(newSample)
        totalWeight += weight

        while (totalWeight > maxWeight) {
            val excessWeight = totalWeight - maxWeight
            val oldestSample = samples[0]
            if (oldestSample.weight <= excessWeight) {
                totalWeight -= oldestSample.weight
                samples.removeAt(0)
                if (recycledSampleCount < MAX_RECYCLED_SAMPLES) {
                    recycledSamples[recycledSampleCount++] = oldestSample
                }
            } else {
                oldestSample.weight -= excessWeight
                totalWeight -= excessWeight
            }
        }
    }

    fun getPercentile(percentile: Float): Float {
        ensureSortedByValue()
        val desiredWeight = percentile * totalWeight
        var accumulatedWeight = 0
        for (i in samples.indices) {
            val currentSample = samples[i]
            accumulatedWeight += currentSample.weight
            if (accumulatedWeight >= desiredWeight) {
                return currentSample.value
            }
        }
        // Clamp to maximum value or NaN if no values.
        return if (samples.isEmpty()) Float.NaN else samples.get(samples.size - 1).value
    }

    private fun ensureSortedByIndex() {
        if (currentSortOrder != SORT_ORDER_BY_INDEX) {
            Collections.sort<Sample?>(samples, INDEX_COMPARATOR)
            currentSortOrder = SORT_ORDER_BY_INDEX
        }
    }

    private fun ensureSortedByValue() {
        if (currentSortOrder != SORT_ORDER_BY_VALUE) {
            Collections.sort<Sample?>(samples, VALUE_COMPARATOR)
            currentSortOrder = SORT_ORDER_BY_VALUE
        }
    }

    private class Sample {
        var index: Int = 0
        var weight: Int = 0
        var value: Float = 0f
    }

    companion object {
        // Orderings.
        private val INDEX_COMPARATOR =
            Comparator { a: Sample?, b: Sample? -> a!!.index - b!!.index }
        private val VALUE_COMPARATOR =
            Comparator { a: Sample?, b: Sample? -> a!!.value.compareTo(b!!.value) }

        private val SORT_ORDER_NONE = -1
        private const val SORT_ORDER_BY_VALUE = 0
        private const val SORT_ORDER_BY_INDEX = 1

        private const val MAX_RECYCLED_SAMPLES = 5
    }
}
