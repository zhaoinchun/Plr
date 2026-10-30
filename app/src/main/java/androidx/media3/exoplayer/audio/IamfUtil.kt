package androidx.media3.exoplayer.audio

import android.media.AudioFormat
import android.os.Build
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.Log.w

object IamfUtil {
    private const val TAG = "IamfUtil"
    const val REQUESTED_MIX_PRESENTATION_ID_UNSET: Long = -1
    const val OUTPUT_LAYOUT_UNSET: Int = -1
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0: Int = 0
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0: Int = 1
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0: Int = 2
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0: Int = 3
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1: Int = 4
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0: Int = 5
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0: Int = 6
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3: Int = 7
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0: Int = 8
    const val OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0: Int = 9
    const val OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0: Int = 10
    const val OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0: Int = 11
    const val OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0: Int = 12
    const val OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0: Int = 13
    const val OUTPUT_LAYOUT_BINAURAL: Int = 14

    // ===== Additionally defined Channel Masks =====
    // The following channel masks are supplements to those defined in {@link AudioFormat}, to allow
    // more permissive matching of channel masks to output layouts.  In most cases, there are small
    // differences between the channels used in AudioFormat and those used in IAMF or ITU spec.
    // The Android defined 5.1.2 and 7.1.2 use top *side* left/right which does
    // not match their ITU (ITU Sound System C) and IAMF equivalents. We will define the ITU/IAMF
    // versions here.
    @RequiresApi(32)
    private val CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0 = ((AudioFormat.CHANNEL_OUT_5POINT1
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT))

    @RequiresApi(32)
    private val CHANNEL_OUT_IAMF_7POINT1POINT2 = ((AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT))

    // {@link AudioFormat#CHANNEL_OUT_9POINT1POINT4} and {@link AudioFormat#CHANNEL_OUT_9POINT1POINT6}
    // use _WIDE_LEFT and _WIDE_RIGHT but the ITU spec for Sound System G says "left [/right] screen
    // edge" and the ITU spec for Sound System H, which is the basis for the IAMF 9.1.6 uses
    // LEFT/RIGHT_OF_CENTER so we'll allow permissive matching.
    @RequiresApi(32)
    private val CHANNEL_OUT_IAMF_9POINT1POINT4 = ((AudioFormat.CHANNEL_OUT_7POINT1POINT4
            or AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
            or AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER))

    @RequiresApi(32)
    private val CHANNEL_OUT_IAMF_9POINT1POINT6 = ((CHANNEL_OUT_IAMF_9POINT1POINT4
            or AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT
            or AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT))

    // Sound Systems E, F, and H are defined in ITU B.S. 2051-3 but are not defined by Android.
    // We can make them from combinations of speakers available in Android.  Note that H is equivalent
    // to AudioFormat.CHANNEL_OUT_22POINT2, which is hidden.
    @RequiresApi(32)
    private val CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1 =
        (AudioFormat.CHANNEL_OUT_5POINT1POINT4 or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER)

    @RequiresApi(32)
    private val ITU_2051_SOUND_SYSTEM_F_3_7_0 = ((AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT
            or AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER
            or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2))

    @RequiresApi(32)
    val CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3: Int = ((AudioFormat.CHANNEL_OUT_7POINT1POINT4
            or AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
            or AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER
            or AudioFormat.CHANNEL_OUT_BACK_CENTER
            or AudioFormat.CHANNEL_OUT_TOP_CENTER
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER
            or AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER
            or AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT
            or AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT
            or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT
            or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT
            or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER
            or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2))

    // This is just an IAMF layout that does not have an Android-defined version.
    @RequiresApi(32)
    private val CHANNEL_OUT_IAMF_3POINT1POINT2 = ((AudioFormat.CHANNEL_OUT_FRONT_LEFT
            or AudioFormat.CHANNEL_OUT_FRONT_RIGHT
            or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
            or AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT))

    // ===== End of additionally defined Channel Masks =====
    // All channel masks can be translated to IAMF output layouts.
    @VisibleForTesting
    val IAMF_SUPPORTED_CHANNEL_MASKS: Set<Int?> = if (Build.VERSION.SDK_INT < 32)
        setOf<Int?>(
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            AudioFormat.CHANNEL_OUT_MONO
        )
    else
        setOf<Int?>( // The same order as appears in getOutputLayoutForChannelMask.
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.CHANNEL_OUT_5POINT1POINT2,
            CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0,
            AudioFormat.CHANNEL_OUT_5POINT1POINT4,
            CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1,
            ITU_2051_SOUND_SYSTEM_F_3_7_0,
            AudioFormat.CHANNEL_OUT_9POINT1POINT4,
            CHANNEL_OUT_IAMF_9POINT1POINT4,
            CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3,
            AudioFormat.CHANNEL_OUT_7POINT1POINT4,
            AudioFormat.CHANNEL_OUT_7POINT1POINT2,
            CHANNEL_OUT_IAMF_7POINT1POINT2,
            CHANNEL_OUT_IAMF_3POINT1POINT2,
            AudioFormat.CHANNEL_OUT_9POINT1POINT6,
            CHANNEL_OUT_IAMF_9POINT1POINT6
        )

