package androidx.media3.common.audio

import android.util.Pair
import androidx.annotation.IntRange
import androidx.media3.common.C

import androidx.media3.common.util.Util.durationUsToSampleCount
import androidx.media3.common.util.Util.sampleCountToDurationUs
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.collect.Range
import com.google.common.collect.TreeRangeMap
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.math.cos
import kotlin.math.sin

class DefaultGainProvider private constructor(
    gainMap: TreeRangeMap<Long, Function<Pair<Long, Int>, Float>>,
    defaultGain: Float
) : GainProcessor.GainProvider {
    
    class Builder(private val defaultGain: Float) {
        private val gainMap: TreeRangeMap<Long, Function<Pair<Long, Int>, Float>> = TreeRangeMap.create<Long, Function<Pair<Long, Int>, Float>>()

        init {
            // Add default value for all possible positions.
            gainMap.put(Range.all(), Function { a: Pair<Long, Int> -> GAIN_UNSET })
        }

        fun addFadeAt(
            @IntRange(from = 0) positionUs: Long,
            @IntRange(from = 1) durationUs: Long,
            shape: FadeProvider
        ): Builder {
            Preconditions.checkArgument(positionUs >= 0)
            Preconditions.checkArgument(durationUs > 1)
            gainMap.put(
                Range.closedOpen(positionUs, positionUs + durationUs),
                Function { positionSampleRatePair: Pair<Long, Int> ->
                    val sampleRate: Int = positionSampleRatePair.second!!
                    val relativeSamplePosition: Long =
                        positionSampleRatePair.first - durationUsToSampleCount(
                            positionUs,
                            sampleRate
                        )
                    shape.getGainFactorAt(
                        relativeSamplePosition, durationUsToSampleCount(durationUs, sampleRate)
                    )
                })
            return this
        }

        fun build(): DefaultGainProvider {
            return DefaultGainProvider(gainMap, defaultGain)
        }
    }

    fun interface FadeProvider {
        fun getGainFactorAt(
            @IntRange(from = 0) index: Long,
            @IntRange(from = 1) duration: Long
        ): Float
    }

    // Use TreeRangeMap instead of ImmutableRangeMap to allow overlapping ranges.
    private val gainMap: TreeRangeMap<Long, Function<Pair<Long, Int>, Float>> = TreeRangeMap.create<Long, Function<Pair<Long, Int>, Float>>()

    private val defaultGain: Float

    init {
        this.gainMap.putAll(gainMap)
        this.defaultGain = defaultGain
    }

    override fun getGainFactorAtSamplePosition(
        @IntRange(from = 0) samplePosition: Long, @IntRange(from = 1) sampleRate: Int
    ): Float {
        Preconditions.checkState(sampleRate > 0)
        Preconditions.checkArgument(samplePosition >= 0)

        // gainMap has a default value set for all possible values, so it should never return null.
        val gain: Float =
            gainMap.get(sampleCountToDurationUs(samplePosition, sampleRate))!!.apply(
                    Pair.create(samplePosition, sampleRate)
                )!!
        if (gain == GAIN_UNSET) {
            return defaultGain
        }
        return gain
    }

    override fun isUnityUntil(
        @IntRange(from = 0) samplePosition: Long, @IntRange(from = 1) sampleRate: Int
    ): Long {
        Preconditions.checkState(sampleRate > 0)
        Preconditions.checkArgument(samplePosition >= 0)

        val positionUs: Long = sampleCountToDurationUs(samplePosition, sampleRate)
        val entry =gainMap.getEntry(positionUs)!!
        val gainFactor: Float = entry.value!!.apply(
            Pair.create(samplePosition, sampleRate)
        )!!

        // If the gain has been manually set to unity, we do not know how long the unity region is.
        if (gainFactor == 1f) {
            return samplePosition + 1
        }

        if (defaultGain != 1f || gainFactor != GAIN_UNSET) {
            return C.TIME_UNSET
        }

        if (!entry.key!!.hasUpperBound()) {
            return C.TIME_END_OF_SOURCE
        }

        return durationUsToSampleCount(entry.key!!.upperEndpoint(), sampleRate)
    }

    companion object {
        val FADE_IN_LINEAR: FadeProvider =
            DefaultGainProvider.FadeProvider { index: Long, duration: Long -> index.toFloat() / duration }

        val FADE_OUT_LINEAR: FadeProvider =
            DefaultGainProvider.FadeProvider { index: Long, duration: Long -> (duration - index).toFloat() / duration }

        val FADE_IN_EQUAL_POWER: FadeProvider =
            DefaultGainProvider.FadeProvider { index: Long, duration: Long -> sin((Math.PI / 2.0) * index / duration).toFloat() }

        val FADE_OUT_EQUAL_POWER: FadeProvider =
            DefaultGainProvider.FadeProvider { index: Long, duration: Long -> cos((Math.PI / 2.0) * index / duration).toFloat() }

        private const val GAIN_UNSET = C.RATE_UNSET
    }
}
