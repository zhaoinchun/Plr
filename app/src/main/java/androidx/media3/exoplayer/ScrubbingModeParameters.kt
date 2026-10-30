
package androidx.media3.exoplayer

import androidx.annotation.FloatRange
import androidx.media3.common.C
import com.google.common.base.Preconditions
import java.util.Objects

class ScrubbingModeParameters private constructor(builder: Builder) {
    class Builder {
         var disabledTrackTypes: Set<Int>
         var fractionalSeekToleranceBefore: Double? = null
         var fractionalSeekToleranceAfter: Double? = null
         var shouldIncreaseCodecOperatingRate: Boolean
         var allowSkippingMediaCodecFlush: Boolean
         var allowSkippingKeyFrameReset: Boolean
         var shouldEnableDynamicScheduling: Boolean
         var useDecodeOnlyFlag: Boolean

        constructor() {
            this.disabledTrackTypes =setOf<@C.TrackType Int>(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA)
            shouldIncreaseCodecOperatingRate = true
            allowSkippingMediaCodecFlush = true
            allowSkippingKeyFrameReset = true
            shouldEnableDynamicScheduling = true
            useDecodeOnlyFlag = true
        }

         constructor(scrubbingModeParameters: ScrubbingModeParameters) {
            this.disabledTrackTypes = scrubbingModeParameters.disabledTrackTypes
            this.fractionalSeekToleranceBefore =
                scrubbingModeParameters.fractionalSeekToleranceBefore
            this.fractionalSeekToleranceAfter = scrubbingModeParameters.fractionalSeekToleranceAfter
            this.shouldIncreaseCodecOperatingRate =
                scrubbingModeParameters.shouldIncreaseCodecOperatingRate
            this.allowSkippingMediaCodecFlush = scrubbingModeParameters.allowSkippingMediaCodecFlush
            this.allowSkippingKeyFrameReset = scrubbingModeParameters.allowSkippingKeyFrameReset
            this.shouldEnableDynamicScheduling =
                scrubbingModeParameters.shouldEnableDynamicScheduling
            this.useDecodeOnlyFlag = scrubbingModeParameters.useDecodeOnlyFlag
        }

        fun setDisabledTrackTypes(disabledTrackTypes: MutableSet<Int>): Builder {
            this.disabledTrackTypes = disabledTrackTypes.toSet()
            return this
        }
        fun setFractionalSeekTolerance(
            @FloatRange(from = 0.0, to = 1.0) toleranceBefore: Double?,
            @FloatRange(from = 0.0, to = 1.0) toleranceAfter: Double?
        ): Builder {
            Preconditions.checkArgument((toleranceBefore == null) == (toleranceAfter == null))
            Preconditions.checkArgument(toleranceBefore == null || (toleranceBefore >= 0 && toleranceBefore <= 1))
            Preconditions.checkArgument(toleranceAfter == null || (toleranceAfter >= 0 && toleranceAfter <= 1))
            this.fractionalSeekToleranceBefore = toleranceBefore
            this.fractionalSeekToleranceAfter = toleranceAfter
            return this
        }

        fun setShouldIncreaseCodecOperatingRate(shouldIncreaseCodecOperatingRate: Boolean): Builder {
            this.shouldIncreaseCodecOperatingRate = shouldIncreaseCodecOperatingRate
            return this
        }

        fun setShouldEnableDynamicScheduling(shouldEnableDynamicScheduling: Boolean): Builder {
            this.shouldEnableDynamicScheduling = shouldEnableDynamicScheduling
            return this
        }

        @Deprecated(
            """Use {@link #setAllowSkippingMediaCodecFlush} instead (but note that the value it
          takes is inverted)."""
        )
        fun setIsMediaCodecFlushEnabled(isMediaCodecFlushEnabled: Boolean): Builder {
            this.allowSkippingMediaCodecFlush = !isMediaCodecFlushEnabled
            return this
        }

        fun setAllowSkippingMediaCodecFlush(allowSkippingMediaCodecFlush: Boolean): Builder {
            this.allowSkippingMediaCodecFlush = allowSkippingMediaCodecFlush
            return this
        }

        fun setAllowSkippingKeyFrameReset(allowSkippingKeyFrameReset: Boolean): Builder {
            this.allowSkippingKeyFrameReset = allowSkippingKeyFrameReset
            return this
        }

        fun setUseDecodeOnlyFlag(useDecodeOnlyFlag: Boolean): Builder {
            this.useDecodeOnlyFlag = useDecodeOnlyFlag
            return this
        }

        fun build(): ScrubbingModeParameters {
            return ScrubbingModeParameters(this)
        }
    }

    val disabledTrackTypes: Set<Int>

    @FloatRange(from = 0.0, to = 1.0)
    val fractionalSeekToleranceBefore: Double?

    @FloatRange(from = 0.0, to = 1.0)
    val fractionalSeekToleranceAfter: Double?
    val shouldIncreaseCodecOperatingRate: Boolean

    @Deprecated(
        """Use {@link #allowSkippingMediaCodecFlush} instead (but note that it's value is
        inverted)."""
    )
    val isMediaCodecFlushEnabled: Boolean
    val allowSkippingMediaCodecFlush: Boolean
    val shouldEnableDynamicScheduling: Boolean
    val useDecodeOnlyFlag: Boolean
    val allowSkippingKeyFrameReset: Boolean

    init {
        this.disabledTrackTypes = builder.disabledTrackTypes
        this.fractionalSeekToleranceBefore = builder.fractionalSeekToleranceBefore
        this.fractionalSeekToleranceAfter = builder.fractionalSeekToleranceAfter
        this.shouldIncreaseCodecOperatingRate = builder.shouldIncreaseCodecOperatingRate
        this.isMediaCodecFlushEnabled = !builder.allowSkippingMediaCodecFlush
        this.allowSkippingMediaCodecFlush = builder.allowSkippingMediaCodecFlush
        this.allowSkippingKeyFrameReset = builder.allowSkippingKeyFrameReset
        this.shouldEnableDynamicScheduling = builder.shouldEnableDynamicScheduling
        this.useDecodeOnlyFlag = builder.useDecodeOnlyFlag
    }

    fun buildUpon(): Builder {
        return ScrubbingModeParameters.Builder(this)
    }

    override fun equals(o: Any?): Boolean {
        if (o !is ScrubbingModeParameters) {
            return false
        }
        val that = o
        return disabledTrackTypes == that.disabledTrackTypes
                && allowSkippingMediaCodecFlush == that.allowSkippingMediaCodecFlush && allowSkippingKeyFrameReset == that.allowSkippingKeyFrameReset && Objects.equals(
            fractionalSeekToleranceBefore,
            that.fractionalSeekToleranceBefore
        )
                && Objects.equals(fractionalSeekToleranceAfter, that.fractionalSeekToleranceAfter)
                && shouldIncreaseCodecOperatingRate == that.shouldIncreaseCodecOperatingRate && shouldEnableDynamicScheduling == that.shouldEnableDynamicScheduling && useDecodeOnlyFlag == that.useDecodeOnlyFlag
    }

    override fun hashCode(): Int {
        return Objects.hash(
            disabledTrackTypes,
            fractionalSeekToleranceBefore,
            fractionalSeekToleranceAfter,
            shouldIncreaseCodecOperatingRate,
            allowSkippingMediaCodecFlush,
            allowSkippingKeyFrameReset,
            shouldEnableDynamicScheduling,
            useDecodeOnlyFlag
        )
    }

    companion object {
        val DEFAULT: ScrubbingModeParameters = Builder().build()
    }
}
