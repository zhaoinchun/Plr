package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.C.ColorRange
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray

import androidx.media3.common.util.Util.formatInvariant

class Av1Config private constructor(
   val bitdepth: Int,
   val codecs: String?,
   val colorSpace: @C.ColorSpace Int = Format.NO_VALUE,
   val colorRange: @ColorRange Int = Format.NO_VALUE,
   val colorTransfer: @ColorTransfer Int = Format.NO_VALUE
) {
    companion object {
        private const val TAG = "Av1Config"

        @Throws(ParserException::class)
        fun parse(initializationData: ByteArray): Av1Config? {
            try {
                val bitArray = ParsableBitArray(initializationData)
                bitArray.skipBit() // marker
                val version = bitArray.readBits(7)
                if (version != 1) {
                    w(TAG, "Unsupported av1C version: " + version)
                    return null
                }
                val seqProfile = bitArray.readBits(3)
                val seqLevelIdx0 = bitArray.readBits(5)
                val seqTier0 = bitArray.readBit()
                val highBitdepth = bitArray.readBit() // high_bitdepth
                val twelveBit = bitArray.readBit() // twelve_bit
                val bitdepth: Int
                if (highBitdepth) {
                    bitdepth = if (twelveBit) 12 else 10
                } else {
                    bitdepth = 8
                }
                // Skip monochrome, chroma_subsampling_x, chroma_subsampling_y, chroma_sample_position,
                // reserved and initial_presentation_delay.
                bitArray.skipBits(13)
                val codecs: String = buildCodecString(seqProfile, seqLevelIdx0, seqTier0, bitdepth)
                // The configOBUs array is optional. If the payload is exactly 4 bytes, we are done.
                if (bitArray.bitsLeft() <= 0) {
                    return Av1Config(bitdepth, codecs)
                }

                // 5.3.1. General OBU syntax
                bitArray.skipBit() // obu_forbidden_bit
                val obuType = bitArray.readBits(4) // obu_type
                if (obuType != 1) { // obu_type != OBU_SEQUENCE_HEADER
                    i(TAG, "Unsupported obu_type: " + obuType)
                    return Av1Config(bitdepth, codecs)
                }
                if (bitArray.readBit()) { // obu_extension_flag
                    i(TAG, "Unsupported obu_extension_flag")
                    return Av1Config(bitdepth, codecs)
                }
                val obuHasSizeField = bitArray.readBit() // obu_has_size_field
                bitArray.skipBit() // obu_reserved_1bit
                // obu_size is unsigned leb128 and if obu_size <= 127 then it can be simplified as
                // readBits(8).
                if (obuHasSizeField && bitArray.readBits(8) > 127) { // obu_size
                    i(TAG, "Excessive obu_size")
                    return Av1Config(bitdepth, codecs)
                }
                // 5.5.1. General OBU sequence header syntax
                val obuSeqHeaderSeqProfile = bitArray.readBits(3) // seq_profile
                bitArray.skipBit() // still_picture
                if (bitArray.readBit()) { // reduced_still_picture_header
                    i(TAG, "Unsupported reduced_still_picture_header")
                    return Av1Config(bitdepth, codecs)
                }
                if (bitArray.readBit()) { // timing_info_present_flag
                    i(TAG, "Unsupported timing_info_present_flag")
                    return Av1Config(bitdepth, codecs)
                }
                if (bitArray.readBit()) { // initial_display_delay_present_flag
                    i(TAG, "Unsupported initial_display_delay_present_flag")
                    return Av1Config(bitdepth, codecs)
                }
                val operatingPointsCountMinus1 =
                    bitArray.readBits(5) // operating_points_cnt_minus_1
                for (i in 0..operatingPointsCountMinus1) {
                    bitArray.skipBits(12) // operating_point_idc[i]
                    val seqLevelIdx = bitArray.readBits(5) // seq_level_idx[i]
                    if (seqLevelIdx > 7) {
                        bitArray.skipBit() // seq_tier[i]
                    }
                }
                val frameWidthBitsMinus1 = bitArray.readBits(4) // frame_width_bits_minus_1
                val frameHeightBitsMinus1 = bitArray.readBits(4) // frame_height_bits_minus_1
                bitArray.skipBits(frameWidthBitsMinus1 + 1) // max_frame_width_minus_1
                bitArray.skipBits(frameHeightBitsMinus1 + 1) // max_frame_height_minus_1
                if (bitArray.readBit()) { // frame_id_numbers_present_flag
                    bitArray.skipBits(7) // delta_frame_id_length_minus_2, additional_frame_id_length_minus_1
                }
                bitArray.skipBits(7) // use_128x128_superblock...enable_dual_filter: 7 flags
                val enableOrderHint = bitArray.readBit() // enable_order_hint
                if (enableOrderHint) {
                    bitArray.skipBits(2) // enable_jnt_comp, enable_ref_frame_mvs
                }
                val seqForceScreenContentTools =
                    if (bitArray.readBit() // seq_choose_screen_content_tools
                    )
                        2 // SELECT_SCREEN_CONTENT_TOOLS
                    else
                        bitArray.readBits(1) // seq_force_screen_content_tools
                if (seqForceScreenContentTools > 0) {
                    if (!bitArray.readBit()) { // seq_choose_integer_mv
                        bitArray.skipBits(1) // seq_force_integer_mv
                    }
                }
                if (enableOrderHint) {
                    bitArray.skipBits(3) // order_hint_bits_minus_1
                }
                bitArray.skipBits(3) // enable_superres, enable_cdef, enable_restoration
                // 5.5.2. OBU Color config syntax
                val colorConfigHighBitdepth = bitArray.readBit() // high_bitdepth
                if (obuSeqHeaderSeqProfile == 2 && colorConfigHighBitdepth) {
                    bitArray.skipBit() // twelve_bit
                }

                val monochrome = (obuSeqHeaderSeqProfile != 1) && bitArray.readBit() // mono_chrome

                if (!bitArray.readBit()) { // color_description_present_flag
                    return Av1Config(bitdepth, codecs)
                }
                val colorPrimaries = bitArray.readBits(8) // color_primaries
                val transferCharacteristics = bitArray.readBits(8) // transfer_characteristics
                val matrixCoefficients = bitArray.readBits(8) // matrix_coefficients
                val colorRangeValue =
                    if (!monochrome && colorPrimaries == 1 // CP_BT_709
                        && transferCharacteristics == 13 // TC_SRGB
                        && matrixCoefficients == 0
                    )
                        1
                    else
                        bitArray.readBits(1) // color_range;
                val colorSpace: @C.ColorSpace Int =
                    ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries)
                val colorRange: @ColorRange Int =
                    if (colorRangeValue == 1) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED
                val colorTransfer: @ColorTransfer Int =
                    ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics)
                return Av1Config(bitdepth, codecs, colorSpace, colorRange, colorTransfer)
            } catch (e: RuntimeException) {
                throw ParserException.createForMalformedContainer("Error parsing AV1 config", e)
            }
        }

        private fun buildCodecString(
            seqProfile: Int, seqLevelIdx0: Int, seqTier0: Boolean, bitdepth: Int
        ): String {
            return ("av01"
                    + "."
                    + seqProfile
                    + "."
                    + formatInvariant("%02d", seqLevelIdx0)
                    + (if (seqTier0) "H" else "M")
                    + "."
                    + formatInvariant("%02d", bitdepth))
        }
    }
}
