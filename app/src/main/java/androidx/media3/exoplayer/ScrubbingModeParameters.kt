package androidx.media3.exoplayer

import androidx.media3.common.C

data class ScrubbingModeParameters(
    var disabledTrackTypes: Set<Int> =
        setOf<@C.TrackType Int>(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA),
    var fractionalSeekToleranceBefore: Double? = null,
    var fractionalSeekToleranceAfter: Double? = null,
    var shouldIncreaseCodecOperatingRate: Boolean = true,
    var allowSkippingMediaCodecFlush: Boolean = true,
    var allowSkippingKeyFrameReset: Boolean = true,
    var shouldEnableDynamicScheduling: Boolean = true,
    var useDecodeOnlyFlag: Boolean = true,
) {
    companion object {
        val DEFAULT: ScrubbingModeParameters = ScrubbingModeParameters()
    }
}
