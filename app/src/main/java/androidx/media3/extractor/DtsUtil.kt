
package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.annotation.StringDef
import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.crc16
import androidx.media3.common.util.Util.scaleLargeTimestamp
import com.google.common.base.Preconditions
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

object DtsUtil {
    
    const val FRAME_TYPE_UNKNOWN: Int = 0

    const val FRAME_TYPE_CORE: Int = 1

    const val FRAME_TYPE_EXTENSION_SUBSTREAM: Int = 2

    const val FRAME_TYPE_UHD_SYNC: Int = 3

    const val FRAME_TYPE_UHD_NON_SYNC: Int = 4

    val DTS_MAX_RATE_BYTES_PER_SECOND: Int = 1536 * 1000 / 8

    val DTS_HD_MAX_RATE_BYTES_PER_SECOND: Int = 18000 * 1000 / 8

    const val DTS_EXPRESS_MAX_RATE_BITS_PER_SECOND: Int = 768000

    private const val SYNC_VALUE_BE = 0x7FFE8001

    private const val SYNC_VALUE_14B_BE = 0x1FFFE800
    private const val SYNC_VALUE_LE = -0x180fe80
    private const val SYNC_VALUE_14B_LE = -0xe0ff18

    private const val SYNC_VALUE_EXTSS_BE = 0x64582025

    private const val SYNC_VALUE_EXTSS_LE = 0x25205864

    private const val SYNC_VALUE_UHD_FTOC_SYNC_BE = 0x40411BF2

    private const val SYNC_VALUE_UHD_FTOC_SYNC_LE = -0xde4bec0
    private const val SYNC_VALUE_UHD_FTOC_NONSYNC_BE = 0x71C442E8
    private const val SYNC_VALUE_UHD_FTOC_NONSYNC_LE = -0x17bd3b8f

    private val FIRST_BYTE_BE = (SYNC_VALUE_BE ushr 24).toByte()
    private val FIRST_BYTE_14B_BE = (SYNC_VALUE_14B_BE ushr 24).toByte()
    private val FIRST_BYTE_LE = (SYNC_VALUE_LE ushr 24).toByte()
    private val FIRST_BYTE_14B_LE = (SYNC_VALUE_14B_LE ushr 24).toByte()
    private val FIRST_BYTE_EXTSS_BE = (SYNC_VALUE_EXTSS_BE ushr 24).toByte()
    private val FIRST_BYTE_EXTSS_LE = (SYNC_VALUE_EXTSS_LE ushr 24).toByte()
    private val FIRST_BYTE_UHD_FTOC_SYNC_BE = (SYNC_VALUE_UHD_FTOC_SYNC_BE ushr 24).toByte()
    private val FIRST_BYTE_UHD_FTOC_SYNC_LE = (SYNC_VALUE_UHD_FTOC_SYNC_LE ushr 24).toByte()
    private val FIRST_BYTE_UHD_FTOC_NONSYNC_BE = (SYNC_VALUE_UHD_FTOC_NONSYNC_BE ushr 24).toByte()
    private val FIRST_BYTE_UHD_FTOC_NONSYNC_LE = (SYNC_VALUE_UHD_FTOC_NONSYNC_LE ushr 24).toByte()

    private val CHANNELS_BY_AMODE = intArrayOf(1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8)

    private val SAMPLE_RATE_BY_SFREQ = intArrayOf(
        -1, 8000, 16000, 32000, -1, -1, 11025, 22050, 44100, -1, -1, 12000, 24000, 48000,
        -1, -1
    )

    private val TWICE_BITRATE_KBPS_BY_RATE = intArrayOf(
        64, 112, 128, 192, 224, 256, 384, 448, 512, 640, 768, 896, 1024, 1152, 1280, 1536,
        1920, 2048, 2304, 2560, 2688, 2816, 2823, 2944, 3072, 3840, 4096, 6144, 7680
    )

    private val SAMPLE_RATE_BY_INDEX = intArrayOf(
        8000, 16000, 32000, 64000, 128000, 22050, 44100, 88200, 176400, 352800, 12000,
        24000, 48000, 96000, 192000, 384000
    )

    private val UHD_FTOC_PAYLOAD_LENGTH_TABLE = intArrayOf(5, 8, 10, 12)

    private val UHD_METADATA_CHUNK_SIZE_LENGTH_TABLE = intArrayOf(6, 9, 12, 15)

    private val UHD_AUDIO_CHUNK_ID_LENGTH_TABLE = intArrayOf(2, 4, 6, 8)

    private val UHD_AUDIO_CHUNK_SIZE_LENGTH_TABLE = intArrayOf(9, 11, 13, 16)

