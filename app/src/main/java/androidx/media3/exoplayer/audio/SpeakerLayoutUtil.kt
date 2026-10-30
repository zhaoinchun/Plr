package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioProfile
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.isEncodingLinearPcm
import java.util.TreeSet
import java.util.function.Function

internal object SpeakerLayoutUtil {
    private const val TAG = "SpeakerLayoutUtil"

    // Bluetooth devices are always max 2 channels.  We assume stereo.
    private val channelMasksForBluetooth=listOf(AudioFormat.CHANNEL_OUT_STEREO)

    fun getLoudspeakerLayoutChannelMasks(
        audioDeviceInfo: AudioDeviceInfo
    ): List<Int> {
        if (DeviceTypeUtil.isBluetoothDevice(audioDeviceInfo.getType())) {
            return channelMasksForBluetooth
        }
        if (DeviceTypeUtil.isBuiltInEarpiece(audioDeviceInfo.getType())) {
            return listOf(AudioFormat.CHANNEL_OUT_MONO)
        }
        if (DeviceTypeUtil.isBuiltInSpeaker(audioDeviceInfo.getType())) {
            return getChannelMasksForBuiltInSpeakers(audioDeviceInfo)
        }
        if (Build.VERSION.SDK_INT >= 31 && DeviceTypeUtil.isHdmiArc(audioDeviceInfo.getType())) {
            return getChannelMasksForHdmiArc(audioDeviceInfo)
        }
        if (Build.VERSION.SDK_INT >= 31 && DeviceTypeUtil.isHdmiEarc(audioDeviceInfo.getType())) {
            return getChannelMasksForHdmiEarc(audioDeviceInfo)
        }
        if (Build.VERSION.SDK_INT >= 31 && DeviceTypeUtil.isUsbDevice(audioDeviceInfo.getType())) {
            return getChannelMasksForUsb(audioDeviceInfo)
        }

        // Default
        return channelMasksForBluetooth
    }

    private fun getChannelMasksForBuiltInSpeakers(
        audioDeviceInfo: AudioDeviceInfo
    ): List<Int> {
        if (Build.VERSION.SDK_INT >= 36) {
            // New API in SDK level 36, backed by a new field in
            // media/aidl/android/media/audio/common/AudioPortDeviceExt.aidl
            val builtInChannelMask = audioDeviceInfo.speakerLayoutChannelMask
            if (builtInChannelMask != AudioFormat.CHANNEL_INVALID
                && builtInChannelMask != AudioFormat.CHANNEL_OUT_DEFAULT
            ) {
                return listOf(builtInChannelMask)
            }
        }
        // If the manufacturer has not populated the field in the Audio HAL, fall back to stereo.
        w(TAG, "Built-in speaker's getSpeakerLayoutChannelMask not usable, defaulting to stereo.")
        return channelMasksForBluetooth
    }

    @RequiresApi(31)
    private fun getChannelMasksForHdmiArc(audioDeviceInfo: AudioDeviceInfo): List<Int> {
        // First, check AudioProfiles
        val channelMasksFromAudioProfiles =
            getChannelMasksFromPcmAudioProfiles(audioDeviceInfo)
        if (!channelMasksFromAudioProfiles.isEmpty()) {
            return channelMasksFromAudioProfiles
        }
        // Fall-back to Short Audio Descriptors (SADs).
        val channelMasks =
            AudioDescriptorUtil.getAllLpcmChannelMasksFromPcmSads(
                audioDeviceInfo.audioDescriptors
            )
        if (!channelMasks.isEmpty()) {
            return channelMasks
        }
        // Last resort, return stereo.
        return channelMasksForBluetooth
    }

    @RequiresApi(31)
    private fun getChannelMasksForHdmiEarc(
        audioDeviceInfo: AudioDeviceInfo
    ): List<Int> {
        // First, check AudioProfiles
        val channelMasksFromAudioProfiles =
            getChannelMasksFromPcmAudioProfiles(audioDeviceInfo)
        if (!channelMasksFromAudioProfiles.isEmpty()) {
            return channelMasksFromAudioProfiles
        }
        // Next check for Speaker Allocation Data Block (SADB).
        val audioDescriptors = audioDeviceInfo.audioDescriptors
        if (Build.VERSION.SDK_INT >= 34) {
            val channelMasksFromSadbs =
                AudioDescriptorUtil.getAllChannelMasksFromSadbs(audioDescriptors)
            if (!channelMasksFromSadbs.isEmpty()) {
                return channelMasksFromSadbs
            }
        }
        // Then check for Short Audio Descriptors (SADs).
        val channelMasksFromSads =
            AudioDescriptorUtil.getAllLpcmChannelMasksFromPcmSads(audioDescriptors)
        if (!channelMasksFromSads.isEmpty()) {
            return channelMasksFromSads
        }
        // Last resort, return stereo.
        return channelMasksForBluetooth
    }

    @RequiresApi(31)
    private fun getChannelMasksForUsb(audioDeviceInfo: AudioDeviceInfo): List<Int> {
        // First, check AudioProfiles
        val channelMasksFromAudioProfiles =
            getChannelMasksFromPcmAudioProfiles(audioDeviceInfo)
        if (!channelMasksFromAudioProfiles.isEmpty()) {
            return channelMasksFromAudioProfiles
        }
        // Default stereo.
        return channelMasksForBluetooth
    }

    @RequiresApi(31)  // AudioFormat encoding passed to isEncodingLinearPcm.
    private fun getChannelMasksFromPcmAudioProfiles(
        audioDeviceInfo: AudioDeviceInfo
    ): List<Int> {
        val audioProfiles = audioDeviceInfo.audioProfiles
        val channelMasks =
            TreeSet(Comparator.comparing(Function<Int, Int>{ i->
                i.countOneBits()
            }).reversed())
        for (audioProfile in audioProfiles) {
            if (audioProfile.encapsulationType == AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937) {
                continue
            }
            if (isEncodingLinearPcm(audioProfile.format)) {
                val masks = audioProfile.channelMasks
                for (mask in masks) {
                    channelMasks.add(mask)
                }
            }
        }
        return channelMasks.toList()
    }
}
