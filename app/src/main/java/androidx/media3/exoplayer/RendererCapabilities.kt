package androidx.media3.exoplayer

import android.annotation.SuppressLint
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format

interface RendererCapabilities {
    interface Listener {
        fun onRendererCapabilitiesChanged(renderer: Renderer)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [ADAPTIVE_SEAMLESS, ADAPTIVE_NOT_SEAMLESS, ADAPTIVE_NOT_SUPPORTED])
    annotation class AdaptiveSupport

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [TUNNELING_SUPPORTED, TUNNELING_NOT_SUPPORTED])
    annotation class TunnelingSupport

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [HARDWARE_ACCELERATION_SUPPORTED, HARDWARE_ACCELERATION_NOT_SUPPORTED
        ]
    )
    annotation class HardwareAccelerationSupport

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [DECODER_SUPPORT_FALLBACK_MIMETYPE, DECODER_SUPPORT_PRIMARY, DECODER_SUPPORT_FALLBACK])
    annotation class DecoderSupport

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED, AUDIO_OFFLOAD_GAPLESS_SUPPORTED, AUDIO_OFFLOAD_SUPPORTED, AUDIO_OFFLOAD_NOT_SUPPORTED
        ]
    )
    annotation class AudioOffloadSupport

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE) // Intentionally empty to prevent assignment or comparison with individual flags without masking.
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    annotation class Capabilities

    val name: String
    val trackType: @C.TrackType Int

    @Throws(ExoPlaybackException::class)
    fun supportsFormat(format: Format): @Capabilities Int

    @Throws(ExoPlaybackException::class)
    fun supportsMixedMimeTypeAdaptation(): @AdaptiveSupport Int

    fun setListener(listener: Listener?) {
        // Do nothing.
    }

    fun clearListener() {
        // Do nothing.
    }

    companion object {
        fun create(
            @C.FormatSupport formatSupport: @C.FormatSupport Int,
            adaptiveSupport: @AdaptiveSupport Int,
            tunnelingSupport: @TunnelingSupport Int
        ): @Capabilities Int {
            return create(
                formatSupport,
                adaptiveSupport,
                tunnelingSupport,
                HARDWARE_ACCELERATION_NOT_SUPPORTED,
                DECODER_SUPPORT_PRIMARY,
                AUDIO_OFFLOAD_NOT_SUPPORTED
            )
        }

        fun create(
            @C.FormatSupport formatSupport: @C.FormatSupport Int,
            adaptiveSupport: @AdaptiveSupport Int = ADAPTIVE_NOT_SUPPORTED,
            tunnelingSupport: @TunnelingSupport Int = TUNNELING_NOT_SUPPORTED,
            audioOffloadSupport: @AudioOffloadSupport Int = AUDIO_OFFLOAD_NOT_SUPPORTED
        ): @Capabilities Int {
            return create(
                formatSupport,
                adaptiveSupport,
                tunnelingSupport,
                HARDWARE_ACCELERATION_NOT_SUPPORTED,
                DECODER_SUPPORT_PRIMARY,
                audioOffloadSupport
            )
        }

        fun create(
            @C.FormatSupport formatSupport: @C.FormatSupport Int,
            adaptiveSupport: @AdaptiveSupport Int,
            tunnelingSupport: @TunnelingSupport Int,
            hardwareAccelerationSupport: @HardwareAccelerationSupport Int,
            decoderSupport: @DecoderSupport Int
        ): @Capabilities Int {
            return create(
                formatSupport,
                adaptiveSupport,
                tunnelingSupport,
                hardwareAccelerationSupport,
                decoderSupport,
                AUDIO_OFFLOAD_NOT_SUPPORTED
            )
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun create(
            @C.FormatSupport formatSupport: @C.FormatSupport Int,
            adaptiveSupport: @AdaptiveSupport Int,
            tunnelingSupport: @TunnelingSupport Int,
            hardwareAccelerationSupport: @HardwareAccelerationSupport Int,
            decoderSupport: @DecoderSupport Int,
            audioOffloadSupport: @AudioOffloadSupport Int
        ): @Capabilities Int {
            return (formatSupport
                    or adaptiveSupport
                    or tunnelingSupport
                    or hardwareAccelerationSupport
                    or decoderSupport
                    or audioOffloadSupport)
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        @C.FormatSupport
        fun getFormatSupport(supportFlags: @Capabilities Int): @C.FormatSupport Int {
            return supportFlags and FORMAT_SUPPORT_MASK
        }

        fun isFormatSupported(
            supportFlags: @Capabilities Int, allowExceedsCapabilities: Boolean
        ): Boolean {
            @C.FormatSupport val formatSupport: @C.FormatSupport Int =
                getFormatSupport(supportFlags)
            return formatSupport == C.FORMAT_HANDLED
                    || (allowExceedsCapabilities && formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES)
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getAdaptiveSupport(supportFlags: @Capabilities Int): @AdaptiveSupport Int {
            return supportFlags and ADAPTIVE_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getTunnelingSupport(supportFlags: @Capabilities Int): @TunnelingSupport Int {
            return supportFlags and TUNNELING_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getHardwareAccelerationSupport(
            supportFlags: @Capabilities Int
        ): @HardwareAccelerationSupport Int {
            return supportFlags and HARDWARE_ACCELERATION_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getDecoderSupport(supportFlags: @Capabilities Int): @DecoderSupport Int {
            return supportFlags and DECODER_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getAudioOffloadSupport(supportFlags: @Capabilities Int): @AudioOffloadSupport Int {
            return supportFlags and AUDIO_OFFLOAD_SUPPORT_MASK
        }

        const val FORMAT_SUPPORT_MASK: Int = 7
        const val ADAPTIVE_SUPPORT_MASK: Int = 3 shl 3
        const val ADAPTIVE_SEAMLESS: Int = 2 shl 3
        const val ADAPTIVE_NOT_SEAMLESS: Int = 1 shl 3
        const val ADAPTIVE_NOT_SUPPORTED: Int = 0
        const val TUNNELING_SUPPORT_MASK: Int = 1 shl 5
        const val TUNNELING_SUPPORTED: Int = 1 shl 5
        const val TUNNELING_NOT_SUPPORTED: Int = 0
        const val HARDWARE_ACCELERATION_SUPPORT_MASK: Int = 1 shl 6
        const val HARDWARE_ACCELERATION_SUPPORTED: Int = 1 shl 6
        const val HARDWARE_ACCELERATION_NOT_SUPPORTED: Int = 0
        const val DECODER_SUPPORT_MASK: Int = 3 shl 7
        const val DECODER_SUPPORT_FALLBACK_MIMETYPE: Int = 2 shl 7
        const val DECODER_SUPPORT_PRIMARY: Int = 1 shl 7
        const val DECODER_SUPPORT_FALLBACK: Int = 0
        const val AUDIO_OFFLOAD_SUPPORT_MASK: Int = 7 shl 9
        const val AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED: Int = 4 shl 9
        const val AUDIO_OFFLOAD_GAPLESS_SUPPORTED: Int = 2 shl 9
        const val AUDIO_OFFLOAD_SUPPORTED: Int = 1 shl 9
        const val AUDIO_OFFLOAD_NOT_SUPPORTED: Int = 0
    }
}