    fun getOutputLayoutForChannelMask(channelMask: Int): @OutputLayout Int {
        when (channelMask) {
            AudioFormat.CHANNEL_OUT_STEREO -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0
            AudioFormat.CHANNEL_OUT_5POINT1 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0
            AudioFormat.CHANNEL_OUT_MONO -> return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0
            else -> {}
        }
        if (Build.VERSION.SDK_INT >= 32) {
            when (channelMask) {
                AudioFormat.CHANNEL_OUT_5POINT1POINT2, CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0
                AudioFormat.CHANNEL_OUT_5POINT1POINT4 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0
                CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1
                ITU_2051_SOUND_SYSTEM_F_3_7_0 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0
                AudioFormat.CHANNEL_OUT_9POINT1POINT4, CHANNEL_OUT_IAMF_9POINT1POINT4 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0
                CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3
                AudioFormat.CHANNEL_OUT_7POINT1POINT4 -> return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0
                AudioFormat.CHANNEL_OUT_7POINT1POINT2, CHANNEL_OUT_IAMF_7POINT1POINT2 -> return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0
                CHANNEL_OUT_IAMF_3POINT1POINT2 -> return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0
                AudioFormat.CHANNEL_OUT_9POINT1POINT6, CHANNEL_OUT_IAMF_9POINT1POINT6 -> return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0
                else -> {}
            }
        }
        throw IllegalArgumentException("Unsupported channel mask: " + channelMask)
    }

    fun getChannelMaskForOutputLayout(outputLayout: @OutputLayout Int): Int {
        when (outputLayout) {
            OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0 -> return AudioFormat.CHANNEL_OUT_STEREO
            OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0 -> return AudioFormat.CHANNEL_OUT_5POINT1
            OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0 -> return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0 -> return AudioFormat.CHANNEL_OUT_MONO
            else -> {}
        }
        if (Build.VERSION.SDK_INT >= 32) {
            when (outputLayout) {
                OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0 -> return CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0
                OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0 -> return AudioFormat.CHANNEL_OUT_5POINT1POINT4
                OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1 -> return CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1
                OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0 -> return ITU_2051_SOUND_SYSTEM_F_3_7_0
                OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0 -> return CHANNEL_OUT_IAMF_9POINT1POINT4
                OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3 -> return CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3
                OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0 -> return AudioFormat.CHANNEL_OUT_7POINT1POINT4
                OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0 -> return CHANNEL_OUT_IAMF_7POINT1POINT2
                OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0 -> return CHANNEL_OUT_IAMF_3POINT1POINT2
                OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0 -> return CHANNEL_OUT_IAMF_9POINT1POINT6
                else -> {}
            }
        }
        throw IllegalArgumentException("Unsupported output layout: $outputLayout")
    }

    fun getOutputLayoutForCurrentConfiguration(
        audioCapabilities: AudioCapabilities, useIntegratedBinauralRenderer: Boolean
    ): @OutputLayout Int {
        if (!audioCapabilities.spatializerChannelMasks.isEmpty()) {
            // If Spatializer enabled and available, we will use that as a signal that the user is likely
            // using headphones and wants spatial audio, but we will use decoder built-in binaural.
            if (useIntegratedBinauralRenderer) {
                // Directly request binaural audio from the decoder.
                return OUTPUT_LAYOUT_BINAURAL
            }
            val spatializerChannelMask =
                getFirstCompatibleChannelMask(audioCapabilities.spatializerChannelMasks)
            if (spatializerChannelMask != AudioFormat.CHANNEL_INVALID) {
                return getOutputLayoutForChannelMask(spatializerChannelMask)
            }
            w(
                TAG,
                "No spatializer channel mask is compatible with IAMF, falling back to speaker layout."
            )
        }

        val speakerLayoutChannelMask =
            getFirstCompatibleChannelMask(audioCapabilities.speakerLayoutChannelMasks)
        if (speakerLayoutChannelMask != AudioFormat.CHANNEL_INVALID) {
            return getOutputLayoutForChannelMask(speakerLayoutChannelMask)
        }
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0 // Default to stereo.
    }

    fun getOutputChannelMaskForCurrentConfiguration(
        audioCapabilities: AudioCapabilities
    ): Int {
        val spatializerChannelMask =
            getFirstCompatibleChannelMask(audioCapabilities.spatializerChannelMasks)
        if (spatializerChannelMask != AudioFormat.CHANNEL_INVALID) {
            return spatializerChannelMask
        }

        val speakerLayoutChannelMask =
            getFirstCompatibleChannelMask(audioCapabilities.speakerLayoutChannelMasks)
        if (speakerLayoutChannelMask != AudioFormat.CHANNEL_INVALID) {
            return speakerLayoutChannelMask
        }
        return AudioFormat.CHANNEL_OUT_STEREO
    }

    private fun getFirstCompatibleChannelMask(channelMasks: List<Int?>): Int {
        return channelMasks.find { IAMF_SUPPORTED_CHANNEL_MASKS.contains(it) }
            ?: AudioFormat.CHANNEL_INVALID
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [OUTPUT_LAYOUT_UNSET, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0, OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0, OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0, OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0, OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0, OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0, OUTPUT_LAYOUT_BINAURAL]
    )
    annotation class OutputLayout
}
