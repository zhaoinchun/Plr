package androidx.media3.common.util

import android.annotation.SuppressLint
import android.media.MediaCodecInfo.CodecProfileLevel
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.getMimeTypeFromMp4ObjectType
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.formatInvariant
import com.google.common.base.Preconditions
import com.google.common.primitives.UnsignedBytes
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

@SuppressLint("InlinedApi")
object CodecSpecificDataUtil {
    private val NAL_START_CODE = byteArrayOf(0, 0, 0, 1)
    private val HEVC_GENERAL_PROFILE_SPACE_STRINGS: Array<String> =
        arrayOf<String>("", "A", "B", "C")

    // MP4V-ES
    private const val VISUAL_OBJECT_LAYER = 1
    private const val VISUAL_OBJECT_LAYER_START = 0x20
    private const val EXTENDED_PAR = 0x0F
    private const val RECTANGULAR = 0x00

    // VP9 constants from VP9 Bitstream Specification.
    private const val VP9_FRAME_MARKER = 2
    private const val VP9_FRAME_TYPE_KEY_FRAME = 0
    private const val VP9_SYNC_CODE = 0x498342
    private const val VP9_COLOR_SPACE_RGB = 7
    private const val VP9_CHROMA_SUBSAMPLING_420 = 0
    private const val VP9_CHROMA_SUBSAMPLING_422 = 2
    private const val VP9_CHROMA_SUBSAMPLING_444 = 3
    private const val VP9_MAX_UNCOMPRESSED_HEADER_SIZE_BYTES = 128

    // IAMF OBU types.
    private const val OBU_IA_CODEC_CONFIG = 0x00
    private const val OBU_IA_SEQUENCE_HEADER = 0x1F

    // Codecs to constant mappings.
    // H263
    private const val CODEC_ID_H263 = "s263"

    // AVC.
    private const val CODEC_ID_AVC1 = "avc1"
    private const val CODEC_ID_AVC2 = "avc2"

    // VP9
    private const val CODEC_ID_VP09 = "vp09"

    // HEVC.
    private const val CODEC_ID_HEV1 = "hev1"
    private const val CODEC_ID_HVC1 = "hvc1"

    // VVC.
    private const val CODEC_ID_VVC1 = "vvc1"
    private const val CODEC_ID_VVI1 = "vvi1"

    // AV1.
    private const val CODEC_ID_AV01 = "av01"

    // APV.
    private const val CODEC_ID_APV1 = "apv1"

    // MP4A AAC.
    private const val CODEC_ID_MP4A = "mp4a"

    // AC-4
    private const val CODEC_ID_AC4 = "ac-4"

    // IAMF
    private const val CODEC_ID_IAMF = "iamf"

    // TODO(b/482032815): Replace VVC literal constants with MediaCodecInfo.CodecProfileLevel
    // constants when compile SDK is updated to 37.
    private const val VVC_PROFILE_MAIN_8 = 0x01
    private const val VVC_PROFILE_MAIN_10 = 0x02
    private const val VVC_PROFILE_MAIN_10_STILL = 0x04
    private const val VVC_PROFILE_MAIN_10_HDR10 = 0x1000

    private const val VVC_MAIN_TIER_LEVEL_1_0 = 0x01
    private const val VVC_MAIN_TIER_LEVEL_2_0 = 0x02
    private const val VVC_MAIN_TIER_LEVEL_2_1 = 0x04
    private const val VVC_MAIN_TIER_LEVEL_3_0 = 0x08
    private const val VVC_MAIN_TIER_LEVEL_3_1 = 0x10
    private const val VVC_MAIN_TIER_LEVEL_4_0 = 0x20
    private const val VVC_HIGH_TIER_LEVEL_4_0 = 0x40
    private const val VVC_MAIN_TIER_LEVEL_4_1 = 0x80
    private const val VVC_HIGH_TIER_LEVEL_4_1 = 0x100
    private const val VVC_MAIN_TIER_LEVEL_5_0 = 0x200
    private const val VVC_HIGH_TIER_LEVEL_5_0 = 0x400
    private const val VVC_MAIN_TIER_LEVEL_5_1 = 0x800
    private const val VVC_HIGH_TIER_LEVEL_5_1 = 0x1000
    private const val VVC_MAIN_TIER_LEVEL_5_2 = 0x2000
    private const val VVC_HIGH_TIER_LEVEL_5_2 = 0x4000
    private const val VVC_MAIN_TIER_LEVEL_6_0 = 0x8000
    private const val VVC_HIGH_TIER_LEVEL_6_0 = 0x10000
    private const val VVC_MAIN_TIER_LEVEL_6_1 = 0x20000
    private const val VVC_HIGH_TIER_LEVEL_6_1 = 0x40000
    private const val VVC_MAIN_TIER_LEVEL_6_2 = 0x80000
    private const val VVC_HIGH_TIER_LEVEL_6_2 = 0x100000
    private const val VVC_MAIN_TIER_LEVEL_6_3 = 0x200000
    private const val VVC_HIGH_TIER_LEVEL_6_3 = 0x400000

    private val PROFILE_PATTERN: Pattern = Pattern.compile("^\\D?(\\d+)$")

    private const val TAG = "CodecSpecificDataUtil"

    fun parseAlacAudioSpecificConfig(audioSpecificConfig: ByteArray): IntArray {
        val byteArray = ParsableByteArray(audioSpecificConfig)
        byteArray.setPosition(5)
        val bitDepth = byteArray.readUnsignedByte()
        byteArray.setPosition(9)
        val channelCount = byteArray.readUnsignedByte()
        byteArray.setPosition(20)
        val sampleRate = byteArray.readUnsignedIntToInt()
        return intArrayOf(sampleRate, channelCount, bitDepth)
    }

    fun buildCea708InitializationData(isWideAspectRatio: Boolean): MutableList<ByteArray> {
        return mutableListOf<ByteArray>(if (isWideAspectRatio) byteArrayOf(1) else byteArrayOf(0))
    }

    fun buildIamfCodecString(initializationData: ByteArray): String? {
        val parsableByteArray = ParsableByteArray(initializationData)

        var iaSequenceHeader: String? = null
        var codecConfigCodecId: String? = null

        while (parsableByteArray.bytesLeft() > 0
            && (iaSequenceHeader == null || codecConfigCodecId == null)
        ) {
            // OBUHeader
            // obu_type (5 bits) + obu_redundant_copy (1 bit) + obu_trimming_status_flag  (1 bit) +
            // obu_extension_flag (1 bit)
            val obuHeaderByte = parsableByteArray.readUnsignedByte()
            val obuType = obuHeaderByte shr 3 // obu_type (5 bits)
            // skip obu_redundant_copy (1 bit)
            val obuTrimmingStatusFlag = (obuHeaderByte and 0x2) != 0 // obu_trimming_status (1 bit)
            val obuExtensionFlag = (obuHeaderByte and 0x1) != 0 // obu_extension (1 bit)

            val obuSize = parsableByteArray.readUnsignedLeb128ToInt() // obu_size

            if ((obuType > 4 && obuType < 24) && obuTrimmingStatusFlag) {
                parsableByteArray.skipLeb128() // num_samples_to_trim_at_end
                parsableByteArray.skipLeb128() // num_samples_to_trim_at_start
            }
            if (obuExtensionFlag) {
                val extensionHeaderSize =
                    parsableByteArray.readUnsignedLeb128ToInt() // extension_header_size
                parsableByteArray.skipBytes(extensionHeaderSize) // extension_header_bytes
            }

            val nextObuPosition = parsableByteArray.getPosition() + obuSize
            if (obuType == OBU_IA_SEQUENCE_HEADER) { // OBU_IA_Sequence_Header
                // IASequenceHeaderOBU
                parsableByteArray.skipBytes(4) // ia_code (4 bytes)
                val primaryProfile =
                    parsableByteArray.readUnsignedByte() // primary_profile (1 byte)
                val additionalProfile =
                    parsableByteArray.readUnsignedByte() // additional_profile (1 byte)
                iaSequenceHeader =
                    formatInvariant("iamf.%03X.%03X", primaryProfile, additionalProfile)
            } else if (obuType == OBU_IA_CODEC_CONFIG) { // OBU_IA_Codec_Config
                // CodecConfigOBU
                parsableByteArray.skipLeb128() // codec_config_id
                codecConfigCodecId = parsableByteArray.readString(4) // codec_id (4 bytes)

                if (codecConfigCodecId == CODEC_ID_MP4A) {
                    parsableByteArray.skipLeb128() // num_samples_per_frame
                    parsableByteArray.skipBytes(2) // audio_roll_distance (2 bytes)

                    val decoderConfigBitArray = ParsableBitArray()
                    decoderConfigBitArray.reset(parsableByteArray)
                    var audioObjectType = decoderConfigBitArray.readBits(5)
                    // If audioObjectType is an escape value, then we need to read extra 6 bits.
                    // Refer to ISO/IEC 14496-3 (2005) Table 1.14 for more details.
                    if (audioObjectType == 0x1F) {
                        audioObjectType = 32 + decoderConfigBitArray.readBits(6)
                    }
                    codecConfigCodecId += ".40." + audioObjectType
                }
            }

            parsableByteArray.setPosition(nextObuPosition)
        }

        if (iaSequenceHeader != null && codecConfigCodecId != null) {
            return iaSequenceHeader + "." + codecConfigCodecId
        } else {
            return null
        }
    }

