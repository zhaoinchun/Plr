package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray

object AacUtil {
    private const val TAG = "AacUtil"
    const val AAC_LC_AUDIO_SAMPLE_COUNT: Int = 1024
    val AAC_XHE_AUDIO_SAMPLE_COUNT: Int = AAC_LC_AUDIO_SAMPLE_COUNT
    const val AAC_HE_AUDIO_SAMPLE_COUNT: Int = 2048
    const val AAC_LD_AUDIO_SAMPLE_COUNT: Int = 512
    const val AAC_LC_MAX_RATE_BYTES_PER_SECOND: Int = 800 * 1000 / 8
    const val AAC_HE_V1_MAX_RATE_BYTES_PER_SECOND: Int = 128 * 1000 / 8
    const val AAC_HE_V2_MAX_RATE_BYTES_PER_SECOND: Int = 56 * 1000 / 8
    const val AAC_XHE_MAX_RATE_BYTES_PER_SECOND: Int = 2048 * 1000 / 8
    const val AAC_ELD_MAX_RATE_BYTES_PER_SECOND: Int = 64 * 1000 / 8

    private const val AUDIO_SPECIFIC_CONFIG_FREQUENCY_INDEX_ARBITRARY = 0xF
    private val AUDIO_SPECIFIC_CONFIG_SAMPLING_RATE_TABLE = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )
    private val AUDIO_SPECIFIC_CONFIG_CHANNEL_CONFIGURATION_INVALID = -1

    private val AUDIO_SPECIFIC_CONFIG_CHANNEL_COUNT_TABLE = intArrayOf(
        0,
        1,  
        2,  
        3,  
        4,  
        5,  
        6,  
        8,  
        AUDIO_SPECIFIC_CONFIG_CHANNEL_CONFIGURATION_INVALID,
        AUDIO_SPECIFIC_CONFIG_CHANNEL_CONFIGURATION_INVALID,
        AUDIO_SPECIFIC_CONFIG_CHANNEL_CONFIGURATION_INVALID,
        7,  
        8,  
        AUDIO_SPECIFIC_CONFIG_CHANNEL_CONFIGURATION_INVALID,
        8,  
        AUDIO_SPECIFIC_CONFIG_CHANNEL_CONFIGURATION_INVALID
    )

    private const val CODECS_STRING_PREFIX = "mp4a.40."

    // Advanced Audio Coding Low-Complexity profile.
    const val AUDIO_OBJECT_TYPE_AAC_LC: Int = 2

    // Spectral Band Replication.
    const val AUDIO_OBJECT_TYPE_AAC_SBR: Int = 5

    // Error Resilient Bit-Sliced Arithmetic Coding.
    const val AUDIO_OBJECT_TYPE_AAC_ER_BSAC: Int = 22

    // Enhanced low delay.
    const val AUDIO_OBJECT_TYPE_AAC_ELD: Int = 23

    // Parametric Stereo.
    const val AUDIO_OBJECT_TYPE_AAC_PS: Int = 29

    // Escape code for extended audio object types.
    private const val AUDIO_OBJECT_TYPE_ESCAPE = 31

    // Extended high efficiency.
    const val AUDIO_OBJECT_TYPE_AAC_XHE: Int = 42

    @Throws(ParserException::class)
    fun parseAudioSpecificConfig(audioSpecificConfig: ByteArray): Config {
        return parseAudioSpecificConfig(
            ParsableBitArray(audioSpecificConfig),  false
        )
    }

    @Throws(ParserException::class)
    fun parseAudioSpecificConfig(bitArray: ParsableBitArray, forceReadToEnd: Boolean): Config {
        var audioObjectType = getAudioObjectType(bitArray)
        var sampleRateHz = getSamplingFrequency(bitArray)
        var channelConfiguration = bitArray.readBits(4)
        val codecs = CODECS_STRING_PREFIX + audioObjectType
        if (audioObjectType == AUDIO_OBJECT_TYPE_AAC_SBR
            || audioObjectType == AUDIO_OBJECT_TYPE_AAC_PS
        ) {
            // For an AAC bitstream using spectral band replication (SBR) or parametric stereo (PS) with
            // explicit signaling, we return the extension sampling frequency as the sample rate of the
            // content; this is identical to the sample rate of the decoded output but may differ from
            // the sample rate set above.
            // Use the extensionSamplingFrequencyIndex.
            sampleRateHz = getSamplingFrequency(bitArray)
            audioObjectType = getAudioObjectType(bitArray)
            if (audioObjectType == AUDIO_OBJECT_TYPE_AAC_ER_BSAC) {
                // Use the extensionChannelConfiguration.
                channelConfiguration = bitArray.readBits(4)
            }
        }

        if (forceReadToEnd) {
            when (audioObjectType) {
                1, 2, 3, 4, 6, 7, 17, 19, 20, 21, 22, 23 -> parseGaSpecificConfig(
                    bitArray,
                    audioObjectType,
                    channelConfiguration
                )

                else -> throw ParserException.createForUnsupportedContainerFeature(
                    "Unsupported audio object type: " + audioObjectType
                )
            }
            when (audioObjectType) {
                17, 19, 20, 21, 22, 23 -> {
                    val epConfig = bitArray.readBits(2)
                    if (epConfig == 2 || epConfig == 3) {
                        throw ParserException.createForUnsupportedContainerFeature(
                            "Unsupported epConfig: " + epConfig
                        )
                    }
                }

                else -> {}
            }
        }
        // For supported containers, bits_to_decode() is always 0.
        val channelCount = AUDIO_SPECIFIC_CONFIG_CHANNEL_COUNT_TABLE[channelConfiguration]
        if (channelCount == AUDIO_SPECIFIC_CONFIG_CHANNEL_CONFIGURATION_INVALID) {
            throw ParserException.createForMalformedContainer( null,  
                null
            )
        }
        return AacUtil.Config(sampleRateHz, channelCount, codecs)
    }

    fun buildAacLcAudioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
        var sampleRateIndex = C.INDEX_UNSET
        for (i in AUDIO_SPECIFIC_CONFIG_SAMPLING_RATE_TABLE.indices) {
            if (sampleRate == AUDIO_SPECIFIC_CONFIG_SAMPLING_RATE_TABLE[i]) {
                sampleRateIndex = i
            }
        }
        var channelConfig = C.INDEX_UNSET
        for (i in AUDIO_SPECIFIC_CONFIG_CHANNEL_COUNT_TABLE.indices) {
            if (channelCount == AUDIO_SPECIFIC_CONFIG_CHANNEL_COUNT_TABLE[i]) {
                channelConfig = i
            }
        }
        require(!(sampleRate == C.INDEX_UNSET || channelConfig == C.INDEX_UNSET)) { "Invalid sample rate or number of channels: " + sampleRate + ", " + channelCount }
        return buildAudioSpecificConfig(AUDIO_OBJECT_TYPE_AAC_LC, sampleRateIndex, channelConfig)
    }

    fun buildAudioSpecificConfig(
        audioObjectType: Int, sampleRateIndex: Int, channelConfig: Int
    ): ByteArray {
        val specificConfig = ByteArray(2)
        specificConfig[0] =
            (((audioObjectType shl 3) and 0xF8) or ((sampleRateIndex shr 1) and 0x07)).toByte()
        specificConfig[1] =
            (((sampleRateIndex shl 7) and 0x80) or ((channelConfig shl 3) and 0x78)).toByte()
        return specificConfig
    }

    private fun getAudioObjectType(bitArray: ParsableBitArray): Int {
        var audioObjectType = bitArray.readBits(5)
        if (audioObjectType == AUDIO_OBJECT_TYPE_ESCAPE) {
            audioObjectType = 32 + bitArray.readBits(6)
        }
        return audioObjectType
    }

    @Throws(ParserException::class)
    private fun getSamplingFrequency(bitArray: ParsableBitArray): Int {
        val samplingFrequency: Int
        val frequencyIndex = bitArray.readBits(4)
        if (frequencyIndex == AUDIO_SPECIFIC_CONFIG_FREQUENCY_INDEX_ARBITRARY) {
            if (bitArray.bitsLeft() < 24) {
                throw ParserException.createForMalformedContainer( 
                    "AAC header insufficient data",  null
                )
            }
            samplingFrequency = bitArray.readBits(24)
        } else if (frequencyIndex < 13) {
            samplingFrequency = AUDIO_SPECIFIC_CONFIG_SAMPLING_RATE_TABLE[frequencyIndex]
        } else {
            throw ParserException.createForMalformedContainer( 
                "AAC header wrong Sampling Frequency Index",  null
            )
        }
        return samplingFrequency
    }

    private fun parseGaSpecificConfig(
        bitArray: ParsableBitArray, audioObjectType: Int, channelConfiguration: Int
    ) {
        val frameLengthFlag = bitArray.readBit()
        if (frameLengthFlag) {
            w(TAG, "Unexpected frameLengthFlag = 1")
        }
        val dependsOnCoreDecoder = bitArray.readBit()
        if (dependsOnCoreDecoder) {
            bitArray.skipBits(14) // coreCoderDelay.
        }
        val extensionFlag = bitArray.readBit()
        if (channelConfiguration == 0) {
            throw UnsupportedOperationException() // TODO: Implement programConfigElement();
        }
        if (audioObjectType == 6 || audioObjectType == 20) {
            bitArray.skipBits(3) // layerNr.
        }
        if (extensionFlag) {
            if (audioObjectType == 22) {
                bitArray.skipBits(16) // numOfSubFrame (5), layer_length(11).
            }
            if (audioObjectType == 17 || audioObjectType == 19 || audioObjectType == 20 || audioObjectType == 23) {
                // aacSectionDataResilienceFlag, aacScalefactorDataResilienceFlag,
                // aacSpectralDataResilienceFlag.
                bitArray.skipBits(3)
            }
            bitArray.skipBits(1) // extensionFlag3.
        }
    }

    class Config  constructor(
         val sampleRateHz: Int,
         val channelCount: Int,
         val codecs: String?
    )

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [AUDIO_OBJECT_TYPE_AAC_LC, AUDIO_OBJECT_TYPE_AAC_SBR, AUDIO_OBJECT_TYPE_AAC_ER_BSAC, AUDIO_OBJECT_TYPE_AAC_ELD, AUDIO_OBJECT_TYPE_AAC_PS, AUDIO_OBJECT_TYPE_AAC_XHE
        ]
    )
    annotation class AacAudioObjectType
}
