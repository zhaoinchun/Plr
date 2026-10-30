package androidx.media3.extractor.ts

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import com.google.common.base.Preconditions
import com.google.common.math.IntMath
import com.google.common.math.LongMath
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Double
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.Throws
import kotlin.arrayOf
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

internal object MpeghUtil {
    private const val MHAS_SYNC_WORD = 0xC001A5

    fun isSyncWord(word: Int): Boolean {
        return (word and 0xFFFFFF) == MHAS_SYNC_WORD
    }

    @Throws(ParserException::class)
    fun parseMhasPacketHeader(data: ParsableBitArray, header: MhasPacketHeader): Boolean {
        val dataStartPos = data.bytePosition
        header.packetType = readEscapedIntValue(data, 3, 8, 8)
        if (header.packetType == -1) {
            return false
        }

        header.packetLabel = readEscapedLongValue(data, 2, 8, 32)
        if (header.packetLabel == -1L) {
            return false
        }

        if (header.packetLabel > 0x10) {
            throw ParserException.createForUnsupportedContainerFeature(
                "Contains sub-stream with an invalid packet label " + header.packetLabel
            )
        }

        if (header.packetLabel == 0L) {
            when (header.packetType) {
                MhasPacketHeader.PACTYP_MPEGH3DACFG -> throw ParserException.createForMalformedContainer(
                    "Mpegh3daConfig packet with invalid packet label 0",  null
                )

                MhasPacketHeader.PACTYP_AUDIOTRUNCATION -> throw ParserException.createForMalformedContainer(
                    "AudioTruncation packet with invalid packet label 0",  null
                )

                MhasPacketHeader.PACTYP_MPEGH3DAFRAME -> throw ParserException.createForMalformedContainer(
                    "Mpegh3daFrame packet with invalid packet label 0",  null
                )

                else -> {}
            }
        }

        header.packetLength = readEscapedIntValue(data, 11, 24, 24)
        return header.packetLength != -1
    }