    private val UHD_HEADER_SIZE_LENGTH_TABLE = intArrayOf(5, 8, 10, 12)

        fun isDtsBaseAudioMimeType(mimeType: String?): Boolean {
        return mimeType == MimeTypes.AUDIO_DTS
                || mimeType == MimeTypes.AUDIO_DTS_HD
    }

        fun getFrameType(word: Int): @FrameType Int {
        if (word == SYNC_VALUE_BE || word == SYNC_VALUE_LE || word == SYNC_VALUE_14B_BE || word == SYNC_VALUE_14B_LE) {
            return FRAME_TYPE_CORE
        } else if (word == SYNC_VALUE_EXTSS_BE || word == SYNC_VALUE_EXTSS_LE) {
            return FRAME_TYPE_EXTENSION_SUBSTREAM
        } else if (word == SYNC_VALUE_UHD_FTOC_SYNC_BE || word == SYNC_VALUE_UHD_FTOC_SYNC_LE) {
            return FRAME_TYPE_UHD_SYNC
        } else if (word == SYNC_VALUE_UHD_FTOC_NONSYNC_BE || word == SYNC_VALUE_UHD_FTOC_NONSYNC_LE) {
            return FRAME_TYPE_UHD_NON_SYNC
        }
        return FRAME_TYPE_UNKNOWN
    }