    fun parseCea708InitializationData(initializationData: List<ByteArray>): Boolean {
        return initializationData.size == 1 && initializationData[0]!!.size == 1 && initializationData.get(
            0
        )!![0].toInt() == 1
    }

    fun buildVp9CodecPrivateInitializationData(
        profile: Byte, level: Byte, bitDepth: Byte, chromaSubsampling: Byte
    ): List<ByteArray> {
        val profileId: Byte = 0x01
        val levelId: Byte = 0x02
        val bitDepthId: Byte = 0x03
        val chromaSubsamplingId: Byte = 0x04
        val length: Byte = 0x01
        return listOf(
            byteArrayOf(
                profileId, length, profile,
                levelId, length, level,
                bitDepthId, length, bitDepth,
                chromaSubsamplingId, length, chromaSubsampling
            )
        )
    }

    fun buildVp9CodecPrivateFromUncompressedHeader(sample: ByteBuffer): ByteArray? {
        val headerSize = min(sample.remaining(), VP9_MAX_UNCOMPRESSED_HEADER_SIZE_BYTES)
        val data = ByteArray(headerSize)
        sample.get(data,  0, headerSize)
        val bits = ParsableBitArray(data)
        // The uncompressed header should be at least 5 bytes to contain the profile, bit depth,
        // and chroma subsampling.
        Preconditions.checkState(sample.remaining() >= 5)

        // First 2 bits of the uncompressed header should be the frame_marker.
        if (bits.readBits(2) != VP9_FRAME_MARKER) {
            return null
        }

        // profile_low_bit (1 bit), profile_high_bit (1 bit)
        val profileLowBit = bits.readBits(1)
        val profileHighBit = bits.readBits(1)
        val profile = (profileHighBit shl 1) or profileLowBit

        // One reserved '0' bit if profile is 3.
        if (profile == 3) {
            if (bits.bitsLeft() < 1 || bits.readBit()) {
                return null
            }
        }

        // If show_existing_frame is set, we get no more data. Since this is
        // expected to be the first frame, we can return null, indicating a malformed stream.
        if (bits.readBit()) { // show_existing_frame
            return null
        }

        val frameType = bits.readBits(1)
        val showFrame = bits.readBit()
        val errorResilientMode = bits.readBit()

        var bitDepth = 8
        var chromaSubsampling = VP9_CHROMA_SUBSAMPLING_420

        val isKeyFrame = frameType == VP9_FRAME_TYPE_KEY_FRAME
        var canParseHeader = isKeyFrame
        var colorConfigPresent = isKeyFrame

        if (!isKeyFrame) {
            var intraOnly = 0
            if (!showFrame) {
                intraOnly = bits.readBits(1)
            }
            if (!errorResilientMode) {
                bits.skipBits(2) // reset_frame_context
            }

            if (intraOnly != 0) {
                canParseHeader = true
                // color_config() is only present for intra-only frames if profile > 0.
                colorConfigPresent = profile > 0
            }
        }

        if (!canParseHeader) {
            return null
        }

        if (bits.readBits(24) != VP9_SYNC_CODE) {
            return null
        }

        if (colorConfigPresent) {
            val results = getVp9BitdepthChromaSubSampling(bits, profile)
            if (results == null) {
                return null
            }
            bitDepth = results.first!!
            chromaSubsampling = results.second!!
        } else {
            // For intra-only, profile 0 frames, color config is not present. The default
            // bitDepth (8) and chromaSubsampling (4:2:0) are used.
            bitDepth = 8
            chromaSubsampling = VP9_CHROMA_SUBSAMPLING_420
        }

        // Level 0 signals that the level is unknown/unspecified.
        return buildVp9CodecPrivateInitializationData(
            profile.toByte(),  0.toByte(), bitDepth.toByte(), chromaSubsampling.toByte()
        )[0]
    }

    private fun getVp9BitdepthChromaSubSampling(
        bits: ParsableBitArray, profile: Int
    ): Pair<Int?, Int?>? {
        val bitDepth: Int
        if (profile >= 2) {
            bitDepth = if (bits.readBit()) 12 else 10
        } else {
            bitDepth = 8
        }
        val colorSpace = bits.readBits(3)
        var chromaSubsampling = VP9_CHROMA_SUBSAMPLING_420

        if (colorSpace != VP9_COLOR_SPACE_RGB) {
            bits.skipBits(1) // yuv_range_flag
            if (profile == 1 || profile == 3) {
                val ssX = bits.readBits(1)
                val ssY = bits.readBits(1)
                // From bitstream chroma subsampling to MP4 equivalent chroma subsampling conversion.
                if (ssX == 1 && ssY == 1) {
                    return null // ERROR: 4:2:0 is invalid for Profiles 1 & 3
                } else if (ssX == 1 && ssY == 0) {
                    chromaSubsampling = VP9_CHROMA_SUBSAMPLING_422
                } else if (ssX == 0 && ssY == 0) {
                    chromaSubsampling = VP9_CHROMA_SUBSAMPLING_444
                } else {
                    // ssX == 0 && ssY == 1 (4:4:0) is not supported in VP9 ISO MP4.
                    return null
                }
            } else {
                chromaSubsampling = VP9_CHROMA_SUBSAMPLING_420
            }
        } else {
            chromaSubsampling = VP9_CHROMA_SUBSAMPLING_444
        }
        return Pair.create<Int?, Int?>(bitDepth, chromaSubsampling)
    }

    fun getVorbisInitializationData(format: Format): ByteBuffer {
        Preconditions.checkArgument(
            format.initializationData.size > 1, "csd-0 and csd-1 must be present for Vorbis."
        )

        val identificationHeader = format.initializationData[0]
        val setupHeader = format.initializationData.get(1)
        val commentHeader =
            byteArrayOf(
                3,
                'v'.code.toByte(),
                'o'.code.toByte(),
                'r'.code.toByte(),
                'b'.code.toByte(),
                'i'.code.toByte(),
                's'.code.toByte(),
                7,
                0,
                0,
                0,
                'a'.code.toByte(),
                'n'.code.toByte(),
                'd'.code.toByte(),
                'r'.code.toByte(),
                'o'.code.toByte(),
                'i'.code.toByte(),
                'd'.code.toByte(),
                0,
                0,
                0,
                0,
                1
            )

        val identificationHeaderSize = identificationHeader!!.size
        val commentHeaderSize = commentHeader.size
        val setupHeaderSize = setupHeader!!.size

        val identificationHeaderLaced = xiphLaceEnc(identificationHeaderSize)
        val commentHeaderLaced = xiphLaceEnc(commentHeaderSize)

        val codecPrivateSize =  
            (1
                    + identificationHeaderLaced.size
                    + commentHeaderLaced.size
                    + identificationHeaderSize
                    + commentHeaderSize
                    + setupHeaderSize)

        val codecPrivateBuf = ByteBuffer.allocate(codecPrivateSize)

        codecPrivateBuf.put(0x02.toByte()) // Number of headers - 1 (Id, Comment, Setup)

        // Encode and put Xiph laced lengths
        codecPrivateBuf.put(identificationHeaderLaced)
        codecPrivateBuf.put(commentHeaderLaced)

        codecPrivateBuf.put(identificationHeader)
        codecPrivateBuf.put(commentHeader)
        codecPrivateBuf.put(setupHeader)

        codecPrivateBuf.flip()

        return codecPrivateBuf
    }

    private fun xiphLaceEnc(size: Int): ByteArray {
        val xiphLacedSizeArray = ByteArray((size / 255) + 1)

        Arrays.fill(xiphLacedSizeArray, 0xFF.toByte())

        xiphLacedSizeArray[xiphLacedSizeArray.size - 1] = (size % 255).toByte()
        return xiphLacedSizeArray
    }

