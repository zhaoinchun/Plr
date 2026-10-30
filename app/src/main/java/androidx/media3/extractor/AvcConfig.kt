
package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.C.ColorRange
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.CodecSpecificDataUtil.buildAvcCodecString
import androidx.media3.common.util.CodecSpecificDataUtil.buildNalUnit
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.NalUnitUtil
import androidx.media3.container.NalUnitUtil.parseSpsNalUnit

class AvcConfig private constructor(
   val initializationData: MutableList<ByteArray>?,
   val nalUnitLengthFieldLength: Int,
   val width: Int,
   val height: Int,
   val bitdepthLuma: Int,
   val bitdepthChroma: Int,
   val colorSpace: @C.ColorSpace Int,
   val colorRange: @ColorRange Int,
   val colorTransfer: @ColorTransfer Int,
   val maxNumReorderFrames: Int,
   val pixelWidthHeightRatio: Float,
   val codecs: String?
) {
    companion object {

        @Throws(ParserException::class)
        fun parse(data: ParsableByteArray): AvcConfig {
            try {
                data.skipBytes(4) // Skip to the AVCDecoderConfigurationRecord (defined in 14496-15)
                val nalUnitLengthFieldLength = (data.readUnsignedByte() and 0x3) + 1
                check(nalUnitLengthFieldLength != 3)
                val initializationData: MutableList<ByteArray> = ArrayList<ByteArray>()
                val numSequenceParameterSets = data.readUnsignedByte() and 0x1F
                for (j in 0..<numSequenceParameterSets) {
                    initializationData.add(buildNalUnitForChild(data))
                }
                val numPictureParameterSets = data.readUnsignedByte()
                for (j in 0..<numPictureParameterSets) {
                    initializationData.add(buildNalUnitForChild(data))
                }

                var width = Format.NO_VALUE
                var height = Format.NO_VALUE
                var bitdepthLuma = Format.NO_VALUE
                var bitdepthChroma = Format.NO_VALUE
                var colorSpace: @C.ColorSpace Int = Format.NO_VALUE
                var colorRange: @ColorRange Int = Format.NO_VALUE
                var colorTransfer: @ColorTransfer Int = Format.NO_VALUE
                var pixelWidthHeightRatio = 1f
                var codecs: String? = null
                // Max possible value defined in section E.2.1 of the H.264 spec.
                var maxNumReorderFrames = 16
                if (numSequenceParameterSets > 0) {
                    val sps = initializationData[0]
                    val spsData =
                        parseSpsNalUnit(
                            initializationData[0], NalUnitUtil.NAL_START_CODE.size, sps.size
                        )
                    width = spsData.width
                    height = spsData.height
                    bitdepthLuma = spsData.bitDepthLumaMinus8 + 8
                    bitdepthChroma = spsData.bitDepthChromaMinus8 + 8
                    colorSpace = spsData.colorSpace
                    colorRange = spsData.colorRange
                    colorTransfer = spsData.colorTransfer
                    maxNumReorderFrames = spsData.maxNumReorderFrames
                    pixelWidthHeightRatio = spsData.pixelWidthHeightRatio
                    codecs =
                        buildAvcCodecString(
                            spsData.profileIdc,
                            spsData.constraintsFlagsAndReservedZero2Bits,
                            spsData.levelIdc
                        )
                }

                return AvcConfig(
                    initializationData,
                    nalUnitLengthFieldLength,
                    width,
                    height,
                    bitdepthLuma,
                    bitdepthChroma,
                    colorSpace,
                    colorRange,
                    colorTransfer,
                    maxNumReorderFrames,
                    pixelWidthHeightRatio,
                    codecs
                )
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw ParserException.createForMalformedContainer("Error parsing AVC config", e)
            }
        }

        private fun buildNalUnitForChild(data: ParsableByteArray): ByteArray {
            val length = data.readUnsignedShort()
            val offset = data.getPosition()
            data.skipBytes(length)
            return buildNalUnit(data.data, offset, length)
        }
    }
}
