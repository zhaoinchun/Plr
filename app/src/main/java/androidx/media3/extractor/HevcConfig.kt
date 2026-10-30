
package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.C.ColorRange
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.C.StereoMode
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.CodecSpecificDataUtil.buildHevcCodecString
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.NalUnitUtil
import androidx.media3.container.NalUnitUtil.H265VpsData
import androidx.media3.container.NalUnitUtil.parseH265Sei3dRefDisplayInfo
import androidx.media3.container.NalUnitUtil.parseH265SpsNalUnit
import androidx.media3.container.NalUnitUtil.parseH265VpsNalUnit

class HevcConfig private constructor(
  
   val initializationData: MutableList<ByteArray>?,
  
   val nalUnitLengthFieldLength: Int,
  
   val maxSubLayers: Int,
  
    val width: Int,
  
    val height: Int,
  
   val decodedWidth: Int,
  
   val decodedHeight: Int,
  
   val bitdepthLuma: Int,
  
   val bitdepthChroma: Int,
  
   val colorSpace: @C.ColorSpace Int,
  
   val colorRange: @ColorRange Int,
  
   val colorTransfer: @ColorTransfer Int,
  
   val stereoMode: @StereoMode Int,
  
   val pixelWidthHeightRatio: Float,
  
   val maxNumReorderPics: Int,
  
   val codecs: String?,
  
   val vpsData: H265VpsData?
) {
    companion object {
        
                @Throws(ParserException::class)
        fun parse(data: ParsableByteArray): HevcConfig {
            return parseImpl(data,  false,  null)
        }

                @Throws(ParserException::class)
        fun parseLayered(data: ParsableByteArray, vpsData: H265VpsData?): HevcConfig {
            return parseImpl(data,  true, vpsData)
        }

        @Throws(ParserException::class)
        private fun parseImpl(
            data: ParsableByteArray, layered: Boolean, vpsData: H265VpsData?
        ): HevcConfig {
            try {
                // Skip to the NAL unit length size field.
                if (layered) {
                    data.skipBytes(4)
                } else {
                    data.skipBytes(21)
                }
                val lengthSizeMinusOne = data.readUnsignedByte() and 0x03

                // Calculate the combined size of all VPS/SPS/PPS bitstreams.
                val numberOfArrays = data.readUnsignedByte()
                var csdLength = 0
                val csdStartPosition = data.getPosition()
                for (i in 0..<numberOfArrays) {
                    data.skipBytes(1) // completeness (1), reserved (1), nal_unit_type (6)
                    val numberOfNalUnits = data.readUnsignedShort()
                    for (j in 0..<numberOfNalUnits) {
                        val nalUnitLength = data.readUnsignedShort()
                        csdLength += 4 + nalUnitLength // Start code and NAL unit.
                        data.skipBytes(nalUnitLength)
                    }
                }

                // Concatenate the codec-specific data into a single buffer.
                data.setPosition(csdStartPosition)
                val buffer = ByteArray(csdLength)
                var maxSubLayers = Format.NO_VALUE
                var bufferPosition = 0
                var width = Format.NO_VALUE
                var height = Format.NO_VALUE
                var decodedWidth = Format.NO_VALUE
                var decodedHeight = Format.NO_VALUE
                var bitdepthLuma = Format.NO_VALUE
                var bitdepthChroma = Format.NO_VALUE
                var colorSpace: @C.ColorSpace Int = Format.NO_VALUE
                var colorRange: @ColorRange Int = Format.NO_VALUE
                var colorTransfer: @ColorTransfer Int = Format.NO_VALUE
                var stereoMode: @StereoMode Int = Format.NO_VALUE
                var pixelWidthHeightRatio = 1f
                var maxNumReorderPics = Format.NO_VALUE
                var codecs: String? = null
                var currentVpsData = vpsData
                for (i in 0..<numberOfArrays) {
                    val nalUnitType =
                        data.readUnsignedByte() and 0x3F // completeness (1), reserved (1), nal_unit_type (6)
                    val numberOfNalUnits = data.readUnsignedShort()
                    for (j in 0..<numberOfNalUnits) {
                        val nalUnitLength = data.readUnsignedShort()
                        System.arraycopy(
                            NalUnitUtil.NAL_START_CODE,
                            0,
                            buffer,
                            bufferPosition,
                            NalUnitUtil.NAL_START_CODE.size
                        )
                        bufferPosition += NalUnitUtil.NAL_START_CODE.size
                        System.arraycopy(
                            data.data,
                            data.getPosition(),
                            buffer,
                            bufferPosition,
                            nalUnitLength
                        )
                        if (nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_VPS && j == 0) {
                            currentVpsData =
                                parseH265VpsNalUnit(
                                    buffer, bufferPosition, bufferPosition + nalUnitLength
                                )
                        } else if (nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_SPS && j == 0) {
                            val spsData =
                                parseH265SpsNalUnit(
                                    buffer,
                                    bufferPosition,
                                    bufferPosition + nalUnitLength,
                                    currentVpsData
                                )
                            maxSubLayers = spsData.maxSubLayersMinus1 + 1
                            width = spsData.width
                            height = spsData.height
                            decodedWidth = spsData.decodedWidth
                            decodedHeight = spsData.decodedHeight
                            bitdepthLuma = spsData.bitDepthLumaMinus8 + 8
                            bitdepthChroma = spsData.bitDepthChromaMinus8 + 8
                            colorSpace = spsData.colorSpace
                            colorRange = spsData.colorRange
                            colorTransfer = spsData.colorTransfer
                            pixelWidthHeightRatio = spsData.pixelWidthHeightRatio
                            maxNumReorderPics = spsData.maxNumReorderPics

                            if (spsData.profileTierLevel != null) {
                                codecs =
                                    buildHevcCodecString(
                                        spsData.profileTierLevel.generalProfileSpace,
                                        spsData.profileTierLevel.generalTierFlag,
                                        spsData.profileTierLevel.generalProfileIdc,
                                        spsData.profileTierLevel.generalProfileCompatibilityFlags,
                                        spsData.profileTierLevel.constraintBytes,
                                        spsData.profileTierLevel.generalLevelIdc
                                    )
                            }
                        } else if (nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI && j == 0) {
                            val seiData =
                                parseH265Sei3dRefDisplayInfo(
                                    buffer, bufferPosition, bufferPosition + nalUnitLength
                                )
                            if (seiData != null && currentVpsData != null) {
                                stereoMode =
                                    if (seiData.leftViewId == currentVpsData.layerInfos[0]!!.viewId)
                                        C.STEREO_MODE_INTERLEAVED_LEFT_PRIMARY
                                    else
                                        C.STEREO_MODE_INTERLEAVED_RIGHT_PRIMARY
                            }
                        }
                        bufferPosition += nalUnitLength
                        data.skipBytes(nalUnitLength)
                    }
                }

                val initializationData =
                    if (csdLength == 0) mutableListOf<ByteArray>() else mutableListOf<ByteArray>(
                        buffer
                    )
                return HevcConfig(
                    initializationData,
                    lengthSizeMinusOne + 1,
                    maxSubLayers,
                    width,
                    height,
                    decodedWidth,
                    decodedHeight,
                    bitdepthLuma,
                    bitdepthChroma,
                    colorSpace,
                    colorRange,
                    colorTransfer,
                    stereoMode,
                    pixelWidthHeightRatio,
                    maxNumReorderPics,
                    codecs,
                    currentVpsData
                )
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw ParserException.createForMalformedContainer(
                    "Error parsing" + (if (layered) "L-HEVC config" else "HEVC config"), e
                )
            }
        }
    }
}
