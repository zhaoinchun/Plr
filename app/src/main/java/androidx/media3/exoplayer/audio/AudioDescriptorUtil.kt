package androidx.media3.exoplayer.audio

import android.media.AudioDescriptor
import android.media.AudioFormat
import android.os.Build
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.getAudioTrackChannelConfig
import java.util.TreeSet

internal object AudioDescriptorUtil {
    const val TAG: String = "AudioDescriptorUtil"

    fun getAllLpcmChannelMasksFromPcmSads(audioDescriptors: MutableList<AudioDescriptor>)
            : List<Int> {
        // AudioDeviceInfoBuilder has support for setting AudioDescriptors.  The null check can be
        // removed when that is done.
        if (Build.VERSION.SDK_INT < 31) {
            // AudioDescriptor.STANDARD_EDID requires API 31.
            return listOf()
        }
        val channelMasks =
            TreeSet(Comparator.comparing<Int, Int> { it.countOneBits() }.reversed())
        for (audioDescriptor in audioDescriptors) {
            if (audioDescriptor.standard == AudioDescriptor.STANDARD_EDID) {
                val data = audioDescriptor.descriptor
                if (data.size != 3) {
                    // Invalid SAD.
                    w(TAG, "Invalid SAD length: " + data.size)
                    continue
                }
                val firstByte = data[0]
                // First three bits are number of channels - 1
                val numChannels = (firstByte.toInt() and 7) + 1
                // Bits 3–6 are audio format type.  We only care about LPCM (0b0001).
                val audioFormat = (firstByte.toInt() shr 3) and 15
                if (audioFormat == 1) {
                    channelMasks.add(getAudioTrackChannelConfig(numChannels))
                }
            }
        }
        return channelMasks.toList()
    }

    fun getAllChannelMasksFromSadbs(audioDescriptors: MutableList<AudioDescriptor>)
            : List<Int> {
        // AudioDeviceInfoBuilder has support for setting AudioDescriptors.  The null check can be
        // removed when that is done.
        if (Build.VERSION.SDK_INT < 34) {
            // AudioDescriptor.STANDARD_SADB requires API 34.
            return listOf()
        }
        val channelMasks: MutableList<Int> = ArrayList<Int>()
        for (audioDescriptor in audioDescriptors) {
            if (audioDescriptor.standard == AudioDescriptor.STANDARD_SADB) {
                val data = audioDescriptor.descriptor
                if (data.size != 3) {
                    // Though it is not clear from the getDescriptor() javadoc, we can see in the
                    // implementation of AudioDescriptor that the bytes returned are just the 3-byte payload,
                    // not including the header byte.
                    w(TAG, "Invalid SADB length: " + data.size)
                    continue
                }
                val channelMask = getChannelMaskFromSadb(data)
                channelMasks.add(channelMask)
            }
        }
        channelMasks.sortWith(Comparator { a: Int, b: Int ->
            Integer.bitCount(b) - Integer.bitCount(a)
        })
        return channelMasks.toList()
    }

    fun getChannelMaskFromSadb(data: ByteArray): Int {
        if (Build.VERSION.SDK_INT < 34 || data.size != 3) {
            // AudioDescriptor.STANDARD_SADB requires API 34.
            return 0
        }
        var channelMask = 0
        val byte1 = data[0]
        // Byte 1
        // Bit 0 Front left/right (FL/FR).
        if ((byte1.toInt() and 1) != 0) {
            channelMask =
                channelMask or (AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT)
        }
        // Bit 1 Low-frequency effects (LFE1).
        if ((byte1.toInt() and 2) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY
        }
        // Bit 2 Front center (FC).
        if ((byte1.toInt() and 4) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        }
        // Bit 3 Back left/right (BL/BR).
        if ((byte1.toInt() and 8) != 0) {
            channelMask =
                channelMask or (AudioFormat.CHANNEL_OUT_BACK_LEFT or AudioFormat.CHANNEL_OUT_BACK_RIGHT)
        }
        // Bit 4 Back center (BC).
        if ((byte1.toInt() and 16) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_BACK_CENTER
        }
        // Bit 5 Front left/right center (FLc/FRc).
        if ((byte1.toInt() and 32) != 0) {
            channelMask = channelMask or
                    (AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
                            or AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER)
        }

        // Bit 6 Rear left/right center (RLC/RRC) are deprecated and also do not exist in AudioFormat
        // constants.

        // Bit 7 Front left/right wide (FLw/FRw).
        if ((byte1.toInt() and 128) != 0) {
            channelMask = channelMask or (
                    AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT or AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT)
        }

        val byte2 = data[1]
        // Byte 2
        // Bit 0 Top front left/right (TpFL/TpFR).
        if ((byte2.toInt() and 1) != 0) {
            channelMask = channelMask or (
                    AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT or AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT)
        }
        // Bit 1 Top center (TpC).
        if ((byte2.toInt() and 2) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_TOP_CENTER
        }
        // Bit 2 Top front center (TpFC).
        if ((byte2.toInt() and 4) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER
        }
        // Bit 3 Left surround/right surround (LS/RS).
        if ((byte2.toInt() and 8) != 0) {
            channelMask =
                channelMask or (AudioFormat.CHANNEL_OUT_SIDE_LEFT or AudioFormat.CHANNEL_OUT_SIDE_RIGHT)
        }
        // Bit 4 Low-frequency effects 2 (LFE2). [Deprecated]
        if ((byte2.toInt() and 16) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2
        }
        // Bit 5 Top back center (TpBC). [Deprecated]
        if ((byte2.toInt() and 32) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER
        }
        // Bit 6 Side left/right (SiL/SiR). [Deprecated]
        if ((byte2.toInt() and 64) != 0) {
            // Same as left/right surrounds.
            channelMask =
                channelMask or (AudioFormat.CHANNEL_OUT_SIDE_LEFT or AudioFormat.CHANNEL_OUT_SIDE_RIGHT)
        }
        // Bit 7 Top side left/right (TpSiL/TpSiR). [Deprecated]
        if ((byte2.toInt() and 128) != 0) {
            channelMask =
                channelMask or (AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT or AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT)
        }

        val byte3 = data[2]
        // Byte 3
        // Bit 1 Top back left/right (TpBL/TpBR). [Deprecated]
        if ((byte3.toInt() and 1) != 0) {
            channelMask =
                channelMask or (AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT or AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT)
        }
        // Bit 2 Bottom front center (BtFC). [Deprecated]
        if ((byte3.toInt() and 2) != 0) {
            channelMask = channelMask or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER
        }
        // Bit 3 Bottom front left/right (BtFL/BtFR). [Deprecated]
        if ((byte3.toInt() and 4) != 0) {
            channelMask = channelMask or (
                    AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT)
        }

        // Remaining bits are reserved.
        return channelMask
    }
}
