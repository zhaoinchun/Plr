
package androidx.media3.exoplayer

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.msToUs
import com.google.common.base.Preconditions
import com.google.common.primitives.Longs
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.math.abs
import kotlin.math.max

class DefaultLivePlaybackSpeedControl private constructor(
    private val fallbackMinPlaybackSpeed: Float,
    private val fallbackMaxPlaybackSpeed: Float,
    private val minUpdateIntervalMs: Long,
    private val proportionalControlFactor: Float,
    private val maxLiveOffsetErrorUsForUnitSpeed: Long,
    private val targetLiveOffsetRebufferDeltaUs: Long,
    private val minPossibleLiveOffsetSmoothingFactor: Float
) : LivePlaybackSpeedControl {
    class Builder {
        private var fallbackMinPlaybackSpeed: Float
        private var fallbackMaxPlaybackSpeed: Float
        private var minUpdateIntervalMs: Long
        private var proportionalControlFactorUs: Float
        private var maxLiveOffsetErrorUsForUnitSpeed: Long
        private var targetLiveOffsetIncrementOnRebufferUs: Long
        private var minPossibleLiveOffsetSmoothingFactor: Float

        init {
            fallbackMinPlaybackSpeed = DEFAULT_FALLBACK_MIN_PLAYBACK_SPEED
            fallbackMaxPlaybackSpeed = DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED
            minUpdateIntervalMs = DEFAULT_MIN_UPDATE_INTERVAL_MS
            proportionalControlFactorUs = DEFAULT_PROPORTIONAL_CONTROL_FACTOR / C.MICROS_PER_SECOND
            maxLiveOffsetErrorUsForUnitSpeed =
                msToUs(DEFAULT_MAX_LIVE_OFFSET_ERROR_MS_FOR_UNIT_SPEED)
            targetLiveOffsetIncrementOnRebufferUs =
                msToUs(DEFAULT_TARGET_LIVE_OFFSET_INCREMENT_ON_REBUFFER_MS)
            minPossibleLiveOffsetSmoothingFactor = DEFAULT_MIN_POSSIBLE_LIVE_OFFSET_SMOOTHING_FACTOR
        }

        fun setFallbackMinPlaybackSpeed(fallbackMinPlaybackSpeed: Float): Builder {
            Preconditions.checkArgument(0 < fallbackMinPlaybackSpeed && fallbackMinPlaybackSpeed <= 1f)
            this.fallbackMinPlaybackSpeed = fallbackMinPlaybackSpeed
            return this
        }

        fun setFallbackMaxPlaybackSpeed(fallbackMaxPlaybackSpeed: Float): Builder {
            Preconditions.checkArgument(fallbackMaxPlaybackSpeed >= 1f)
            this.fallbackMaxPlaybackSpeed = fallbackMaxPlaybackSpeed
            return this
        }

        fun setMinUpdateIntervalMs(minUpdateIntervalMs: Long): Builder {
            Preconditions.checkArgument(minUpdateIntervalMs > 0)
            this.minUpdateIntervalMs = minUpdateIntervalMs
            return this
        }

        fun setProportionalControlFactor(proportionalControlFactor: Float): Builder {
            Preconditions.checkArgument(proportionalControlFactor > 0)
            this.proportionalControlFactorUs = proportionalControlFactor / C.MICROS_PER_SECOND
            return this
        }

        fun setMaxLiveOffsetErrorMsForUnitSpeed(maxLiveOffsetErrorMsForUnitSpeed: Long): Builder {
            Preconditions.checkArgument(maxLiveOffsetErrorMsForUnitSpeed > 0)
            this.maxLiveOffsetErrorUsForUnitSpeed = msToUs(maxLiveOffsetErrorMsForUnitSpeed)
            return this
        }

        fun setTargetLiveOffsetIncrementOnRebufferMs(
            targetLiveOffsetIncrementOnRebufferMs: Long
        ): Builder {
            Preconditions.checkArgument(targetLiveOffsetIncrementOnRebufferMs >= 0)
            this.targetLiveOffsetIncrementOnRebufferUs =
                msToUs(targetLiveOffsetIncrementOnRebufferMs)
            return this
        }

        fun setMinPossibleLiveOffsetSmoothingFactor(
            minPossibleLiveOffsetSmoothingFactor: Float
        ): Builder {
            Preconditions.checkArgument(
                minPossibleLiveOffsetSmoothingFactor >= 0 && minPossibleLiveOffsetSmoothingFactor < 1f
            )
            this.minPossibleLiveOffsetSmoothingFactor = minPossibleLiveOffsetSmoothingFactor
            return this
        }

        fun build(): DefaultLivePlaybackSpeedControl {
            return DefaultLivePlaybackSpeedControl(
                fallbackMinPlaybackSpeed,
                fallbackMaxPlaybackSpeed,
                minUpdateIntervalMs,
                proportionalControlFactorUs,
                maxLiveOffsetErrorUsForUnitSpeed,
                targetLiveOffsetIncrementOnRebufferUs,
                minPossibleLiveOffsetSmoothingFactor
            )
        }
    }

    private var mediaConfigurationTargetLiveOffsetUs: Long
    private var targetLiveOffsetOverrideUs: Long
    private var idealTargetLiveOffsetUs: Long
    private var minTargetLiveOffsetUs: Long
    private var maxTargetLiveOffsetUs: Long
    private var currentTargetLiveOffsetUs: Long

    private var maxPlaybackSpeed: Float
    private var minPlaybackSpeed: Float
    private var adjustedPlaybackSpeed: Float
    private var lastPlaybackSpeedUpdateMs: Long

    private var smoothedMinPossibleLiveOffsetUs: Long
    private var smoothedMinPossibleLiveOffsetDeviationUs: Long

    init {
        mediaConfigurationTargetLiveOffsetUs = C.TIME_UNSET
        targetLiveOffsetOverrideUs = C.TIME_UNSET
        minTargetLiveOffsetUs = C.TIME_UNSET
        maxTargetLiveOffsetUs = C.TIME_UNSET
        minPlaybackSpeed = fallbackMinPlaybackSpeed
        maxPlaybackSpeed = fallbackMaxPlaybackSpeed
        adjustedPlaybackSpeed = 1.0f
        lastPlaybackSpeedUpdateMs = C.TIME_UNSET
        idealTargetLiveOffsetUs = C.TIME_UNSET
        currentTargetLiveOffsetUs = C.TIME_UNSET
        smoothedMinPossibleLiveOffsetUs = C.TIME_UNSET
        smoothedMinPossibleLiveOffsetDeviationUs = C.TIME_UNSET
    }

    override fun setLiveConfiguration(liveConfiguration: MediaItem.LiveConfiguration) {
        mediaConfigurationTargetLiveOffsetUs = msToUs(liveConfiguration.targetOffsetMs)
        minTargetLiveOffsetUs = msToUs(liveConfiguration.minOffsetMs)
        maxTargetLiveOffsetUs = msToUs(liveConfiguration.maxOffsetMs)
        minPlaybackSpeed =
            if (liveConfiguration.minPlaybackSpeed != C.RATE_UNSET)
                liveConfiguration.minPlaybackSpeed
            else
                fallbackMinPlaybackSpeed
        maxPlaybackSpeed =
            if (liveConfiguration.maxPlaybackSpeed != C.RATE_UNSET)
                liveConfiguration.maxPlaybackSpeed
            else
                fallbackMaxPlaybackSpeed
        if (minPlaybackSpeed == 1f && maxPlaybackSpeed == 1f) {
            // Don't bother calculating adjustments if it's not possible to change the speed.
            mediaConfigurationTargetLiveOffsetUs = C.TIME_UNSET
        }
        maybeResetTargetLiveOffsetUs()
    }

    override fun setTargetLiveOffsetOverrideUs(liveOffsetUs: Long) {
        targetLiveOffsetOverrideUs = liveOffsetUs
        maybeResetTargetLiveOffsetUs()
    }

    override fun notifyRebuffer() {
        if (currentTargetLiveOffsetUs == C.TIME_UNSET) {
            return
        }
        currentTargetLiveOffsetUs += targetLiveOffsetRebufferDeltaUs
        if (maxTargetLiveOffsetUs != C.TIME_UNSET
            && currentTargetLiveOffsetUs > maxTargetLiveOffsetUs
        ) {
            currentTargetLiveOffsetUs = maxTargetLiveOffsetUs
        }
        lastPlaybackSpeedUpdateMs = C.TIME_UNSET
    }

    override fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long): Float {
        if (mediaConfigurationTargetLiveOffsetUs == C.TIME_UNSET) {
            return 1f
        }

        updateSmoothedMinPossibleLiveOffsetUs(liveOffsetUs, bufferedDurationUs)

        if (lastPlaybackSpeedUpdateMs != C.TIME_UNSET
            && SystemClock.elapsedRealtime() - lastPlaybackSpeedUpdateMs < minUpdateIntervalMs
        ) {
            return adjustedPlaybackSpeed
        }
        lastPlaybackSpeedUpdateMs = SystemClock.elapsedRealtime()

        adjustTargetLiveOffsetUs(liveOffsetUs)
        val liveOffsetErrorUs = liveOffsetUs - currentTargetLiveOffsetUs
        if (abs(liveOffsetErrorUs) < maxLiveOffsetErrorUsForUnitSpeed) {
            adjustedPlaybackSpeed = 1f
        } else {
            val calculatedSpeed = 1f + proportionalControlFactor * liveOffsetErrorUs
            adjustedPlaybackSpeed =
                constrainValue(calculatedSpeed, minPlaybackSpeed, maxPlaybackSpeed)
        }
        return adjustedPlaybackSpeed
    }

    override val targetLiveOffsetUs: Long
        get() = currentTargetLiveOffsetUs

    private fun maybeResetTargetLiveOffsetUs() {
        var idealOffsetUs = C.TIME_UNSET
        if (mediaConfigurationTargetLiveOffsetUs != C.TIME_UNSET) {
            if (targetLiveOffsetOverrideUs != C.TIME_UNSET) {
                idealOffsetUs = targetLiveOffsetOverrideUs
            } else {
                idealOffsetUs = mediaConfigurationTargetLiveOffsetUs
                if (minTargetLiveOffsetUs != C.TIME_UNSET && idealOffsetUs < minTargetLiveOffsetUs) {
                    idealOffsetUs = minTargetLiveOffsetUs
                }
                if (maxTargetLiveOffsetUs != C.TIME_UNSET && idealOffsetUs > maxTargetLiveOffsetUs) {
                    idealOffsetUs = maxTargetLiveOffsetUs
                }
            }
        }
        if (idealTargetLiveOffsetUs == idealOffsetUs) {
            return
        }
        idealTargetLiveOffsetUs = idealOffsetUs
        currentTargetLiveOffsetUs = idealOffsetUs
        smoothedMinPossibleLiveOffsetUs = C.TIME_UNSET
        smoothedMinPossibleLiveOffsetDeviationUs = C.TIME_UNSET
        lastPlaybackSpeedUpdateMs = C.TIME_UNSET
    }

    private fun updateSmoothedMinPossibleLiveOffsetUs(
        liveOffsetUs: Long,
        bufferedDurationUs: Long
    ) {
        val minPossibleLiveOffsetUs = liveOffsetUs - bufferedDurationUs
        if (smoothedMinPossibleLiveOffsetUs == C.TIME_UNSET) {
            smoothedMinPossibleLiveOffsetUs = minPossibleLiveOffsetUs
            smoothedMinPossibleLiveOffsetDeviationUs = 0
        } else {
            // Use the maximum here to ensure we keep track of the upper bound of what is safely possible,
            // not the average.
            smoothedMinPossibleLiveOffsetUs =
                max(
                    minPossibleLiveOffsetUs,
                    smooth(
                        smoothedMinPossibleLiveOffsetUs,
                        minPossibleLiveOffsetUs,
                        minPossibleLiveOffsetSmoothingFactor
                    )
                )
            val minPossibleLiveOffsetDeviationUs =
                abs(minPossibleLiveOffsetUs - smoothedMinPossibleLiveOffsetUs)
            smoothedMinPossibleLiveOffsetDeviationUs =
                smooth(
                    smoothedMinPossibleLiveOffsetDeviationUs,
                    minPossibleLiveOffsetDeviationUs,
                    minPossibleLiveOffsetSmoothingFactor
                )
        }
    }

    private fun adjustTargetLiveOffsetUs(liveOffsetUs: Long) {
        // Stay in a safe distance (3 standard deviations = >99%) to the minimum possible live offset.
        val safeOffsetUs =
            smoothedMinPossibleLiveOffsetUs + 3 * smoothedMinPossibleLiveOffsetDeviationUs
        if (currentTargetLiveOffsetUs > safeOffsetUs) {
            // There is room for decreasing the target offset towards the ideal or safe offset (whichever
            // is larger). We want to limit the decrease so that the playback speed delta we achieve is
            // the same as the maximum delta when slowing down towards the target.
            val minUpdateIntervalUs = msToUs(minUpdateIntervalMs)
            val decrementToOffsetCurrentSpeedUs =
                ((adjustedPlaybackSpeed - 1f) * minUpdateIntervalUs).toLong()
            val decrementToIncreaseSpeedUs =
                ((maxPlaybackSpeed - 1f) * minUpdateIntervalUs).toLong()
            val maxDecrementUs = decrementToOffsetCurrentSpeedUs + decrementToIncreaseSpeedUs
            currentTargetLiveOffsetUs =
                Longs.max(
                    safeOffsetUs,
                    idealTargetLiveOffsetUs,
                    currentTargetLiveOffsetUs - maxDecrementUs
                )
        } else {
            // We'd like to reach a stable condition where the current live offset stays just below the
            // safe offset. But don't increase the target offset to more than what would allow us to slow
            // down gradually from the current offset.
            val offsetWhenSlowingDownNowUs =
                liveOffsetUs - (max(
                    0f,
                    adjustedPlaybackSpeed - 1f
                ) / proportionalControlFactor).toLong()
            currentTargetLiveOffsetUs =
                constrainValue(offsetWhenSlowingDownNowUs, currentTargetLiveOffsetUs, safeOffsetUs)
            if (maxTargetLiveOffsetUs != C.TIME_UNSET
                && currentTargetLiveOffsetUs > maxTargetLiveOffsetUs
            ) {
                currentTargetLiveOffsetUs = maxTargetLiveOffsetUs
            }
        }
    }

    companion object {
        const val DEFAULT_FALLBACK_MIN_PLAYBACK_SPEED: Float = 0.97f
        const val DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED: Float = 1.03f
        const val DEFAULT_MIN_UPDATE_INTERVAL_MS: Long = 1000
        const val DEFAULT_PROPORTIONAL_CONTROL_FACTOR: Float = 0.1f
        const val DEFAULT_TARGET_LIVE_OFFSET_INCREMENT_ON_REBUFFER_MS: Long = 500
        const val DEFAULT_MIN_POSSIBLE_LIVE_OFFSET_SMOOTHING_FACTOR: Float = 0.999f
        const val DEFAULT_MAX_LIVE_OFFSET_ERROR_MS_FOR_UNIT_SPEED: Long = 20

        private fun smooth(smoothedValue: Long, newValue: Long, smoothingFactor: Float): Long {
            return (smoothingFactor * smoothedValue + (1f - smoothingFactor) * newValue).toLong()
        }
    }
}
