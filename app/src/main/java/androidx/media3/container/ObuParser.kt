package androidx.media3.container

import androidx.media3.common.util.ParsableBitArray
import com.google.common.base.Preconditions
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import kotlin.math.min

object ObuParser {
    
    const val OBU_SEQUENCE_HEADER: Int = 1

    const val OBU_TEMPORAL_DELIMITER: Int = 2

    const val OBU_FRAME_HEADER: Int = 3

    const val OBU_METADATA: Int = 5

    const val OBU_FRAME: Int = 6

    const val OBU_PADDING: Int = 15

    fun split(sample: ByteBuffer): MutableList<Obu> {
        // Create a read-only buffer that shares content with the sample to avoid modifying the
        // input buffer's position, mark or limit.
        val readOnlySample = sample.asReadOnlyBuffer()
        val obuList: MutableList<Obu> = ArrayList<Obu>()
        while (readOnlySample.hasRemaining()) {
            val obuType: Int
            val obuSize: Int
            val header = readOnlySample.duplicate()
            try {
                val headerByte = readOnlySample.get().toInt()
                obuType = (headerByte shr 3) and 0xF
                val extensionFlag = (headerByte shr 2) and 0x1
                if (extensionFlag != 0) {
                    readOnlySample.get() // skip obu_extension_header()
                }
                val obuHasSizeField = (headerByte shr 1) and 0x1
                if (obuHasSizeField != 0) {
                    obuSize = leb128(readOnlySample)
                } else {
                    // Only the last sample is allowed to have obu_has_size_field == 0, and the size is
                    // assumed to fill the remainder of the sample.
                    obuSize = readOnlySample.remaining()
                }
            } catch (ignored: BufferUnderflowException) {
                // Intentionally ignoring this exception because this method supports truncated input
                // which means the contents are cut off.
                // ByteBuffer reading fails with underflow exception if the input sample is truncated.
                break
            }
            if (readOnlySample.position() + obuSize > readOnlySample.limit()) {
                // The input sample was truncated and doesn't hold the full OBU.
                break
            }
            header.limit(readOnlySample.position())
            val payload = readOnlySample.duplicate()
            payload.limit(readOnlySample.position() + obuSize)
            obuList.add(Obu(obuType, header, payload))
            readOnlySample.position(readOnlySample.position() + obuSize)
        }
        return obuList
    }

    private fun leb128(data: ByteBuffer): Int {
        var value = 0
        for (i in 0..7) {
            val leb128Byte = data.get().toInt()
            value = value or ((leb128Byte and 0x7F) shl (i * 7))
            if ((leb128Byte and 0x80) == 0) {
                break
            }
        }
        return value
    }

    private fun skipUvlc(parsableBitArray: ParsableBitArray) {
        var leadingZeros = 0
        while (true) {
            val done = parsableBitArray.readBit()
            if (done) {
                break
            }
            leadingZeros++
        }
        // 32 or more leading zeros returns (1 << 32) - 1 from the uvlc() process without reading more
        // bits.
        if (leadingZeros < 32) {
            parsableBitArray.skipBits(leadingZeros)
        }
    }

    @Throws(NotYetImplementedException::class)
    private fun throwWhenFeatureRequired(expression: Boolean) {
        if (expression) {
            throw NotYetImplementedException()
        }
    }

    class Obu constructor(
        
        val type: Int,
        
        val header: ByteBuffer?,
        
        val payload: ByteBuffer
    )