    @Throws(ParserException::class)
    private fun getOutputFrameLength(index: Int): Int {
        when (index) {
            0 -> return 768
            1 -> return 1024
            2, 3 -> return 2048
            4 -> return 4096
            else -> throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported coreSbrFrameLengthIndex " + index
            )
        }
    }

    @Throws(ParserException::class)
    private fun getSbrRatioIndex(index: Int): Int {
        when (index) {
            0, 1 -> return 0
            2 -> return 2
            3 -> return 3
            4 -> return 1
            else -> throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported coreSbrFrameLengthIndex " + index
            )
        }
    }

    @Throws(ParserException::class)
    private fun getResamplingRatio(usacSamplingFrequency: Int): Double {
        when (usacSamplingFrequency) {
            96000, 88200, 48000, 44100 -> return 1.0
            64000, 58800, 32000, 29400 -> return 1.5
            24000, 22050 -> return 2.0
            16000, 14700 -> return 3.0
            else -> throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported sampling rate " + usacSamplingFrequency
            )
        }
    }

    @Throws(ParserException::class)
    fun parseMpegh3daConfig(data: ParsableBitArray): Mpegh3daConfig {
        var compatibleProfileLevelSet: ByteArray? = null
        val profileLevelIndication = data.readBits(8)

        val usacSamplingFrequency: Int
        val samplingFrequencyIndex = data.readBits(5)
        if (samplingFrequencyIndex == 0x1F) {
            usacSamplingFrequency = data.readBits(24)
        } else {
            usacSamplingFrequency = getSamplingFrequency(samplingFrequencyIndex)
        }

        val coreSbrFrameLengthIndex = data.readBits(3)
        val outputFrameLength = getOutputFrameLength(coreSbrFrameLengthIndex)
        val sbrRatioIndex = getSbrRatioIndex(coreSbrFrameLengthIndex)

        data.skipBits(2) // cfg_reserved(1), receiverDelayCompensation(1)

        skipSpeakerConfig3d(data) // referenceLayout
        val numSignals = parseSignals3d(data) // frameworkConfig3d
        skipMpegh3daDecoderConfig(data, numSignals, sbrRatioIndex) // decoderConfig

        if (data.readBit()) { // usacConfigExtensionPresent
            // Mpegh3daConfigExtension
            val numConfigExtensions = readEscapedIntValue(data, 2, 4, 8) + 1
            for (confExtIdx in 0..<numConfigExtensions) {
                val usacConfigExtType = readEscapedIntValue(data, 4, 8, 16)
                val usacConfigExtLength = readEscapedIntValue(data, 4, 8, 16)

                if (usacConfigExtType == 7 ) {
                    val numCompatibleSets = data.readBits(4) + 1
                    data.skipBits(4) // reserved
                    compatibleProfileLevelSet = ByteArray(numCompatibleSets)
                    for (idx in 0..<numCompatibleSets) {
                        compatibleProfileLevelSet[idx] = data.readBits(8).toByte()
                    }
                } else {
                    data.skipBits(C.BITS_PER_BYTE * usacConfigExtLength)
                }
            }
        }

        // Get the resampling ratio and adjust the samplingFrequency and the standardFrameSamples
        // accordingly.
        val resamplingRatio = getResamplingRatio(usacSamplingFrequency)
        val samplingFrequency = (usacSamplingFrequency * resamplingRatio).toInt()
        val standardFrameLength = (outputFrameLength * resamplingRatio).toInt()

        return Mpegh3daConfig(
            profileLevelIndication,
            samplingFrequency,
            standardFrameLength,
            compatibleProfileLevelSet
        )
    }

    @Throws(ParserException::class)
    private fun getSamplingFrequency(index: Int): Int {
        when (index) {
            0 -> return 96000
            1 -> return 88200
            2 -> return 64000
            3 -> return 48000
            4 -> return 44100
            5 -> return 32000
            6 -> return 24000
            7 -> return 22050
            8 -> return 16000
            9 -> return 12000
            10 -> return 11025
            11 -> return 8000
            12 -> return 7350
            15 -> return 57600
            16 -> return 51200
            17 -> return 40000
            18 -> return 38400
            19 -> return 34150
            20 -> return 28800
            21 -> return 25600
            22 -> return 20000
            23 -> return 19200
            24 -> return 17075
            25 -> return 14400
            26 -> return 12800
            27 -> return 9600
            else -> throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported sampling rate index " + index
            )
        }
    }

    fun parseAudioTruncationInfo(data: ParsableBitArray): Int {
        if (data.readBit()) { // isActive
            data.skipBits(2) // reserved(1), truncFromBegin(1)
            return data.readBits(13)
        }
        return 0
    }

    private fun skipSpeakerConfig3d(data: ParsableBitArray) {
        val speakerLayoutType = data.readBits(2)
        if (speakerLayoutType == 0) {
            data.skipBits(6) // cicpSpeakerLayoutIdx
            return
        }

        val numberOfSpeakers = readEscapedIntValue(data, 5, 8, 16) + 1
        if (speakerLayoutType == 1) {
            data.skipBits(7 * numberOfSpeakers) // cicpSpeakerIdx per speaker
        } else if (speakerLayoutType == 2) {
            skipMpegh3daFlexibleSpeakerConfig(data, numberOfSpeakers)
        }
    }

    private fun skipMpegh3daFlexibleSpeakerConfig(
        data: ParsableBitArray, numberOfSpeakers: Int
    ) {
        val angularPrecision = data.readBit()
        val angularPrecisionDegrees = if (angularPrecision) 1 else 5
        val elevationAngleBits = if (angularPrecision) 7 else 5
        val azimuthAngleBits = if (angularPrecision) 8 else 6

        // Mpegh3daSpeakerDescription array
        var i = 0
        while (i < numberOfSpeakers) {
            var azimuthAngle = 0
            if (data.readBit()) { // isCICPspeakerIdx
                data.skipBits(7) // cicpSpeakerIdx
            } else {
                val elevationClass = data.readBits(2)
                if (elevationClass == 3) {
                    val elevationAngleIdx = data.readBits(elevationAngleBits)
                    val elevationAngle = elevationAngleIdx * angularPrecisionDegrees
                    if (elevationAngle != 0) {
                        data.skipBit() // elevationDirection
                    }
                }
                val azimuthAngleIdx = data.readBits(azimuthAngleBits)
                azimuthAngle = azimuthAngleIdx * angularPrecisionDegrees
                if ((azimuthAngle != 0) && (azimuthAngle != 180)) {
                    data.skipBit() // azimuthDirection
                }
                data.skipBit() // isLFE
            }

            if ((azimuthAngle != 0) && (azimuthAngle != 180)) {
                if (data.readBit()) { // alsoAddSymmetricPair
                    i++
                }
            }
            i++
        }
    }

    private fun parseSignals3d(data: ParsableBitArray): Int {
        var numberOfSignals = 0
        val numberOfSignalGroupsInBitstream = data.readBits(5)

        for (grp in 0..<numberOfSignalGroupsInBitstream + 1) {
            val signalGroupType = data.readBits(3)
            val bsNumberOfSignals = readEscapedIntValue(data, 5, 8, 16)

            numberOfSignals += bsNumberOfSignals + 1
            if (signalGroupType == 0 
                || signalGroupType == 2 ) {
                if (data.readBit()) { // differsFromReferenceLayout OR saocDmxLayoutPresent
                    skipSpeakerConfig3d(data) // audioChannelLayout[grp] OR saocDmxChannelLayout
                }
            }
        }
        return numberOfSignals
    }

    private fun skipMpegh3daDecoderConfig(
        data: ParsableBitArray, numSignals: Int, sbrRatioIndex: Int
    ) {
        val numElements = readEscapedIntValue(data, 4, 8, 16) + 1
        data.skipBit() // elementLengthPresent

        for (elemIdx in 0..<numElements) {
            val usacElementType = data.readBits(2)

            when (usacElementType) {
                0 -> {
                    parseMpegh3daCoreConfig(data) // coreConfig
                    if (sbrRatioIndex > 0) {
                        skipSbrConfig(data) // sbrConfig
                    }
                }

                1 -> {
                    val enhancedNoiseFilling = parseMpegh3daCoreConfig(data) // coreConfig
                    if (enhancedNoiseFilling) {
                        data.skipBit() // igfIndependentTiling
                    }
                    var stereoConfigIndex = 0
                    if (sbrRatioIndex > 0) {
                        skipSbrConfig(data) // sbrConfig
                        stereoConfigIndex = data.readBits(2)
                    }
                    if (stereoConfigIndex > 0) {
                        // mps212Config
                        data.skipBits(6) // bsFreqRes(3), bsFixedGainDMX(3),
                        val bsTempShapeConfig = data.readBits(2)
                        data.skipBits(4) // bsDecorrConfig(2), bsHighRateMode(1), bsPhaseCoding(1)
                        if (data.readBit()) { // bsOttBandsPhasePresent
                            data.skipBits(5) // bsOttBandsPhase
                        }
                        if (stereoConfigIndex == 2 || stereoConfigIndex == 3) {
                            data.skipBits(6) // bsResidualBands(5), bsPseudoLr(1)
                        }
                        if (bsTempShapeConfig == 2) {
                            data.skipBit() // bsEnvQuantMode
                        }
                    }

                    val nBits = floor(ln((numSignals - 1).toDouble()) / ln(2.0)).toInt() + 1
                    val qceIndex = data.readBits(2)
                    if (qceIndex > 0) {
                        if (data.readBit()) { // shiftIndex0
                            data.skipBits(nBits) // shiftChannel0
                        }
                    }
                    if (data.readBit()) { // shiftIndex1
                        data.skipBits(nBits) // shiftChannel1
                    }
                    if (sbrRatioIndex == 0 && qceIndex == 0) {
                        data.skipBit() // lpdStereoIndex
                    }
                }

                3 -> {
                    readEscapedIntValue(data, 4, 8, 16) // usacExtElementType
                    val usacExtElementConfigLength = readEscapedIntValue(data, 4, 8, 16)

                    if (data.readBit()) { // usacExtElementDefaultLengthPresent
                        readEscapedIntValue(data, 8, 16, 0)  // usacExtElementDefaultLength
                    }
                    data.skipBit() // usacExtElementPayloadFrag

                    if (usacExtElementConfigLength > 0) {
                        data.skipBits(8 * usacExtElementConfigLength)
                    }
                }

                else -> {}
            }
        }
    }

    private fun parseMpegh3daCoreConfig(data: ParsableBitArray): Boolean {
        data.skipBits(3) // tw_mdct(1), fullbandLpd(1), noiseFilling(1)
        val enhancedNoiseFilling = data.readBit()
        if (enhancedNoiseFilling) {
            // igfUseEnf(1), igfUseHighRes(1), igfUseWhitening(1), igfAfterTnsSynth(1), igfStartIndex(5),
            // igfStopIndex(4)
            data.skipBits(13)
        }
        return enhancedNoiseFilling
    }

    private fun skipSbrConfig(data: ParsableBitArray) {
        data.skipBits(3) // harmonicSBR(1), bs_interTes(1), bs_pvc(1)
        data.skipBits(8) // dflt_start_freq(4), dflt_stop_freq(4)
        val dfltHeaderExtra1 = data.readBit()
        val dfltHeaderExtra2 = data.readBit()
        if (dfltHeaderExtra1) {
            data.skipBits(5) // dflt_freq_scale(2), dflt_alter_scale(1), dflt_noise_bands(2)
        }
        if (dfltHeaderExtra2) {
            // dflt_limiter_bands(2), dflt_limiter_gains(2), dflt_interpol_freq(1), dflt_smoothing_mode(1)
            data.skipBits(6)
        }
    }

    private fun readEscapedIntValue(
        data: ParsableBitArray,
        bits1: Int,
        bits2: Int,
        bits3: Int
    ): Int {
        // Ensure that the calculated value will fit within the range of a Java {@code int}.
        val maxBitCount = max(max(bits1, bits2), bits3)
        Preconditions.checkArgument(maxBitCount <= Integer.SIZE - 1)
        // Result is intentionally unused, checking if the operation causes overflow
        val unused =
            IntMath.checkedAdd(
                IntMath.checkedAdd((1 shl bits1) - 1, (1 shl bits2) - 1),
                (1 shl bits3)
            )

        if (data.bitsLeft() < bits1) {
            return -1
        }

        var value = data.readBits(bits1)
        if (value == (1 shl bits1) - 1) {
            if (data.bitsLeft() < bits2) {
                return -1
            }
            var valueAdd = data.readBits(bits2)
            value += valueAdd

            if (valueAdd == (1 shl bits2) - 1) {
                if (data.bitsLeft() < bits3) {
                    return -1
                }
                valueAdd = data.readBits(bits3)
                value += valueAdd
            }
        }
        return value
    }

    private fun readEscapedLongValue(
        data: ParsableBitArray,
        bits1: Int,
        bits2: Int,
        bits3: Int
    ): Long {
        // Ensure that the calculated value will fit within the range of a Java {@code long}.
        val maxBitCount = max(max(bits1, bits2), bits3)
        Preconditions.checkArgument(maxBitCount <= 64 - 1)
        // Result is intentionally unused, checking if the operation causes overflow
        val unused =
            LongMath.checkedAdd(
                LongMath.checkedAdd((1L shl bits1) - 1, (1L shl bits2) - 1), (1L shl bits3)
            )

        if (data.bitsLeft() < bits1) {
            return -1L
        }

        var value = data.readBitsToLong(bits1)
        if (value == (1L shl bits1) - 1) {
            if (data.bitsLeft() < bits2) {
                return -1
            }

            var valueAdd = data.readBitsToLong(bits2)
            value += valueAdd

            if (valueAdd == (1L shl bits2) - 1) {
                if (data.bitsLeft() < bits3) {
                    return -1
                }

                valueAdd = data.readBitsToLong(bits3)
                value += valueAdd
            }
        }
        return value
    }

    class MhasPacketHeader {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [PACTYP_FILLDATA, PACTYP_MPEGH3DACFG, PACTYP_MPEGH3DAFRAME, PACTYP_AUDIOSCENEINFO, PACTYP_SYNC, PACTYP_SYNCGAP, PACTYP_MARKER, PACTYP_CRC16, PACTYP_CRC32, PACTYP_DESCRIPTOR, PACTYP_USERINTERACTION, PACTYP_LOUDNESS_DRC, PACTYP_BUFFERINFO, PACTYP_GLOBAL_CRC16, PACTYP_GLOBAL_CRC32, PACTYP_AUDIOTRUNCATION, PACTYP_GENDATA, PACTYPE_EARCON, PACTYPE_PCMCONFIG, PACTYPE_PCMDATA, PACTYP_LOUDNESS
            ]
        )
        annotation class Type

        var packetType: @Type Int = 0

        var packetLabel: kotlin.Long = 0

        var packetLength: Int = 0

        companion object {
            const val PACTYP_FILLDATA: Int = 0
            const val PACTYP_MPEGH3DACFG: Int = 1
            const val PACTYP_MPEGH3DAFRAME: Int = 2
            const val PACTYP_AUDIOSCENEINFO: Int = 3
            const val PACTYP_SYNC: Int = 6
            const val PACTYP_SYNCGAP: Int = 7
            const val PACTYP_MARKER: Int = 8
            const val PACTYP_CRC16: Int = 9
            const val PACTYP_CRC32: Int = 10
            const val PACTYP_DESCRIPTOR: Int = 11
            const val PACTYP_USERINTERACTION: Int = 12
            const val PACTYP_LOUDNESS_DRC: Int = 13
            const val PACTYP_BUFFERINFO: Int = 14
            const val PACTYP_GLOBAL_CRC16: Int = 15
            const val PACTYP_GLOBAL_CRC32: Int = 16
            const val PACTYP_AUDIOTRUNCATION: Int = 17
            const val PACTYP_GENDATA: Int = 18
            const val PACTYPE_EARCON: Int = 19
            const val PACTYPE_PCMCONFIG: Int = 20
            const val PACTYPE_PCMDATA: Int = 21
            const val PACTYP_LOUDNESS: Int = 22
        }
    }

    class Mpegh3daConfig  constructor(
        
        val profileLevelIndication: Int,
        
        val samplingFrequency: Int,
        
        val standardFrameLength: Int,
        
        val compatibleProfileLevelSet: ByteArray?
    )
}
