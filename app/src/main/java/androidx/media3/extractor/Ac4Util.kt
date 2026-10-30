
package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.formatInvariant
import java.nio.ByteBuffer

object Ac4Util {
    private const val CHANNEL_MODE_UNKNOWN = -1
    private const val CHANNEL_MODE_MONO = 0
    private const val CHANNEL_MODE_STEREO = 1
    private const val CHANNEL_MODE_3_0 = 2
    private const val CHANNEL_MODE_5_0 = 3
    private const val CHANNEL_MODE_5_1 = 4
    private const val CHANNEL_MODE_7_0_34 = 5
    private const val CHANNEL_MODE_7_1_34 = 6
    private const val CHANNEL_MODE_7_0_52 = 7
    private const val CHANNEL_MODE_7_1_52 = 8
    private const val CHANNEL_MODE_7_0_322 = 9
    private const val CHANNEL_MODE_7_1_322 = 10
    private const val CHANNEL_MODE_7_0_4 = 11
    private const val CHANNEL_MODE_7_1_4 = 12
    private const val CHANNEL_MODE_9_0_4 = 13
    private const val CHANNEL_MODE_9_1_4 = 14
    private const val CHANNEL_MODE_22_2 = 15

    const val AC40_SYNCWORD: Int = 0xAC40
    const val AC41_SYNCWORD: Int = 0xAC41

    val MAX_RATE_BYTES_PER_SECOND: Int = 2688 * 1000 / 8

    private const val TAG = "Ac4Util"
    private const val CHANNEL_COUNT_2 = 2
    const val SAMPLE_HEADER_SIZE: Int = 7
    const val HEADER_SIZE_FOR_PARSER: Int = 16
    private val SAMPLE_COUNT = intArrayOf( 2002,  
        2000,  
        1920,  
        1601,  // 1601 | 1602 | 1601 | 1602 | 1602
        
        1600,  
        1001,  
        1000,  
        960,  
        800,  //  800 |  801 |  801 |  801 |  801
        
        800,  
        480,  
        400,  //  400 |  400 |  401 |  400 |  401
        
        400,  
        2048
    )

    @Throws(ParserException::class)
    fun parseAc4AnnexEFormat(
        data: ParsableByteArray,
        trackId: String?,
        language: String?,
        drmInitData: DrmInitData?
    ): Format {
        val dataBitArray = ParsableBitArray()
        dataBitArray.reset(data)

        val dsiSize = dataBitArray.bitsLeft()
        val ac4DsiVersion = dataBitArray.readBits(3) // ac4_dsi_version
        if (ac4DsiVersion > 1) {
            throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported AC-4 DSI version: " + ac4DsiVersion
            )
        }

        val bitstreamVersion = dataBitArray.readBits(7) // bitstream_version
        val sampleRate = if (dataBitArray.readBit()) 48000 else 44100 // fs_index
        dataBitArray.skipBits(4) // frame_rate_index
        val numberOfPresentations = dataBitArray.readBits(9) // n_presentations

