package androidx.media3.exoplayer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Pair
import android.util.SparseArray
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioManagerCompat.getAudioManager
import androidx.media3.common.hasElement
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.getAudioTrackChannelConfig
import androidx.media3.common.util.Util.isAutomotive
import androidx.media3.common.util.Util.isEncodingLinearPcm
import androidx.media3.common.util.Util.isTv
import com.google.common.primitives.Ints

data class AudioCapabilities private constructor(
    val encodingToAudioProfile: SparseArray<AudioProfile>,
    val speakerLayoutChannelMasks: List<Int>,
    val spatializerChannelMasks: List<Int>,
    val maxChannelCount: Int
) {
    private constructor(
        audioProfiles: List<AudioProfile>,
        speakerLayoutChannelMasks: List<Int>,
        spatializerChannelMasks: List<Int>,
    ) : this(
        SparseArray<AudioProfile>().apply {
            audioProfiles.forEach {
                put(it.encoding, it)
            }
        },
        speakerLayoutChannelMasks.toList(),
        spatializerChannelMasks.toList(),
        audioProfiles.maxBy { it.maxChannelCount }.maxChannelCount
    )

    fun supportsEncoding(encoding: @C.Encoding Int): Boolean {
        return encodingToAudioProfile.contains(encoding)
    }

    fun isPassthroughPlaybackSupported(format: Format, audioAttributes: AudioAttributes): Boolean {
        return getEncodingAndChannelConfigForPassthrough(format, audioAttributes) != null
    }

    fun getEncodingAndChannelConfigForPassthrough(format: Format, audioAttributes: AudioAttributes)
            : Pair<Int, Int>? {
        var encoding: @C.Encoding Int =
            MimeTypes.getEncoding(format.sampleMimeType!!, format.codecs)
        // Check that this is an encoding known to work for passthrough. This avoids trying to use
        // passthrough with an encoding where the device/app reports it's capable but it is untested or
        // known to be broken (for example AAC-LC).
        if (!ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.containsKey(encoding)) {
            return null
        }

        if (encoding == C.ENCODING_E_AC3_JOC && !supportsEncoding(C.ENCODING_E_AC3_JOC)) {
            // E-AC3 receivers support E-AC3 JOC streams (but decode only the base layer).
            encoding = C.ENCODING_E_AC3
        } else if ((encoding == C.ENCODING_DTS_HD && !supportsEncoding(C.ENCODING_DTS_HD))
            || (encoding == C.ENCODING_DTS_UHD_P2 && !supportsEncoding(C.ENCODING_DTS_UHD_P2))
        ) {
            // DTS receivers support DTS-HD streams (but decode only the core layer).
            encoding = C.ENCODING_DTS
        }
        if (!supportsEncoding(encoding)) {
            return null
        }

        val audioProfile = encodingToAudioProfile.get(encoding)!!
        val channelCount: Int
        if (format.channelCount == Format.NO_VALUE || encoding == C.ENCODING_E_AC3_JOC) {
            // In HLS chunkless preparation, the format channel count and sample rate may be unset. See
            // https://github.com/google/ExoPlayer/issues/10204 and b/222127949 for more details.
            // For E-AC3 JOC, the format is object based so the format channel count is arbitrary.
            val sampleRate =
                if (format.sampleRate != Format.NO_VALUE) format.sampleRate else DEFAULT_SAMPLE_RATE_HZ
            channelCount =
                audioProfile.getMaxSupportedChannelCountForPassthrough(sampleRate, audioAttributes)
        } else {
            channelCount = format.channelCount
            if (format.sampleMimeType == MimeTypes.AUDIO_DTS_UHD_P2 && Build.VERSION.SDK_INT < 33) {
                // Some DTS:X TVs reports ACTION_HDMI_AUDIO_PLUG.EXTRA_MAX_CHANNEL_COUNT as 8
                // instead of 10. See https://github.com/androidx/media/issues/396
                if (channelCount > 10) {
                    return null
                }
            } else if (!audioProfile.supportsChannelConfig(channelCount, format)) {
                return null
            }
        }
        val channelConfig: Int = getChannelConfigForPassthrough(channelCount, format)
        if (channelConfig == AudioFormat.CHANNEL_INVALID) {
            return null
        }
        return Pair.create(encoding, channelConfig)
    }

    data class AudioProfile(
        val encoding: @C.Encoding Int,
        val maxChannelCount: Int,
        private val channelMasks: Set<Int>? = null
    ) {
        @RequiresApi(33)
        constructor(encoding: @C.Encoding Int, channelMasks: Set<Int>) :
                this(
                    encoding,
                    channelMasks.maxBy { it.countOneBits() },
                    channelMasks.toSet()
                )

        fun supportsChannelConfig(channelCount: Int, format: Format): Boolean {
            if (channelMasks == null) {
                return channelCount <= maxChannelCount
            }
            val channelMask =
                if (format.channelMask != Format.NO_VALUE && format.channelCount == channelCount)
                    format.channelMask
                else
                    getAudioTrackChannelConfig(channelCount)
            if (channelMask == AudioFormat.CHANNEL_INVALID) {
                return false
            }
            return channelMasks.contains(channelMask)
        }

        fun getMaxSupportedChannelCountForPassthrough(
            sampleRate: Int, audioAttributes: AudioAttributes
        ): Int {
            if (channelMasks != null) {
                // We built the AudioProfile on API 33.
                return maxChannelCount
            } else if (Build.VERSION.SDK_INT >= 29) {
                return Api29.getMaxSupportedChannelCountForPassthrough(
                    encoding, sampleRate, audioAttributes
                )
            }
            return ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.getOrDefault(
                encoding,
                0
            )
        }

        companion object {
            val DEFAULT_AUDIO_PROFILE = if (Build.VERSION.SDK_INT >= 33)
                AudioProfile(
                    C.ENCODING_PCM_16BIT,
                    getAllChannelMasksForMaxChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                )
            else
                AudioProfile(C.ENCODING_PCM_16BIT, DEFAULT_MAX_CHANNEL_COUNT)

            private fun getAllChannelMasksForMaxChannelCount(maxChannelCount: Int): Set<Int> {
                val allChannelMasks = mutableSetOf<Int>()
                for (i in 1..maxChannelCount) {
                    allChannelMasks.add(getAudioTrackChannelConfig(i))
                }
                return allChannelMasks.toSet()
            }
        }
    }

    @RequiresApi(29)
    private object Api29 {
        fun getDirectPlaybackSupportedEncodings(audioAttributes: AudioAttributes): List<Int> {
            val list = mutableListOf<Int>()
            for (encoding in ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.keys) {
                if (Build.VERSION.SDK_INT <
                    Util.getApiLevelThatAudioFormatIntroducedAudioEncoding(encoding)
                ) {
                    // Example: AudioFormat.ENCODING_DTS_UHD_P2 is supported only from API 34.
                    continue
                }
                if (AudioTrack.isDirectPlaybackSupported(
                        AudioFormat.Builder()
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(encoding)
                            .setSampleRate(DEFAULT_SAMPLE_RATE_HZ)
                            .build(),
                        audioAttributes.platformAudioAttributes!!
                    )
                ) {
                    list.add(encoding)
                }
            }
            list.add(AudioFormat.ENCODING_PCM_16BIT)
            return list
        }

        fun getMaxSupportedChannelCountForPassthrough(
            encoding: @C.Encoding Int, sampleRate: Int, audioAttributes: AudioAttributes
        ): Int {
            for (channelCount in DEFAULT_MAX_CHANNEL_COUNT downTo 1) {
                val channelConfig = getAudioTrackChannelConfig(channelCount)
                if (channelConfig == AudioFormat.CHANNEL_INVALID) {
                    continue
                }
                val audioFormat =
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                if (AudioTrack.isDirectPlaybackSupported(
                        audioFormat, audioAttributes.platformAudioAttributes!!
                    )
                ) {
                    return channelCount
                }
            }
            return 0
        }
    }

    @RequiresApi(33)
    private object Api33 {
        fun getCapabilitiesInternalForDirectPlayback(
            audioManager: AudioManager,
            audioAttributes: AudioAttributes,
            speakerLayoutChannelMasks: List<Int>,
            spatializerChannelMasks: List<Int>
        ): AudioCapabilities {
            val directAudioProfiles =
                audioManager.getDirectProfilesForAttributes(audioAttributes.platformAudioAttributes!!)
            return AudioCapabilities(
                getAudioProfiles(directAudioProfiles),
                speakerLayoutChannelMasks,
                spatializerChannelMasks
            )
        }

        fun getDefaultRoutedDeviceForAttributes(
            audioManager: AudioManager?, audioAttributes: AudioAttributes
        ): AudioDeviceInfo? {
            val audioDevices =
                audioManager!!.getAudioDevicesForAttributes(audioAttributes.platformAudioAttributes!!)
            if (audioDevices.isEmpty()) {
                // Can't find current device.
                return null
            }
            // List only has more than one element if output devices are duplicated, so we assume the
            // first device in the list has all the information we need.
            return audioDevices[0]
        }
    }

    companion object {
        const val DEFAULT_MAX_CHANNEL_COUNT: Int = 10
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 48000
        private val DEFAULT_SPEAKER_LAYOUT_CHANNEL_MASKS = listOf(
            AudioFormat.CHANNEL_OUT_STEREO
        )
        private val DEFAULT_EMPTY_SPATIALIZER_CHANNEL_MASKS = listOf<Int>()

        val DEFAULT_AUDIO_CAPABILITIES: AudioCapabilities = AudioCapabilities(
            listOf(AudioProfile.DEFAULT_AUDIO_PROFILE),
            DEFAULT_SPEAKER_LAYOUT_CHANNEL_MASKS,
            DEFAULT_EMPTY_SPATIALIZER_CHANNEL_MASKS
        )

        private val EXTERNAL_SURROUND_SOUND_ENCODINGS: List<Int> = listOf(
            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3
        )

        val ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS = mutableMapOf<Int, Int>().apply {
            put(C.ENCODING_AC3, 6)
            put(C.ENCODING_AC4, 6)
            put(C.ENCODING_DTS, 6)
            put(C.ENCODING_DTS_UHD_P2, 10)
            put(C.ENCODING_E_AC3_JOC, 6)
            put(C.ENCODING_E_AC3, 8)
            put(C.ENCODING_DTS_HD, 8)
            put(C.ENCODING_DOLBY_TRUEHD, 8)
        }
        private const val EXTERNAL_SURROUND_SOUND_KEY = "external_surround_sound_enabled"
        private const val FORCE_EXTERNAL_SURROUND_SOUND_KEY = "use_external_surround_sound_flag"

        fun getCapabilities(
            context: Context,
            audioAttributes: AudioAttributes,
            routedDevice: AudioDeviceInfo?,
            spatializerChannelMasks: List<Int>
        ): AudioCapabilities {
            return getCapabilitiesInternal(
                context,
                audioAttributes,
                routedDevice,
                spatializerChannelMasks
            )
        }

        @SuppressLint("UnprotectedReceiver") // ACTION_HDMI_AUDIO_PLUG is protected since API 16
        fun getCapabilitiesInternal(
            context: Context,
            audioAttributes: AudioAttributes,
            routedDevice: AudioDeviceInfo?,
            spatializerChannelMasks: List<Int>
        ): AudioCapabilities {
            val intent =
                context.registerReceiver(
                    null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                )
            return getCapabilitiesInternal(
                context, intent, audioAttributes, routedDevice, spatializerChannelMasks
            )
        }

        fun getCapabilitiesInternal(
            context: Context,
            intent: Intent?,
            audioAttributes: AudioAttributes,
            routedDevice: AudioDeviceInfo?,
            spatializerChannelMasks: List<Int>
        ): AudioCapabilities {
            val audioManager = getAudioManager(context)
            val currentDevice =
                routedDevice ?: if (Build.VERSION.SDK_INT >= 33)
                    Api33.getDefaultRoutedDeviceForAttributes(audioManager, audioAttributes)
                else
                    null

            val speakerLayoutChannelMasks: List<Int> =
                if (currentDevice != null)
                    SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(currentDevice)
                else
                    DEFAULT_SPEAKER_LAYOUT_CHANNEL_MASKS

            if (Build.VERSION.SDK_INT >= 33 && (isTv(context) || isAutomotive(context))) {
                // TV or automotive devices generally shouldn't support audio offload for surround encodings,
                // so the encodings we get from AudioManager.getDirectProfilesForAttributes should include
                // the PCM encodings and surround encodings for passthrough mode.
                return Api33.getCapabilitiesInternalForDirectPlayback(
                    audioManager,
                    audioAttributes,
                    speakerLayoutChannelMasks,
                    spatializerChannelMasks
                )
            }

            // If a connection to Bluetooth device is detected, we only return the minimum capabilities that
            // is supported by all the devices.
            if (isBluetoothConnected(audioManager, currentDevice)) {
                return AudioCapabilities(
                    listOf(AudioProfile.DEFAULT_AUDIO_PROFILE),
                    speakerLayoutChannelMasks,
                    spatializerChannelMasks
                )
            }

            val supportedEncodings = mutableSetOf<Int>()
            supportedEncodings.add(C.ENCODING_PCM_16BIT)

            // AudioTrack.isDirectPlaybackSupported returns true for encodings that are supported for audio
            // offload, as well as for encodings we want to list for passthrough mode. Therefore we only use
            // it on TV and automotive devices, which generally shouldn't support audio offload for surround
            // encodings.
            if (Build.VERSION.SDK_INT >= 29 && (isTv(context) || isAutomotive(context))) {
                supportedEncodings.addAll(Api29.getDirectPlaybackSupportedEncodings(audioAttributes))
                return AudioCapabilities(
                    getAudioProfiles(
                        Ints.toArray(supportedEncodings),
                        DEFAULT_MAX_CHANNEL_COUNT
                    ),
                    speakerLayoutChannelMasks,
                    spatializerChannelMasks
                )
            }

            val contentResolver = context.contentResolver
            val forceExternalSurroundSoundSetting =
                Settings.Global.getInt(contentResolver, FORCE_EXTERNAL_SURROUND_SOUND_KEY, 0) == 1
            if ((forceExternalSurroundSoundSetting || deviceMaySetExternalSurroundSoundGlobalSetting())
                && Settings.Global.getInt(contentResolver, EXTERNAL_SURROUND_SOUND_KEY, 0) == 1
            ) {
                supportedEncodings.addAll(EXTERNAL_SURROUND_SOUND_ENCODINGS)
            }

            if (intent != null && !forceExternalSurroundSoundSetting && intent.getIntExtra(
                    AudioManager.EXTRA_AUDIO_PLUG_STATE, 0
                ) == 1
            ) {
                val encodingsFromExtra = intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)
                if (encodingsFromExtra != null) {
                    supportedEncodings.addAll(Ints.asList(*encodingsFromExtra))
                }
                return AudioCapabilities(
                    getAudioProfiles(
                        Ints.toArray(supportedEncodings),
                        intent.getIntExtra(
                            AudioManager.EXTRA_MAX_CHANNEL_COUNT,  
                            DEFAULT_MAX_CHANNEL_COUNT
                        )
                    ),
                    speakerLayoutChannelMasks,
                    spatializerChannelMasks
                )
            }

            return AudioCapabilities(
                getAudioProfiles(
                    Ints.toArray(supportedEncodings),
                    DEFAULT_MAX_CHANNEL_COUNT
                ),
                speakerLayoutChannelMasks,
                spatializerChannelMasks
            )
        }

        val externalSurroundSoundGlobalSettingUri: Uri?
            get() = if (deviceMaySetExternalSurroundSoundGlobalSetting())
                Settings.Global.getUriFor(EXTERNAL_SURROUND_SOUND_KEY)
            else
                null

        private fun deviceMaySetExternalSurroundSoundGlobalSetting(): Boolean {
            return Build.MANUFACTURER == "Amazon" || Build.MANUFACTURER == "Xiaomi"
        }

        private fun getChannelConfigForPassthrough(channelCount: Int, format: Format): Int {
            var channelCount = channelCount
            if (Build.VERSION.SDK_INT <= 28) {
                // In passthrough mode the channel count used to configure the audio track doesn't affect how
                // the stream is handled, except that some devices do overly-strict channel configuration
                // checks. Therefore we override the channel count so that a known-working channel
                // configuration is chosen in all cases. See [Internal: b/29116190].
                if (channelCount == 7) {
                    channelCount = 8
                } else if (channelCount == 3 || channelCount == 4 || channelCount == 5) {
                    channelCount = 6
                }
            }

            // Workaround for Nexus Player not reporting support for mono passthrough.
            if (enableWorkarounds()
                && Build.VERSION.SDK_INT <= 26 && "fugu" == Build.DEVICE
                && channelCount == 1
            ) {
                channelCount = 2
            }
            return if (format.channelMask != Format.NO_VALUE && format.channelCount == channelCount)
                format.channelMask
            else
                getAudioTrackChannelConfig(channelCount)
        }

        @RequiresApi(33)
        private fun getAudioProfiles(audioProfiles: List<android.media.AudioProfile>)
                : List<AudioProfile> {
            val formatToChannelMasks = mutableMapOf<Int, MutableSet<Int>>()
            // Enforce the support of stereo 16bit-PCM.
            formatToChannelMasks[C.ENCODING_PCM_16BIT] =
                mutableSetOf(AudioFormat.CHANNEL_OUT_STEREO)
            for (i in audioProfiles.indices) {
                val audioProfile = audioProfiles[i]
                if ((audioProfile.encapsulationType
                            == android.media.AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937)
                ) {
                    // Skip the IEC61937 encapsulation because we don't support it yet.
                    continue
                }
                val encoding = audioProfile.format
                if (!isEncodingLinearPcm(encoding)
                    && !ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.containsKey(encoding)
                ) {
                    continue
                }
                if (formatToChannelMasks[encoding]?.addAll(audioProfile.channelMasks.toSet()) != true) {
                    formatToChannelMasks[encoding] = audioProfile.channelMasks.toMutableSet()
                }
            }
            val localAudioProfiles = formatToChannelMasks.entries.map {
                AudioProfile(it.key, it.value)
            }
            return localAudioProfiles
        }

        private fun getAudioProfiles(supportedEncodings: IntArray, maxChannelCount: Int)
                : List<AudioProfile> {
            return supportedEncodings.map {
                AudioProfile(it, maxChannelCount)
            }
        }

        private fun isBluetoothConnected(
            audioManager: AudioManager, currentDevice: AudioDeviceInfo?
        ): Boolean {
            // Check the current device if known or all devices otherwise.
            currentDevice?.also { return DeviceTypeUtil.isBluetoothDevice(it.type) }
            val audioDeviceInfos = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return audioDeviceInfos.hasElement { DeviceTypeUtil.isBluetoothDevice(it.type) }
        }
    }
}