    class SequenceHeader private constructor(obu: Obu) {
        val reducedStillPictureHeader: Boolean
        val decoderModelInfoPresentFlag: Boolean
        val frameIdNumbersPresentFlag: Boolean
        val seqForceScreenContentTools: Boolean
        val seqForceIntegerMv: Boolean
        val orderHintBits: Int
        val seqProfile: Int
        val seqLevelIdx0: Int
        val seqTier0: Int
        val initialDisplayDelayPresentFlag: Boolean
        val initialDisplayDelayMinus1: Int
        val highBitdepth: Boolean
        val twelveBit: Boolean
        val monochrome: Boolean
        val subsamplingX: Boolean
        val subsamplingY: Boolean
        val chromaSamplePosition: Int
        val colorPrimaries: Byte
        val transferCharacteristics: Byte
        val matrixCoefficients: Byte
        init {
            var seqLevelIdx0 = 0
            var seqTier0 = 0
            var initialDisplayDelayMinus1 = 0
            Preconditions.checkArgument(obu.type == OBU_SEQUENCE_HEADER)
            val data = ByteArray(obu.payload.remaining())
            // Do not modify obu.payload while reading it.
            obu.payload.asReadOnlyBuffer().get(data)
            val obuData = ParsableBitArray(data)
            seqProfile = obuData.readBits(3)
            obuData.skipBit() // still_picture
            reducedStillPictureHeader = obuData.readBit()
            if (reducedStillPictureHeader) {
                seqLevelIdx0 = obuData.readBits(5)
                decoderModelInfoPresentFlag = false
                initialDisplayDelayPresentFlag = false
            } else {
                val timingInfoPresentFlag = obuData.readBit()
                if (timingInfoPresentFlag) {
                    skipTimingInfo(obuData)
                    decoderModelInfoPresentFlag = obuData.readBit()
                    if (decoderModelInfoPresentFlag) {
                        // skip decoder_model_info()
                        obuData.skipBits(47)
                    }
                } else {
                    decoderModelInfoPresentFlag = false
                }
                initialDisplayDelayPresentFlag = obuData.readBit()
                val operatingPointsCntMinus1 = obuData.readBits(5)
                for (i in 0..operatingPointsCntMinus1) {
                    obuData.skipBits(12) // operating_point_idc[ i ]
                    if (i == 0) {
                        seqLevelIdx0 = obuData.readBits(5)
                        if (seqLevelIdx0 > 7) {
                            seqTier0 = if (obuData.readBit()) 1 else 0
                        }
                    } else {
                        val seqLevelIdx = obuData.readBits(5)
                        if (seqLevelIdx > 7) {
                            obuData.skipBit() // seq_tier[ i ]
                        }
                    }
                    if (decoderModelInfoPresentFlag) {
                        obuData.skipBit() // decoder_model_present_for_this_op
                    }
                    if (initialDisplayDelayPresentFlag) {
                        val initialDisplayDelayPresentForThisOpFlag = obuData.readBit()
                        if (initialDisplayDelayPresentForThisOpFlag) {
                            if (i == 0) {
                                initialDisplayDelayMinus1 = obuData.readBits(4)
                            } else {
                                obuData.skipBits(4) // initial_display_delay_minus_1[ i ]
                            }
                        }
                    }
                }
            }
            val frameWidthBitsMinus1 = obuData.readBits(4)
            val frameHeightBitsMinus1 = obuData.readBits(4)
            obuData.skipBits(frameWidthBitsMinus1 + 1) // max_frame_width_minus_1
            obuData.skipBits(frameHeightBitsMinus1 + 1) // max_frame_height_minus_1
            if (!reducedStillPictureHeader) {
                frameIdNumbersPresentFlag = obuData.readBit()
            } else {
                frameIdNumbersPresentFlag = false
            }
            if (frameIdNumbersPresentFlag) {
                obuData.skipBits(4) // delta_frame_id_length_minus_2
                obuData.skipBits(3) // additional_frame_id_length_minus_1
            }
            // use_128x128_superblock, enable_filter_intra, and enable_intra_edge_filter
            obuData.skipBits(3)
            if (reducedStillPictureHeader) {
                seqForceIntegerMv = true
                seqForceScreenContentTools = true
                orderHintBits = 0
            } else {
                // enable_interintra_compound, enable_masked_compound, enable_warped_motion, and
                // enable_dual_filter
                obuData.skipBits(4)
                val enableOrderHint = obuData.readBit()
                if (enableOrderHint) {
                    obuData.skipBits(2) // enable_jnt_comp and enable_ref_frame_mvs
                }
                val seqChooseScreenContentTools = obuData.readBit()
                if (seqChooseScreenContentTools) {
                    seqForceScreenContentTools = true
                } else {
                    seqForceScreenContentTools = obuData.readBit()
                }
                if (seqForceScreenContentTools) {
                    val seqChooseIntegerMv = obuData.readBit()
                    if (seqChooseIntegerMv) {
                        seqForceIntegerMv = true
                    } else {
                        seqForceIntegerMv = obuData.readBit()
                    }
                } else {
                    seqForceIntegerMv = true
                }
                if (enableOrderHint) {
                    val orderHintBitsMinus1 = obuData.readBits(3)
                    orderHintBits = orderHintBitsMinus1 + 1
                } else {
                    orderHintBits = 0
                }
            }
            this.seqLevelIdx0 = seqLevelIdx0
            this.seqTier0 = seqTier0
            this.initialDisplayDelayMinus1 = initialDisplayDelayMinus1
            // enable_superres, enable_cdef, enable_restoration
            obuData.skipBits(3)
            // Begin Color Config
            highBitdepth = obuData.readBit()
            if (seqProfile == 2 && highBitdepth) {
                twelveBit = obuData.readBit()
            } else {
                twelveBit = false
            }
            if (seqProfile != 1) {
                monochrome = obuData.readBit()
            } else {
                monochrome = false
            }
            val colorDescriptionPresent = obuData.readBit()
            if (colorDescriptionPresent) {
                colorPrimaries = obuData.readBits(8).toByte()
                transferCharacteristics = obuData.readBits(8).toByte()
                matrixCoefficients = obuData.readBits(8).toByte()
            } else {
                colorPrimaries = 0
                transferCharacteristics = 0
                matrixCoefficients = 0
            }
            if (monochrome) {
                obuData.skipBit() // color_range
                subsamplingX = false
                subsamplingY = false
                chromaSamplePosition = 0
            } else if (colorPrimaries.toInt() == 0x1  && transferCharacteristics.toInt() == 13  && matrixCoefficients.toInt() == 0x0 ) {
                // Nothing to read from obu.
                subsamplingX = false
                subsamplingY = false
                chromaSamplePosition = 0
            } else {
                obuData.skipBit() // color_range
                if (seqProfile == 0) {
                    subsamplingX = true
                    subsamplingY = true
                } else if (seqProfile == 1) {
                    subsamplingX = false
                    subsamplingY = false
                } else {
                    if (twelveBit) {
                        subsamplingX = obuData.readBit()
                        if (subsamplingX) {
                            subsamplingY = obuData.readBit()
                        } else {
                            subsamplingY = false
                        }
                    } else {
                        subsamplingX = true
                        subsamplingY = false
                    }
                }
                if (subsamplingX && subsamplingY) {
                    chromaSamplePosition = obuData.readBits(2)
                } else {
                    chromaSamplePosition = 0
                }
            }
            obuData.skipBit() // separate_uv_delta_q
        }

