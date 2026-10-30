package androidx.media3.extractor

import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.NalUnitUtil
import java.util.Locale

class VvcConfig private constructor(
   val initializationData: List<ByteArray>?,
   val nalUnitLengthFieldLength: Int,
   val codecs: String?,
   val bitdepthLuma: Int
) {
    companion object {
        @Throws(ParserException::class)
        fun parse(data: ParsableByteArray): VvcConfig {
            try {
                // 11.2.4.3.2: VvcConfigurationBox extends FullBox (version 0, flags 0).
                if (data.readInt() != 0) {
                    throw ParserException.createForMalformedContainer(
                        "Unsupported VVC version",
                        null
                    )
                }

                // Clause 11.2.4.2.2: VvcDecoderConfigurationRecord
                // reserved (5), lengthSizeMinusOne (2), ptl_present_flag (1)
                val firstByte = data.readUnsignedByte()
                val lengthSizeMinusOne = (firstByte shr 1) and 0x03
                val ptlPresentFlag = (firstByte and 0x01) != 0
                val nalUnitLengthFieldLength = lengthSizeMinusOne + 1

                var profileIdc = 0
                var levelIdc = 0
                var tierString = "L"
                var bitDepthMinus8 = 0

                if (ptlPresentFlag) {
                    // Clause 11.2.4.2.3: VvcPTLRecord
                    data.skipBytes(1) // ols_idx (8)
                    // ols_idx (1), num_sublayers (3), constant_frame_rate (2), chroma_format_idc (2)
                    val olsAndSublayersByte = data.readUnsignedByte()
                    val numSublayers = (olsAndSublayersByte shr 4) and 0x07

                    val bitDepthByte = data.readUnsignedByte()
                    // bit_depth_minus8 (3 bits) from bits 7-5
                    bitDepthMinus8 = (bitDepthByte shr 5) and 0x07

                    val constraintInfoByte = data.readUnsignedByte()
                    // reserved (2), num_bytes_constraint_info (6)
                    val numBytesConstraintInfo = constraintInfoByte and 0x3F

                    val profileAndTierByte = data.readUnsignedByte()
                    // general_profile_idc (7), general_tier_flag (1)
                    profileIdc = (profileAndTierByte shr 1) and 0x7F
                    tierString = if ((profileAndTierByte and 0x01) != 0) "H" else "L"

                    levelIdc = data.readUnsignedByte() // general_level_idc (8)

                    data.skipBytes(numBytesConstraintInfo)
                    if (numSublayers > 1) {
                        // Skip ptl_sublayer_level_present_flag and ptl_reserved_zero_bit (1 byte total)
                        val sublayerFlags = data.readUnsignedByte()
                        for (i in 0..<numSublayers - 1) {
                            // Check ptl_sublayer_level_present_flag[i] starting from MSB
                            if (((sublayerFlags shr (7 - i)) and 0x01) != 0) {
                                data.skipBytes(1) // unsigned int(8) sublayer_level_idc[i]
                            }
                        }
                    }

                    val numSubProfiles = data.readUnsignedByte() // ptl_num_sub_profiles (8)
                    data.skipBytes(numSubProfiles * 4) // general_sub_profile_idc[j] (32 bits each)

                    // Skip max_picture_width (16), max_picture_height (16), and avg_frame_rate (16)
                    data.skipBytes(6)
                }

                val numArrays = data.readUnsignedByte() // numArrays (8)
                val csdStartPosition = data.getPosition()
                var csdLength = 0
                // First pass: calculate total length for the single concatenated CSD buffer.
                for (i in 0..<numArrays) {
                    val arrayHeader = data.readUnsignedByte()
                    val nalType =
                        arrayHeader and 0x1F // array_completeness (1), reserved (2), NAL_unit_type (5)
                    val numNalus =
                        if (nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_DCI
                            && nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_OPI
                        )
                            data.readUnsignedShort()
                        else
                            1
                    for (j in 0..<numNalus) {
                        val nalUnitLength = data.readUnsignedShort() // nalUnitLength (16)
                        csdLength += 4 + nalUnitLength // 4-byte start code + NALU
                        data.skipBytes(nalUnitLength)
                    }
                }

                data.setPosition(csdStartPosition)
                val buffer = ByteArray(csdLength)
                var bufferPosition = 0
                // Second pass: fill the buffer with start codes.
                for (i in 0..<numArrays) {
                    val arrayHeader = data.readUnsignedByte()
                    val nalType = arrayHeader and 0x1F
                    val numNalus =
                        if (nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_DCI
                            && nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_OPI
                        )
                            data.readUnsignedShort()
                        else
                            1
                    for (j in 0..<numNalus) {
                        val nalUnitLength = data.readUnsignedShort()
                        System.arraycopy(NalUnitUtil.NAL_START_CODE, 0, buffer, bufferPosition, 4)
                        bufferPosition += 4
                        data.readBytes(buffer, bufferPosition, nalUnitLength)
                        bufferPosition += nalUnitLength
                    }
                }

                // Build RFC 6381 codec string: vvc1.profile_idc.tier_and_level
                val codecs =
                    String.format(Locale.US, "vvc1.%d.%s%d", profileIdc, tierString, levelIdc)
                return VvcConfig(
                    listOf(buffer),
                    nalUnitLengthFieldLength,
                    codecs,
                    bitDepthMinus8 + 8
                )
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw ParserException.createForMalformedContainer(
                    "Error parsing VVC configuration",
                    e
                )
            }
        }
    }
}
