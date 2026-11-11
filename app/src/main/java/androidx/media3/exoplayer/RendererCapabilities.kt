package androidx.media3.exoplayer

import android.annotation.SuppressLint
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format

interface RendererCapabilities {
    interface Listener {
        fun onRendererCapabilitiesChanged(renderer: Renderer)
    }
    val name: String
    val trackType: Int

    @Throws(ExoPlaybackException::class)
    fun supportsFormat(format: Format):  Int

    @Throws(ExoPlaybackException::class)
    fun supportsMixedMimeTypeAdaptation(): Int

    fun setListener(listener: Listener?) {
        // Do nothing.
    }

    fun clearListener() {
        // Do nothing.
    }

    companion object {
        fun create(
             formatSupport:  Int,
            adaptiveSupport: Int,
            tunnelingSupport:  Int
        ):  Int {
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
             formatSupport:  Int,
            adaptiveSupport: Int = ADAPTIVE_NOT_SUPPORTED,
            tunnelingSupport:  Int = TUNNELING_NOT_SUPPORTED,
            audioOffloadSupport:  Int = AUDIO_OFFLOAD_NOT_SUPPORTED
        ):  Int {
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
             formatSupport:  Int,
            adaptiveSupport: Int,
            tunnelingSupport:  Int,
            hardwareAccelerationSupport: Int,
            decoderSupport:  Int
        ):  Int {
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
             formatSupport:  Int,
            adaptiveSupport: Int,
            tunnelingSupport:  Int,
            hardwareAccelerationSupport: Int,
            decoderSupport:  Int,
            audioOffloadSupport:  Int
        ):  Int {
            return (formatSupport
                    or adaptiveSupport
                    or tunnelingSupport
                    or hardwareAccelerationSupport
                    or decoderSupport
                    or audioOffloadSupport)
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        
        fun getFormatSupport(supportFlags:  Int):  Int {
            return supportFlags and FORMAT_SUPPORT_MASK
        }

        fun isFormatSupported(
            supportFlags:  Int, allowExceedsCapabilities: Boolean
        ): Boolean {
             val formatSupport:  Int =
                getFormatSupport(supportFlags)
            return formatSupport == C.FORMAT_HANDLED
                    || (allowExceedsCapabilities && formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES)
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getAdaptiveSupport(supportFlags:  Int): Int {
            return supportFlags and ADAPTIVE_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getTunnelingSupport(supportFlags:  Int):  Int {
            return supportFlags and TUNNELING_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getHardwareAccelerationSupport(
            supportFlags:  Int
        ): Int {
            return supportFlags and HARDWARE_ACCELERATION_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getDecoderSupport(supportFlags:  Int):  Int {
            return supportFlags and DECODER_SUPPORT_MASK
        }

        // Suppression needed for IntDef casting.
        @SuppressLint("WrongConstant")
        fun getAudioOffloadSupport(supportFlags:  Int):  Int {
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