        if (bitstreamVersion > 1) {
            if (ac4DsiVersion == 0) {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Invalid AC-4 DSI version: " + ac4DsiVersion
                )
            }
            if (dataBitArray.readBit()) { // b_program_id
                dataBitArray.skipBits(16) // short_program_id
                if (dataBitArray.readBit()) { // b_uuid
                    dataBitArray.skipBits(16 * 8) // program_uuid
                }
            }
        }

        if (ac4DsiVersion == 1) {
            if (!skipDsiBitrate(dataBitArray)) {
                throw ParserException.createForUnsupportedContainerFeature("Invalid AC-4 DSI bitrate.")
            }
            dataBitArray.byteAlign()
        }

        val ac4Presentation = Ac4Presentation()
        for (presentationIdx in 0..<numberOfPresentations) {
            var isSingleSubstream = false
            var isSingleSubstreamGroup = false
            val presentationConfig: Int
            val presentationVersion: Int
            var presentationBytes = 0
            var start = 0

            if (ac4DsiVersion == 0) {
                isSingleSubstream = dataBitArray.readBit() // b_single_substream
                presentationConfig = dataBitArray.readBits(5) // presentation_config
                presentationVersion = dataBitArray.readBits(5) // presentation_version
            } else {
                presentationVersion = dataBitArray.readBits(8) // presentation_version
                presentationBytes = dataBitArray.readBits(8) // pres_bytes
                if (presentationBytes == 0xff) {
                    presentationBytes += dataBitArray.readBits(16) // pres_bytes
                }
                if (presentationVersion > 2) {
                    dataBitArray.skipBits(presentationBytes * 8)
                    continue
                }
                // record a marker, less the size of the presentation_config
                start = (dsiSize - dataBitArray.bitsLeft()) / 8
                // ac4_presentation_v0_dsi(), ac4_presentation_v1_dsi() and ac4_presentation_v2_dsi()
                // all start with a presentation_config of 5 bits
                presentationConfig = dataBitArray.readBits(5) // presentation_config
                isSingleSubstreamGroup = (presentationConfig == 0x1f)
            }
            ac4Presentation.version = presentationVersion

            val addEmdfSubstreams: Boolean
            if (!(isSingleSubstream || isSingleSubstreamGroup) && presentationConfig == 6) {
                addEmdfSubstreams = true
            } else {
                ac4Presentation.level = dataBitArray.readBits(3) // mdcompat

                if (dataBitArray.readBit()) { // b_presentation_group_index
                    dataBitArray.skipBits(5) // group_index
                }

                dataBitArray.skipBits(2) // dsi_frame_rate_multiply_info
                if (ac4DsiVersion == 1 && (presentationVersion == 1 || presentationVersion == 2)) {
                    dataBitArray.skipBits(2) // dsi_frame_rate_fraction_info
                }
                dataBitArray.skipBits(5) // presentation_emdf_version
                dataBitArray.skipBits(10) // presentation_key_id

                if (ac4DsiVersion == 1) {
                    if (presentationVersion > 0) {
                        ac4Presentation.isChannelCoded =
                            dataBitArray.readBit() // b_presentation_channel_coded
                    }

                    if (ac4Presentation.isChannelCoded) {
                        if (presentationVersion == 1 || presentationVersion == 2) {
                            val channelMode = dataBitArray.readBits(5) // dsi_presentation_ch_mode
                            if (channelMode >= 0 && channelMode <= 15) {
                                ac4Presentation.channelMode = channelMode
                            }

                            if (channelMode >= 11 && channelMode <= 14) {
                                ac4Presentation.hasBackChannels =
                                    dataBitArray.readBit() // pres_b_4_back_channels_present
                                ac4Presentation.topChannelPairs =
                                    dataBitArray.readBits(2) // pres_top_channel_pairs
                            }
                        }
                        // presentation_channel_mask in ac4_presentation_v0_dsi()
                        dataBitArray.skipBits(24) // presentation_channel_mask_v1
                    }

                    if (presentationVersion == 1 || presentationVersion == 2) {
                        if (dataBitArray.readBit()) { // b_presentation_core_differs
                            if (dataBitArray.readBit()) { // b_presentation_core_channel_coded
                                dataBitArray.skipBits(2) // dsi_presentation_channel_mode_core
                            }
                        }
                        if (dataBitArray.readBit()) { // b_presentation_filter
                            // Ignore b_enable_presentation field since this flag occurs in AC-4 elementary stream
                            // TOC and AC-4 decoder doesn't handle it either.
                            dataBitArray.skipBit() // b_enable_presentation
                            val filterBytes = dataBitArray.readBits(8) // n_filter_bytes
                            for (i in 0..<filterBytes) {
                                dataBitArray.skipBits(8) // filter_data
                            }
                        }
                    }
                }

                if (isSingleSubstream || isSingleSubstreamGroup) {
                    if (presentationVersion == 0) {
                        parseDsiSubstream(dataBitArray, ac4Presentation)
                    } else {
                        parseDsiSubstreamGroup(dataBitArray, ac4Presentation)
                    }
                } else {
                    // b_hsf_ext for ac4DsiVersion 0 OR b_multi_pid for ac4DsiVersion 1
                    dataBitArray.skipBit()
                    when (presentationConfig) {
                        0, 1, 2 -> if (presentationVersion == 0) {
                            var substreamId = 0
                            while (substreamId < 2) {
                                parseDsiSubstream(dataBitArray, ac4Presentation)
                                substreamId++
                            }
                        } else {
                            var substreamGroupId = 0
                            while (substreamGroupId < 2) {
                                parseDsiSubstreamGroup(dataBitArray, ac4Presentation)
                                substreamGroupId++
                            }
                        }

                        3, 4 -> if (presentationVersion == 0) {
                            var substreamId = 0
                            while (substreamId < 3) {
                                parseDsiSubstream(dataBitArray, ac4Presentation)
                                substreamId++
                            }
                        } else {
                            var substreamGroupId = 0
                            while (substreamGroupId < 3) {
                                parseDsiSubstreamGroup(dataBitArray, ac4Presentation)
                                substreamGroupId++
                            }
                        }

                        5 -> if (presentationVersion == 0) {
                            parseDsiSubstream(dataBitArray, ac4Presentation)
                        } else {
                            val nSubstreamGroupsMinus2 = dataBitArray.readBits(3)
                            var substreamGroupId = 0
                            while (substreamGroupId < nSubstreamGroupsMinus2 + 2
                            ) {
                                parseDsiSubstreamGroup(dataBitArray, ac4Presentation)
                                substreamGroupId++
                            }
                        }

                        else -> {
                            val nSkipBytes = dataBitArray.readBits(7) // n_skip_bytes
                            var j = 0
                            while (j < nSkipBytes) {
                                dataBitArray.skipBits(8)
                                j++
                            }
                        }
                    }
                }
                dataBitArray.skipBit() // b_pre_virtualized
                addEmdfSubstreams = dataBitArray.readBit() // b_add_emdf_substreams
            }
            if (addEmdfSubstreams) {
                val nAddEmdfSubstreams = dataBitArray.readBits(7) // n_add_emdf_substreams
                for (j in 0..<nAddEmdfSubstreams) {
                    dataBitArray.skipBits(5 + 10) // substream_emdf_version and substream_key_id
                }
            }

            if (presentationVersion > 0) {
                if (dataBitArray.readBit()) { // b_presentation_bitrate_info
                    if (!skipDsiBitrate(dataBitArray)) {
                        throw ParserException.createForUnsupportedContainerFeature("Can't parse bitrate DSI.")
                    }
                }

                if (dataBitArray.readBit()) { // b_alternative
                    dataBitArray.byteAlign()
                    val nameLen = dataBitArray.readBits(16) // name_len
                    dataBitArray.skipBytes(nameLen) // presentation_name

                    val nTargets = dataBitArray.readBits(5) // n_targets
                    for (i in 0..<nTargets) {
                        dataBitArray.skipBits(3) // target_md_compat
                        dataBitArray.skipBits(8) // target_device_category
                    }
                }
            }

            dataBitArray.byteAlign()

            if (ac4DsiVersion == 1) {
                val end = (dsiSize - dataBitArray.bitsLeft()) / 8
                val presentationBytesRead = end - start
                if (presentationBytes < presentationBytesRead) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "pres_bytes is smaller than presentation bytes read."
                    )
                }
                val skipBytes = presentationBytes - presentationBytesRead
                dataBitArray.skipBytes(skipBytes)
            }
            // We should know this or something is probably wrong with the bitstream (or we don't support
            // it)
            if (ac4Presentation.isChannelCoded && ac4Presentation.channelMode == CHANNEL_MODE_UNKNOWN) {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Can't determine channel mode of presentation " + presentationIdx
                )
            }
            break // Successfully parsed the first presentation with presentation version 0, 1 or 2.
        }

        var channelCount: Int
        if (ac4Presentation.isChannelCoded) {
            channelCount =
                getAdjustedChannelCount(
                    ac4Presentation.channelMode,
                    ac4Presentation.hasBackChannels,
                    ac4Presentation.topChannelPairs
                )
        } else {
            if (ac4Presentation.numOfUmxObjects > 0) {
                // The ETSI TS 103 190-2 V1.2.1 (2018-02) specification defines the parameter
                // n_umx_objects_minus1 in Annex E (E.11.11) to specify the number of fullband objects.
                // While the elementary stream specification (section 6.3.2.8.1 and 6.3.2.10.4) provides
                // information about the presence of an LFE channel within the set of dynamic objects, this
                // detail is not explicitly stated in the ISO Base Media File Format (Annex E). However,
                // current implementation practices consistently include the LFE channel when creating an
                // object-based substream. As a result, it has been decided that when interpreting the ISO
                // Base Media File Format, the LFE channel should always be counted as part of the total
                // channel count.
                val lfeChannelCount = 1
                channelCount = ac4Presentation.numOfUmxObjects + lfeChannelCount
                // TODO: There is a bug in ETSI TS 103 190-2 V1.2.1 (2018-02), E.11.11
                // For AC-4 level 4 stream, the intention is to set 19 to n_umx_objects_minus1 but it is
                // equal to 15 based on current specification. Dolby has filed a bug report to ETSI.
                // The following sentence should be deleted after ETSI specification error is fixed.
                if (ac4Presentation.level == 4) {
                    channelCount = if (channelCount == 17) 21 else channelCount
                }
            } else {
                // This presentation includes a substream with discrete objects. Due to limitations in the
                // current AC-4 specification (ETSI TS 103 190-2 V1.2.1 (2018-02)), discrete object number
                // information is not explicitly stated in the ISO Base Media File Format (Annex E). To
                // prevent exceptions, "Maximum number of tracks if audio presentation includes object
                // audio" in table 77 of ETSI TS 103 190-2 V1.2.1 (2018-02) is used as the channel count.
                when (ac4Presentation.level) {
                    0 ->             // This should not happen because level 0 is always channel coded.
                        channelCount = 2

                    1 -> channelCount = 6
                    2 -> channelCount = 8
                    3 -> channelCount = 10
                    4 -> channelCount = 12
                    else -> {
                        // For forward-compatibility, default to 2 channels for unknown future AC-4 levels
                        // rather than throwing an exception. This allows the device to attempt playback.
                        w(TAG, "AC-4 level " + ac4Presentation.level + " has not been defined.")
                        channelCount = 2
                    }
                }
            }
        }

        if (channelCount <= 0) {
            throw ParserException.createForUnsupportedContainerFeature(
                "Cannot determine channel count of presentation."
            )
        }

        val codecString =
            createCodecsString(bitstreamVersion, ac4Presentation.version, ac4Presentation.level)

        return Format.Builder()
            .setId(trackId)
            .setSampleMimeType(MimeTypes.AUDIO_AC4)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRate)
            .setDrmInitData(drmInitData)
            .setLanguage(language)
            .setCodecs(codecString)
            .build()
    }

    @Throws(ParserException::class)
    private fun parseDsiSubstream(data: ParsableBitArray, ac4Presentation: Ac4Presentation) {
        val channelMode = data.readBits(5) // channel_mode
        data.skipBits(2) // dsi_sf_multiplier

        if (data.readBit()) { // b_bitrate_indicator
            data.skipBits(5) // b_bitrate_indicator
        }
        if (channelMode >= 7 && channelMode <= 10) {
            data.skipBit() // add_ch_base
        }

        if (data.readBit()) { // b_content_type
            val contentClassifier = data.readBits(3) // content_classifier
            // For streams based on TS 103 190 part 1 the presentation level channel_mode doesn't exist
            // and so we use the channel_mode from either the CM or M&E substream (they are mutually
            // exclusive).
            if (ac4Presentation.channelMode == CHANNEL_MODE_UNKNOWN && (channelMode >= 0 && channelMode <= 15)
                && (contentClassifier == 0 || contentClassifier == 1)
            ) {
                ac4Presentation.channelMode = channelMode
            }

            if (data.readBit()) { // b_language_indicator
                skipDsiLanguage(data)
            }
        }
    }

    @Throws(ParserException::class)
    private fun parseDsiSubstreamGroup(data: ParsableBitArray, ac4Presentation: Ac4Presentation) {
        data.skipBits(2) // b_substreams_present(1), b_hsf_ext(1)
        val channelCoded = data.readBit() // b_channel_coded
        val numberOfSubstreams = data.readBits(8) // n_substreams

        for (i in 0..<numberOfSubstreams) {
            data.skipBits(2) // dsi_sf_multiplier
            if (data.readBit()) { // b_substream_bitrate_indicator
                data.skipBits(5) // substream_bitrate_indicator
            }
            if (channelCoded) {
                data.skipBits(24) // dsi_substream_channel_mask
            } else {
                if (data.readBit()) { // b_ajoc
                    if (!data.readBit()) { // b_static_dmx
                        data.skipBits(4) // n_dmx_objects_minus1
                    }
                    ac4Presentation.numOfUmxObjects = data.readBits(6) + 1 // n_umx_objects_minus1
                }
                data.skipBits(4) // objects_assignment_mask
            }
        }

        if (data.readBit()) { // b_content_type
            data.skipBits(3) // content_classifier

            if (data.readBit()) { // b_language_indicator
                skipDsiLanguage(data)
            }
        }
    }

    @Throws(ParserException::class)
    private fun skipDsiLanguage(data: ParsableBitArray) {
        val languageTagBytesNumber = data.readBits(6) // n_language_tag_bytes
        if (languageTagBytesNumber < 2 || languageTagBytesNumber > 42) {
            throw ParserException.createForUnsupportedContainerFeature(
                String.format(
                    "Invalid language tag bytes number: %d. Must be between 2 and 42.",
                    languageTagBytesNumber
                )
            )
        }
        // Can't use readBytes() since it is not byte-aligned here.
        data.skipBits(languageTagBytesNumber * C.BITS_PER_BYTE)
    }

    private fun skipDsiBitrate(data: ParsableBitArray): Boolean {
        val totalBitsToSkip = 2 + 32 + 32 // bit_rate_mode, bit_rate, bit_rate_precision
        if (data.bitsLeft() < totalBitsToSkip) {
            return false
        }
        data.skipBits(totalBitsToSkip)
        return true
    }

    private fun getAdjustedChannelCount(
        channelMode: @ChannelMode Int, hasBackChannels: Boolean, topChannelPairs: Int
    ): Int {
        var channelCount = getChannelCountFromChannelMode(channelMode)
        if (channelMode == CHANNEL_MODE_7_0_4 || channelMode == CHANNEL_MODE_7_1_4 || channelMode == CHANNEL_MODE_9_0_4 || channelMode == CHANNEL_MODE_9_1_4) {
            if (!hasBackChannels) {
                channelCount -= 2
            }

            when (topChannelPairs) {
                0 -> channelCount -= 4
                1 -> channelCount -= 2
                else -> {}
            }
        }
        return channelCount
    }

    private fun getChannelCountFromChannelMode(channelMode: @ChannelMode Int): Int {
        when (channelMode) {
            CHANNEL_MODE_MONO -> return 1
            CHANNEL_MODE_STEREO -> return 2
            CHANNEL_MODE_3_0 -> return 3
            CHANNEL_MODE_5_0 -> return 5
            CHANNEL_MODE_5_1 -> return 6
            CHANNEL_MODE_7_0_34, CHANNEL_MODE_7_0_52, CHANNEL_MODE_7_0_322 -> return 7
            CHANNEL_MODE_7_1_34, CHANNEL_MODE_7_1_52, CHANNEL_MODE_7_1_322 -> return 8
            CHANNEL_MODE_7_0_4 -> return 11
            CHANNEL_MODE_7_1_4 -> return 12
            CHANNEL_MODE_9_0_4 -> return 13
            CHANNEL_MODE_9_1_4 -> return 14
            CHANNEL_MODE_22_2 -> return 24
            else -> return -1
        }
    }

    private fun createCodecsString(
        bitstreamVersion: Int, presentationVersion: Int, mdcompat: Int
    ): String {
        return formatInvariant(
            "ac-4.%02d.%02d.%02d", bitstreamVersion, presentationVersion, mdcompat
        )
    }

        fun parseAc4SyncframeInfo(data: ParsableBitArray): SyncFrameInfo {
        var headerSize = 0
        val syncWord = data.readBits(16)
        headerSize += 2
        var frameSize = data.readBits(16)
        headerSize += 2
        if (frameSize == 0xFFFF) {
            frameSize = data.readBits(24)
            headerSize += 3 // Extended frame_size
        }
        frameSize += headerSize
        if (syncWord == AC41_SYNCWORD) {
            frameSize += 2 // crc_word
        }
        var bitstreamVersion = data.readBits(2)
        if (bitstreamVersion == 3) {
            bitstreamVersion += readVariableBits(data,  2)
        }
        val sequenceCounter = data.readBits(10)
        if (data.readBit()) { // b_wait_frames
            if (data.readBits(3) > 0) { // wait_frames
                data.skipBits(2) // reserved
            }
        }
        val sampleRate = if (data.readBit()) 48000 else 44100
        val frameRateIndex = data.readBits(4)
        var sampleCount = 0
        if (sampleRate == 44100 && frameRateIndex == 13) {
            sampleCount = SAMPLE_COUNT[frameRateIndex]
        } else if (sampleRate == 48000 && frameRateIndex < SAMPLE_COUNT.size) {
            sampleCount = SAMPLE_COUNT[frameRateIndex]
            when (sequenceCounter % 5) {
                1, 3 -> if (frameRateIndex == 3 || frameRateIndex == 8) {
                    sampleCount++
                }

                2 -> if (frameRateIndex == 8 || frameRateIndex == 11) {
                    sampleCount++
                }

                4 -> if (frameRateIndex == 3 || frameRateIndex == 8 || frameRateIndex == 11) {
                    sampleCount++
                }

                else -> {}
            }
        }
        return Ac4Util.SyncFrameInfo(
            bitstreamVersion,
            CHANNEL_COUNT_2,
            sampleRate,
            frameSize,
            sampleCount
        )
    }

        fun parseAc4SyncframeSize(data: ByteArray, syncword: Int): Int {
        if (data.size < 7) {
            return C.LENGTH_UNSET
        }
        var headerSize = 2 // syncword
        var frameSize = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        headerSize += 2
        if (frameSize == 0xFFFF) {
            frameSize =
                ((data[4].toInt() and 0xFF) shl 16) or ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
            headerSize += 3
        }
        if (syncword == AC41_SYNCWORD) {
            headerSize += 2
        }
        frameSize += headerSize
        return frameSize
    }

    fun parseAc4SyncframeAudioSampleCount(buffer: ByteBuffer): Int {
        val bufferBytes = ByteArray(HEADER_SIZE_FOR_PARSER)
        val position = buffer.position()
        buffer.get(bufferBytes)
        buffer.position(position)
        return parseAc4SyncframeInfo(ParsableBitArray(bufferBytes)).sampleCount
    }

        fun getAc4SampleHeader(size: Int, buffer: ParsableByteArray) {
        // See ETSI TS 103 190-1 V1.3.1, Annex G.
        buffer.reset(SAMPLE_HEADER_SIZE)
        val data = buffer.data
        data[0] = 0xAC.toByte()
        data[1] = 0x40
        data[2] = 0xFF.toByte()
        data[3] = 0xFF.toByte()
        data[4] = ((size shr 16) and 0xFF).toByte()
        data[5] = ((size shr 8) and 0xFF).toByte()
        data[6] = (size and 0xFF).toByte()
    }

    private fun readVariableBits(data: ParsableBitArray, bitsPerRead: Int): Int {
        var value = 0
        while (true) {
            value += data.readBits(bitsPerRead)
            if (!data.readBit()) {
                break
            }
            value++
            value = value shl bitsPerRead
        }
        return value
    }

    class SyncFrameInfo  constructor(
        val bitstreamVersion: Int,
       val channelCount: Int,
       val sampleRate: Int,
       val frameSize: Int,
       val sampleCount: Int
    )

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [CHANNEL_MODE_UNKNOWN, CHANNEL_MODE_MONO, CHANNEL_MODE_STEREO, CHANNEL_MODE_3_0, CHANNEL_MODE_5_0, CHANNEL_MODE_5_1, CHANNEL_MODE_7_0_34, CHANNEL_MODE_7_1_34, CHANNEL_MODE_7_0_52, CHANNEL_MODE_7_1_52, CHANNEL_MODE_7_0_322, CHANNEL_MODE_7_1_322, CHANNEL_MODE_7_0_4, CHANNEL_MODE_7_1_4, CHANNEL_MODE_9_0_4, CHANNEL_MODE_9_1_4, CHANNEL_MODE_22_2
        ]
    )
    private annotation class ChannelMode

    private class Ac4Presentation {
        var isChannelCoded: Boolean = true
        var channelMode: @ChannelMode Int
        var numOfUmxObjects: Int
        var hasBackChannels: Boolean = true
        var topChannelPairs: Int = 2
        var version: Int = 1
        var level: Int = 0

        init {
            channelMode = CHANNEL_MODE_UNKNOWN
            numOfUmxObjects = -1
        }
    }
}