    fun buildDolbyVisionInitializationData(profile: Int, level: Int): ByteArray {
        val dolbyVisionCsd = ByteArray(24)
        var blCompatibilityId: Byte = 0x00
        // MD compression is not permitted for profile 7 and earlier. Only some devices
        // support it from profile 8
        var mdCompression: Byte = 0x00
        if (profile == 8) {
            blCompatibilityId = 0x04
        } else if (profile == 9) {
            blCompatibilityId = 0x02
            mdCompression = 0x01
        }

        dolbyVisionCsd[0] = 0x01 // dv_version_major
        dolbyVisionCsd[1] = 0x00 // dv_version_minor
        dolbyVisionCsd[2] = ((profile and 0x7f) shl 1).toByte() // dv_profile
        dolbyVisionCsd[2] =
            ((dolbyVisionCsd[2].toInt() or ((level shr 5) and 0x1)) and 0xff).toByte()
        dolbyVisionCsd[3] = ((level and 0x1f) shl 3).toByte() // dv_level
        dolbyVisionCsd[3] = (dolbyVisionCsd[3].toInt() or (1 shl 2)).toByte() // rpu_present_flag
        dolbyVisionCsd[3] = (dolbyVisionCsd[3].toInt() or (0 shl 1)).toByte() // el_present_flag
        dolbyVisionCsd[3] = (dolbyVisionCsd[3].toInt() or 1).toByte() // bl_present_flag
        dolbyVisionCsd[4] =
            (blCompatibilityId.toInt() shl 4).toByte() // dv_bl_signal_compatibility_id
        dolbyVisionCsd[4] =
            (dolbyVisionCsd[4].toInt() or (mdCompression.toInt() shl 2)).toByte() // dv_md_compression
        return dolbyVisionCsd
    }

    fun getOpusInitializationData(format: Format): ByteArray {
        Preconditions.checkArgument(
            !format.initializationData.isEmpty(),
            "csd-0 must be present for Opus."
        )
        // If csd0 starts with "AOPUSHDR", it indicates a custom CSD structure of:
        // Marker (AOPUSHDR) | Length (Length of OpusIdentificationHeader) |
        // OpusIdentificationHeader("OpusHead" + data).
        //     8 bytes       |                    8 bytes                  |   Length no. of bytes
        // Reference:
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/media/module/foundation/include/media/stagefright/foundation/OpusHeader.h;l=28
        val aopushdrSignatureLength = 8
        val opusHeadSignatureLength = 8
        val csd0 = format.initializationData[0]
        Preconditions.checkArgument(csd0!!.size >= opusHeadSignatureLength)
        val parsableCsd0 = ParsableByteArray(csd0)
        var payloadOffset = 0
        var payloadLength = csd0.size
        val csd0SignatureString = parsableCsd0.readString(aopushdrSignatureLength)
        if (csd0SignatureString == "AOPUSHDR") {
            // The offset of the OpusIdentificationHeader data within the "AOPUSHDR" custom CSD.
            val opusIdentificationHeaderOffset = 16
            Preconditions.checkArgument(csd0.size >= opusIdentificationHeaderOffset + opusHeadSignatureLength)
            val identificationHeaderLength = parsableCsd0.readLittleEndianLong()
            Preconditions.checkArgument(opusIdentificationHeaderOffset + identificationHeaderLength <= csd0.size)
            payloadOffset = opusIdentificationHeaderOffset
            payloadLength = identificationHeaderLength.toInt()
        } else {
            // Otherwise, the OpusIdentificationHeader must start with "OpusHead" signature.
            Preconditions.checkArgument(csd0SignatureString == "OpusHead")
        }
        return Arrays.copyOfRange(csd0, payloadOffset, payloadOffset + payloadLength)
    }

    fun getVideoResolutionFromMpeg4VideoConfig(
        videoSpecificConfig: ByteArray
    ): Pair<Int?, Int?>? {
        var offset = 0
        var foundVOL = false
        val scratchBytes = ParsableByteArray(videoSpecificConfig)
        while (offset + 3 < videoSpecificConfig.size) {
            if (scratchBytes.readUnsignedInt24() != VISUAL_OBJECT_LAYER
                || (videoSpecificConfig[offset + 3].toInt() and 0xF0) != VISUAL_OBJECT_LAYER_START
            ) {
                scratchBytes.setPosition(scratchBytes.getPosition() - 2)
                offset++
                continue
            }
            foundVOL = true
            break
        }

        Preconditions.checkArgument(foundVOL, "Invalid input: VOL not found.")

        val scratchBits = ParsableBitArray(videoSpecificConfig)
        // Skip the start codecs from the bitstream
        scratchBits.skipBits((offset + 4) * 8)
        scratchBits.skipBits(1) // random_accessible_vol
        scratchBits.skipBits(8) // video_object_type_indication

        if (scratchBits.readBit()) { // object_layer_identifier
            scratchBits.skipBits(4) // video_object_layer_verid
            scratchBits.skipBits(3) // video_object_layer_priority
        }

        val aspectRatioInfo = scratchBits.readBits(4)
        if (aspectRatioInfo == EXTENDED_PAR) {
            scratchBits.skipBits(8) // par_width
            scratchBits.skipBits(8) // par_height
        }

        if (scratchBits.readBit()) { // vol_control_parameters
            scratchBits.skipBits(2) // chroma_format
            scratchBits.skipBits(1) // low_delay
            if (scratchBits.readBit()) { // vbv_parameters
                scratchBits.skipBits(79)
            }
        }

        val videoObjectLayerShape = scratchBits.readBits(2)
        Preconditions.checkArgument(
            videoObjectLayerShape == RECTANGULAR,
            "Only supports rectangular video object layer shape."
        )

        Preconditions.checkArgument(scratchBits.readBit()) // marker_bit
        var vopTimeIncrementResolution = scratchBits.readBits(16)
        Preconditions.checkArgument(scratchBits.readBit()) // marker_bit

        if (scratchBits.readBit()) { // fixed_vop_rate
            Preconditions.checkArgument(vopTimeIncrementResolution > 0)
            vopTimeIncrementResolution--
            var numBitsToSkip = 0
            while (vopTimeIncrementResolution > 0) {
                numBitsToSkip++
                vopTimeIncrementResolution = vopTimeIncrementResolution shr 1
            }
            scratchBits.skipBits(numBitsToSkip) // fixed_vop_time_increment
        }

        Preconditions.checkArgument(scratchBits.readBit()) // marker_bit
        val videoObjectLayerWidth = scratchBits.readBits(13)
        Preconditions.checkArgument(scratchBits.readBit()) // marker_bit
        val videoObjectLayerHeight = scratchBits.readBits(13)
        Preconditions.checkArgument(scratchBits.readBit()) // marker_bit

        scratchBits.skipBits(1) // interlaced

        return Pair.create<Int?, Int?>(videoObjectLayerWidth, videoObjectLayerHeight)
    }

    fun buildAvcCodecString(
        profileIdc: Int, constraintsFlagsAndReservedZero2Bits: Int, levelIdc: Int
    ): String {
        return String.format(
            "avc1.%02X%02X%02X", profileIdc, constraintsFlagsAndReservedZero2Bits, levelIdc
        )
    }

    fun buildHevcCodecString(
        generalProfileSpace: Int,
        generalTierFlag: Boolean,
        generalProfileIdc: Int,
        generalProfileCompatibilityFlags: Int,
        constraintBytes: IntArray,
        generalLevelIdc: Int
    ): String {
        val builder =
            StringBuilder(
                formatInvariant(
                    "hvc1.%s%d.%X.%c%d",
                    HEVC_GENERAL_PROFILE_SPACE_STRINGS[generalProfileSpace],
                    generalProfileIdc,
                    generalProfileCompatibilityFlags,
                    if (generalTierFlag) 'H' else 'L',
                    generalLevelIdc
                )
            )
        // Omit trailing zero bytes.
        var trailingZeroIndex = constraintBytes.size
        while (trailingZeroIndex > 0 && constraintBytes[trailingZeroIndex - 1] == 0) {
            trailingZeroIndex--
        }
        for (i in 0..<trailingZeroIndex) {
            builder.append(String.format(".%02X", constraintBytes[i]))
        }
        return builder.toString()
    }

    fun buildApvCodecString(initializationData: ByteArray): String {
        Preconditions.checkArgument(
            initializationData.size >= 17, "Invalid APV CSD length: %s", initializationData.size
        )
        Preconditions.checkArgument(
            initializationData[0].toInt() == 0x01,
            "Invalid APV CSD version: %s",
            initializationData[0].toInt()
        ) // configurationVersion == 1

        val profile = UnsignedBytes.toInt(initializationData[5])
        val level = UnsignedBytes.toInt(initializationData[6])
        val band = UnsignedBytes.toInt(initializationData[7])
        return formatInvariant("apv1.apvf%d.apvl%d.apvb%d", profile, level, band)
    }