        fun parseDtsFormat(
        frame: ByteArray,
        trackId: String?,
        language: String?,
        @RoleFlags roleFlags: @RoleFlags Int,
        containerMimeType: String?,
        drmInitData: DrmInitData?
    ): Format {
        val frameBits = getNormalizedFrame(frame)
        frameBits.skipBits(32 + 1 + 5 + 1 + 7 + 14) // SYNC, FTYPE, SHORT, CPF, NBLKS, FSIZE
        val amode = frameBits.readBits(6)
        var channelCount = CHANNELS_BY_AMODE[amode]
        val sfreq = frameBits.readBits(4)
        val sampleRate = SAMPLE_RATE_BY_SFREQ[sfreq]
        val rate = frameBits.readBits(5)
        val bitrate =
            if (rate >= TWICE_BITRATE_KBPS_BY_RATE.size)
                Format.NO_VALUE
            else
                TWICE_BITRATE_KBPS_BY_RATE[rate] * 1000 / 2
        frameBits.skipBits(10) // MIX, DYNF, TIMEF, AUXF, HDCD, EXT_AUDIO_ID, EXT_AUDIO, ASPF
        channelCount += if (frameBits.readBits(2) > 0) 1 else 0 // LFF
        return Format.Builder()
            .setId(trackId)
            .setContainerMimeType(containerMimeType)
            .setSampleMimeType(MimeTypes.AUDIO_DTS)
            .setAverageBitrate(bitrate)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRate)
            .setDrmInitData(drmInitData)
            .setLanguage(language)
            .setRoleFlags(roleFlags)
            .build()
    }

        fun parseDtsAudioSampleCount(data: ByteArray): Int {
        val nblks: Int
        when (data[0]) {
            FIRST_BYTE_LE -> nblks =
                ((data[5].toInt() and 0x01) shl 6) or ((data[4].toInt() and 0xFC) shr 2)

            FIRST_BYTE_14B_LE -> nblks =
                ((data[4].toInt() and 0x07) shl 4) or ((data[7].toInt() and 0x3C) shr 2)

            FIRST_BYTE_14B_BE -> nblks =
                ((data[5].toInt() and 0x07) shl 4) or ((data[6].toInt() and 0x3C) shr 2)

            else ->         // We blindly assume FIRST_BYTE_BE if none of the others match.
                nblks = ((data[4].toInt() and 0x01) shl 6) or ((data[5].toInt() and 0xFC) shr 2)
        }
        return (nblks + 1) * 32
    }

    fun parseDtsAudioSampleCount(buffer: ByteBuffer): Int {
        if ((buffer.getInt(0) == SYNC_VALUE_UHD_FTOC_SYNC_LE)
            || (buffer.getInt(0) == SYNC_VALUE_UHD_FTOC_NONSYNC_LE)
        ) {
            // Check for DTS:X Profile 2 sync or non sync word and return 1024 if found. This is the only
            // audio sample count that is used by DTS:X Streaming Encoder.
            return 1024
        } else if (buffer.getInt(0) == SYNC_VALUE_EXTSS_LE) {
            // Check for DTS Express sync word and return 4096 if found. This is the only audio sample
            // count that is used by DTS Streaming Encoder.
            return 4096
        }

        // See ETSI TS 102 114 subsection 5.4.1.
        val position = buffer.position()
        val nblks: Int
        when (buffer.get(position)) {
            FIRST_BYTE_LE -> nblks =
                ((buffer.get(position + 5).toInt() and 0x01) shl 6) or ((buffer.get(position + 4)
                    .toInt() and 0xFC) shr 2)

            FIRST_BYTE_14B_LE -> nblks =
                ((buffer.get(position + 4).toInt() and 0x07) shl 4) or ((buffer.get(position + 7)
                    .toInt() and 0x3C) shr 2)

            FIRST_BYTE_14B_BE -> nblks =
                ((buffer.get(position + 5).toInt() and 0x07) shl 4) or ((buffer.get(position + 6)
                    .toInt() and 0x3C) shr 2)

            else ->         // We blindly assume FIRST_BYTE_BE if none of the others match.
                nblks = ((buffer.get(position + 4)
                    .toInt() and 0x01) shl 6) or ((buffer.get(position + 5).toInt() and 0xFC) shr 2)
        }
        return (nblks + 1) * 32
    }

        fun getDtsFrameSize(data: ByteArray): Int {
        val fsize: Int
        var uses14BitPerWord = false
        when (data[0]) {
            FIRST_BYTE_14B_BE -> {
                fsize =
                    (((data[6].toInt() and 0x03) shl 12) or ((data[7].toInt() and 0xFF) shl 4) or ((data[8].toInt() and 0x3C) shr 2)) + 1
                uses14BitPerWord = true
            }

            FIRST_BYTE_LE -> fsize =
                (((data[4].toInt() and 0x03) shl 12) or ((data[7].toInt() and 0xFF) shl 4) or ((data[6].toInt() and 0xF0) shr 4)) + 1

            FIRST_BYTE_14B_LE -> {
                fsize =
                    (((data[7].toInt() and 0x03) shl 12) or ((data[6].toInt() and 0xFF) shl 4) or ((data[9].toInt() and 0x3C) shr 2)) + 1
                uses14BitPerWord = true
            }

            else ->         // We blindly assume FIRST_BYTE_BE if none of the others match.
                fsize =
                    (((data[5].toInt() and 0x03) shl 12) or ((data[6].toInt() and 0xFF) shl 4) or ((data[7].toInt() and 0xF0) shr 4)) + 1
        }

        // If the frame is stored in 14-bit mode, adjust the frame size to reflect the actual byte size.
        return if (uses14BitPerWord) fsize * 16 / 14 else fsize
    }

        @Throws(ParserException::class)
    fun parseDtsHdHeader(header: ByteArray): DtsHeader {
        val headerBits = getNormalizedFrame(header)
        headerBits.skipBits(32 + 8) // SYNCEXTSSH, UserDefinedBits

        val extensionSubstreamIndex = headerBits.readBits(2) // nExtSSIndex
        val headerSizeInBits: Int // nuBits4Header
        val extensionSubstreamFrameSizeBits: Int // nuBits4ExSSFsize
        if (!headerBits.readBit()) { // bHeaderSizeType
            headerSizeInBits = 8
            extensionSubstreamFrameSizeBits = 16
        } else {
            headerSizeInBits = 12
            extensionSubstreamFrameSizeBits = 20
        }
        headerBits.skipBits(headerSizeInBits) // nuExtSSHeaderSize
        val extensionSubstreamFrameSize =
            headerBits.readBits(extensionSubstreamFrameSizeBits) + 1 // nuExtSSFsize

        val assetsCount: Int // nuNumAssets
        val referenceClockCode: Int // nuRefClockCode
        val extensionSubstreamFrameDurationCode: Int // nuExSSFrameDurationCode
        var enableMixMetadata = false // bMixMetadataEnbl
        var mixerOutChannels: IntArray? = null

        val staticFieldsPresent = headerBits.readBit() // bStaticFieldsPresent
        if (staticFieldsPresent) {
            referenceClockCode = headerBits.readBits(2)
            extensionSubstreamFrameDurationCode = 512 * (headerBits.readBits(3) + 1)

            if (headerBits.readBit()) { // bTimeStampFlag
                headerBits.skipBits(32 + 4) // nuTimeStamp, nLSB
            }

            val audioPresentationsCount = headerBits.readBits(3) + 1 // nuNumAudioPresnt
            assetsCount = headerBits.readBits(3) + 1
            if (audioPresentationsCount != 1 || assetsCount != 1) {
                throw ParserException.createForUnsupportedContainerFeature( 
                    "Multiple audio presentations or assets not supported"
                )
            }

            // We've already asserted audioPresentationsCount = 1.
            val activeExtensionSubstreamMask =
                headerBits.readBits(extensionSubstreamIndex + 1) // nuActiveExSSMask

            for (i in 0..<extensionSubstreamIndex + 1) {
                if (((activeExtensionSubstreamMask shr i) and 0x1) == 1) {
                    headerBits.skipBits(8) // nuActiveAssetMask
                }
            }

            enableMixMetadata = headerBits.readBit()
            if (enableMixMetadata) { // bMixMetadataEnbl
                headerBits.skipBits(2) // nuMixMetadataAdjLevel
                val mixerOutputMaskBits = (headerBits.readBits(2) + 1) shl 2 // nuBits4MixOutMask
                val mixerOutputConfigurationCount = headerBits.readBits(2) + 1 // nuNumMixOutConfigs
                mixerOutChannels = IntArray(mixerOutputConfigurationCount)
                // Output Mixing Configuration Loop
                for (i in 0..<mixerOutputConfigurationCount) {
                    val mask = headerBits.readBits(mixerOutputMaskBits) // nuMixOutChMask
                    mixerOutChannels[i] = getRemapChannelCount(mask)
                }
            }
        } else {
            // Assignments below are placeholders and will never be used as they are only relevant when
            // staticFieldsPresent == true. Initialised here to keep the compiler happy.
            referenceClockCode = C.INDEX_UNSET
            extensionSubstreamFrameDurationCode = 0
        }

        // We've already asserted assetsCount = 1.
        headerBits.skipBits(extensionSubstreamFrameSizeBits) // nuAssetFsize
        var sampleRate = C.RATE_UNSET_INT
        var channelCount = C.LENGTH_UNSET // nuTotalNumChs
        var embeddedStereo = false // bEmbeddedStereoFlag
        var embedded6ch = false // bEmbeddedSixChFlag

        // Asset descriptor: Size, Index and Per Stream Static Metadata, see ETSI TS 102 114 V1.6.1
        // (2019-08) Table 7-5.
        headerBits.skipBits(9 + 3) // nuAssetDescriptFsize, nuAssetIndex
        var mimeType: String? = null
        if (staticFieldsPresent) {
            if (headerBits.readBit()) { // bAssetTypeDescrPresent
                headerBits.skipBits(4) // nuAssetTypeDescriptor
            }
            if (headerBits.readBit()) { // bLanguageDescrPresent
                headerBits.skipBits(24) // LanguageDescriptor
            }
            if (headerBits.readBit()) { // bInfoTextPresent
                val infoTextByteSize = headerBits.readBits(10) + 1 // nuInfoTextByteSize
                headerBits.skipBytes(infoTextByteSize) // InfoTextString
            }
            headerBits.skipBits(5) // nuBitResolution
            sampleRate = SAMPLE_RATE_BY_INDEX[headerBits.readBits(4)] // nuMaxSampleRate
            channelCount = headerBits.readBits(8) + 1
            if (headerBits.readBit()) { // bOne2OneMapChannels2Speakers
                if (channelCount > 2) {
                    embeddedStereo = headerBits.readBit() // bEmbeddedStereoFlag
                }
                if (channelCount > 6) {
                    embedded6ch = headerBits.readBit() // bEmbeddedSixChFlag
                }
                var speakerMaskLength = 0
                if (headerBits.readBit()) { // bSpkrMaskEnabled
                    speakerMaskLength = (headerBits.readBits(2) + 1) shl 2 // nuNumBits4SAMask
                    headerBits.skipBits(speakerMaskLength) // nuSpkrActivityMask
                }
                val speakerRemapSetsCount = headerBits.readBits(3) // nuNumSpkrRemapSets
                val speakerRemapSets = IntArray(speakerRemapSetsCount)
                for (i in 0..<speakerRemapSetsCount) {
                    speakerRemapSets[i] =
                        headerBits.readBits(speakerMaskLength) // nuStndrSpkrLayoutMask[ns]
                }
                for (i in 0..<speakerRemapSetsCount) {
                    val remapChannelCount = getRemapChannelCount(speakerRemapSets[i])
                    val remapMaskLength = headerBits.readBits(5) + 1 // nuNumDecCh4Remap[ns]
                    for (j in 0..<remapChannelCount) {
                        val remapMask =
                            headerBits.readBits(remapMaskLength) // nuRemapDecChMask[ns][nCh]
                        val coef = Integer.bitCount(remapMask) // nCoef
                        headerBits.skipBits(coef * 5) // nuSpkrRemapCodes[ns][nCh][nc]
                    }
                }
            } else {
                headerBits.skipBits(3) // nuRepresentationType
            }

            parseAssetDescriptorDynamicData(
                headerBits,
                embeddedStereo,
                embedded6ch,
                channelCount,
                enableMixMetadata,
                mixerOutChannels
            )

            mimeType = parseDecoderNavigationData(headerBits)
        }

        // Done reading necessary bits, ignoring the rest.
        var frameDurationUs = C.TIME_UNSET
        if (staticFieldsPresent) {
            val referenceClockFrequency: Int
            //  ETSI TS 102 114 V1.6.1 (2019-08) Table 7-3.
            when (referenceClockCode) {
                0 -> referenceClockFrequency = 32000
                1 -> referenceClockFrequency = 44100
                2 -> referenceClockFrequency = 48000
                else -> throw ParserException.createForMalformedContainer( 
                    "Unsupported reference clock code in DTS HD header: "
                            + referenceClockCode,  
                    null
                )
            }
            frameDurationUs =
                scaleLargeTimestamp(
                    extensionSubstreamFrameDurationCode.toLong(),
                    C.MICROS_PER_SECOND,
                    referenceClockFrequency.toLong()
                )
        }
        return DtsHeader(
            mimeType,
            channelCount,
            sampleRate,
            extensionSubstreamFrameSize,
            frameDurationUs,  
            0
        )
    }

    // Asset descriptor: Dynamic Metadata - DRC, DNC and Mixing Metadata, see ETSI TS 102 114
    // V1.6.1 (2019-08) Table 7-6.
    private fun parseAssetDescriptorDynamicData(
        headerBits: ParsableBitArray,
        embeddedStereo: Boolean,
        embedded6ch: Boolean,
        channelCount: Int,
        enableMixMetadata: Boolean,
        mixerOutChannels: IntArray?
    ) {
        val hasDrcCoef = headerBits.readBit()
        if (hasDrcCoef) { // bDRCCoefPresent
            headerBits.skipBits(8) // nuDRCCode
        }
        if (headerBits.readBit()) { // bDialNormPresent
            headerBits.skipBits(5) // nuDialNormCode
        }
        if (hasDrcCoef && embeddedStereo) {
            headerBits.skipBits(8) // nuDRC2ChDmixCode
        }
        if (enableMixMetadata && headerBits.readBit()) { // bMixMetadataPresent
            headerBits.skipBits(1 + 6) // bExternalMixFlag, nuPostMixGainAdjCode
            if (headerBits.readBits(2) < 3) { // nuControlMixerDRC
                headerBits.skipBits(3) // nuLimit4EmbeddedDRC
            } else {
                headerBits.skipBits(8) // nuCustomDRCCode
            }
            val audioScalePerChannel = headerBits.readBit() // bEnblPerChMainAudioScale
            for (mixerOutChannel in mixerOutChannels!!) {
                if (audioScalePerChannel) {
                    headerBits.skipBits(6 * mixerOutChannel) // nuMainAudioScaleCode[ns][nCh]
                } else {
                    headerBits.skipBits(6) // nuMainAudioScaleCode[ns][0]
                }
            }
            var mixesCount = 1 // nEmDM
            val channelCountsForDownmixes = IntArray(3)
            channelCountsForDownmixes[0] = channelCount // nDecCh[0]
            if (embedded6ch) {
                channelCountsForDownmixes[mixesCount] = 6 // nDecCh[nEmDM]
                mixesCount++ // nEmDM
            }
            if (embeddedStereo) {
                channelCountsForDownmixes[mixesCount] = 2 // nDecCh[nEmDM]
                mixesCount++ // nEmDM
            }
            for (mixerOutChannel in mixerOutChannels) {
                for (downmix in 0..<mixesCount) {
                    val channelCountForDownmix = channelCountsForDownmixes[downmix]
                    for (downmixChannel in 0..<channelCountForDownmix) {
                        val mask = headerBits.readBits(mixerOutChannel) // nuMixMapMask[ns][nE][nCh]
                        val coefficients = Integer.bitCount(mask) // nuNumMixCoefs[ns][nE][nCh]
                        headerBits.skipBits(coefficients * 6) // nuMixCoeffs[ns][nE][nCh][nC]
                    }
                }
            }
        }
    }

    // Asset descriptor: Decoder Navigation Data, see ETSI TS 102 114 V1.6.1 (2019-08) Table 7-7.
    @Throws(ParserException::class)
    private fun parseDecoderNavigationData(headerBits: ParsableBitArray): String {
        val codingMode = headerBits.readBits(2) // nuCodingMode
        when (codingMode) {
            0 -> {
                val extensionMask = headerBits.readBits(12)
                if ((extensionMask and 0x100) != 0) { // Low bit rate component
                    return MimeTypes.AUDIO_DTS_EXPRESS
                } else {
                    return MimeTypes.AUDIO_DTS_HD
                }
            }

            1 -> return MimeTypes.AUDIO_DTS_HD
            2 -> return MimeTypes.AUDIO_DTS_EXPRESS
            3 -> throw ParserException.createForMalformedContainer( 
                "Unsupported coding mode in DTS HD header: " + codingMode,  
                null
            )

            else -> throw ParserException.createForMalformedContainer(
                "Unsupported coding mode in DTS HD header: " + codingMode,
                null
            )
        }
    }

    // See Table 7-10 in ETSI TS 102 114 V1.6.1
    private fun getRemapChannelCount(mask: Int): Int {
        var remapChannelCount = 0
        if ((mask and 0x0001) != 0) { // Centre in front of listener
            remapChannelCount += 1
        }
        if ((mask and 0x0002) != 0) { // Left/Right in front
            remapChannelCount += 2
        }
        if ((mask and 0x0004) != 0) { // Left/Right surround on side in rear
            remapChannelCount += 2
        }
        if ((mask and 0x0008) != 0) { // Low frequency effects subwoofer
            remapChannelCount += 1
        }
        if ((mask and 0x0010) != 0) { // Centre surround in rear
            remapChannelCount += 1
        }
        if ((mask and 0x0020) != 0) { // Left/Right height in front
            remapChannelCount += 2
        }
        if ((mask and 0x0040) != 0) { // Left/Right surround in rear
            remapChannelCount += 2
        }
        if ((mask and 0x0080) != 0) { // Centre Height in front
            remapChannelCount += 1
        }
        if ((mask and 0x0100) != 0) { // Over the listener's head
            remapChannelCount += 1
        }
        if ((mask and 0x0200) != 0) { // Between left/right and centre in front
            remapChannelCount += 2
        }
        if ((mask and 0x0400) != 0) { // Left/Right on side in front
            remapChannelCount += 2
        }
        if ((mask and 0x0800) != 0) { // Left/Right surround on side
            remapChannelCount += 2
        }
        if ((mask and 0x1000) != 0) { // Second low frequency effects subwoofer
            remapChannelCount += 1
        }
        if ((mask and 0x2000) != 0) { // Left/Right height on side
            remapChannelCount += 2
        }
        if ((mask and 0x4000) != 0) { // Centre height in rear
            remapChannelCount += 1
        }
        if ((mask and 0x8000) != 0) { // Left/Right height in rear
            remapChannelCount += 2
        }
        return remapChannelCount
    }

        fun parseDtsHdHeaderSize(headerPrefix: ByteArray): Int {
        val headerPrefixBits = getNormalizedFrame(headerPrefix)
        headerPrefixBits.skipBits(32 + 8 + 2) // SYNCEXTSSH, UserDefinedBits, nExtSSIndex
        // Unpack the num of bits to be used to read header size
        val headerBits = if (headerPrefixBits.readBit()) 12 else 8 // bHeaderSizeType
        // Unpack the substream header size
        return headerPrefixBits.readBits(headerBits) + 1 // nuExtSSHeaderSize
    }

        @Throws(ParserException::class)
    fun parseDtsUhdHeader(header: ByteArray, uhdAudioChunkId: AtomicInteger): DtsHeader {
        val headerBits = getNormalizedFrame(header)
        val syncWord = headerBits.readBits(32)
        val syncFrameFlag = syncWord == SYNC_VALUE_UHD_FTOC_SYNC_BE

        val ftocPayloadInBytes =
            (parseUnsignedVarInt(
                headerBits, UHD_FTOC_PAYLOAD_LENGTH_TABLE,  true
            )
                    + 1)

        // ETSI TS 103 491 V1.2.1, Section 6.4.5.
        var sampleRate = C.RATE_UNSET_INT // m_unAudioSamplRate
        var frameDurationUs = C.TIME_UNSET
        if (syncFrameFlag) {
            // ETSI TS 103 491 V1.2.1, Section 6.4.6.1.
            if (!headerBits.readBit()) { // m_bFullChannelBasedMixFlag
                throw ParserException.createForUnsupportedContainerFeature( 
                    "Only supports full channel mask-based audio presentation"
                )
            }

            // ETSI TS 103 491 V1.2.1, Section 6.4.6.2.
            checkCrc(header, ftocPayloadInBytes)

            val baseDurationIndex = headerBits.readBits(2)
            val baseDuration: Int // m_unBaseDuration
            // ETSI TS 103 491 V1.2.1 (2019-05) Table 6-13.
            when (baseDurationIndex) {
                0 -> baseDuration = 512
                1 -> baseDuration = 480
                2 -> baseDuration = 384
                else -> throw ParserException.createForMalformedContainer( 
                    "Unsupported base duration index in DTS UHD header: "
                            + baseDurationIndex,  
                    null
                )
            }
            val frameDurationInClockPeriods =
                baseDuration * (headerBits.readBits(3) + 1) // m_unFrameDuration
            val clockRateIndex = headerBits.readBits(2)
            val clockRateHertz: Int // m_unClockRateInHz
            when (clockRateIndex) {
                0 -> clockRateHertz = 32000
                1 -> clockRateHertz = 44100
                2 -> clockRateHertz = 48000
                else -> throw ParserException.createForMalformedContainer( 
                    "Unsupported clock rate index in DTS UHD header: " + clockRateIndex,  
                    null
                )
            }
            // Skip time stamp information if present, see section 5.2.3.2.
            if (headerBits.readBit()) { // m_bParamPresent
                // m_bUpdateFlag == true as m_bSyncFramePredefValueExists is set to false in the encoder.
                headerBits.skipBits(32 + 4) // m_TimeStamp
            }
            val sampleRateMultiplier = (1 shl headerBits.readBits(2))
            sampleRate = clockRateHertz * sampleRateMultiplier
            frameDurationUs =
                scaleLargeTimestamp(
                    frameDurationInClockPeriods.toLong(),
                    C.MICROS_PER_SECOND,
                    clockRateHertz.toLong()
                )
        }

        // ETSI TS 103 491 V1.2.1, Table 6-20.
        // m_bFullChannelBasedMixFlag == true as we throw unsupported container feature otherwise.
        var chunkPayloadBytes = 0
        val numOfMetadataChunks = if (syncFrameFlag) 1 else 0 // Metadata chunks
        for (i in 0..<numOfMetadataChunks) {
            val metadataChunkSize =
                parseUnsignedVarInt(
                    headerBits, UHD_METADATA_CHUNK_SIZE_LENGTH_TABLE,  true
                )
            chunkPayloadBytes += metadataChunkSize
        }

        // See ETSI TS 103 491 V1.2.1, Section 6.4.14.4.
        // m_bFullChannelBasedMixFlag == true as we throw unsupported container feature otherwise.
        val numAudioChunks = 1
        for (i in 0..<numAudioChunks) {
            // If syncFrameFlag is true the audio chunk ID will be present.
            if (syncFrameFlag) {
                uhdAudioChunkId.set(
                    parseUnsignedVarInt(
                        headerBits, UHD_AUDIO_CHUNK_ID_LENGTH_TABLE,  true
                    )
                )
            }
            val audioChunkSize =
                if (uhdAudioChunkId.get() != 0)
                    parseUnsignedVarInt(
                        headerBits, UHD_AUDIO_CHUNK_SIZE_LENGTH_TABLE,  true
                    )
                else
                    0
            chunkPayloadBytes += audioChunkSize
        }

        val frameSize = ftocPayloadInBytes + chunkPayloadBytes
        return DtsHeader(
            MimeTypes.AUDIO_DTS_UHD_P2,  // To determine the actual number of channels from a bit stream, we need to read the
            // metadata chunk bytes. If defining a constant channel count causes problems, we can
            // consider adding additional parsing logic for UHD frames.
            // For now, using the estimated number of channels for DTS UHD bitstreams as 2.
            
            2,
            sampleRate,
            frameSize,
            frameDurationUs,  
            0
        )
    }

        fun parseDtsUhdHeaderSize(headerPrefix: ByteArray): Int {
        val headerPrefixBits = getNormalizedFrame(headerPrefix)
        headerPrefixBits.skipBits(32) // SYNC
        return (parseUnsignedVarInt(
            headerPrefixBits, UHD_HEADER_SIZE_LENGTH_TABLE,  true
        )
                + 1)
    }

        @Throws(IOException::class)
    fun updateFormatWithDtsHdInfo(
        input: ExtractorInput, sampleSize: Int, format: Format
    ): Format {
        val sampleData = ParsableByteArray(sampleSize)
        if (!input.peekFully(
                sampleData.data,  0, sampleSize,  true
            )
        ) {
            return format
        }
        input.resetPeekPosition()
        var word = sampleData.peekInt()
        // Skip the core frame if present (it doesn't have to be).
        if (getFrameType(word) == FRAME_TYPE_CORE) {
            if (sampleData.bytesLeft() < 10) {
                return format
            }
            val header = ByteArray(10)
            sampleData.readBytes(header,  0,  10)
            val frameSize = getDtsFrameSize(header)
            if (frameSize <= 0 || sampleData.limit() < frameSize + 4) {
                return format
            }
            sampleData.setPosition(frameSize)
            word = sampleData.peekInt()
        }
        if (getFrameType(word) != FRAME_TYPE_EXTENSION_SUBSTREAM) {
            return format
        }
        if (sampleData.bytesLeft() < 7) {
            return format
        }
        val extHeaderOffset = sampleData.getPosition()
        val headerPrefix = ByteArray(7)
        sampleData.readBytes(headerPrefix,  0,  7)
        sampleData.setPosition(extHeaderOffset)
        val frameSize = parseDtsHdHeaderSize(headerPrefix)
        if (frameSize <= 0 || sampleData.bytesLeft() < frameSize) {
            return format
        }
        val header = ByteArray(frameSize)
        sampleData.readBytes(header,  0,  frameSize)
        val dtsHeader = parseDtsHdHeader(header)
        // If the MIME type was parsed successfully, use it. If it is null (e.g. because static
        // fields were missing), there's nothing we can do other than assume it is DTS-HD.
        val mimeType =
            if (dtsHeader.mimeType != null) dtsHeader.mimeType else MimeTypes.AUDIO_DTS_HD
        if (format.sampleMimeType == mimeType) {
            return format
        }
        return format.buildUpon().setSampleMimeType(mimeType).build()
    }

    @Throws(ParserException::class)
    private fun checkCrc(frame: ByteArray, sizeInBytes: Int) {
        val initialValue = 0xFFFF
        val extractedCrc =
            (((frame[sizeInBytes - 2].toInt() shl 8) and initialValue) or (frame[sizeInBytes - 1].toInt() and 0xFF))
        val calculatedCrc = crc16(frame,  0,  sizeInBytes - 2, initialValue)
        if (extractedCrc != calculatedCrc) {
            throw ParserException.createForMalformedContainer( 
                "CRC check failed",  null
            )
        }
    }

    private fun parseUnsignedVarInt(
        frameBits: ParsableBitArray, lengths: IntArray, extractAndAddFlag: Boolean
    ): Int {
        var index = 0
        for (i in 0..2) {
            if (frameBits.readBit()) {
                index++
            } else {
                break
            }
        }

        var value = 0
        if (extractAndAddFlag) {
            for (i in 0..<index) {
                value += (1 shl lengths[i])
            }
        }
        return value + frameBits.readBits(lengths[index])
    }

    private fun getNormalizedFrame(frame: ByteArray): ParsableBitArray {
        var frame = frame
        if (frame[0] == FIRST_BYTE_BE || frame[0] == FIRST_BYTE_EXTSS_BE || frame[0] == FIRST_BYTE_UHD_FTOC_SYNC_BE || frame[0] == FIRST_BYTE_UHD_FTOC_NONSYNC_BE) {
            // The frame is already 16-bit mode, big endian.
            return ParsableBitArray(frame)
        }
        // Data is not normalized, but we don't want to modify frame.
        frame = frame.copyOf(frame.size)
        if (isLittleEndianFrameHeader(frame)) {
            // Change endianness.
            var i = 0
            while (i < frame.size - 1) {
                val temp = frame[i]
                frame[i] = frame[i + 1]
                frame[i + 1] = temp
                i += 2
            }
        }
        val frameBits = ParsableBitArray(frame)
        if (frame[0] == (SYNC_VALUE_14B_BE shr 24).toByte()) {
            // Discard the 2 most significant bits of each 16 bit word.
            val scratchBits = ParsableBitArray(frame)
            while (scratchBits.bitsLeft() >= 16) {
                scratchBits.skipBits(2)
                frameBits.putInt(scratchBits.readBits(14), 14)
            }
        }
        frameBits.reset(frame)
        return frameBits
    }

    private fun isLittleEndianFrameHeader(frameHeader: ByteArray): Boolean {
        return frameHeader[0] == FIRST_BYTE_LE || frameHeader[0] == FIRST_BYTE_14B_LE || frameHeader[0] == FIRST_BYTE_EXTSS_LE || frameHeader[0] == FIRST_BYTE_UHD_FTOC_SYNC_LE || frameHeader[0] == FIRST_BYTE_UHD_FTOC_NONSYNC_LE
    }

    class DtsHeader  constructor(
       val mimeType: @DtsAudioMimeType String?,
       val channelCount: Int,
       val sampleRate: Int,
       val frameSize: Int,
       val frameDurationUs: Long,
        val bitrate: Int
    )

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @StringDef(
        value = [MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_UHD_P2
        ]
    )
    annotation class DtsAudioMimeType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [FRAME_TYPE_UNKNOWN, FRAME_TYPE_CORE, FRAME_TYPE_EXTENSION_SUBSTREAM, FRAME_TYPE_UHD_SYNC, FRAME_TYPE_UHD_NON_SYNC
        ]
    )
    annotation class FrameType
}