        companion object {
            
            fun parse(obu: Obu): SequenceHeader? {
                try {
                    return SequenceHeader(obu)
                } catch (ignored: NotYetImplementedException) {
                    return null
                }
            }

            private fun skipTimingInfo(parsableBitArray: ParsableBitArray) {
                parsableBitArray.skipBits(64) // num_units_in_display_tick and time_scale
                val equalPictureInterval = parsableBitArray.readBit()
                if (equalPictureInterval) {
                    skipUvlc(parsableBitArray)
                }
            }
        }
    }

    class FrameHeader private constructor(sequenceHeader: SequenceHeader, obu: Obu) {
        
        val isDependedOn: Boolean

        init {
            Preconditions.checkArgument(obu.type == OBU_FRAME || obu.type == OBU_FRAME_HEADER)
            val bytes = ByteArray(min(PROBE_BYTES, obu.payload.remaining()))
            // Do not modify obu.payload while reading it.
            obu.payload.asReadOnlyBuffer().get(bytes)
            val obuData = ParsableBitArray(bytes)
            throwWhenFeatureRequired(sequenceHeader.reducedStillPictureHeader)
            val showExistingFrame = obuData.readBit()
            if (showExistingFrame) {
                isDependedOn = false
            } else {
                val frameType = obuData.readBits(2)
                val showFrame = obuData.readBit()
                throwWhenFeatureRequired(sequenceHeader.decoderModelInfoPresentFlag)
                if (!showFrame) {
                    // show_frame equal to 0 specifies that this frame should not be immediately output.
                    // If a frame is output later, then it is depended on.
                    isDependedOn = true
                } else {
                    val errorResilientMode: Boolean
                    if (frameType == FRAME_TYPE_SWITCH_FRAME || (frameType == FRAME_TYPE_KEY_FRAME)) {
                        errorResilientMode = true
                    } else {
                        errorResilientMode = obuData.readBit()
                    }
                    obuData.skipBit() // disable_cdf_update
                    throwWhenFeatureRequired(!sequenceHeader.seqForceScreenContentTools)
                    val allowScreenContentTools = obuData.readBit()
                    if (allowScreenContentTools) {
                        throwWhenFeatureRequired(!sequenceHeader.seqForceIntegerMv)
                        obuData.skipBit() // force_integer_mv
                    }
                    throwWhenFeatureRequired(sequenceHeader.frameIdNumbersPresentFlag)
                    if (frameType != FRAME_TYPE_SWITCH_FRAME) {
                        obuData.skipBit() // frame_size_override_flag
                    }
                    obuData.skipBits(sequenceHeader.orderHintBits) // order_hint
                    if (frameType != FRAME_TYPE_INTRA_ONLY_FRAME && frameType != FRAME_TYPE_KEY_FRAME && !errorResilientMode) {
                        obuData.skipBits(3) // primary_ref_frame
                    }
                    val refreshFrameFlags: Int
                    if (frameType == FRAME_TYPE_SWITCH_FRAME || (frameType == FRAME_TYPE_KEY_FRAME)) {
                        refreshFrameFlags = (1 shl 8) - 1
                    } else {
                        refreshFrameFlags = obuData.readBits(8)
                    }
                    isDependedOn = refreshFrameFlags != 0
                }
            }
        }