    fun buildH263CodecString(profile: Int, level: Int): String {
        return formatInvariant("s263.%d.%d", profile, level)
    }

    fun buildDolbyVisionCodecString(profile: Int, level: Int): String {
        if (profile > 9) {
            return formatInvariant("dvh1.%02d.%02d", profile, level)
        } else if (profile > 8) {
            return formatInvariant("dvav.%02d.%02d", profile, level)
        } else {
            return formatInvariant("dvhe.%02d.%02d", profile, level)
        }
    }

    // TODO: b/492523731 - Consider moving this and related methods back to MediaCodecUtil.
    fun getCodecProfileAndLevel(format: Format): Pair<Int?, Int?>? {
        val mediaCodecProfileAndLevel = getMediaCodecProfileAndLevel(format)
        if (mediaCodecProfileAndLevel != null
            && mediaCodecProfileAndLevel.isSupportableByMediaCodec
        ) {
            return Pair<Int?, Int?>(
                mediaCodecProfileAndLevel.getProfile(), mediaCodecProfileAndLevel.getLevel()
            )
        } else {
            return null
        }
    }

    fun getMediaCodecProfileAndLevel(format: Format): MediaCodecProfileAndLevel? {
        if (format.codecs == null) {
            return null
        }
        val parts =
            format.codecs.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        // Dolby Vision can use DV, AVC or HEVC codec IDs, so check the MIME type first.
        if (MimeTypes.VIDEO_DOLBY_VISION == format.sampleMimeType) {
            return getDolbyVisionProfileAndLevel(format.codecs, parts)
        }
        when (parts[0]) {
            CODEC_ID_H263 -> return getH263ProfileAndLevel(format.codecs, parts)
            CODEC_ID_AVC1, CODEC_ID_AVC2 -> return getAvcProfileAndLevel(format.codecs, parts)
            CODEC_ID_VP09 -> return getVp9ProfileAndLevel(format.codecs, parts)
            CODEC_ID_HEV1, CODEC_ID_HVC1 -> return getHevcProfileAndLevel(
                format.codecs,
                parts,
                format.colorInfo
            )

            CODEC_ID_VVC1, CODEC_ID_VVI1 -> return getVvcProfileAndLevel(
                format.codecs,
                parts,
                format.colorInfo
            )

            CODEC_ID_AV01 -> return getAv1ProfileAndLevel(format.codecs, parts, format.colorInfo)
            CODEC_ID_APV1 -> return getApvProfileAndLevel(format.codecs, parts)
            CODEC_ID_MP4A -> return getAacCodecProfileAndLevel(format.codecs, parts)
            CODEC_ID_AC4 -> return getAc4CodecProfileAndLevel(format.codecs, parts)
            CODEC_ID_IAMF -> return getIamfCodecProfileAndLevel(format.codecs, parts)
            else -> return null
        }
    }

    private fun getVvcProfileAndLevel(
        codec: String?, parts: Array<String>, colorInfo: ColorInfo?
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 3) {
            w(TAG, "Ignoring malformed VVC codec string: " + codec)
            return null
        }

        val profileIdc: Int
        try {
            profileIdc = parts[1].toInt()
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed VVC codec string: " + codec)
            return null
        }

        val profile: Int
        if (profileIdc == 1) {
            if (colorInfo != null && colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) {
                profile = VVC_PROFILE_MAIN_10_HDR10
            } else if (colorInfo != null && colorInfo.lumaBitdepth == 8) {
                profile = VVC_PROFILE_MAIN_8
            } else {
                profile = VVC_PROFILE_MAIN_10
            }
        } else if (profileIdc == 65) {
            profile = VVC_PROFILE_MAIN_10_STILL
        } else {
            w(TAG, "Unknown VVC profile IDC: " + parts[1])
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }

