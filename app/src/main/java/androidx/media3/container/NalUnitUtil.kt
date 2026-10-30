package androidx.media3.container

import androidx.media3.common.C
import androidx.media3.common.C.ColorRange
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil.buildHevcCodecString
import androidx.media3.common.util.Log.w
import com.google.common.base.Preconditions
import com.google.common.math.DoubleMath
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

object NalUnitUtil {
    private const val TAG = "NalUnitUtil"

    const val H264_NAL_UNIT_TYPE_NON_IDR: Int = 1

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_NON_IDR}")
    val NAL_UNIT_TYPE_NON_IDR: Int = H264_NAL_UNIT_TYPE_NON_IDR

    const val H264_NAL_UNIT_TYPE_PARTITION_A: Int = 2

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_PARTITION_A}")
    val NAL_UNIT_TYPE_PARTITION_A: Int = H264_NAL_UNIT_TYPE_PARTITION_A

    const val H264_NAL_UNIT_TYPE_IDR: Int = 5

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_IDR}")
    val NAL_UNIT_TYPE_IDR: Int = H264_NAL_UNIT_TYPE_IDR

    const val H264_NAL_UNIT_TYPE_SEI: Int = 6

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_SEI}")
    val NAL_UNIT_TYPE_SEI: Int = H264_NAL_UNIT_TYPE_SEI

    const val H264_NAL_UNIT_TYPE_SPS: Int = 7

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_SPS}")
    val NAL_UNIT_TYPE_SPS: Int = H264_NAL_UNIT_TYPE_SPS

    const val H264_NAL_UNIT_TYPE_PPS: Int = 8

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_PPS}")
    val NAL_UNIT_TYPE_PPS: Int = H264_NAL_UNIT_TYPE_PPS

    const val H264_NAL_UNIT_TYPE_AUD: Int = 9

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_AUD}")
    val NAL_UNIT_TYPE_AUD: Int = H264_NAL_UNIT_TYPE_AUD

    const val H264_NAL_UNIT_TYPE_PREFIX: Int = 14

    @Deprecated("Use {@link #H264_NAL_UNIT_TYPE_PREFIX}")
    val NAL_UNIT_TYPE_PREFIX: Int = H264_NAL_UNIT_TYPE_PREFIX

    const val H264_NAL_UNIT_TYPE_UNSPECIFIED: Int = 24

    const val H265_NAL_UNIT_TYPE_RASL_R: Int = 9

    const val H265_NAL_UNIT_TYPE_BLA_W_LP: Int = 16

    const val H265_NAL_UNIT_TYPE_CRA: Int = 21

    const val H265_NAL_UNIT_TYPE_VPS: Int = 32

    const val H265_NAL_UNIT_TYPE_SPS: Int = 33

    const val H265_NAL_UNIT_TYPE_PPS: Int = 34

    const val H265_NAL_UNIT_TYPE_AUD: Int = 35

    const val H265_NAL_UNIT_TYPE_PREFIX_SEI: Int = 39

    const val H265_NAL_UNIT_TYPE_SUFFIX_SEI: Int = 40

    const val H265_NAL_UNIT_TYPE_UNSPECIFIED: Int = 48

    // VVC NAL unit types.
    // See ITU-T Rec. H.266 (09/2023) Table 5 – NAL unit type codes and NAL unit type classes.
    
    const val VVC_NAL_UNIT_TYPE_OPI: Int = 12

    const val VVC_NAL_UNIT_TYPE_DCI: Int = 13

    const val VVC_NAL_UNIT_TYPE_PREFIX_SEI: Int = 23

    val NAL_START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)

    const val EXTENDED_SAR: Int = 0xFF

    val ASPECT_RATIO_IDC_VALUES: FloatArray = floatArrayOf(
        1f,  
        1f,
        12f / 11f,
        10f / 11f,
        16f / 11f,
        40f / 33f,
        24f / 11f,
        20f / 11f,
        32f / 11f,
        80f / 33f,
        18f / 11f,
        15f / 11f,
        64f / 33f,
        160f / 99f,
        4f / 3f,
        3f / 2f,
        2f
    )

    private val INVALID_ID = -1 // Invalid ID.

    private val scratchEscapePositionsLock = Any()

    private var scratchEscapePositions = IntArray(10)

    fun unescapeStream(data: ByteArray, limit: Int): Int {
        synchronized(scratchEscapePositionsLock) {
            var position = 0
            var scratchEscapeCount = 0
            while (position < limit) {
                position = findNextUnescapeIndex(data, position, limit)
                if (position < limit) {
                    if (scratchEscapePositions.size <= scratchEscapeCount) {
                        // Grow scratchEscapePositions to hold a larger number of positions.
                        scratchEscapePositions =
                            scratchEscapePositions.copyOf(scratchEscapePositions.size * 2)
                    }
                    scratchEscapePositions[scratchEscapeCount++] = position
                    position += 3
                }
            }

            val unescapedLength = limit - scratchEscapeCount
            var escapedPosition = 0 // The position being read from.
            var unescapedPosition = 0 // The position being written to.
            for (i in 0..<scratchEscapeCount) {
                val nextEscapePosition = scratchEscapePositions[i]
                val copyLength = nextEscapePosition - escapedPosition
                System.arraycopy(data, escapedPosition, data, unescapedPosition, copyLength)
                unescapedPosition += copyLength
                data[unescapedPosition++] = 0
                data[unescapedPosition++] = 0
                escapedPosition += copyLength + 3
            }

            val remainingLength = unescapedLength - unescapedPosition
            System.arraycopy(data, escapedPosition, data, unescapedPosition, remainingLength)
            return unescapedLength
        }
    }

    fun discardToSps(data: ByteBuffer) {
        val length = data.position()
        var consecutiveZeros = 0
        var offset = 0
        while (offset + 1 < length) {
            val value = data.get(offset).toInt() and 0xFF
            if (consecutiveZeros == 3) {
                if (value == 1 && (data.get(offset + 1)
                        .toInt() and 0x1F) == H264_NAL_UNIT_TYPE_SPS
                ) {
                    // Copy from this NAL unit onwards to the start of the buffer.
                    val offsetData = data.duplicate()
                    offsetData.position(offset - 3)
                    offsetData.limit(length)
                    data.position(0)
                    data.put(offsetData)
                    return
                }
            } else if (value == 0) {
                consecutiveZeros++
            }
            if (value != 0) {
                consecutiveZeros = 0
            }
            offset++
        }
        // Empty the buffer if the SPS NAL unit was not found.
        data.clear()
    }

    @Deprecated(
        """Use {@link #isNalUnitSei(Format, byte[], int)} in order to support {@link
   *     MimeTypes#VIDEO_DOLBY_VISION} tracks with backwards compatible {@link MimeTypes#VIDEO_H264}
        or {@link MimeTypes#VIDEO_H265} data."""
    )
    fun isNalUnitSei(mimeType: String?, nalUnitHeaderFirstByte: Byte): Boolean {
        return (MimeTypes.VIDEO_H264 == mimeType
                && (nalUnitHeaderFirstByte.toInt() and 0x1F) == H264_NAL_UNIT_TYPE_SEI)
                || (MimeTypes.VIDEO_H265 == mimeType
                && ((nalUnitHeaderFirstByte.toInt() and 0x7E) shr 1) == H265_NAL_UNIT_TYPE_PREFIX_SEI)
    }

    @Deprecated("Use {@link #isNalUnitSei(Format, byte[], int)} instead.")
    fun isNalUnitSei(format: Format, nalUnitHeaderFirstByte: Byte): Boolean {
        return isNalUnitSei(format, byteArrayOf(nalUnitHeaderFirstByte),  0)
    }

    fun isNalUnitSei(format: Format, data: ByteArray, offset: Int): Boolean {
        val mimeType = getNalStructureMimeType(format)
        if (mimeType == null) {
            return false
        }
        when (mimeType) {
            MimeTypes.VIDEO_H264 -> return (data[offset].toInt() and 0x1F) == H264_NAL_UNIT_TYPE_SEI
            MimeTypes.VIDEO_H265 -> return ((data[offset].toInt() and 0x7E) shr 1) == H265_NAL_UNIT_TYPE_PREFIX_SEI
            MimeTypes.VIDEO_H266 -> {
                // See ITU-T Rec. H.266 (09/2023) Section 7.3.1.2.
                // The NAL unit type is in the first 5 bits of the second byte.
                val nalUnitType = (data[offset + 1].toInt() and 0xF8) shr 3
                return nalUnitType == VVC_NAL_UNIT_TYPE_PREFIX_SEI
            }

            else -> return false
        }
    }

    fun getNalUnitType(data: ByteArray, offset: Int): Int {
        return data[offset + 3].toInt() and 0x1F
    }

    fun isH264NalUnitDependedOn(nalUnitHeaderFirstByte: Byte): Boolean {
        val nalRefIdc = ((nalUnitHeaderFirstByte.toInt() and 0x60) shr 5)
        if (nalRefIdc != 0) {
            // A picture with nal_ref_idc not equal to 0 is a reference picture, which contains
            // samples that may be used for inter prediction in the decoding process of subsequent
            // pictures in decoding order.
            return true
        }

        val nalUnitType = nalUnitHeaderFirstByte.toInt() and 0x1F
        if (nalUnitType == H264_NAL_UNIT_TYPE_NON_IDR) {
            // For pictures (Video Coding Layer NAL units), we can rely on nal_ref_idc to determine
            // whether future NAL units depend on it.
            return false
        }
        if (nalUnitType == H264_NAL_UNIT_TYPE_AUD) {
            // NAL unit delimiters are not depended on.
            return false
        }
        if (nalUnitType == H264_NAL_UNIT_TYPE_PREFIX) {
            // Prefix NAL units are only used by Annex G scalable video coding to mark temporal layers.
            // Rely on nal_ref_idc to identify sample dependencies.
            return false
        }
        // Treat any other NAL unit type as depended on. This might be too restrictive, but reduces
        // risks around closed captions, HDR metadata in SEI messages.
        return true
    }

    fun numberOfBytesInNalUnitHeader(format: Format): Int {
        val mimeType = getNalStructureMimeType(format)
        if (mimeType == MimeTypes.VIDEO_H264) {
            return 1
        }
        if (mimeType == MimeTypes.VIDEO_H265
            || mimeType == MimeTypes.VIDEO_H266
        ) {
            return 2
        }
        return 0
    }

    fun isDependedOn(data: ByteArray, offset: Int, length: Int, format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_H264) {
            return isH264NalUnitDependedOn(data[offset])
        }
        if (format.sampleMimeType == MimeTypes.VIDEO_H265) {
            return isH265NalUnitDependedOn(data, offset, length, format)
        }
        return true
    }

    private fun isH265NalUnitDependedOn(
        data: ByteArray, offset: Int, length: Int, format: Format
    ): Boolean {
        val header =
            parseH265NalHeader(ParsableNalUnitBitArray(data, offset,  offset + length))
        if (header.nalUnitType == H265_NAL_UNIT_TYPE_AUD) {
            // NAL unit delimiters are not depended on.
            return false
        }
        val isSubLayerNonReferencePicture = header.nalUnitType <= 14 && header.nalUnitType % 2 == 0
        if (isSubLayerNonReferencePicture && header.temporalId == format.maxSubLayers - 1) {
            // Sub-layer non-reference (SLNR) pictures cannot be used for inter prediction in the same
            // temporal layer. That is, SLNR pictures are not depended on if they are part of the highest
            // temporal layer.
            return false
        }
        return true
    }

    fun getH265NalUnitType(data: ByteArray, offset: Int): Int {
        return (data[offset + 3].toInt() and 0x7E) shr 1
    }

    fun parseSpsNalUnit(nalData: ByteArray, nalOffset: Int, nalLimit: Int): SpsData {
        return parseSpsNalUnitPayload(nalData, nalOffset + 1, nalLimit)
    }

    fun parseSpsNalUnitPayload(nalData: ByteArray, nalOffset: Int, nalLimit: Int): SpsData {
        val data = ParsableNalUnitBitArray(nalData, nalOffset, nalLimit)
        val profileIdc = data.readBits(8)
        val constraintsFlagsAndReservedZero2Bits = data.readBits(8)
        val levelIdc = data.readBits(8)
        val seqParameterSetId = data.readUnsignedExpGolombCodedInt()

        var chromaFormatIdc = 1 // Default is 4:2:0
        var separateColorPlaneFlag = false
        var bitDepthLumaMinus8 = 0
        var bitDepthChromaMinus8 = 0
        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 || profileIdc == 128 || profileIdc == 138) {
            chromaFormatIdc = data.readUnsignedExpGolombCodedInt()
            if (chromaFormatIdc == 3) {
                separateColorPlaneFlag = data.readBit()
            }
            bitDepthLumaMinus8 = data.readUnsignedExpGolombCodedInt()
            bitDepthChromaMinus8 = data.readUnsignedExpGolombCodedInt()
            data.skipBit() // qpprime_y_zero_transform_bypass_flag
            val seqScalingMatrixPresentFlag = data.readBit()
            if (seqScalingMatrixPresentFlag) {
                val limit = if (chromaFormatIdc != 3) 8 else 12
                for (i in 0..<limit) {
                    val seqScalingListPresentFlag = data.readBit()
                    if (seqScalingListPresentFlag) {
                        skipScalingList(data, if (i < 6) 16 else 64)
                    }
                }
            }
        }

        val frameNumLength =
            data.readUnsignedExpGolombCodedInt() + 4 // log2_max_frame_num_minus4 + 4
        val picOrderCntType = data.readUnsignedExpGolombCodedInt()
        var picOrderCntLsbLength = 0
        var deltaPicOrderAlwaysZeroFlag = false
        if (picOrderCntType == 0) {
            // log2_max_pic_order_cnt_lsb_minus4 + 4
            picOrderCntLsbLength = data.readUnsignedExpGolombCodedInt() + 4
        } else if (picOrderCntType == 1) {
            deltaPicOrderAlwaysZeroFlag = data.readBit() // delta_pic_order_always_zero_flag
            data.readSignedExpGolombCodedInt() // offset_for_non_ref_pic
            data.readSignedExpGolombCodedInt() // offset_for_top_to_bottom_field
            val numRefFramesInPicOrderCntCycle = data.readUnsignedExpGolombCodedInt().toLong()
            for (i in 0..<numRefFramesInPicOrderCntCycle) {
                data.readUnsignedExpGolombCodedInt() // offset_for_ref_frame[i]
            }
        }
        val maxNumRefFrames = data.readUnsignedExpGolombCodedInt() // max_num_ref_frames
        data.skipBit() // gaps_in_frame_num_value_allowed_flag

        val picWidthInMbs = data.readUnsignedExpGolombCodedInt() + 1
        val picHeightInMapUnits = data.readUnsignedExpGolombCodedInt() + 1
        val frameMbsOnlyFlag = data.readBit()
        val frameHeightInMbs = (2 - (if (frameMbsOnlyFlag) 1 else 0)) * picHeightInMapUnits
        if (!frameMbsOnlyFlag) {
            data.skipBit() // mb_adaptive_frame_field_flag
        }

        data.skipBit() // direct_8x8_inference_flag
        var frameWidth = picWidthInMbs * 16
        var frameHeight = frameHeightInMbs * 16
        val frameCroppingFlag = data.readBit()
        if (frameCroppingFlag) {
            val frameCropLeftOffset = data.readUnsignedExpGolombCodedInt()
            val frameCropRightOffset = data.readUnsignedExpGolombCodedInt()
            val frameCropTopOffset = data.readUnsignedExpGolombCodedInt()
            val frameCropBottomOffset = data.readUnsignedExpGolombCodedInt()
            val cropUnitX: Int
            val cropUnitY: Int
            if (chromaFormatIdc == 0) {
                cropUnitX = 1
                cropUnitY = 2 - (if (frameMbsOnlyFlag) 1 else 0)
            } else {
                val subWidthC = if (chromaFormatIdc == 3) 1 else 2
                val subHeightC = if (chromaFormatIdc == 1) 2 else 1
                cropUnitX = subWidthC
                cropUnitY = subHeightC * (2 - (if (frameMbsOnlyFlag) 1 else 0))
            }
            frameWidth -= (frameCropLeftOffset + frameCropRightOffset) * cropUnitX
            frameHeight -= (frameCropTopOffset + frameCropBottomOffset) * cropUnitY
        }

        var colorSpace: @C.ColorSpace Int = Format.NO_VALUE
        var colorRange: @ColorRange Int = Format.NO_VALUE
        var colorTransfer: @ColorTransfer Int = Format.NO_VALUE
        var pixelWidthHeightRatio = 1f
        // Initialize to the default value defined in section E.2.1 of the H.264 spec. Precisely
        // calculating MaxDpbFrames is complicated, so we short-circuit to the max value of 16 here
        // instead.
        var maxNumReorderFrames =
            if ((profileIdc == 44 || profileIdc == 86 || profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244)
                && ((constraintsFlagsAndReservedZero2Bits and 0x10) != 0)
            )
                0
            else
                16
        if (data.readBit()) { // vui_parameters_present_flag
            // Section E.1.1: VUI parameters syntax
            val aspectRatioInfoPresentFlag = data.readBit()
            if (aspectRatioInfoPresentFlag) {
                val aspectRatioIdc = data.readBits(8)
                if (aspectRatioIdc == EXTENDED_SAR) {
                    val sarWidth = data.readBits(16)
                    val sarHeight = data.readBits(16)
                    if (sarWidth != 0 && sarHeight != 0) {
                        pixelWidthHeightRatio = sarWidth.toFloat() / sarHeight
                    }
                } else if (aspectRatioIdc < ASPECT_RATIO_IDC_VALUES.size) {
                    pixelWidthHeightRatio = ASPECT_RATIO_IDC_VALUES[aspectRatioIdc]
                } else {
                    w(TAG, "Unexpected aspect_ratio_idc value: " + aspectRatioIdc)
                }
            }
            if (data.readBit()) { // overscan_info_present_flag
                data.skipBit() // overscan_appropriate_flag
            }
            if (data.readBit()) { // video_signal_type_present_flag
                data.skipBits(3) // video_format
                colorRange =
                    if (data.readBit()) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED // video_full_range_flag
                if (data.readBit()) { // colour_description_present_flag
                    val colorPrimaries = data.readBits(8) // colour_primaries
                    val transferCharacteristics = data.readBits(8) // transfer_characteristics
                    data.skipBits(8) // matrix_coeffs

                    colorSpace = ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries)
                    colorTransfer =
                        ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics)
                }
            }
            if (data.readBit()) { // chroma_loc_info_present_flag
                data.readUnsignedExpGolombCodedInt() // chroma_sample_loc_type_top_field
                data.readUnsignedExpGolombCodedInt() // chroma_sample_loc_type_bottom_field
            }
            if (data.readBit()) { // timing_info_present_flag
                data.skipBits(65) // num_units_in_tick (32), time_scale (32), fixed_frame_rate_flag (1)
            }
            val nalHrdParametersPresent = data.readBit() // nal_hrd_parameters_present_flag
            if (nalHrdParametersPresent) {
                skipHrdParameters(data)
            }
            val vclHrdParametersPresent = data.readBit() // vcl_hrd_parameters_present_flag
            if (vclHrdParametersPresent) {
                skipHrdParameters(data)
            }
            if (nalHrdParametersPresent || vclHrdParametersPresent) {
                data.skipBit() // low_delay_hrd_flag
            }
            data.skipBit() // pic_struct_present_flag
            if (data.readBit()) { // bitstream_restriction_flag
                data.skipBit() // motion_vectors_over_pic_boundaries_flag
                data.readUnsignedExpGolombCodedInt() // max_bytes_per_pic_denom
                data.readUnsignedExpGolombCodedInt() // max_bits_per_mb_denom
                data.readUnsignedExpGolombCodedInt() // log2_max_mv_length_horizontal
                data.readUnsignedExpGolombCodedInt() // log2_max_mv_length_vertical
                maxNumReorderFrames = data.readUnsignedExpGolombCodedInt() // max_num_reorder_frames
                data.readUnsignedExpGolombCodedInt() // max_dec_frame_buffering
            }
        }

        return SpsData(
            profileIdc,
            constraintsFlagsAndReservedZero2Bits,
            levelIdc,
            seqParameterSetId,
            maxNumRefFrames,
            frameWidth,
            frameHeight,
            pixelWidthHeightRatio,
            bitDepthLumaMinus8,
            bitDepthChromaMinus8,
            separateColorPlaneFlag,
            frameMbsOnlyFlag,
            frameNumLength,
            picOrderCntType,
            picOrderCntLsbLength,
            deltaPicOrderAlwaysZeroFlag,
            colorSpace,
            colorRange,
            colorTransfer,
            maxNumReorderFrames
        )
    }

    fun parseH265VpsNalUnit(nalData: ByteArray, nalOffset: Int, nalLimit: Int): H265VpsData {
        val data = ParsableNalUnitBitArray(nalData, nalOffset, nalLimit)
        val nalHeader = parseH265NalHeader(data)
        return parseH265VpsNalUnitPayload(data, nalHeader)
    }

    private fun parseH265NalHeader(data: ParsableNalUnitBitArray): H265NalHeader {
        // For HEVC and L-HEVC, the layer ID and temporal layer ID can be extracted from the 2 byte
        // nal_unit_header().
        data.skipBit() // forbidden_zero_bit
        val nalUnitType = data.readBits(6) // nal_unit_type
        val layerId = data.readBits(6) // nuh_layer_id
        val temporalId = data.readBits(3) - 1 // nuh_temporal_id_plus1
        return H265NalHeader(nalUnitType, layerId, temporalId)
    }

    private fun parseH265VpsNalUnitPayload(
        data: ParsableNalUnitBitArray, nalHeader: H265NalHeader?
    ): H265VpsData {
        data.skipBits(4) // vps_video_parameter_set_id
        val baseLayerInternalFlag = data.readBit() // vps_base_layer_internal_flag
        val baseLayerAvailableFlag = data.readBit() // vps_base_layer_available_flag
        val maxLayers = data.readBits(6) + 1 // vps_max_layers_minus1

        val maxSubLayersMinus1 = data.readBits(3) // vps_max_sub_layers_minus1
        data.skipBits(17) // vps_temporal_id_nesting_flag, vps_reserved_0xffff_16bits

        val profileTierLevel =
            parseH265ProfileTierLevel(
                data,  
                true,
                maxSubLayersMinus1,  
                null
            )

        val start = if (data.readBit()) 0 else maxSubLayersMinus1
        // for (i = vps_sub_layer_ordering_info_present_flag ? 0 : vps_max_sub_layers_minus1; ...)
        for (i in start..maxSubLayersMinus1) {
            data.readUnsignedExpGolombCodedInt() // vps_max_dec_pic_buffering_minus1[i]
            data.readUnsignedExpGolombCodedInt() // vps_max_num_reorder_pics[i]
            data.readUnsignedExpGolombCodedInt() // vps_max_latency_increase_plus1[i]
        }

        val maxLayerId = data.readBits(6) // vps_max_layer_id
        val numLayerSets = data.readUnsignedExpGolombCodedInt() + 1 // vps_num_layer_sets_minus1

        var profileTierLevels = listOf<H265ProfileTierLevel?>(profileTierLevel)
        val baseLayerProfileTierLevelsAndIndices =
            H265ProfileTierLevelsAndIndices(profileTierLevels, IntArray(1))

        // Given that the first layer set is dedicated for the base layer, numLayerSets must be greater
        // than 1 for the L-HEVC case.
        val haveEnoughLayerSets = maxLayers >= 2 && numLayerSets >= 2
        // This implementation only supports the case where the base layer is included within the L-HEVC
        // bitstream.
        val baseLayerIncluded = baseLayerInternalFlag && baseLayerAvailableFlag
        // The NAL unit header's layer ID, nuh_layer_id, (which doesn't need to be sequential) better
        // have enough range to cover the specified max number of layers.
        val haveLargeEnoughMaxLayerIdInNuh = maxLayerId + 1 >= maxLayers
        if (!haveEnoughLayerSets || !baseLayerIncluded || !haveLargeEnoughMaxLayerIdInNuh) {
            // Fallback to single layer HEVC.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }

        // Define each layer set information: list of layer IDs, number of layers, and max layer ID.
        val layerSetLayerIdList = Array<IntArray?>(numLayerSets) { IntArray(maxLayerId + 1) }
        val numLayersInIdList = IntArray(numLayerSets)
        val layerSetMaxLayerId = IntArray(numLayerSets)
        // The first layer set is comprised of only the base layer.
        layerSetLayerIdList[0]!![0] = 0
        numLayersInIdList[0] = 1
        layerSetMaxLayerId[0] = 0
        // Define other layer sets.
        for (i in 1..<numLayerSets) {
            var n = 0
            for (j in 0..maxLayerId) {
                if (data.readBit()) { // layer_id_included_flag[i][j]
                    layerSetLayerIdList[i]!![n++] = j
                    layerSetMaxLayerId[i] = j
                }
                numLayersInIdList[i] = n
            }
        }

        if (data.readBit()) { // vps_timing_info_present_flag
            data.skipBits(64) // vps_num_units_in_tick, vps_time_scale
            if (data.readBit()) { // vps_poc_proportional_to_timing_flag
                data.readUnsignedExpGolombCodedInt() // vps_num_ticks_poc_diff_one_minus1
            }
            val numHrdParameters = data.readUnsignedExpGolombCodedInt() // vps_num_hrd_parameters
            for (i in 0..<numHrdParameters) {
                data.readUnsignedExpGolombCodedInt() // hrd_layer_set_idx[i]
                skipH265HrdParameters(
                    data,  i == 0 || data.readBit(), maxSubLayersMinus1
                )
            }
        }

        // For L-HEVC, vps_extension() needs to be parsed.
        if (!data.readBit()) { // vps_extension_flag
            // If no vps_extension(), then fallback to single layer HEVC.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }

        data.byteAlign()

        // Parsing vps_extension().
        val baseLayerProfileTierLevel =
            parseH265ProfileTierLevel(
                data,  false, maxSubLayersMinus1, profileTierLevel
            )

        val splittingFlag = data.readBit() // splitting_flag
        val scalabilityMaskFlag = BooleanArray(16)
        var numScalabilityTypes = 0
        for (i in 0..15) {
            scalabilityMaskFlag[i] = data.readBit() // scalability_mask_flag[i]
            if (scalabilityMaskFlag[i]) {
                numScalabilityTypes++
            }
        }
        // As listed in Table F.1 of the spec, numScalabilityTypes indicates the number of different
        // scalability dimensions.  If there is no scalability dimension, then we simply have a
        // single-layer HEVC.  Of the 16 scalability dimensions, dimension 1 is used for multiview;
        // currently only the multiview case is supported.
        if (numScalabilityTypes == 0 || !scalabilityMaskFlag[1]) {
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }

        val dimensionIdLenMinus1 = IntArray(numScalabilityTypes)
        for (i in 0..<numScalabilityTypes - (if (splittingFlag) 1 else 0)) {
            dimensionIdLenMinus1[i] = data.readBits(3) // dimension_id_len_minus1[i]
        }
        val dimBitOffset = IntArray(numScalabilityTypes + 1)
        if (splittingFlag) {
            for (i in 1..<numScalabilityTypes) {
                for (j in 0..<i) {
                    dimBitOffset[i] += dimensionIdLenMinus1[j] + 1
                }
            }
            dimBitOffset[numScalabilityTypes] = 6
        }

        val dimensionId = Array<IntArray?>(maxLayers) { IntArray(numScalabilityTypes) }
        // Get layerIdInNuh that maps the layer ID used in this VPS to the NAL unit header's parsed
        // layer ID - nuh_layer_id.
        val layerIdInNuh = IntArray(maxLayers)
        layerIdInNuh[0] = 0
        val nuhLayerIdPresentFlag = data.readBit() // vps_nuh_layer_id_present_flag
        for (i in 1..<maxLayers) {
            if (nuhLayerIdPresentFlag) {
                layerIdInNuh[i] = data.readBits(6) // layer_id_in_nuh[i]
            } else {
                layerIdInNuh[i] = i
            }
            if (!splittingFlag) {
                for (j in 0..<numScalabilityTypes) {
                    dimensionId[i]!![j] =
                        data.readBits(dimensionIdLenMinus1[j] + 1) // dimension_id[i][j]
                }
            } else {
                for (j in 0..<numScalabilityTypes) {
                    dimensionId[i]!![j] =
                        (layerIdInNuh[i] and ((1 shl dimBitOffset[j + 1]) - 1)) shr dimBitOffset[j]
                }
            }
        }

        // Derive viewOrderIdx[] and numViews following (F-3) in subsection F.7.4.3.1.1.
        val viewOrderIdx = IntArray(maxLayerId + 1)
        var numViews = 1
        for (i in 0..<maxLayers) {
            viewOrderIdx[layerIdInNuh[i]] = C.INDEX_UNSET
            // for (smId x= 0, j = 0; smIdx < 16; ...)
            var scalabilityMaskFlagIndex = 0
            var j = 0
            while (scalabilityMaskFlagIndex < 16
            ) {
                if (scalabilityMaskFlag[scalabilityMaskFlagIndex]) {
                    if (scalabilityMaskFlagIndex == 1) { // multiview
                        // Note that viewOrderIdx is expected to be an index as it is used to access
                        // view_id_val[]; however, dimensionId[i][j] is not expected to follow the index
                        // constraint.  It is up to the encoder to ensure that the dimensionId[i][j] is
                        // consistent with the use of viewOrderIdx.
                        viewOrderIdx[layerIdInNuh[i]] = dimensionId[i]!![j]
                    }
                    j++
                }
                scalabilityMaskFlagIndex++
            }
            if (i > 0) {
                var newView = true
                for (j in 0..<i) {
                    if (viewOrderIdx[layerIdInNuh[i]] == viewOrderIdx[layerIdInNuh[j]]) {
                        newView = false
                        break
                    }
                }
                if (newView) {
                    numViews++
                }
            }
        }

        val viewIdLen = data.readBits(4) // view_id_len
        if (numViews < 2 || viewIdLen == 0) {
            // This means all views have viewId of 0, so no multiview effect.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }
        val viewIdVals = IntArray(numViews)
        for (i in 0..<numViews) {
            viewIdVals[i] = data.readBits(viewIdLen) // view_id_val[i]
        }

        val layerIdInVps = IntArray(maxLayerId + 1)
        for (i in 0..<maxLayers) {
            layerIdInVps[min(layerIdInNuh[i], maxLayerId)] = i
        }
        val layerInfosBuilder = mutableListOf<H265LayerInfo?>()
        for (i in 0..maxLayerId) {
            val viewIdValIdx = min(viewOrderIdx[i], numViews - 1)
            val viewIdVal = if (viewIdValIdx >= 0) viewIdVals[viewIdValIdx] else INVALID_ID
            layerInfosBuilder.add(H265LayerInfo(layerIdInVps[i], viewIdVal))
        }
        val layerInfos = layerInfosBuilder.toList()

        if (layerInfos[0]!!.viewId == INVALID_ID) {
            // The base layer must be the primary view; fallback to single layer HEVC.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }
        var secondaryViewLayerId = INVALID_ID
        for (i in 1..maxLayerId) {
            if (layerInfos[i]!!.viewId != INVALID_ID) {
                secondaryViewLayerId = i
                break
            }
        }
        if (secondaryViewLayerId == INVALID_ID) {
            // No secondary view defined; fallback to single layer HEVC.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }

        // Derive H.265 layer dependency structure following (F-4) in subsection F.7.4.3.1.1.
        val directDependencyFlag = Array<BooleanArray?>(maxLayers) { BooleanArray(maxLayers) }
        val dependencyFlag = Array<BooleanArray?>(maxLayers) { BooleanArray(maxLayers) }
        for (i in 1..<maxLayers) {
            for (j in 0..<i) {
                dependencyFlag[i]!![j] = data.readBit()
                directDependencyFlag[i]!![j] =
                    dependencyFlag[i]!![j] // direct_dependency_flag[i][j]
            }
        }
        for (i in 1..<maxLayers) {
            for (j in 0..<maxLayers - 1) {
                for (k in 0..<i) {
                    if (dependencyFlag[i]!![k] && dependencyFlag[k]!![j]) {
                        dependencyFlag[i]!![j] = true
                        break
                    }
                }
            }
        }

        // Derive numDirectRefLayers following (F-5) in subsection F.7.4.3.1.1.
        val numDirectRefLayers = IntArray(maxLayerId + 1)
        for (i in 0..<maxLayers) {
            var d = 0
            for (j in 0..<i) {
                d += if (directDependencyFlag[i]!![j]) 1 else 0
            }
            numDirectRefLayers[layerIdInNuh[i]] = d
        }
        // Derive numIndependentLayers following (F-6) in subsection F.7.4.3.1.1.
        var numIndependentLayers = 0
        for (i in 0..<maxLayers) {
            if (numDirectRefLayers[layerIdInNuh[i]] == 0) {
                numIndependentLayers++
            }
        }
        if (numIndependentLayers > 1) {
            // Current implementation only supports one independent layer - the base layer.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }

        // Since only one independent layer is expected, num_add_layer_sets is implied to be 0.
        val subLayersVpsMaxMinus1 = IntArray(maxLayers)
        val maxSubLayersInLayerSet = IntArray(numLayerSets)
        if (data.readBit()) { // vps_sub_layers_max_minus1_present_flag
            for (i in 0..<maxLayers) {
                subLayersVpsMaxMinus1[i] = data.readBits(3) // sub_layers_vps_max_minus1[i]
            }
        } else {
            Arrays.fill(subLayersVpsMaxMinus1, 0, maxLayers, maxSubLayersMinus1)
        }
        for (i in 0..<numLayerSets) {
            var layerSetMaxSubLayersMinus1 = 0 // maxSlMinus1
            for (k in 0..<numLayersInIdList[i]) {
                val layerId = layerSetLayerIdList[i]!![k]
                layerSetMaxSubLayersMinus1 =
                    max(
                        layerSetMaxSubLayersMinus1,
                        subLayersVpsMaxMinus1[layerInfos.get(layerId)!!.layerIdInVps]
                    )
            }
            maxSubLayersInLayerSet[i] = layerSetMaxSubLayersMinus1 + 1
        }

        if (data.readBit()) { // max_tid_ref_present_flag
            for (i in 0..<maxLayers - 1) {
                for (j in i + 1..<maxLayers) {
                    if (directDependencyFlag[j]!![i]) {
                        data.skipBits(3) // max_tid_il_ref_pics_plus1[i][j]
                    }
                }
            }
        }
        data.skipBit() // default_ref_layers_active_flag

        // Get profile_tier_level()s needed for non-base layer.
        val numProfileTierLevels = data.readUnsignedExpGolombCodedInt() + 1
        val profileTierLevelsBuilder = mutableListOf<H265ProfileTierLevel?>()
        profileTierLevelsBuilder.add(profileTierLevel)
        if (numProfileTierLevels > 1) {
            profileTierLevelsBuilder.add(baseLayerProfileTierLevel)
            var prevProfileTierLevel = baseLayerProfileTierLevel
            for (i in 2..<numProfileTierLevels) {
                val nextProfileTierLevel =
                    parseH265ProfileTierLevel(
                        data,  
                        data.readBit(),
                        maxSubLayersMinus1,
                        prevProfileTierLevel
                    )
                profileTierLevelsBuilder.add(nextProfileTierLevel)
                prevProfileTierLevel = nextProfileTierLevel
            }
        }
        profileTierLevels = profileTierLevelsBuilder

        // Define output layer sets.
        val numOutputLayerSets = numLayerSets + data.readUnsignedExpGolombCodedInt() // num_add_olss
        if (numOutputLayerSets > numLayerSets) {
            // Current implementation only supports the output layer set being the same as the layer set.
            // Fallback to single layer HEVC if the constraint is not met.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }

        val defaultOutputLayerIdc = data.readBits(2) // default_output_layer_idc

        val outputLayerFlag =
            Array<BooleanArray?>(numOutputLayerSets) { BooleanArray(maxLayerId + 1) }
        val numOutputLayersInOutputLayerSet = IntArray(numOutputLayerSets)
        val olsHighestOutputLayerId = IntArray(numOutputLayerSets)
        for (i in 0..<numLayerSets) {
            numOutputLayersInOutputLayerSet[i] = 0
            olsHighestOutputLayerId[i] = layerSetMaxLayerId[i]
            if (defaultOutputLayerIdc == 0) {
                Arrays.fill(outputLayerFlag[i], 0, numLayersInIdList[i], true)
                numOutputLayersInOutputLayerSet[i] = numLayersInIdList[i]
            } else if (defaultOutputLayerIdc == 1) {
                val highestLayerId = layerSetMaxLayerId[i]
                for (j in 0..<numLayersInIdList[i]) {
                    outputLayerFlag[i]!![j] = layerSetLayerIdList[i]!![j] == highestLayerId
                }
                numOutputLayersInOutputLayerSet[i] = 1
            } else {
                outputLayerFlag[0]!![0] = true
                numOutputLayersInOutputLayerSet[0] = 1
            }
        }

        val profileTierLevelIndices = IntArray(maxLayerId + 1)
        val necessaryLayerFlag =
            Array<BooleanArray?>(numOutputLayerSets) { BooleanArray(maxLayerId + 1) }
        var targetOutputLayerSetIdx = 0

        for (i in 1..<numOutputLayerSets) {
            if (defaultOutputLayerIdc == 2) {
                for (j in 0..<numLayersInIdList[i]) {
                    outputLayerFlag[i]!![j] = data.readBit() // output_layer_flag[i][j]
                    numOutputLayersInOutputLayerSet[i] += if (outputLayerFlag[i]!![j]) 1 else 0
                    if (outputLayerFlag[i]!![j]) {
                        olsHighestOutputLayerId[i] = layerSetLayerIdList[i]!![j]
                    }
                }
            }

            // Look for the first output layer set that includes the base layer (primary view) and the
            // layer with the secondary view.
            if (targetOutputLayerSetIdx == 0 && layerSetLayerIdList[i]!![0] == 0 && outputLayerFlag[i]!![0]) {
                for (j in 1..<numLayersInIdList[i]) {
                    if (layerSetLayerIdList[i]!![j] == secondaryViewLayerId
                        && outputLayerFlag[i]!![secondaryViewLayerId]
                    ) {
                        targetOutputLayerSetIdx = i
                    }
                }
            }

            for (j in 0..<numLayersInIdList[i]) {
                if (numProfileTierLevels > 1) {
                    necessaryLayerFlag[i]!![j] = outputLayerFlag[i]!![j]
                    val bitLen =
                        DoubleMath.log2(numProfileTierLevels.toDouble(), RoundingMode.CEILING)
                    if (!necessaryLayerFlag[i]!![j]) {
                        val currLayerIdInVps =
                            layerInfos.get(layerSetLayerIdList[i]!![j])!!.layerIdInVps
                        for (k in 0..<j) {
                            val refLayerIdInVps =
                                layerInfos.get(layerSetLayerIdList[i]!![k])!!.layerIdInVps
                            if (dependencyFlag[currLayerIdInVps]!![refLayerIdInVps]) {
                                necessaryLayerFlag[i]!![j] = true
                                break
                            }
                        }
                    }
                    if (necessaryLayerFlag[i]!![j]) {
                        if (targetOutputLayerSetIdx > 0 && i == targetOutputLayerSetIdx) {
                            // Only store the information needed for the target output layer set.
                            profileTierLevelIndices[j] =
                                data.readBits(bitLen) // profile_tier_level_idx[i][j]
                        } else {
                            data.skipBits(bitLen) // profile_tier_level_idx[i][j]
                        }
                    }
                }
            }
            if (numOutputLayersInOutputLayerSet[i] == 1
                && numDirectRefLayers[olsHighestOutputLayerId[i]] > 0
            ) {
                data.skipBit() // alt_output_layer_flag[i]
            }
        }

        if (targetOutputLayerSetIdx == 0) {
            // The selected target output layer set only contains the base layer.
            return H265VpsData(
                nalHeader,  
                null,
                baseLayerProfileTierLevelsAndIndices,  
                null,  
                null
            )
        }

        val repFormatsAndIndices = parseH265RepFormatsAndIndices(data, maxLayers)

        data.skipBits(2) // max_one_active_ref_layer_flag, vps_poc_lsb_aligned_flag
        for (i in 1..<maxLayers) {
            if (numDirectRefLayers[layerIdInNuh[i]] == 0) {
                data.skipBit() // poc_lsb_not_present_flag[i]
            }
        }

        skipH265DpbSize(
            data, numOutputLayerSets, maxSubLayersInLayerSet, numLayersInIdList, necessaryLayerFlag
        )

        skipToH265VuiPresentFlagAfterDpbSize(data, maxLayers, directDependencyFlag)

        var videoSignalInfosAndIndices: H265VideoSignalInfosAndIndices? = null
        if (data.readBit()) { // vps_vui_present_flag
            data.byteAlign()
            videoSignalInfosAndIndices =
                parseH265VideoSignalInfosAndIndices(
                    data, maxLayers, numLayerSets, maxSubLayersInLayerSet
                )
        }

        return H265VpsData(
            nalHeader,
            layerInfos,
            H265ProfileTierLevelsAndIndices(profileTierLevels, profileTierLevelIndices),
            repFormatsAndIndices,
            videoSignalInfosAndIndices
        )
    }

    fun parseH265SpsNalUnit(
        nalData: ByteArray, nalOffset: Int, nalLimit: Int, vpsData: H265VpsData?
    ): H265SpsData {
        val nalHeader =
            parseH265NalHeader(ParsableNalUnitBitArray(nalData, nalOffset, nalLimit))
        // The nal_unit_header() - nalHeader - is comprised of 2 bytes.
        return parseH265SpsNalUnitPayload(nalData, nalOffset + 2, nalLimit, nalHeader, vpsData)
    }

    fun parseH265SpsNalUnitPayload(
        nalData: ByteArray,
        nalOffset: Int,
        nalLimit: Int,
        nalHeader: H265NalHeader,
        vpsData: H265VpsData?
    ): H265SpsData {
        val data = ParsableNalUnitBitArray(nalData, nalOffset, nalLimit)
        data.skipBits(4) // sps_video_parameter_set_id
        // Represents sps_max_sub_layers_minus1 (when nuh_layer_id == 0) or
        // sps_ext_or_max_sub_layers_minus1 (when nuh_layer_id != 0).
        val maxSubLayersMinus1 = data.readBits(3)
        val multiLayerExtSpsFlag = nalHeader.layerId != 0 && maxSubLayersMinus1 == 7

        var layerIdInVps = 0
        if (vpsData != null && !vpsData.layerInfos.isEmpty()) {
            val layerId = min(nalHeader.layerId, vpsData.layerInfos.size - 1)
            layerIdInVps = vpsData.layerInfos.get(layerId)!!.layerIdInVps
        }
        var profileTierLevel: H265ProfileTierLevel? = null
        if (!multiLayerExtSpsFlag) {
            data.skipBit() // sps_temporal_id_nesting_flag
            profileTierLevel =
                parseH265ProfileTierLevel(
                    data,  
                    true,
                    maxSubLayersMinus1,  
                    null
                )
        } else if (vpsData != null) {
            val profileTierLevelIdx = vpsData.profileTierLevelsAndIndices.indices[layerIdInVps]
            if (vpsData.profileTierLevelsAndIndices.profileTierLevels.size > profileTierLevelIdx) {
                profileTierLevel =
                    vpsData.profileTierLevelsAndIndices.profileTierLevels.get(profileTierLevelIdx)
            }
        }

        val seqParameterSetId = data.readUnsignedExpGolombCodedInt()
        var chromaFormatIdc = 0
        var frameWidth = 0
        var frameHeight = 0
        var decodedWidth = 0
        var decodedHeight = 0
        var bitDepthLumaMinus8 = 0
        var bitDepthChromaMinus8 = 0
        var spsRepFormatIdx = C.INDEX_UNSET
        if (multiLayerExtSpsFlag) {
            if (data.readBit()) { // update_rep_format_flag
                spsRepFormatIdx = data.readBits(8) // sps_rep_format_idx
            }
            if (vpsData != null && vpsData.repFormatsAndIndices != null) {
                if (spsRepFormatIdx == C.INDEX_UNSET) {
                    spsRepFormatIdx = vpsData.repFormatsAndIndices.indices[layerIdInVps]
                }
                if (spsRepFormatIdx != C.INDEX_UNSET
                    && vpsData.repFormatsAndIndices.repFormats.size > spsRepFormatIdx
                ) {
                    val repFormat = vpsData.repFormatsAndIndices.repFormats.get(spsRepFormatIdx)
                    chromaFormatIdc = repFormat.chromaFormatIdc
                    decodedWidth = repFormat.width
                    decodedHeight = repFormat.height
                    frameWidth = decodedWidth
                    frameHeight = decodedHeight
                    bitDepthLumaMinus8 = repFormat.bitDepthLumaMinus8
                    bitDepthChromaMinus8 = repFormat.bitDepthChromaMinus8
                }
            }
        } else {
            chromaFormatIdc = data.readUnsignedExpGolombCodedInt()
            if (chromaFormatIdc == 3) {
                data.skipBit() // separate_colour_plane_flag
            }
            decodedWidth = data.readUnsignedExpGolombCodedInt()
            decodedHeight = data.readUnsignedExpGolombCodedInt()
            if (data.readBit()) { // conformance_window_flag
                val confWinLeftOffset = data.readUnsignedExpGolombCodedInt()
                val confWinRightOffset = data.readUnsignedExpGolombCodedInt()
                val confWinTopOffset = data.readUnsignedExpGolombCodedInt()
                val confWinBottomOffset = data.readUnsignedExpGolombCodedInt()
                frameWidth =
                    applyConformanceWindowToWidth(
                        decodedWidth, chromaFormatIdc, confWinLeftOffset, confWinRightOffset
                    )
                frameHeight =
                    applyConformanceWindowToHeight(
                        decodedHeight, chromaFormatIdc, confWinTopOffset, confWinBottomOffset
                    )
            } else {
                frameWidth = decodedWidth
                frameHeight = decodedHeight
            }
            bitDepthLumaMinus8 = data.readUnsignedExpGolombCodedInt()
            bitDepthChromaMinus8 = data.readUnsignedExpGolombCodedInt()
        }
        val log2MaxPicOrderCntLsbMinus4 = data.readUnsignedExpGolombCodedInt()
        var maxNumReorderPics = -1
        if (!multiLayerExtSpsFlag) {
            val start = if (data.readBit()) 0 else maxSubLayersMinus1
            // for (i = sps_sub_layer_ordering_info_present_flag ? 0 : sps_max_sub_layers_minus1; ...)
            for (i in start..maxSubLayersMinus1) {
                data.readUnsignedExpGolombCodedInt() // sps_max_dec_pic_buffering_minus1[i]
                // sps_max_num_reorder_pics[i]
                maxNumReorderPics = max(data.readUnsignedExpGolombCodedInt(), maxNumReorderPics)
                data.readUnsignedExpGolombCodedInt() // sps_max_latency_increase_plus1[i]
            }
        }
        data.readUnsignedExpGolombCodedInt() // log2_min_luma_coding_block_size_minus3
        data.readUnsignedExpGolombCodedInt() // log2_diff_max_min_luma_coding_block_size
        data.readUnsignedExpGolombCodedInt() // log2_min_luma_transform_block_size_minus2
        data.readUnsignedExpGolombCodedInt() // log2_diff_max_min_luma_transform_block_size
        data.readUnsignedExpGolombCodedInt() // max_transform_hierarchy_depth_inter
        data.readUnsignedExpGolombCodedInt() // max_transform_hierarchy_depth_intra
        if (data.readBit()) { // scaling_list_enabled_flag
            var inferScalingListFlag = false
            if (multiLayerExtSpsFlag) {
                inferScalingListFlag = data.readBit() // sps_infer_scaling_list_flag
            }
            if (inferScalingListFlag) {
                data.skipBits(6) // sps_scaling_list_ref_layer_id
            } else if (data.readBit()) { // sps_scaling_list_data_present_flag
                skipH265ScalingList(data)
            }
        }
        data.skipBits(2) // amp_enabled_flag (1), sample_adaptive_offset_enabled_flag (1)
        if (data.readBit()) { // pcm_enabled_flag
            // pcm_sample_bit_depth_luma_minus1 (4), pcm_sample_bit_depth_chroma_minus1 (4)
            data.skipBits(8)
            data.readUnsignedExpGolombCodedInt() // log2_min_pcm_luma_coding_block_size_minus3
            data.readUnsignedExpGolombCodedInt() // log2_diff_max_min_pcm_luma_coding_block_size
            data.skipBit() // pcm_loop_filter_disabled_flag
        }
        skipH265ShortTermReferencePictureSets(data)
        if (data.readBit()) { // long_term_ref_pics_present_flag
            val numLongTermRefPicsSps = data.readUnsignedExpGolombCodedInt()
            for (i in 0..<numLongTermRefPicsSps) {
                val ltRefPicPocLsbSpsLength = log2MaxPicOrderCntLsbMinus4 + 4
                // lt_ref_pic_poc_lsb_sps[i], used_by_curr_pic_lt_sps_flag[i]
                data.skipBits(ltRefPicPocLsbSpsLength + 1)
            }
        }
        data.skipBits(2) // sps_temporal_mvp_enabled_flag, strong_intra_smoothing_enabled_flag
        var colorSpace: @C.ColorSpace Int = Format.NO_VALUE
        var colorRange: @ColorRange Int = Format.NO_VALUE
        var colorTransfer: @ColorTransfer Int = Format.NO_VALUE
        var pixelWidthHeightRatio = 1f
        if (data.readBit()) { // vui_parameters_present_flag
            if (data.readBit()) { // aspect_ratio_info_present_flag
                val aspectRatioIdc = data.readBits(8)
                if (aspectRatioIdc == EXTENDED_SAR) {
                    val sarWidth = data.readBits(16)
                    val sarHeight = data.readBits(16)
                    if (sarWidth != 0 && sarHeight != 0) {
                        pixelWidthHeightRatio = sarWidth.toFloat() / sarHeight
                    }
                } else if (aspectRatioIdc < ASPECT_RATIO_IDC_VALUES.size) {
                    pixelWidthHeightRatio = ASPECT_RATIO_IDC_VALUES[aspectRatioIdc]
                } else {
                    w(TAG, "Unexpected aspect_ratio_idc value: " + aspectRatioIdc)
                }
            }
            if (data.readBit()) { // overscan_info_present_flag
                data.skipBit() // overscan_appropriate_flag
            }
            if (data.readBit()) { // video_signal_type_present_flag
                data.skipBits(3) // video_format
                colorRange =
                    if (data.readBit()) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED // video_full_range_flag
                if (data.readBit()) { // colour_description_present_flag
                    val colorPrimaries = data.readBits(8) // colour_primaries
                    val transferCharacteristics = data.readBits(8) // transfer_characteristics
                    data.skipBits(8) // matrix_coeffs

                    colorSpace = ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries)
                    colorTransfer =
                        ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics)
                }
            } else if (vpsData != null && vpsData.videoSignalInfosAndIndices != null) {
                val videoSignalInfoIdx = vpsData.videoSignalInfosAndIndices.indices[layerIdInVps]
                if (vpsData.videoSignalInfosAndIndices.videoSignalInfos.size > videoSignalInfoIdx) {
                    val videoSignalInfo =
                        vpsData.videoSignalInfosAndIndices.videoSignalInfos.get(videoSignalInfoIdx)
                    colorSpace = videoSignalInfo.colorSpace
                    colorRange = videoSignalInfo.colorRange
                    colorTransfer = videoSignalInfo.colorTransfer
                }
            }
            if (data.readBit()) { // chroma_loc_info_present_flag
                data.readUnsignedExpGolombCodedInt() // chroma_sample_loc_type_top_field
                data.readUnsignedExpGolombCodedInt() // chroma_sample_loc_type_bottom_field
            }
            data.skipBit() // neutral_chroma_indication_flag
            if (data.readBit()) { // field_seq_flag
                // field_seq_flag equal to 1 indicates that the coded video sequence conveys pictures that
                // represent fields, which means that frame height is double the picture height.
                frameHeight *= 2
            }
        }

        return H265SpsData(
            nalHeader,
            maxSubLayersMinus1,
            profileTierLevel,
            chromaFormatIdc,
            bitDepthLumaMinus8,
            bitDepthChromaMinus8,
            seqParameterSetId,
            frameWidth,
            frameHeight,
            decodedWidth,
            decodedHeight,
            pixelWidthHeightRatio,
            maxNumReorderPics,
            colorSpace,
            colorRange,
            colorTransfer
        )
    }

    fun parsePpsNalUnit(nalData: ByteArray, nalOffset: Int, nalLimit: Int): PpsData {
        return parsePpsNalUnitPayload(nalData, nalOffset + 1, nalLimit)
    }

    fun parsePpsNalUnitPayload(nalData: ByteArray, nalOffset: Int, nalLimit: Int): PpsData {
        val data = ParsableNalUnitBitArray(nalData, nalOffset, nalLimit)
        val picParameterSetId = data.readUnsignedExpGolombCodedInt()
        val seqParameterSetId = data.readUnsignedExpGolombCodedInt()
        data.skipBit() // entropy_coding_mode_flag
        val bottomFieldPicOrderInFramePresentFlag = data.readBit()
        return PpsData(picParameterSetId, seqParameterSetId, bottomFieldPicOrderInFramePresentFlag)
    }

    fun parseH265Sei3dRefDisplayInfo(
        nalData: ByteArray, nalOffset: Int, nalLimit: Int
    ): H265Sei3dRefDisplayInfoData? {
        val seiRbspPos = nalOffset + 2
        var last1BitBytePos = nalLimit - 1
        while (nalData[last1BitBytePos].toInt() == 0 && last1BitBytePos > seiRbspPos) {
            last1BitBytePos--
        }
        if (nalData[last1BitBytePos].toInt() == 0 || last1BitBytePos <= seiRbspPos) {
            return null
        }

        val data =
            ParsableNalUnitBitArray(nalData, seiRbspPos, last1BitBytePos + 1)
        // Every SEI message must have at least 2 bytes for the payload type and size.
        while (data.canReadBits(16)) {
            // Parsing sei_message() in subsection 7.3.5.
            var payloadType = 0
            var nextByte = data.readBits(8)
            while (nextByte == 255) {
                payloadType += 255
                nextByte = data.readBits(8)
            }
            payloadType += nextByte

            var payloadSize = 0
            nextByte = data.readBits(8)
            while (nextByte == 255) {
                payloadSize += 255
                nextByte = data.readBits(8)
            }
            payloadSize += nextByte
            if (payloadSize == 0 || !data.canReadBits(payloadSize)) {
                return null
            }

            if (payloadType == 176) { // three_dimensional_reference_displays_info()
                val precRefDisplayWidth =
                    data.readUnsignedExpGolombCodedInt() // prec_ref_display_width
                val refViewingDistanceFlag = data.readBit() // ref_viewing_distance_flag
                var precRefViewingDist = 0
                if (refViewingDistanceFlag) {
                    precRefViewingDist =
                        data.readUnsignedExpGolombCodedInt() // prec_ref_viewing_dist
                }
                val numRefDisplaysMinus1 =
                    data.readUnsignedExpGolombCodedInt() // num_ref_displays_minus1
                var leftViewId = -1
                var rightViewId = -1
                var exponentRefDisplayWidth = -1
                var mantissaRefDisplayWidth = -1
                var exponentRefViewingDist = -1
                var mantissaRefViewingDist = -1
                for (i in 0..numRefDisplaysMinus1) {
                    leftViewId = data.readUnsignedExpGolombCodedInt() // left_view_id[i]
                    rightViewId = data.readUnsignedExpGolombCodedInt() // right_view_id[i]
                    exponentRefDisplayWidth = data.readBits(6) // exponent_ref_display_width[i]
                    if (exponentRefDisplayWidth == 63) {
                        return null
                    }
                    val refDispWidthBits =
                        if (exponentRefDisplayWidth == 0)
                            max(0, precRefDisplayWidth - 30)
                        else
                            max(0, exponentRefDisplayWidth + precRefDisplayWidth - 31)
                    mantissaRefDisplayWidth =
                        data.readBits(refDispWidthBits) // mantissa_ref_display_width[i]
                    if (refViewingDistanceFlag) {
                        exponentRefViewingDist =
                            data.readBits(6) // exponent_ref_viewing_distance[i]
                        if (exponentRefViewingDist == 63) {
                            return null
                        }
                        val refViewDistBits =
                            if (exponentRefViewingDist == 0)
                                max(0, precRefViewingDist - 30)
                            else
                                max(0, exponentRefViewingDist + precRefViewingDist - 31)
                        mantissaRefViewingDist =
                            data.readBits(refViewDistBits) // mantissa_ref_viewing_distance[i]
                    }
                    if (data.readBit()) { // additional_shift_present_flag[i]
                        data.skipBits(10) // num_sample_shift_plus512[i]
                    }
                }

                return H265Sei3dRefDisplayInfoData(
                    precRefDisplayWidth,
                    precRefViewingDist,
                    numRefDisplaysMinus1 + 1,
                    leftViewId,
                    rightViewId,
                    exponentRefDisplayWidth,
                    mantissaRefDisplayWidth,
                    exponentRefViewingDist,
                    mantissaRefViewingDist
                )
            } else {
                data.skipBits(payloadSize * 8)
            }
        }
        return null
    }

    fun findNalUnit(
        data: ByteArray, startOffset: Int, endOffset: Int, prefixFlags: BooleanArray
    ): Int {
        val length = endOffset - startOffset

        Preconditions.checkState(length >= 0)
        if (length == 0) {
            return endOffset
        }

        if (prefixFlags[0]) {
            clearPrefixFlags(prefixFlags)
            return startOffset - 3
        } else if (length > 1 && prefixFlags[1] && data[startOffset].toInt() == 1) {
            clearPrefixFlags(prefixFlags)
            return startOffset - 2
        } else if (length > 2 && prefixFlags[2]
            && data[startOffset].toInt() == 0 && data[startOffset + 1].toInt() == 1
        ) {
            clearPrefixFlags(prefixFlags)
            return startOffset - 1
        }

        val limit = endOffset - 1
        // We're looking for the NAL unit start code prefix 0x000001. The value of i tracks the index of
        // the third byte.
        var i = startOffset + 2
        while (i < limit) {
            if ((data[i].toInt() and 0xFE) != 0) {
                // There isn't a NAL prefix here, or at the next two positions. Do nothing and let the
                // loop advance the index by three.
            } else if (data[i - 2].toInt() == 0 && data[i - 1].toInt() == 0 && data[i].toInt() == 1) {
                clearPrefixFlags(prefixFlags)
                return i - 2
            } else {
                // There isn't a NAL prefix here, but there might be at the next position. We should
                // only skip forward by one. The loop will skip forward by three, so subtract two here.
                i -= 2
            }
            i += 3
        }

        // True if the last three bytes in the data seen so far are {0,0,1}.
        prefixFlags[0] =
            if (length > 2)
                (data[endOffset - 3].toInt() == 0 && data[endOffset - 2].toInt() == 0 && data[endOffset - 1].toInt() == 1)
            else
                if (length == 2)
                    (prefixFlags[2] && data[endOffset - 2].toInt() == 0 && data[endOffset - 1].toInt() == 1)
                else
                    (prefixFlags[1] && data[endOffset - 1].toInt() == 1)
        // True if the last two bytes in the data seen so far are {0,0}.
        prefixFlags[1] =
            if (length > 1)
                data[endOffset - 2].toInt() == 0 && data[endOffset - 1].toInt() == 0
            else
                prefixFlags[2] && data[endOffset - 1].toInt() == 0
        // True if the last byte in the data seen so far is {0}.
        prefixFlags[2] = data[endOffset - 1].toInt() == 0

        return endOffset
    }

    fun clearPrefixFlags(prefixFlags: BooleanArray) {
        prefixFlags[0] = false
        prefixFlags[1] = false
        prefixFlags[2] = false
    }

    fun getH265BaseLayerCodecsString(csdBuffers: List<ByteArray>): String? {
        for (i in csdBuffers.indices) {
            val buffer = csdBuffers[i]
            val limit = buffer.size
            if (limit > 3) {
                val nalUnitPositions = findNalUnitPositions(buffer)
                for (j in nalUnitPositions.indices) {
                    // Start code prefix of 3 bytes is included in the nalUnitPositions.
                    if (nalUnitPositions.get(j)!! + 3 < limit) {
                        // Use the base layer (layerId == 0) SPS to derive new codecs string.
                        val data =
                            ParsableNalUnitBitArray(buffer, nalUnitPositions.get(j)!! + 3, limit)
                        val nalHeader = parseH265NalHeader(data)
                        if (nalHeader.nalUnitType == H265_NAL_UNIT_TYPE_SPS && nalHeader.layerId == 0) {
                            return createCodecStringFromH265SpsPalyoad(data)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findNalUnitPositions(data: ByteArray): List<Int?> {
        var offset = 0
        val prefixFlags = BooleanArray(3)
        val nalUnitPositions = mutableListOf<Int?>()
        while (offset < data.size) {
            val nalUnitOffset = findNalUnit(data, offset, data.size, prefixFlags)
            if (nalUnitOffset != data.size) {
                nalUnitPositions.add(nalUnitOffset)
            }
            offset = nalUnitOffset + 3
        }
        return nalUnitPositions
    }

    private fun createCodecStringFromH265SpsPalyoad(data: ParsableNalUnitBitArray): String? {
        data.skipBits(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = data.readBits(3)
        data.skipBit() // sps_temporal_id_nesting_flag
        val profileTierLevel =
            parseH265ProfileTierLevel(
                data,  
                true,
                maxSubLayersMinus1,  
                null
            )
        return buildHevcCodecString(
            profileTierLevel.generalProfileSpace,
            profileTierLevel.generalTierFlag,
            profileTierLevel.generalProfileIdc,
            profileTierLevel.generalProfileCompatibilityFlags,
            profileTierLevel.constraintBytes,
            profileTierLevel.generalLevelIdc
        )
    }

    private fun findNextUnescapeIndex(bytes: ByteArray, offset: Int, limit: Int): Int {
        for (i in offset..<limit - 2) {
            if (bytes[i].toInt() == 0x00 && bytes[i + 1].toInt() == 0x00 && bytes[i + 2].toInt() == 0x03) {
                return i
            }
        }
        return limit
    }

    private fun skipH265HrdParameters(
        data: ParsableNalUnitBitArray, commonInfPresentFlag: Boolean, maxSubLayersMinus1: Int
    ) {
        var nalHrdParametersPresentFlag = false
        var vclHrdParametersPresentFlag = false
        var subPicHrdParametersPresentFlag = false
        if (commonInfPresentFlag) {
            nalHrdParametersPresentFlag = data.readBit() // nal_hrd_parameters_present_flag
            vclHrdParametersPresentFlag = data.readBit() // vcl_hrd_parameters_present_flag
            if (nalHrdParametersPresentFlag || vclHrdParametersPresentFlag) {
                subPicHrdParametersPresentFlag = data.readBit() // sub_pic_hrd_params_present_flag
                if (subPicHrdParametersPresentFlag) {
                    // tick_divisor_minus2, du_cpb_removal_delay_increment_length_minus1,
                    // sub_pic_cpb_params_in_pic_timing_sei_flag, dpb_output_delay_du_length_minus1
                    data.skipBits(19)
                }
                data.skipBits(8) // bit_rate_scale, cpb_size_scale
                if (subPicHrdParametersPresentFlag) {
                    data.skipBits(4) // cpb_size_du_scale
                }
                // initial_cpb_removal_delay_length_minus1, au_cpb_removal_delay_length_minus1,
                // dpb_output_delay_length_minus1
                data.skipBits(15)
            }
        }
        for (i in 0..maxSubLayersMinus1) {
            val fixedPicRateGeneralFlag = data.readBit() // fixed_pic_rate_general_flag[i]
            var fixedPicRateWithinCvsFlag = fixedPicRateGeneralFlag
            var lowDelayHrdFlag = false
            var cpbCntMinus1 = 0
            if (!fixedPicRateGeneralFlag) {
                fixedPicRateWithinCvsFlag = data.readBit() // fixed_pic_rate_within_cvs_flag[i]
            }
            if (fixedPicRateWithinCvsFlag) {
                data.readUnsignedExpGolombCodedInt() // elemental_duration_in_tc_minus1[i]
            } else {
                lowDelayHrdFlag = data.readBit() // low_delay_hrd_flag[i]
            }
            if (!lowDelayHrdFlag) {
                cpbCntMinus1 = data.readUnsignedExpGolombCodedInt() // cpb_cnt_minus1[i]
            }
            var numSubLayerHrdParameters = 0
            numSubLayerHrdParameters += if (nalHrdParametersPresentFlag) 1 else 0
            numSubLayerHrdParameters += if (vclHrdParametersPresentFlag) 1 else 0
            for (j in 0..<numSubLayerHrdParameters) {
                for (k in 0..cpbCntMinus1) {
                    data.readUnsignedExpGolombCodedInt() // bit_rate_value_minus1[k]
                    data.readUnsignedExpGolombCodedInt() // cpb_size_value_minus1[k]
                    if (subPicHrdParametersPresentFlag) {
                        data.readUnsignedExpGolombCodedInt() // cpb_size_du_value_minus1[k]
                        data.readUnsignedExpGolombCodedInt() // bit_rate_du_value_minus1[k]
                    }
                    data.skipBit() // cbr_flag[k]
                }
            }
        }
    }

    private fun parseH265ProfileTierLevel(
        data: ParsableNalUnitBitArray,
        profilePresentFlag: Boolean,
        maxSubLayersMinus1: Int,
        prevProfileTierLevel: H265ProfileTierLevel?
    ): H265ProfileTierLevel {
        var generalProfileSpace = 0
        var generalTierFlag = false
        var generalProfileIdc = 0
        var generalProfileCompatibilityFlags = 0
        var constraintBytes = IntArray(6)
        if (profilePresentFlag) {
            generalProfileSpace = data.readBits(2) // general_profile_space
            generalTierFlag = data.readBit() // general_tier_flag
            generalProfileIdc = data.readBits(5) // general_profile_idc
            generalProfileCompatibilityFlags = 0
            for (i in 0..31) {
                if (data.readBit()) {
                    generalProfileCompatibilityFlags =
                        generalProfileCompatibilityFlags or (1 shl i) // general_profile_compatibility_flag[i]
                }
            }
            for (i in constraintBytes.indices) {
                constraintBytes[i] = data.readBits(8)
            }
        } else if (prevProfileTierLevel != null) {
            generalProfileSpace = prevProfileTierLevel.generalProfileSpace
            generalTierFlag = prevProfileTierLevel.generalTierFlag
            generalProfileIdc = prevProfileTierLevel.generalProfileIdc
            generalProfileCompatibilityFlags = prevProfileTierLevel.generalProfileCompatibilityFlags
            constraintBytes = prevProfileTierLevel.constraintBytes
        }
        val generalLevelIdc = data.readBits(8) // general_level_idc

        // Skip to the end of profile_tier_level().
        var toSkip = 0
        for (i in 0..<maxSubLayersMinus1) {
            if (data.readBit()) { // sub_layer_profile_present_flag[i]
                toSkip += 88
            }
            if (data.readBit()) { // sub_layer_level_present_flag[i]
                toSkip += 8
            }
        }
        data.skipBits(toSkip)
        if (maxSubLayersMinus1 > 0) {
            data.skipBits(2 * (8 - maxSubLayersMinus1)) // reserved_zero_2bits
        }

        return H265ProfileTierLevel(
            generalProfileSpace,
            generalTierFlag,
            generalProfileIdc,
            generalProfileCompatibilityFlags,
            constraintBytes,
            generalLevelIdc
        )
    }

    // Applies the conformance window offsets to the width following H.265/HEVC (2014) Table 6-1.
    private fun applyConformanceWindowToWidth(
        width: Int, chromaFormatIdc: Int, offsetLeft: Int, offsetRight: Int
    ): Int {
        val subWidthC = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
        return width - subWidthC * (offsetLeft + offsetRight)
    }

    // Applies the conformance window offsets to the height following H.265/HEVC (2014) Table 6-1.
    private fun applyConformanceWindowToHeight(
        height: Int, chromaFormatIdc: Int, offsetTop: Int, offsetBottom: Int
    ): Int {
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        return height - subHeightC * (offsetTop + offsetBottom)
    }

    private fun parseH265RepFormatsAndIndices(
        data: ParsableNalUnitBitArray, maxLayers: Int
    ): H265RepFormatsAndIndices {
        val numRepFormats = data.readUnsignedExpGolombCodedInt() + 1
        val repFormats = mutableListOf<H265RepFormat>()
        val repFormatIndices = IntArray(maxLayers)
        for (i in 0..<numRepFormats) {
            // rep_format()
            repFormats.add(parseH265RepFormat(data))
        }
        if (numRepFormats > 1 && data.readBit()) { // rep_format_idx_present_flag
            val bitLen = DoubleMath.log2(numRepFormats.toDouble(), RoundingMode.CEILING)
            // Here, vps_base_layer_internal_flag == true as we only support the case where the base layer
            // is included within the L-HEVC bitstream; hence the index i starts from 1.
            for (i in 1..<maxLayers) {
                repFormatIndices[i] = data.readBits(bitLen) // vps_rep_format_idx[i]
            }
        } else {
            for (i in 1..<maxLayers) {
                repFormatIndices[i] = min(i, numRepFormats - 1)
            }
        }
        return H265RepFormatsAndIndices(repFormats, repFormatIndices)
    }

    private fun parseH265RepFormat(data: ParsableNalUnitBitArray): H265RepFormat {
        var frameWidth = data.readBits(16) // pic_width_vps_in_luma_samples
        var frameHeight = data.readBits(16) // pic_height_vps_in_luma_samples
        var chromaFormatIdc = 0
        var bitDepthLumaMinus8 = 0
        var bitDepthChromaMinus8 = 0
        if (data.readBit()) { // chroma_and_bit_depth_vps_present_flag
            chromaFormatIdc = data.readBits(2) // chroma_format_vps_idc
            if (chromaFormatIdc == 3) {
                data.skipBit() // separate_colour_plane_vps_flag
            }
            bitDepthLumaMinus8 = data.readBits(4) // bit_depth_vps_luma_minus8
            bitDepthChromaMinus8 = data.readBits(4) // bit_depth_vps_chroma_minus8
        }
        if (data.readBit()) { // conformance_window_vps_flag
            val confWinLeftOffset = data.readUnsignedExpGolombCodedInt() // conf_win_vps_left_offset
            val confWinRightOffset =
                data.readUnsignedExpGolombCodedInt() // conf_win_vps_right_offset
            val confWinTopOffset = data.readUnsignedExpGolombCodedInt() // conf_win_vps_top_offset
            val confWinBottomOffset =
                data.readUnsignedExpGolombCodedInt() // conf_win_vps_bottom_offset
            frameWidth =
                applyConformanceWindowToWidth(
                    frameWidth, chromaFormatIdc, confWinLeftOffset, confWinRightOffset
                )
            frameHeight =
                applyConformanceWindowToHeight(
                    frameHeight, chromaFormatIdc, confWinTopOffset, confWinBottomOffset
                )
        }
        return H265RepFormat(
            chromaFormatIdc, bitDepthLumaMinus8, bitDepthChromaMinus8, frameWidth, frameHeight
        )
    }

    private fun skipH265DpbSize(
        data: ParsableNalUnitBitArray,
        numOutputLayerSets: Int,
        maxSubLayersInLayerSet: IntArray,
        numLayersInIdList: IntArray,
        necessaryLayerFlag: Array<BooleanArray?>
    ) {
        for (i in 1..<numOutputLayerSets) {
            val subLayerFlagInfoPresentFlag = data.readBit() // sub_layer_flag_info_present_flag[i]
            for (j in 0..<maxSubLayersInLayerSet[i]) {
                val subLayerDpbInfoPresentFlag: Boolean
                if (j > 0 && subLayerFlagInfoPresentFlag) {
                    subLayerDpbInfoPresentFlag =
                        data.readBit() // sub_layer_dpb_info_present_flag[i][j]
                } else {
                    subLayerDpbInfoPresentFlag = j == 0
                }
                if (subLayerDpbInfoPresentFlag) {
                    for (k in 0..<numLayersInIdList[i]) {
                        // Note that here we assume that vps_base_layer_internal_flag is always true.
                        if (necessaryLayerFlag[i]!![k]) {
                            data.readUnsignedExpGolombCodedInt() // max_vps_dec_pic_buffering_minus1[i][k][j]
                        }
                    }
                    data.readUnsignedExpGolombCodedInt() // max_vps_num_reorder_pics[i][j]
                    data.readUnsignedExpGolombCodedInt() // max_vps_latency_increase_plus1[i][j]
                }
            }
        }
    }

    private fun skipToH265VuiPresentFlagAfterDpbSize(
        data: ParsableNalUnitBitArray, maxLayers: Int, directDependencyFlag: Array<BooleanArray?>
    ) {
        val directDepTypeLen =
            data.readUnsignedExpGolombCodedInt() + 2 // direct_dep_type_len_minus2
        if (data.readBit()) { // direct_dependency_all_layers_flag
            data.skipBits(directDepTypeLen) // direct_dependency_all_layers_type
        } else {
            // Here, vps_base_layer_internal_flag == true as we only support the case where the base layer
            // is included within the L-HEVC bitstream; hence the index i starts from 1 and j from 0.
            for (i in 1..<maxLayers) {
                for (j in 0..<i) {
                    if (directDependencyFlag[i]!![j]) {
                        data.skipBits(directDepTypeLen) // direct_dependency_type[i][j]
                    }
                }
            }
        }
        val nonVuiExtensionLen =
            data.readUnsignedExpGolombCodedInt() // vps_non_vui_extension_length
        for (i in 1..nonVuiExtensionLen) {
            data.skipBits(8) // vps_non_vui_extension_data_byte
        }
    }

    private fun parseH265VideoSignalInfosAndIndices(
        data: ParsableNalUnitBitArray,
        maxLayers: Int,
        numLayerSets: Int,
        maxSubLayersInLayerSet: IntArray
    ): H265VideoSignalInfosAndIndices {
        var crossLayerIrapAlignedFlag = true
        if (!data.readBit()) { // cross_layer_pic_type_aligned_flag
            crossLayerIrapAlignedFlag = data.readBit() // cross_layer_irap_aligned_flag
        }
        if (crossLayerIrapAlignedFlag) {
            data.skipBit() // all_layers_idr_aligned_flag
        }

        val bitRatePresentVpsFlag = data.readBit() // bit_rate_present_vps_flag
        val picRatePresentVpsFlag = data.readBit() // pic_rate_present_vps_flag
        if (bitRatePresentVpsFlag || picRatePresentVpsFlag) {
            // Here, vps_base_layer_internal_flag == true as we only support the case where the base layer
            // is included within the L-HEVC bitstream; hence the index i starts from 0.
            for (i in 0..<numLayerSets) {
                for (j in 0..<maxSubLayersInLayerSet[i]) {
                    var bitRatePresentFlag = false
                    var picRatePresentFlag = false
                    if (bitRatePresentVpsFlag) {
                        bitRatePresentFlag = data.readBit() // bit_rate_present_flag[i][j]
                    }
                    if (picRatePresentVpsFlag) {
                        picRatePresentFlag = data.readBit() // pic_rate_present_flag[i][j]
                    }
                    if (bitRatePresentFlag) {
                        data.skipBits(32) // avg_bit_rate[i][j], max_bit_rate[i][j]
                    }
                    if (picRatePresentFlag) {
                        data.skipBits(18) // constant_pic_rate_idc[i][j], avg_pic_rate[i][j]
                    }
                }
            }
        }
        var numVideoSignalInfos = maxLayers
        val videoSignalInfoIdxPresentFlag = data.readBit() // video_signal_info_idx_present_flag
        if (videoSignalInfoIdxPresentFlag) {
            numVideoSignalInfos = data.readBits(4) + 1 // vps_num_video_signal_info_minus1
        }
        val videoSignalInfos = mutableListOf<H265VideoSignalInfo>()
        val videoSignalInfoIdices = IntArray(maxLayers)
        for (i in 0..<numVideoSignalInfos) {
            // video_signal_info()
            videoSignalInfos.add(parseH265VideoSignalInfo(data))
        }
        if (videoSignalInfoIdxPresentFlag && numVideoSignalInfos > 1) {
            // Here, vps_base_layer_internal_flag == true as we only support the case where the base layer
            // is included within the L-HEVC bitstream; hence the index i starts from 0.
            for (i in 0..<maxLayers) {
                videoSignalInfoIdices[i] = data.readBits(4) // vps_video_signal_info_idx[i]
            }
        }
        return H265VideoSignalInfosAndIndices(videoSignalInfos, videoSignalInfoIdices)
    }

    private fun parseH265VideoSignalInfo(data: ParsableNalUnitBitArray): H265VideoSignalInfo {
        data.skipBits(3) // video_vps_format
        val colorRange: @ColorRange Int =
            if (data.readBit()) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED // video_full_range_vps_flag
        val colorSpace: @C.ColorSpace Int =
            ColorInfo.isoColorPrimariesToColorSpace(data.readBits(8)) // colour_primaries_vps
        val colorTransfer: @ColorTransfer Int =
            ColorInfo.isoTransferCharacteristicsToColorTransfer(
                data.readBits(8)
            ) // transfer_characteristics_vps
        data.skipBits(8) // matrix_coeffs_vps

        return H265VideoSignalInfo(colorSpace, colorRange, colorTransfer)
    }

    private fun skipScalingList(bitArray: ParsableNalUnitBitArray, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0..<size) {
            if (nextScale != 0) {
                val deltaScale = bitArray.readSignedExpGolombCodedInt()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private fun skipHrdParameters(data: ParsableNalUnitBitArray) {
        val codedPictureBufferCount = data.readUnsignedExpGolombCodedInt() + 1 // cpb_cnt_minus1
        data.skipBits(8) // bit_rate_scale (4), cpb_size_scale (4)
        for (i in 0..<codedPictureBufferCount) {
            data.readUnsignedExpGolombCodedInt() // bit_rate_value_minus1[i]
            data.readUnsignedExpGolombCodedInt() // cpb_size_value_minus1[i]
            data.skipBit() // cbr_flag[i]
        }
        // initial_cpb_removal_delay_length_minus1 (5)
        // cpb_removal_delay_length_minus1 (5)
        // dpb_output_delay_length_minus1 (5)
        // time_offset_length (5)
        data.skipBits(20)
    }

    private fun skipH265ScalingList(bitArray: ParsableNalUnitBitArray) {
        for (sizeId in 0..3) {
            var matrixId = 0
            while (matrixId < 6) {
                if (!bitArray.readBit()) { // scaling_list_pred_mode_flag[sizeId][matrixId]
                    // scaling_list_pred_matrix_id_delta[sizeId][matrixId]
                    bitArray.readUnsignedExpGolombCodedInt()
                } else {
                    val coefNum = min(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) {
                        // scaling_list_dc_coef_minus8[sizeId - 2][matrixId]
                        bitArray.readSignedExpGolombCodedInt()
                    }
                    for (i in 0..<coefNum) {
                        bitArray.readSignedExpGolombCodedInt() // scaling_list_delta_coef
                    }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    private fun skipH265ShortTermReferencePictureSets(bitArray: ParsableNalUnitBitArray) {
        val numShortTermRefPicSets = bitArray.readUnsignedExpGolombCodedInt()
        // As this method applies in a SPS, each short term reference picture set only accesses data
        // from the previous one. This is because RefRpsIdx = stRpsIdx - (delta_idx_minus1 + 1), and
        // delta_idx_minus1 is always zero in a SPS. Hence we just keep track of variables from the
        // previous one as we iterate.
        var previousNumNegativePics = C.INDEX_UNSET
        var previousNumPositivePics = C.INDEX_UNSET
        var previousDeltaPocS0 = IntArray(0)
        var previousDeltaPocS1 = IntArray(0)
        for (stRpsIdx in 0..<numShortTermRefPicSets) {
            val numNegativePics: Int
            val numPositivePics: Int
            var deltaPocS0: IntArray
            var deltaPocS1: IntArray

            val interRefPicSetPredictionFlag = stRpsIdx != 0 && bitArray.readBit()
            if (interRefPicSetPredictionFlag) {
                val previousNumDeltaPocs = previousNumNegativePics + previousNumPositivePics

                val deltaRpsSign = if (bitArray.readBit()) 1 else 0
                val absDeltaRps = bitArray.readUnsignedExpGolombCodedInt() + 1
                val deltaRps = (1 - 2 * deltaRpsSign) * absDeltaRps

                val useDeltaFlags = BooleanArray(previousNumDeltaPocs + 1)
                for (j in 0..previousNumDeltaPocs) {
                    if (!bitArray.readBit()) { // used_by_curr_pic_flag[j]
                        useDeltaFlags[j] = bitArray.readBit()
                    } else {
                        // When use_delta_flag[j] is not present, its value is 1.
                        useDeltaFlags[j] = true
                    }
                }

                // Derive numNegativePics, numPositivePics, deltaPocS0 and deltaPocS1 as per Rec. ITU-T
                // H.265 v6 (06/2019) Section 7.4.8
                var i = 0
                deltaPocS0 = IntArray(previousNumDeltaPocs + 1)
                deltaPocS1 = IntArray(previousNumDeltaPocs + 1)
                for (j in previousNumPositivePics - 1 downTo 0) {
                    val dPoc = previousDeltaPocS1[j] + deltaRps
                    if (dPoc < 0 && useDeltaFlags[previousNumNegativePics + j]) {
                        deltaPocS0[i++] = dPoc
                    }
                }
                if (deltaRps < 0 && useDeltaFlags[previousNumDeltaPocs]) {
                    deltaPocS0[i++] = deltaRps
                }
                for (j in 0..<previousNumNegativePics) {
                    val dPoc = previousDeltaPocS0[j] + deltaRps
                    if (dPoc < 0 && useDeltaFlags[j]) {
                        deltaPocS0[i++] = dPoc
                    }
                }
                numNegativePics = i
                deltaPocS0 = deltaPocS0.copyOf(numNegativePics)

                i = 0
                for (j in previousNumNegativePics - 1 downTo 0) {
                    val dPoc = previousDeltaPocS0[j] + deltaRps
                    if (dPoc > 0 && useDeltaFlags[j]) {
                        deltaPocS1[i++] = dPoc
                    }
                }
                if (deltaRps > 0 && useDeltaFlags[previousNumDeltaPocs]) {
                    deltaPocS1[i++] = deltaRps
                }
                for (j in 0..<previousNumPositivePics) {
                    val dPoc = previousDeltaPocS1[j] + deltaRps
                    if (dPoc > 0 && useDeltaFlags[previousNumNegativePics + j]) {
                        deltaPocS1[i++] = dPoc
                    }
                }
                numPositivePics = i
                deltaPocS1 = deltaPocS1.copyOf(numPositivePics)
            } else {
                numNegativePics = bitArray.readUnsignedExpGolombCodedInt()
                numPositivePics = bitArray.readUnsignedExpGolombCodedInt()
                deltaPocS0 = IntArray(numNegativePics)
                for (i in 0..<numNegativePics) {
                    deltaPocS0[i] =
                        (if (i > 0) deltaPocS0[i - 1] else 0) - (bitArray.readUnsignedExpGolombCodedInt() + 1)
                    bitArray.skipBit() // used_by_curr_pic_s0_flag[i]
                }
                deltaPocS1 = IntArray(numPositivePics)
                for (i in 0..<numPositivePics) {
                    deltaPocS1[i] =
                        (if (i > 0) deltaPocS1[i - 1] else 0) + (bitArray.readUnsignedExpGolombCodedInt() + 1)
                    bitArray.skipBit() // used_by_curr_pic_s1_flag[i]
                }
            }
            previousNumNegativePics = numNegativePics
            previousNumPositivePics = numPositivePics
            previousDeltaPocS0 = deltaPocS0
            previousDeltaPocS1 = deltaPocS1
        }
    }

    private fun getNalStructureMimeType(format: Format): String? {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
            && format.codecs != null
        ) {
            if (format.codecs.startsWith("dva1") || format.codecs.startsWith("dvav")) {
                return MimeTypes.VIDEO_H264
            } else if (format.codecs.startsWith("dvh1") || format.codecs.startsWith("dvhe")) {
                return MimeTypes.VIDEO_H265
            }
        }
        return format.sampleMimeType
    }

    class SpsData(
        val profileIdc: Int,
        val constraintsFlagsAndReservedZero2Bits: Int,
        val levelIdc: Int,
        val seqParameterSetId: Int,
        val maxNumRefFrames: Int,
        val width: Int,
        val height: Int,
        val pixelWidthHeightRatio: Float,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val separateColorPlaneFlag: Boolean,
        val frameMbsOnlyFlag: Boolean,
        val frameNumLength: Int,
        val picOrderCountType: Int,
        val picOrderCntLsbLength: Int,
        val deltaPicOrderAlwaysZeroFlag: Boolean,
        val colorSpace: @C.ColorSpace Int,
        val colorRange: @ColorRange Int,
        val colorTransfer: @ColorTransfer Int,
        val maxNumReorderFrames: Int
    )

    class H265NalHeader(val nalUnitType: Int, val layerId: Int, val temporalId: Int)
    class H265LayerInfo
    (
        val layerIdInVps: Int,
        
        val viewId: Int
    )

    class H265ProfileTierLevel(
        val generalProfileSpace: Int,
        val generalTierFlag: Boolean,
        val generalProfileIdc: Int,
        val generalProfileCompatibilityFlags: Int,
        val constraintBytes: IntArray,
        val generalLevelIdc: Int
    )

    class H265ProfileTierLevelsAndIndices(
        profileTierLevels: List<H265ProfileTierLevel?>,
        
        val indices: IntArray
    ) {
        
        val profileTierLevels: List<H265ProfileTierLevel?> = profileTierLevels.toList()
    }

    class H265RepFormat(
        val chromaFormatIdc: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val width: Int,
        val height: Int
    )

    class H265RepFormatsAndIndices(
        repFormats: MutableList<H265RepFormat>,
        val indices: IntArray
    ) {
        
        val repFormats: List<H265RepFormat> = repFormats.toList()
    }

    class H265VideoSignalInfo(
        val colorSpace: @C.ColorSpace Int,
        val colorRange: @ColorRange Int,
        val colorTransfer: @ColorTransfer Int
    )

    class H265VideoSignalInfosAndIndices(
        videoSignalInfos: MutableList<H265VideoSignalInfo>,
        
        val indices: IntArray
    ) {
        
        val videoSignalInfos: List<H265VideoSignalInfo>

        init {
            this.videoSignalInfos = videoSignalInfos.toList()
        }
    }

    class H265VpsData(
        val nalHeader: H265NalHeader?,
        layerInfos: List<H265LayerInfo?>?,
        
        val profileTierLevelsAndIndices: H265ProfileTierLevelsAndIndices,
        
        val repFormatsAndIndices: H265RepFormatsAndIndices?,
        
        val videoSignalInfosAndIndices: H265VideoSignalInfosAndIndices?
    ) {
        val layerInfos: List<H265LayerInfo?>

        init {
            this.layerInfos = layerInfos?.toList() ?: listOf()
        }
    }

    class H265SpsData(
        val nalHeader: H265NalHeader?,
        val maxSubLayersMinus1: Int,
        val profileTierLevel: H265ProfileTierLevel?,
        val chromaFormatIdc: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val seqParameterSetId: Int,
        val width: Int,
        val height: Int,
        val decodedWidth: Int,
        val decodedHeight: Int,
        val pixelWidthHeightRatio: Float,
        val maxNumReorderPics: Int,
        val colorSpace: @C.ColorSpace Int,
        val colorRange: @ColorRange Int,
        val colorTransfer: @ColorTransfer Int
    )

    class PpsData(
        val picParameterSetId: Int,
        val seqParameterSetId: Int,
        val bottomFieldPicOrderInFramePresentFlag: Boolean
    )

    class H265Sei3dRefDisplayInfoData(
        val precRefDisplayWidth: Int,
        val precRefViewingDist: Int,
        val numRefDisplays: Int,
        val leftViewId: Int,
        val rightViewId: Int,
        val exponentRefDisplayWidth: Int,
        val mantissaRefDisplayWidth: Int,
        val exponentRefViewingDist: Int,
        val mantissaRefViewingDist: Int
    )
}