        companion object {
            private const val PROBE_BYTES = 4

            private const val FRAME_TYPE_KEY_FRAME = 0
            private const val FRAME_TYPE_INTRA_ONLY_FRAME = 2
            private const val FRAME_TYPE_SWITCH_FRAME = 3

            fun parse(sequenceHeader: SequenceHeader, obu: Obu): FrameHeader? {
                try {
                    return FrameHeader(sequenceHeader, obu)
                } catch (ignored: NotYetImplementedException) {
                    return null
                }
            }
        }
    }

    class Metadata private constructor(
        val type: Int,
        val payload: ByteBuffer?
    ) {
        companion object {
            
            const val METADATA_TYPE_HDR_CLL: Int = 1
            const val METADATA_TYPE_HDR_MDCV: Int = 2
            const val METADATA_TYPE_SCALABILITY: Int = 3
            const val METADATA_TYPE_ITUT_T35: Int = 4
            const val METADATA_TYPE_TIMECODE: Int = 5

            fun parse(obu: Obu): Metadata {
                Preconditions.checkArgument(obu.type == OBU_METADATA)
                val buffer = obu.payload.asReadOnlyBuffer()
                val type = leb128(buffer)
                return Metadata(type, buffer)
            }
        }
    }

    private class NotYetImplementedException : Exception()
}