        val levelString = parts[2]
        val level = vvcCodecStringToProfileLevel(levelString)
        if (level == null) {
            w(TAG, "Unknown VVC level string: " + levelString)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    private fun vvcCodecStringToProfileLevel(codecString: String): Int? {
        when (codecString) {
            "L16" -> return VVC_MAIN_TIER_LEVEL_1_0
            "L32" -> return VVC_MAIN_TIER_LEVEL_2_0
            "L35" -> return VVC_MAIN_TIER_LEVEL_2_1
            "L48" -> return VVC_MAIN_TIER_LEVEL_3_0
            "L51" -> return VVC_MAIN_TIER_LEVEL_3_1
            "L64" -> return VVC_MAIN_TIER_LEVEL_4_0
            "H64" -> return VVC_HIGH_TIER_LEVEL_4_0
            "L67" -> return VVC_MAIN_TIER_LEVEL_4_1
            "H67" -> return VVC_HIGH_TIER_LEVEL_4_1
            "L80" -> return VVC_MAIN_TIER_LEVEL_5_0
            "H80" -> return VVC_HIGH_TIER_LEVEL_5_0
            "L83" -> return VVC_MAIN_TIER_LEVEL_5_1
            "H83" -> return VVC_HIGH_TIER_LEVEL_5_1
            "L86" -> return VVC_MAIN_TIER_LEVEL_5_2
            "H86" -> return VVC_HIGH_TIER_LEVEL_5_2
            "L96" -> return VVC_MAIN_TIER_LEVEL_6_0
            "H96" -> return VVC_HIGH_TIER_LEVEL_6_0
            "L112" -> return VVC_MAIN_TIER_LEVEL_6_1
            "H112" -> return VVC_HIGH_TIER_LEVEL_6_1
            "L128" -> return VVC_MAIN_TIER_LEVEL_6_2
            "H128" -> return VVC_HIGH_TIER_LEVEL_6_2
            "L144" -> return VVC_MAIN_TIER_LEVEL_6_3
            "H144" -> return VVC_HIGH_TIER_LEVEL_6_3
            else -> return null
        }
    }

    fun getHevcProfileAndLevel(
        codec: String?, parts: Array<String>, colorInfo: ColorInfo?
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 4) {
            // The codec has fewer parts than required by the HEVC codec string format.
            w(TAG, "Ignoring malformed HEVC codec string: " + codec)
            return null
        }
        // The profile_space gets ignored.
        val matcher = PROFILE_PATTERN.matcher(parts[1])
        if (!matcher.matches()) {
            w(TAG, "Ignoring malformed HEVC codec string: " + codec)
            return null
        }
        val profileString = matcher.group(1)
        val profile: Int
        if ("1" == profileString) {
            profile = CodecProfileLevel.HEVCProfileMain
        } else if ("2" == profileString) {
            if (colorInfo != null && colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) {
                profile = CodecProfileLevel.HEVCProfileMain10HDR10
            } else {
                // For all other cases, we map to the Main10 profile. Note that this includes HLG
                // HDR. On Android 13+, the platform guarantees that a decoder that advertises
                // HEVCProfileMain10 will be able to decode HLG. This is not guaranteed for older
                // Android versions, but we still map to Main10 for backwards compatibility.
                profile = CodecProfileLevel.HEVCProfileMain10
            }
        } else {
            w(TAG, "Unknown HEVC profile string: " + profileString)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val levelString: String? = parts[3]
        val level = CodecSpecificDataUtil.hevcCodecStringToProfileLevel(levelString!!)
        if (level == null) {
            w(TAG, "Unknown HEVC level string: " + levelString)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    fun buildNalUnit(data: ByteArray, offset: Int, length: Int): ByteArray {
        val nalUnit = ByteArray(length + NAL_START_CODE.size)
        System.arraycopy(NAL_START_CODE, 0, nalUnit, 0, NAL_START_CODE.size)
        System.arraycopy(data, offset, nalUnit, NAL_START_CODE.size, length)
        return nalUnit
    }

    fun splitNalUnits(data: ByteArray): Array<ByteArray?>? {
        if (!isNalStartCode(data, 0)) {
            // data does not consist of NAL start code delimited units.
            return null
        }
        val starts: MutableList<Int?> = ArrayList<Int?>()
        var nalUnitIndex = 0
        do {
            starts.add(nalUnitIndex)
            nalUnitIndex = findNalStartCode(data, nalUnitIndex + NAL_START_CODE.size)
        } while (nalUnitIndex != C.INDEX_UNSET)
        val split = arrayOfNulls<ByteArray>(starts.size)
        for (i in starts.indices) {
            val startIndex: Int = starts[i]!!
            val endIndex: Int = (if (i < starts.size - 1) starts.get(i + 1) else data.size)!!
            val nal = ByteArray(endIndex - startIndex)
            System.arraycopy(data, startIndex, nal, 0, nal.size)
            split[i] = nal
        }
        return split
    }

    fun dolbyVisionConstantToLevelNumber(levelConstant: Int): Int {
        when (levelConstant) {
            CodecProfileLevel.DolbyVisionLevelHd24 -> return 1
            CodecProfileLevel.DolbyVisionLevelHd30 -> return 2
            CodecProfileLevel.DolbyVisionLevelFhd24 -> return 3
            CodecProfileLevel.DolbyVisionLevelFhd30 -> return 4
            CodecProfileLevel.DolbyVisionLevelFhd60 -> return 5
            CodecProfileLevel.DolbyVisionLevelUhd24 -> return 6
            CodecProfileLevel.DolbyVisionLevelUhd30 -> return 7
            CodecProfileLevel.DolbyVisionLevelUhd48 -> return 8
            CodecProfileLevel.DolbyVisionLevelUhd60 -> return 9
            CodecProfileLevel.DolbyVisionLevelUhd120 -> return 10
            CodecProfileLevel.DolbyVisionLevel8k30 -> return 11
            CodecProfileLevel.DolbyVisionLevel8k60 -> return 12
            0x1000 -> return 13
            else -> throw IllegalArgumentException("Unknown Dolby Vision level: " + levelConstant)
        }
    }

    fun dolbyVisionConstantToProfileNumber(profileConstant: Int): Int {
        when (profileConstant) {
            CodecProfileLevel.DolbyVisionProfileDvavPer -> return 0
            CodecProfileLevel.DolbyVisionProfileDvavPen -> return 1
            CodecProfileLevel.DolbyVisionProfileDvheDer -> return 2
            CodecProfileLevel.DolbyVisionProfileDvheDen -> return 3
            CodecProfileLevel.DolbyVisionProfileDvheDtr -> return 4
            CodecProfileLevel.DolbyVisionProfileDvheStn -> return 5
            CodecProfileLevel.DolbyVisionProfileDvheDth -> return 6
            CodecProfileLevel.DolbyVisionProfileDvheDtb -> return 7
            CodecProfileLevel.DolbyVisionProfileDvheSt -> return 8
            CodecProfileLevel.DolbyVisionProfileDvavSe -> return 9
            CodecProfileLevel.DolbyVisionProfileDvav110 -> return 10
            else -> throw IllegalArgumentException("Unknown Dolby Vision profile: " + profileConstant)
        }
    }

    fun calculateMediaCodecDolbyVisionLevel(width: Int, height: Int, frameRate: Float): Int {
        val maxWidthHeight = max(width, height)
        Preconditions.checkState(maxWidthHeight <= 7680)
        val pixelRate = width * height * frameRate

        var level = -1
        if (maxWidthHeight <= 1280) {
            if (pixelRate <= 22118400) {
                level = CodecProfileLevel.DolbyVisionLevelHd24 // Level 01
            } else { // pixelRate <= 27_648_000
                level = CodecProfileLevel.DolbyVisionLevelHd30 // Level 02
            }
        } else if (maxWidthHeight <= 1920 && pixelRate <= 49766400) {
            level = CodecProfileLevel.DolbyVisionLevelFhd24 // Level 03
        } else if (maxWidthHeight <= 2560 && pixelRate <= 62208000) {
            level = CodecProfileLevel.DolbyVisionLevelFhd30 // Level 04
        } else if (maxWidthHeight <= 3840) {
            if (pixelRate <= 124416000) {
                level = CodecProfileLevel.DolbyVisionLevelFhd60 // Level 05
            } else if (pixelRate <= 199065600) {
                level = CodecProfileLevel.DolbyVisionLevelUhd24 // Level 06
            } else if (pixelRate <= 248832000) {
                level = CodecProfileLevel.DolbyVisionLevelUhd30 // Level 07
            } else if (pixelRate <= 398131200) {
                level = CodecProfileLevel.DolbyVisionLevelUhd48 // Level 08
            } else if (pixelRate <= 497664000) {
                level = CodecProfileLevel.DolbyVisionLevelUhd60 // Level 09
            } else { // pixelRate <= 995_328_000
                level = CodecProfileLevel.DolbyVisionLevelUhd120 // Level 10
            }
        } else if (maxWidthHeight <= 7680) {
            if (pixelRate <= 995328000) {
                level = CodecProfileLevel.DolbyVisionLevel8k30 // Level 11
            } else { // pixelRate <= 1_990_656_000
                level = CodecProfileLevel.DolbyVisionLevel8k60 // Level 12
            }
        }

        return level
    }

    private fun findNalStartCode(data: ByteArray, index: Int): Int {
        val endIndex = data.size - NAL_START_CODE.size
        for (i in index..endIndex) {
            if (isNalStartCode(data, i)) {
                return i
            }
        }
        return C.INDEX_UNSET
    }

    private fun isNalStartCode(data: ByteArray, index: Int): Boolean {
        if (data.size - index <= NAL_START_CODE.size) {
            return false
        }
        for (j in NAL_START_CODE.indices) {
            if (data[index + j] != NAL_START_CODE[j]) {
                return false
            }
        }
        return true
    }

    private fun getDolbyVisionProfileAndLevel(
        codec: String?, parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 3) {
            // The codec has fewer parts than required by the Dolby Vision codec string format.
            w(TAG, "Ignoring malformed Dolby Vision codec string: " + codec)
            return null
        }
        // The profile_space gets ignored.
        val matcher = PROFILE_PATTERN.matcher(parts[1])
        if (!matcher.matches()) {
            w(TAG, "Ignoring malformed Dolby Vision codec string: " + codec)
            return null
        }
        val profileString = matcher.group(1)
        val profile = dolbyVisionStringToProfile(profileString)
        if (profile == null) {
            w(TAG, "Unknown Dolby Vision profile string: " + profileString)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val levelString = parts[2]
        val level = dolbyVisionStringToLevel(levelString)
        if (level == null) {
            w(TAG, "Unknown Dolby Vision level string: " + levelString)
            return null
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    fun getDolbyVisionBaseLayerMimeType(format: Format): String? {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) {
            return null
        }
        val codecProfileAndLevel = getCodecProfileAndLevel(format)
        if (codecProfileAndLevel == null) {
            return null
        }
        when (codecProfileAndLevel.first) {
            CodecProfileLevel.DolbyVisionProfileDvheDtr, CodecProfileLevel.DolbyVisionProfileDvheStn, CodecProfileLevel.DolbyVisionProfileDvheSt -> return MimeTypes.VIDEO_H265
            CodecProfileLevel.DolbyVisionProfileDvavSe -> return MimeTypes.VIDEO_H264
            CodecProfileLevel.DolbyVisionProfileDvav110 -> return MimeTypes.VIDEO_AV1
            else -> return null
        }
    }

    private fun getH263ProfileAndLevel(
        codec: String?,
        parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 3) {
            w(TAG, "Ignoring malformed H263 codec string: " + codec)
            return null
        }

        val profileInteger: Int
        val levelInteger: Int
        try {
            profileInteger = parts[1].toInt()
            levelInteger = parts[2].toInt()
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed H263 codec string: " + codec)
            return null
        }
        val profile = h263ProfileNumberToConst(profileInteger)
        if (profile == -1) {
            w(TAG, "Unknown H263 profile: " + profileInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val level = h263LevelNumberToConst(levelInteger)
        if (level == -1) {
            w(TAG, "Unknown H263 level: " + levelInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    private fun getAvcProfileAndLevel(
        codec: String?,
        parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 2) {
            // The codec has fewer parts than required by the AVC codec string format.
            w(TAG, "Ignoring malformed AVC codec string: " + codec)
            return null
        }
        val profileInteger: Int
        val levelInteger: Int
        try {
            if (parts[1].length == 6) {
                // Format: avc1.xxccyy, where xx is profile and yy level, both hexadecimal.
                profileInteger = parts[1].substring(0, 2).toInt(16)
                levelInteger = parts[1].substring(4).toInt(16)
            } else if (parts.size >= 3) {
                // Format: avc1.xx.[y]yy where xx is profile and [y]yy level, both decimal.
                profileInteger = parts[1].toInt()
                levelInteger = parts[2].toInt()
            } else {
                // We don't recognize the format.
                w(TAG, "Ignoring malformed AVC codec string: " + codec)
                return null
            }
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed AVC codec string: " + codec)
            return null
        }

        val profile = avcProfileNumberToConst(profileInteger)
        if (profile == -1) {
            w(TAG, "Unknown AVC profile: " + profileInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val level = avcLevelNumberToConst(levelInteger)
        if (level == -1) {
            w(TAG, "Unknown AVC level: " + levelInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    private fun getVp9ProfileAndLevel(
        codec: String?,
        parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 3) {
            w(TAG, "Ignoring malformed VP9 codec string: " + codec)
            return null
        }
        val profileInteger: Int
        val levelInteger: Int
        try {
            profileInteger = parts[1].toInt()
            levelInteger = parts[2].toInt()
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed VP9 codec string: " + codec)
            return null
        }

        val profile = vp9ProfileNumberToConst(profileInteger)
        if (profile == -1) {
            w(TAG, "Unknown VP9 profile: " + profileInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val level = vp9LevelNumberToConst(levelInteger)
        if (level == -1) {
            w(TAG, "Unknown VP9 level: " + levelInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    private fun getAv1ProfileAndLevel(
        codec: String?, parts: Array<String>, colorInfo: ColorInfo?
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 4) {
            w(TAG, "Ignoring malformed AV1 codec string: " + codec)
            return null
        }
        val profileInteger: Int
        val levelInteger: Int
        val bitDepthInteger: Int
        try {
            profileInteger = parts[1].toInt()
            levelInteger = parts[2].substring(0, 2).toInt()
            bitDepthInteger = parts[3].toInt()
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed AV1 codec string: " + codec)
            return null
        }

        if (profileInteger != 0) {
            w(TAG, "Unknown AV1 profile: " + profileInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        if (bitDepthInteger != 8 && bitDepthInteger != 10) {
            w(TAG, "Unknown AV1 bit depth: " + bitDepthInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val profile: Int
        if (bitDepthInteger == 8) {
            profile = CodecProfileLevel.AV1ProfileMain8
        } else if (colorInfo != null
            && (colorInfo.hdrStaticInfo != null || colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG || colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084)
        ) {
            profile = CodecProfileLevel.AV1ProfileMain10HDR10
        } else {
            profile = CodecProfileLevel.AV1ProfileMain10
        }

        val level = av1LevelNumberToConst(levelInteger)
        if (level == -1) {
            w(TAG, "Unknown AV1 level: " + levelInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    private fun getApvProfileAndLevel(
        codec: String?,
        parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 4) {
            w(TAG, "Ignoring malformed APV codec string: " + codec)
            return null
        }
        val profileInteger: Int
        val levelInteger: Int
        val bandInteger: Int
        try {
            profileInteger = parts[1].substring(4).toInt()
            levelInteger = parts[2].substring(4).toInt()
            bandInteger = parts[3].substring(4).toInt()
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed APV codec string: " + codec, e)
            return null
        }

        val profile: Int
        if (profileInteger == 33) {
            profile = CodecProfileLevel.APVProfile422_10
        } else if (profileInteger == 44) {
            profile = CodecProfileLevel.APVProfile422_10HDR10Plus
        } else {
            w(TAG, "Unrecognized APV profile: " + profileInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val level = apvLevelAndBandNumberToConst(levelInteger, bandInteger)
        if (level == -1) {
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    private fun apvLevelAndBandNumberToConst(levelNumber: Int, bandNumber: Int): Int {
        // levelInteger is 30x larger than the decimal level value, as defined by
        // https://www.ietf.org/archive/id/draft-lim-apv-02.html#_table-levels
        when (levelNumber) {
            30 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel1Band0
                1 -> return CodecProfileLevel.APVLevel1Band1
                2 -> return CodecProfileLevel.APVLevel1Band2
                3 -> return CodecProfileLevel.APVLevel1Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            33 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel11Band0
                1 -> return CodecProfileLevel.APVLevel11Band1
                2 -> return CodecProfileLevel.APVLevel11Band2
                3 -> return CodecProfileLevel.APVLevel11Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            60 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel2Band0
                1 -> return CodecProfileLevel.APVLevel2Band1
                2 -> return CodecProfileLevel.APVLevel2Band2
                3 -> return CodecProfileLevel.APVLevel2Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            63 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel21Band0
                1 -> return CodecProfileLevel.APVLevel21Band1
                2 -> return CodecProfileLevel.APVLevel21Band2
                3 -> return CodecProfileLevel.APVLevel21Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            90 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel3Band0
                1 -> return CodecProfileLevel.APVLevel3Band1
                2 -> return CodecProfileLevel.APVLevel3Band2
                3 -> return CodecProfileLevel.APVLevel3Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            93 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel31Band0
                1 -> return CodecProfileLevel.APVLevel31Band1
                2 -> return CodecProfileLevel.APVLevel31Band2
                3 -> return CodecProfileLevel.APVLevel31Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            120 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel4Band0
                1 -> return CodecProfileLevel.APVLevel4Band1
                2 -> return CodecProfileLevel.APVLevel4Band2
                3 -> return CodecProfileLevel.APVLevel4Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            123 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel41Band0
                1 -> return CodecProfileLevel.APVLevel41Band1
                2 -> return CodecProfileLevel.APVLevel41Band2
                3 -> return CodecProfileLevel.APVLevel41Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            150 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel5Band0
                1 -> return CodecProfileLevel.APVLevel5Band1
                2 -> return CodecProfileLevel.APVLevel5Band2
                3 -> return CodecProfileLevel.APVLevel5Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            153 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel51Band0
                1 -> return CodecProfileLevel.APVLevel51Band1
                2 -> return CodecProfileLevel.APVLevel51Band2
                3 -> return CodecProfileLevel.APVLevel51Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            180 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel6Band0
                1 -> return CodecProfileLevel.APVLevel6Band1
                2 -> return CodecProfileLevel.APVLevel6Band2
                3 -> return CodecProfileLevel.APVLevel6Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            183 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel61Band0
                1 -> return CodecProfileLevel.APVLevel61Band1
                2 -> return CodecProfileLevel.APVLevel61Band2
                3 -> return CodecProfileLevel.APVLevel61Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            210 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel7Band0
                1 -> return CodecProfileLevel.APVLevel7Band1
                2 -> return CodecProfileLevel.APVLevel7Band2
                3 -> return CodecProfileLevel.APVLevel7Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            213 -> when (bandNumber) {
                0 -> return CodecProfileLevel.APVLevel71Band0
                1 -> return CodecProfileLevel.APVLevel71Band1
                2 -> return CodecProfileLevel.APVLevel71Band2
                3 -> return CodecProfileLevel.APVLevel71Band3
                else -> {
                    w(TAG, "Unrecognized APV band: " + bandNumber)
                    return -1
                }
            }

            else -> {
                w(TAG, "Unrecognized APV level index: " + levelNumber)
                return -1
            }
        }
    }

    private fun getAacCodecProfileAndLevel(
        codec: String?, parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size != 3) {
            w(TAG, "Ignoring malformed MP4A codec string: " + codec)
            return null
        }
        try {
            // Get the object type indication, which is a hexadecimal value (see RFC 6381/ISO 14496-1).
            val objectTypeIndication = parts[1].toInt(16)
            val mimeType = getMimeTypeFromMp4ObjectType(objectTypeIndication)
            if (MimeTypes.AUDIO_AAC == mimeType) {
                // For MPEG-4 audio this is followed by an audio object type indication as a decimal number.
                val audioObjectTypeIndication = parts[2].toInt()
                val profile = mp4aAudioObjectTypeToProfile(audioObjectTypeIndication)
                if (profile == -1) {
                    w(TAG, "Unrecognized MP4A profile: " + profile)
                    return MediaCodecProfileAndLevel.UNSUPPORTABLE
                }
                // Level is set to zero in AAC decoder CodecProfileLevels.
                return MediaCodecProfileAndLevel(profile, 0)
            }
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed MP4A codec string: " + codec)
        }
        return null
    }

    private fun getAc4CodecProfileAndLevel(
        codec: String?, parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size != 4) {
            w(TAG, "Ignoring malformed AC-4 codec string: " + codec)
            return null
        }
        val bitstreamVersionInteger: Int
        val presentationVersionInteger: Int
        val levelInteger: Int
        try {
            bitstreamVersionInteger = parts[1].toInt()
            presentationVersionInteger = parts[2].toInt()
            levelInteger = parts[3].toInt()
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed AC-4 codec string: " + codec)
            return null
        }

        val profile =
            ac4BitstreamAndPresentationVersionsToProfileConst(
                bitstreamVersionInteger, presentationVersionInteger
            )
        if (profile == -1) {
            w(
                TAG,
                "Unknown AC-4 profile: " + bitstreamVersionInteger + "." + presentationVersionInteger
            )
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        val level = ac4LevelNumberToConst(levelInteger)
        if (level == -1) {
            w(TAG, "Unknown AC-4 level: " + levelInteger)
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile, level)
    }

    private fun getIamfCodecProfileAndLevel(
        codec: String?, parts: Array<String>
    ): MediaCodecProfileAndLevel? {
        if (parts.size < 4) {
            w(TAG, "Ignoring malformed IAMF codec string: " + codec)
            return null
        }

        val profileNumber: Int
        try {
            profileNumber = parts[1].toInt()
        } catch (e: NumberFormatException) {
            w(TAG, "Ignoring malformed primary profile in IAMF codec string: " + parts[1], e)
            return null
        }
        val profile = iamfProfileNumberToConst( parts[3], profileNumber)
        if (profile == -1) {
            return MediaCodecProfileAndLevel.UNSUPPORTABLE
        }
        return MediaCodecProfileAndLevel(profile,  0)
    }

    private fun iamfProfileNumberToConst(codec: String, profileNumber: Int): Int {
        when (codec) {
            "Opus" -> when (profileNumber) {
                0 -> return CodecProfileLevel.IAMFProfileSimpleOpus
                1 -> return CodecProfileLevel.IAMFProfileBaseOpus
                2 -> return CodecProfileLevel.IAMFProfileBaseEnhancedOpus
                else -> {
                    w(TAG, "Unrecognized IAMF Opus profile: " + profileNumber)
                    return -1
                }
            }

            "mp4a" -> when (profileNumber) {
                0 -> return CodecProfileLevel.IAMFProfileSimpleAac
                1 -> return CodecProfileLevel.IAMFProfileBaseAac
                2 -> return CodecProfileLevel.IAMFProfileBaseEnhancedAac
                else -> {
                    w(TAG, "Unrecognized IAMF AAC profile: " + profileNumber)
                    return -1
                }
            }

            "fLaC" -> when (profileNumber) {
                0 -> return CodecProfileLevel.IAMFProfileSimpleFlac
                1 -> return CodecProfileLevel.IAMFProfileBaseFlac
                2 -> return CodecProfileLevel.IAMFProfileBaseEnhancedFlac
                else -> {
                    w(TAG, "Unrecognized IAMF FLAC profile: " + profileNumber)
                    return -1
                }
            }

            "ipcm" -> when (profileNumber) {
                0 -> return CodecProfileLevel.IAMFProfileSimplePcm
                1 -> return CodecProfileLevel.IAMFProfileBasePcm
                2 -> return CodecProfileLevel.IAMFProfileBaseEnhancedPcm
                else -> {
                    w(TAG, "Unrecognized IAMF PCM profile: " + profileNumber)
                    return -1
                }
            }

            else -> {
                w(TAG, "Unrecognized codec identifier for IAMF auxiliary profile: " + codec)
                return -1
            }
        }
    }

    private fun avcProfileNumberToConst(profileNumber: Int): Int {
        when (profileNumber) {
            66 -> return CodecProfileLevel.AVCProfileBaseline
            77 -> return CodecProfileLevel.AVCProfileMain
            88 -> return CodecProfileLevel.AVCProfileExtended
            100 -> return CodecProfileLevel.AVCProfileHigh
            110 -> return CodecProfileLevel.AVCProfileHigh10
            122 -> return CodecProfileLevel.AVCProfileHigh422
            244 -> return CodecProfileLevel.AVCProfileHigh444
            else -> return -1
        }
    }

    private fun avcLevelNumberToConst(levelNumber: Int): Int {
        // TODO: Find int for CodecProfileLevel.AVCLevel1b.
        when (levelNumber) {
            10 -> return CodecProfileLevel.AVCLevel1
            11 -> return CodecProfileLevel.AVCLevel11
            12 -> return CodecProfileLevel.AVCLevel12
            13 -> return CodecProfileLevel.AVCLevel13
            20 -> return CodecProfileLevel.AVCLevel2
            21 -> return CodecProfileLevel.AVCLevel21
            22 -> return CodecProfileLevel.AVCLevel22
            30 -> return CodecProfileLevel.AVCLevel3
            31 -> return CodecProfileLevel.AVCLevel31
            32 -> return CodecProfileLevel.AVCLevel32
            40 -> return CodecProfileLevel.AVCLevel4
            41 -> return CodecProfileLevel.AVCLevel41
            42 -> return CodecProfileLevel.AVCLevel42
            50 -> return CodecProfileLevel.AVCLevel5
            51 -> return CodecProfileLevel.AVCLevel51
            52 -> return CodecProfileLevel.AVCLevel52
            else -> return -1
        }
    }

    private fun vp9ProfileNumberToConst(profileNumber: Int): Int {
        when (profileNumber) {
            0 -> return CodecProfileLevel.VP9Profile0
            1 -> return CodecProfileLevel.VP9Profile1
            2 -> return CodecProfileLevel.VP9Profile2
            3 -> return CodecProfileLevel.VP9Profile3
            else -> return -1
        }
    }

    private fun vp9LevelNumberToConst(levelNumber: Int): Int {
        when (levelNumber) {
            10 -> return CodecProfileLevel.VP9Level1
            11 -> return CodecProfileLevel.VP9Level11
            20 -> return CodecProfileLevel.VP9Level2
            21 -> return CodecProfileLevel.VP9Level21
            30 -> return CodecProfileLevel.VP9Level3
            31 -> return CodecProfileLevel.VP9Level31
            40 -> return CodecProfileLevel.VP9Level4
            41 -> return CodecProfileLevel.VP9Level41
            50 -> return CodecProfileLevel.VP9Level5
            51 -> return CodecProfileLevel.VP9Level51
            60 -> return CodecProfileLevel.VP9Level6
            61 -> return CodecProfileLevel.VP9Level61
            62 -> return CodecProfileLevel.VP9Level62
            else -> return -1
        }
    }

    private fun hevcCodecStringToProfileLevel(codecString: String): Int? {
        when (codecString) {
            "L30" -> return CodecProfileLevel.HEVCMainTierLevel1
            "L60" -> return CodecProfileLevel.HEVCMainTierLevel2
            "L63" -> return CodecProfileLevel.HEVCMainTierLevel21
            "L90" -> return CodecProfileLevel.HEVCMainTierLevel3
            "L93" -> return CodecProfileLevel.HEVCMainTierLevel31
            "L120" -> return CodecProfileLevel.HEVCMainTierLevel4
            "L123" -> return CodecProfileLevel.HEVCMainTierLevel41
            "L150" -> return CodecProfileLevel.HEVCMainTierLevel5
            "L153" -> return CodecProfileLevel.HEVCMainTierLevel51
            "L156" -> return CodecProfileLevel.HEVCMainTierLevel52
            "L180" -> return CodecProfileLevel.HEVCMainTierLevel6
            "L183" -> return CodecProfileLevel.HEVCMainTierLevel61
            "L186" -> return CodecProfileLevel.HEVCMainTierLevel62
            "H30" -> return CodecProfileLevel.HEVCHighTierLevel1
            "H60" -> return CodecProfileLevel.HEVCHighTierLevel2
            "H63" -> return CodecProfileLevel.HEVCHighTierLevel21
            "H90" -> return CodecProfileLevel.HEVCHighTierLevel3
            "H93" -> return CodecProfileLevel.HEVCHighTierLevel31
            "H120" -> return CodecProfileLevel.HEVCHighTierLevel4
            "H123" -> return CodecProfileLevel.HEVCHighTierLevel41
            "H150" -> return CodecProfileLevel.HEVCHighTierLevel5
            "H153" -> return CodecProfileLevel.HEVCHighTierLevel51
            "H156" -> return CodecProfileLevel.HEVCHighTierLevel52
            "H180" -> return CodecProfileLevel.HEVCHighTierLevel6
            "H183" -> return CodecProfileLevel.HEVCHighTierLevel61
            "H186" -> return CodecProfileLevel.HEVCHighTierLevel62
            else -> return null
        }
    }

    private fun dolbyVisionStringToProfile(profileString: String?): Int? {
        if (profileString == null) {
            return null
        }
        when (profileString) {
            "00" -> return CodecProfileLevel.DolbyVisionProfileDvavPer
            "01" -> return CodecProfileLevel.DolbyVisionProfileDvavPen
            "02" -> return CodecProfileLevel.DolbyVisionProfileDvheDer
            "03" -> return CodecProfileLevel.DolbyVisionProfileDvheDen
            "04" -> return CodecProfileLevel.DolbyVisionProfileDvheDtr
            "05" -> return CodecProfileLevel.DolbyVisionProfileDvheStn
            "06" -> return CodecProfileLevel.DolbyVisionProfileDvheDth
            "07" -> return CodecProfileLevel.DolbyVisionProfileDvheDtb
            "08" -> return CodecProfileLevel.DolbyVisionProfileDvheSt
            "09" -> return CodecProfileLevel.DolbyVisionProfileDvavSe
            "10" -> return CodecProfileLevel.DolbyVisionProfileDvav110
            else -> return null
        }
    }

    private fun dolbyVisionStringToLevel(levelString: String): Int? {
        // TODO (Internal: b/179261323): use framework constant for level 13.
        when (levelString) {
            "01" -> return CodecProfileLevel.DolbyVisionLevelHd24
            "02" -> return CodecProfileLevel.DolbyVisionLevelHd30
            "03" -> return CodecProfileLevel.DolbyVisionLevelFhd24
            "04" -> return CodecProfileLevel.DolbyVisionLevelFhd30
            "05" -> return CodecProfileLevel.DolbyVisionLevelFhd60
            "06" -> return CodecProfileLevel.DolbyVisionLevelUhd24
            "07" -> return CodecProfileLevel.DolbyVisionLevelUhd30
            "08" -> return CodecProfileLevel.DolbyVisionLevelUhd48
            "09" -> return CodecProfileLevel.DolbyVisionLevelUhd60
            "10" -> return CodecProfileLevel.DolbyVisionLevelUhd120
            "11" -> return CodecProfileLevel.DolbyVisionLevel8k30
            "12" -> return CodecProfileLevel.DolbyVisionLevel8k60
            "13" -> return 0x1000
            else -> return null
        }
    }

    private fun av1LevelNumberToConst(levelNumber: Int): Int {
        // See https://aomediacodec.github.io/av1-spec/av1-spec.pdf Annex A: Profiles and levels for
        // more information on mapping AV1 codec strings to levels.
        when (levelNumber) {
            0 -> return CodecProfileLevel.AV1Level2
            1 -> return CodecProfileLevel.AV1Level21
            2 -> return CodecProfileLevel.AV1Level22
            3 -> return CodecProfileLevel.AV1Level23
            4 -> return CodecProfileLevel.AV1Level3
            5 -> return CodecProfileLevel.AV1Level31
            6 -> return CodecProfileLevel.AV1Level32
            7 -> return CodecProfileLevel.AV1Level33
            8 -> return CodecProfileLevel.AV1Level4
            9 -> return CodecProfileLevel.AV1Level41
            10 -> return CodecProfileLevel.AV1Level42
            11 -> return CodecProfileLevel.AV1Level43
            12 -> return CodecProfileLevel.AV1Level5
            13 -> return CodecProfileLevel.AV1Level51
            14 -> return CodecProfileLevel.AV1Level52
            15 -> return CodecProfileLevel.AV1Level53
            16 -> return CodecProfileLevel.AV1Level6
            17 -> return CodecProfileLevel.AV1Level61
            18 -> return CodecProfileLevel.AV1Level62
            19 -> return CodecProfileLevel.AV1Level63
            20 -> return CodecProfileLevel.AV1Level7
            21 -> return CodecProfileLevel.AV1Level71
            22 -> return CodecProfileLevel.AV1Level72
            23 -> return CodecProfileLevel.AV1Level73
            else -> return -1
        }
    }

    private fun mp4aAudioObjectTypeToProfile(profileNumber: Int): Int {
        when (profileNumber) {
            1 -> return CodecProfileLevel.AACObjectMain
            2 -> return CodecProfileLevel.AACObjectLC
            3 -> return CodecProfileLevel.AACObjectSSR
            4 -> return CodecProfileLevel.AACObjectLTP
            5 -> return CodecProfileLevel.AACObjectHE
            6 -> return CodecProfileLevel.AACObjectScalable
            17 -> return CodecProfileLevel.AACObjectERLC
            20 -> return CodecProfileLevel.AACObjectERScalable
            23 -> return CodecProfileLevel.AACObjectLD
            29 -> return CodecProfileLevel.AACObjectHE_PS
            39 -> return CodecProfileLevel.AACObjectELD
            42 -> return CodecProfileLevel.AACObjectXHE
            else -> return -1
        }
    }

    private fun ac4BitstreamAndPresentationVersionsToProfileConst(
        bitstreamVersionInteger: Int, presentationVersionInteger: Int
    ): Int {
        var ac4Profile = -1
        when (bitstreamVersionInteger) {
            0 -> if (presentationVersionInteger == 0) {
                ac4Profile = CodecProfileLevel.AC4Profile00
            }

            1 -> if (presentationVersionInteger == 0) {
                ac4Profile = CodecProfileLevel.AC4Profile10
            } else if (presentationVersionInteger == 1) {
                ac4Profile = CodecProfileLevel.AC4Profile11
            }

            2 -> if (presentationVersionInteger == 1) {
                ac4Profile = CodecProfileLevel.AC4Profile21
            } else if (presentationVersionInteger == 2) {
                ac4Profile = CodecProfileLevel.AC4Profile22
            }

            else -> {}
        }
        return ac4Profile
    }

    private fun ac4LevelNumberToConst(levelNumber: Int): Int {
        when (levelNumber) {
            0 -> return CodecProfileLevel.AC4Level0
            1 -> return CodecProfileLevel.AC4Level1
            2 -> return CodecProfileLevel.AC4Level2
            3 -> return CodecProfileLevel.AC4Level3
            4 -> return CodecProfileLevel.AC4Level4
            else -> return -1
        }
    }

    private fun h263ProfileNumberToConst(profileNumber: Int): Int {
        when (profileNumber) {
            0 -> return CodecProfileLevel.H263ProfileBaseline
            1 -> return CodecProfileLevel.H263ProfileH320Coding
            2 -> return CodecProfileLevel.H263ProfileBackwardCompatible
            3 -> return CodecProfileLevel.H263ProfileISWV2
            4 -> return CodecProfileLevel.H263ProfileISWV3
            5 -> return CodecProfileLevel.H263ProfileHighCompression
            6 -> return CodecProfileLevel.H263ProfileInternet
            7 -> return CodecProfileLevel.H263ProfileInterlace
            8 -> return CodecProfileLevel.H263ProfileHighLatency
            else -> return -1
        }
    }

    private fun h263LevelNumberToConst(levelNumber: Int): Int {
        when (levelNumber) {
            10 -> return CodecProfileLevel.H263Level10
            20 -> return CodecProfileLevel.H263Level20
            30 -> return CodecProfileLevel.H263Level30
            40 -> return CodecProfileLevel.H263Level40
            45 -> return CodecProfileLevel.H263Level45
            50 -> return CodecProfileLevel.H263Level50
            60 -> return CodecProfileLevel.H263Level60
            70 -> return CodecProfileLevel.H263Level70
            else -> return -1
        }
    }

    fun h263ConstToProfileNumber(profileConst: Int): Int {
        when (profileConst) {
            CodecProfileLevel.H263ProfileBaseline -> return 0
            CodecProfileLevel.H263ProfileH320Coding -> return 1
            CodecProfileLevel.H263ProfileBackwardCompatible -> return 2
            CodecProfileLevel.H263ProfileISWV2 -> return 3
            CodecProfileLevel.H263ProfileISWV3 -> return 4
            CodecProfileLevel.H263ProfileHighCompression -> return 5
            CodecProfileLevel.H263ProfileInternet -> return 6
            CodecProfileLevel.H263ProfileInterlace -> return 7
            CodecProfileLevel.H263ProfileHighLatency -> return 8
            else -> return -1
        }
    }

    fun h263ConstToLevelNumber(levelConst: Int): Int {
        when (levelConst) {
            CodecProfileLevel.H263Level10 -> return 10
            CodecProfileLevel.H263Level20 -> return 20
            CodecProfileLevel.H263Level30 -> return 30
            CodecProfileLevel.H263Level40 -> return 40
            CodecProfileLevel.H263Level45 -> return 45
            CodecProfileLevel.H263Level50 -> return 50
            CodecProfileLevel.H263Level60 -> return 60
            CodecProfileLevel.H263Level70 -> return 70
            else -> return -1
        }
    }

    class MediaCodecProfileAndLevel private constructor(
        private val profile: Int,
        private val level: Int,
        val isSupportableByMediaCodec: Boolean
    ) {

        constructor(profile: Int, level: Int) : this(
            profile,
            level,  
            true
        )

        fun getProfile(): Int {
            Preconditions.checkState(isSupportableByMediaCodec)
            return profile
        }

        fun getLevel(): Int {
            Preconditions.checkState(isSupportableByMediaCodec)
            return level
        }

        companion object {
            val UNSUPPORTABLE: MediaCodecProfileAndLevel = MediaCodecProfileAndLevel( 
                0,  0,  false
            )
        }
    }
}
