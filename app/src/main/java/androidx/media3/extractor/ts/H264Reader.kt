
package androidx.media3.extractor.ts

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil.buildAvcCodecString
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.NalUnitUtil.PpsData
import androidx.media3.container.NalUnitUtil.SpsData
import androidx.media3.container.NalUnitUtil.clearPrefixFlags
import androidx.media3.container.NalUnitUtil.findNalUnit
import androidx.media3.container.NalUnitUtil.getNalUnitType
import androidx.media3.container.NalUnitUtil.parsePpsNalUnit
import androidx.media3.container.NalUnitUtil.parseSpsNalUnit
import androidx.media3.container.NalUnitUtil.unescapeStream
import androidx.media3.container.ParsableNalUnitBitArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull

class H264Reader(
    private val seiReader: SeiReader,
    private val allowNonIdrKeyframes: Boolean,
    private val detectAccessUnits: Boolean,
    private val containerMimeType: String?
) : ElementaryStreamReader {
    private val sps: NalUnitTargetBuffer
    private val pps: NalUnitTargetBuffer
    private val sei: NalUnitTargetBuffer
    private var totalBytesWritten: Long = 0
    private val prefixFlags: BooleanArray

    private var formatId:String? = null
    private var output:TrackOutput? = null
    private var sampleReader:SampleReader? = null

    // State that should not be reset on seek.
    private var hasOutputFormat = false

    // Per PES packet state that gets reset at the start of each PES packet.
    private var pesTimeUs: Long

    // State inherited from the TS packet header.
    private var randomAccessIndicator = false

    // Scratch variables to avoid allocations.
    private val seiWrapper: ParsableByteArray

    init {
        prefixFlags = BooleanArray(3)
        sps = NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_SPS, 128)
        pps = NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_PPS, 128)
        sei = NalUnitTargetBuffer(NalUnitUtil.H264_NAL_UNIT_TYPE_SEI, 128)
        pesTimeUs = C.TIME_UNSET
        seiWrapper = ParsableByteArray()
    }

    override fun seek() {
        totalBytesWritten = 0
        randomAccessIndicator = false
        pesTimeUs = C.TIME_UNSET
        clearPrefixFlags(prefixFlags)
        sps.reset()
        pps.reset()
        sei.reset()
        seiReader.clear()
        if (sampleReader != null) {
            sampleReader!!.reset()
        }
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO)
        sampleReader = H264Reader.SampleReader(output!!, allowNonIdrKeyframes, detectAccessUnits)
        seiReader.createTracks(extractorOutput, idGenerator)
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        this.pesTimeUs = pesTimeUs
        randomAccessIndicator =
            randomAccessIndicator or ((flags and TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR) != 0)
    }

    override fun consume(data: ParsableByteArray) {
        assertTracksCreated()

        var offset = data.getPosition()
        val limit = data.limit()
        val dataArray = data.data

        // Append the data to the buffer.
        totalBytesWritten += data.bytesLeft().toLong()
        output!!.sampleData(data, data.bytesLeft())

        // Scan the appended data, processing NAL units as they are encountered
        while (true) {
            var nalUnitOffset = findNalUnit(dataArray, offset, limit, prefixFlags)

            if (nalUnitOffset == limit) {
                // We've scanned to the end of the data without finding the start of another NAL unit.
                nalUnitData(dataArray, offset, limit)
                return
            }

            // We've seen the start of a NAL unit of the following type.
            val nalUnitType = getNalUnitType(dataArray, nalUnitOffset)

            // Case of a 4 byte start code prefix 0x00000001, recoil NAL unit offset by one byte
            // to avoid previous byte being assigned to the previous access unit.
            var prefixSize = 3
            if (nalUnitOffset > 0 && dataArray[nalUnitOffset - 1].toInt() == 0x00) {
                nalUnitOffset--
                prefixSize = 4
            }

            // This is the number of bytes from the current offset to the start of the next NAL unit.
            // It may be negative if the NAL unit started in the previously consumed data.
            val lengthToNalUnit = nalUnitOffset - offset
            if (lengthToNalUnit > 0) {
                nalUnitData(dataArray, offset, nalUnitOffset)
            }
            val bytesWrittenPastPosition = limit - nalUnitOffset
            val absolutePosition = totalBytesWritten - bytesWrittenPastPosition
            // Indicate the end of the previous NAL unit. If the length to the start of the next unit
            // is negative then we wrote too many bytes to the NAL buffers. Discard the excess bytes
            // when notifying that the unit has ended.
            endNalUnit(
                absolutePosition,
                bytesWrittenPastPosition,
                if (lengthToNalUnit < 0) -lengthToNalUnit else 0,
                pesTimeUs
            )
            // Indicate the start of the next NAL unit.
            startNalUnit(absolutePosition, nalUnitType, pesTimeUs)
            // Continue scanning the data.
            offset = nalUnitOffset + prefixSize
        }
    }

    override fun endOfInputReached() {
        assertTracksCreated()
        seiReader.flush()
        // Simulate end of current NAL unit and start an AUD one to trigger output of current sample
        endNalUnit(totalBytesWritten, 0, 0, pesTimeUs)
        startNalUnit(totalBytesWritten, NalUnitUtil.H264_NAL_UNIT_TYPE_AUD, pesTimeUs)
        endNalUnit(totalBytesWritten, 0, 0, pesTimeUs)
    }

    @RequiresNonNull("sampleReader")
    private fun startNalUnit(position: Long, nalUnitType: Int, pesTimeUs: Long) {
        if (!hasOutputFormat || sampleReader!!.needsSpsPps()) {
            sps.startNalUnit(nalUnitType)
            pps.startNalUnit(nalUnitType)
        }
        sei.startNalUnit(nalUnitType)
        sampleReader!!.startNalUnit(position, nalUnitType, pesTimeUs, randomAccessIndicator)
    }

    @RequiresNonNull("sampleReader")
    private fun nalUnitData(dataArray: ByteArray, offset: Int, limit: Int) {
        if (!hasOutputFormat || sampleReader!!.needsSpsPps()) {
            sps.appendToNalUnit(dataArray, offset, limit)
            pps.appendToNalUnit(dataArray, offset, limit)
        }
        sei.appendToNalUnit(dataArray, offset, limit)
        sampleReader!!.appendToNalUnit(dataArray, offset, limit)
    }

    @RequiresNonNull("output", "sampleReader")
    private fun endNalUnit(position: Long, offset: Int, discardPadding: Int, pesTimeUs: Long) {
        if (!hasOutputFormat || sampleReader!!.needsSpsPps()) {
            sps.endNalUnit(discardPadding)
            pps.endNalUnit(discardPadding)
            if (!hasOutputFormat) {
                if (sps.isCompleted && pps.isCompleted) {
                    val initializationData: MutableList<ByteArray> = ArrayList<ByteArray>()
                    initializationData.add(sps.nalData.copyOf(sps.nalLength))
                    initializationData.add(pps.nalData.copyOf(pps.nalLength))
                    val spsData = parseSpsNalUnit(sps.nalData, 3, sps.nalLength)
                    val ppsData = parsePpsNalUnit(pps.nalData, 3, pps.nalLength)
                    val codecs =
                        buildAvcCodecString(
                            spsData.profileIdc,
                            spsData.constraintsFlagsAndReservedZero2Bits,
                            spsData.levelIdc
                        )
                    output!!.format(
                        Format.Builder()
                            .setId(formatId)
                            .setContainerMimeType(containerMimeType)
                            .setSampleMimeType(MimeTypes.VIDEO_H264)
                            .setCodecs(codecs)
                            .setWidth(spsData.width)
                            .setHeight(spsData.height)
                            .setColorInfo(
                                ColorInfo.Builder()
                                    .setColorSpace(spsData.colorSpace)
                                    .setColorRange(spsData.colorRange)
                                    .setColorTransfer(spsData.colorTransfer)
                                    .setLumaBitdepth(spsData.bitDepthLumaMinus8 + 8)
                                    .setChromaBitdepth(spsData.bitDepthChromaMinus8 + 8)
                                    .build()
                            )
                            .setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
                            .setInitializationData(initializationData)
                            .setMaxNumReorderSamples(spsData.maxNumReorderFrames)
                            .build()
                    )
                    hasOutputFormat = true
                    seiReader.setReorderingQueueSize(spsData.maxNumReorderFrames)
                    sampleReader!!.putSps(spsData)
                    sampleReader!!.putPps(ppsData)
                    sps.reset()
                    pps.reset()
                }
            } else if (sps.isCompleted) {
                val spsData = parseSpsNalUnit(sps.nalData, 3, sps.nalLength)
                seiReader.setReorderingQueueSize(spsData.maxNumReorderFrames)
                sampleReader!!.putSps(spsData)
                sps.reset()
            } else if (pps.isCompleted) {
                val ppsData = parsePpsNalUnit(pps.nalData, 3, pps.nalLength)
                sampleReader!!.putPps(ppsData)
                pps.reset()
            }
        }
        if (sei.endNalUnit(discardPadding)) {
            val unescapedLength = unescapeStream(sei.nalData, sei.nalLength)
            seiWrapper.reset(sei.nalData, unescapedLength)
            seiWrapper.setPosition(4) // NAL prefix and nal_unit() header.
            seiReader.consume(pesTimeUs, seiWrapper)
        }
        val sampleIsKeyFrame = sampleReader!!.endNalUnit(position, offset, hasOutputFormat)
        if (sampleIsKeyFrame) {
            // This is either an IDR frame or the first I-frame since the random access indicator, so mark
            // it as a keyframe. Clear the flag so that subsequent non-IDR I-frames are not marked as
            // keyframes until we see another random access indicator.
            randomAccessIndicator = false
        }
    }

    private fun assertTracksCreated() {
      output!!
        sampleReader!!
    }

    private class SampleReader(
        private val output: TrackOutput,
        private val allowNonIdrKeyframes: Boolean,
        private val detectAccessUnits: Boolean
    ) {
        private val sps: SparseArray<SpsData>
        private val pps: SparseArray<PpsData>
        private val bitArray: ParsableNalUnitBitArray

        private var buffer: ByteArray
        private var bufferLength = 0

        // Per NAL unit state. A sample consists of one or more NAL units.
        private var nalUnitType = 0
        private var nalUnitStartPosition: Long = 0
        private var isFilling = false
        private var nalUnitTimeUs: Long = 0
        private var previousSliceHeader: SliceHeaderData
        private var sliceHeader: SliceHeaderData

        // Per sample state that gets reset at the start of each sample.
        private var readingSample = false
        private var samplePosition: Long = 0
        private var sampleTimeUs: Long = 0
        private var sampleIsKeyframe = false
        private var randomAccessIndicator = false

        init {
            sps = SparseArray<SpsData>()
            pps = SparseArray<PpsData>()
            previousSliceHeader = SliceHeaderData()
            sliceHeader = SliceHeaderData()
            buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            bitArray = ParsableNalUnitBitArray(buffer, 0, 0)
            reset()
        }

        fun needsSpsPps(): Boolean {
            return detectAccessUnits
        }

        fun putSps(spsData: SpsData) {
            sps.append(spsData.seqParameterSetId, spsData)
        }

        fun putPps(ppsData: PpsData) {
            pps.append(ppsData.picParameterSetId, ppsData)
        }

        fun reset() {
            isFilling = false
            readingSample = false
            sliceHeader.clear()
        }

        fun startNalUnit(
            position: Long, type: Int, pesTimeUs: Long, randomAccessIndicator: Boolean
        ) {
            nalUnitType = type
            nalUnitTimeUs = pesTimeUs
            nalUnitStartPosition = position
            this.randomAccessIndicator = randomAccessIndicator
            if ((allowNonIdrKeyframes && nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR)
                || (detectAccessUnits
                        && (nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR || nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR || nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_PARTITION_A))
            ) {
                // Store the previous header and prepare to populate the new one.
                val newSliceHeader = previousSliceHeader
                previousSliceHeader = sliceHeader
                sliceHeader = newSliceHeader
                sliceHeader.clear()
                bufferLength = 0
                isFilling = true
            }
        }

        fun appendToNalUnit(data: ByteArray, offset: Int, limit: Int) {
            if (!isFilling) {
                return
            }
            val readLength = limit - offset
            if (buffer.size < bufferLength + readLength) {
                buffer = buffer.copyOf((bufferLength + readLength) * 2)
            }
            System.arraycopy(data, offset, buffer, bufferLength, readLength)
            bufferLength += readLength

            bitArray.reset(buffer, 0, bufferLength)
            if (!bitArray.canReadBits(8)) {
                return
            }
            bitArray.skipBit() // forbidden_zero_bit
            val nalRefIdc = bitArray.readBits(2)
            bitArray.skipBits(5) // nal_unit_type

            // Read the slice header using the syntax defined in ITU-T Recommendation H.264 (2013)
            // subsection 7.3.3.
            if (!bitArray.canReadExpGolombCodedNum()) {
                return
            }
            bitArray.readUnsignedExpGolombCodedInt() // first_mb_in_slice
            if (!bitArray.canReadExpGolombCodedNum()) {
                return
            }
            val sliceType = bitArray.readUnsignedExpGolombCodedInt()
            if (!detectAccessUnits) {
                // There are AUDs in the stream so the rest of the header can be ignored.
                isFilling = false
                sliceHeader.setSliceType(sliceType)
                return
            }
            if (!bitArray.canReadExpGolombCodedNum()) {
                return
            }
            val picParameterSetId = bitArray.readUnsignedExpGolombCodedInt()
            if (pps.indexOfKey(picParameterSetId) < 0) {
                // We have not seen the PPS yet, so don't try to decode the slice header.
                isFilling = false
                return
            }
            val ppsData = pps.get(picParameterSetId)
            val spsData = sps.get(ppsData.seqParameterSetId)
            if (spsData.separateColorPlaneFlag) {
                if (!bitArray.canReadBits(2)) {
                    return
                }
                bitArray.skipBits(2) // colour_plane_id
            }
            if (!bitArray.canReadBits(spsData.frameNumLength)) {
                return
            }
            var fieldPicFlag = false
            var bottomFieldFlagPresent = false
            var bottomFieldFlag = false
            val frameNum = bitArray.readBits(spsData.frameNumLength)
            if (!spsData.frameMbsOnlyFlag) {
                if (!bitArray.canReadBits(1)) {
                    return
                }
                fieldPicFlag = bitArray.readBit()
                if (fieldPicFlag) {
                    if (!bitArray.canReadBits(1)) {
                        return
                    }
                    bottomFieldFlag = bitArray.readBit()
                    bottomFieldFlagPresent = true
                }
            }
            val idrPicFlag = nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR
            var idrPicId = 0
            if (idrPicFlag) {
                if (!bitArray.canReadExpGolombCodedNum()) {
                    return
                }
                idrPicId = bitArray.readUnsignedExpGolombCodedInt()
            }
            var picOrderCntLsb = 0
            var deltaPicOrderCntBottom = 0
            var deltaPicOrderCnt0 = 0
            var deltaPicOrderCnt1 = 0
            if (spsData.picOrderCountType == 0) {
                if (!bitArray.canReadBits(spsData.picOrderCntLsbLength)) {
                    return
                }
                picOrderCntLsb = bitArray.readBits(spsData.picOrderCntLsbLength)
                if (ppsData.bottomFieldPicOrderInFramePresentFlag && !fieldPicFlag) {
                    if (!bitArray.canReadExpGolombCodedNum()) {
                        return
                    }
                    deltaPicOrderCntBottom = bitArray.readSignedExpGolombCodedInt()
                }
            } else if (spsData.picOrderCountType == 1 && !spsData.deltaPicOrderAlwaysZeroFlag) {
                if (!bitArray.canReadExpGolombCodedNum()) {
                    return
                }
                deltaPicOrderCnt0 = bitArray.readSignedExpGolombCodedInt()
                if (ppsData.bottomFieldPicOrderInFramePresentFlag && !fieldPicFlag) {
                    if (!bitArray.canReadExpGolombCodedNum()) {
                        return
                    }
                    deltaPicOrderCnt1 = bitArray.readSignedExpGolombCodedInt()
                }
            }
            sliceHeader.setAll(
                spsData,
                nalRefIdc,
                sliceType,
                frameNum,
                picParameterSetId,
                fieldPicFlag,
                bottomFieldFlagPresent,
                bottomFieldFlag,
                idrPicFlag,
                idrPicId,
                picOrderCntLsb,
                deltaPicOrderCntBottom,
                deltaPicOrderCnt0,
                deltaPicOrderCnt1
            )
            isFilling = false
        }

        fun endNalUnit(position: Long, offset: Int, hasOutputFormat: Boolean): Boolean {
            if (nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_AUD
                || (detectAccessUnits && sliceHeader.isFirstVclNalUnitOfPicture(previousSliceHeader))
            ) {
                // If the NAL unit ending is the start of a new sample, output the previous one.
                if (hasOutputFormat && readingSample) {
                    val nalUnitLength = (position - nalUnitStartPosition).toInt()
                    outputSample(offset + nalUnitLength)
                }
                samplePosition = nalUnitStartPosition
                sampleTimeUs = nalUnitTimeUs
                sampleIsKeyframe = false
                readingSample = true
            }
            setSampleIsKeyframe()
            // Reset NAL unit type to avoid stale state
            nalUnitType = NalUnitUtil.H264_NAL_UNIT_TYPE_UNSPECIFIED
            return sampleIsKeyframe
        }

        fun setSampleIsKeyframe() {
            val treatIFrameAsKeyframe =
                if (allowNonIdrKeyframes) sliceHeader.isISlice else randomAccessIndicator
            sampleIsKeyframe = sampleIsKeyframe or (
                    nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_IDR
                            || (treatIFrameAsKeyframe && nalUnitType == NalUnitUtil.H264_NAL_UNIT_TYPE_NON_IDR))
        }

        fun outputSample(offset: Int) {
            if (sampleTimeUs == C.TIME_UNSET || nalUnitStartPosition == samplePosition) {
                return
            }
            val flags: @BufferFlags Int = if (sampleIsKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0
            val size = (nalUnitStartPosition - samplePosition).toInt()
            output.sampleMetadata(sampleTimeUs, flags, size, offset, null)
        }

        private class SliceHeaderData {
            private var isComplete = false
            private var hasSliceType = false

            private var spsData: SpsData? = null
            private var nalRefIdc = 0
            private var sliceType = 0
            private var frameNum = 0
            private var picParameterSetId = 0
            private var fieldPicFlag = false
            private var bottomFieldFlagPresent = false
            private var bottomFieldFlag = false
            private var idrPicFlag = false
            private var idrPicId = 0
            private var picOrderCntLsb = 0
            private var deltaPicOrderCntBottom = 0
            private var deltaPicOrderCnt0 = 0
            private var deltaPicOrderCnt1 = 0

            fun clear() {
                hasSliceType = false
                isComplete = false
            }

            fun setSliceType(sliceType: Int) {
                this.sliceType = sliceType
                hasSliceType = true
            }

            fun setAll(
                spsData: SpsData?,
                nalRefIdc: Int,
                sliceType: Int,
                frameNum: Int,
                picParameterSetId: Int,
                fieldPicFlag: Boolean,
                bottomFieldFlagPresent: Boolean,
                bottomFieldFlag: Boolean,
                idrPicFlag: Boolean,
                idrPicId: Int,
                picOrderCntLsb: Int,
                deltaPicOrderCntBottom: Int,
                deltaPicOrderCnt0: Int,
                deltaPicOrderCnt1: Int
            ) {
                this.spsData = spsData
                this.nalRefIdc = nalRefIdc
                this.sliceType = sliceType
                this.frameNum = frameNum
                this.picParameterSetId = picParameterSetId
                this.fieldPicFlag = fieldPicFlag
                this.bottomFieldFlagPresent = bottomFieldFlagPresent
                this.bottomFieldFlag = bottomFieldFlag
                this.idrPicFlag = idrPicFlag
                this.idrPicId = idrPicId
                this.picOrderCntLsb = picOrderCntLsb
                this.deltaPicOrderCntBottom = deltaPicOrderCntBottom
                this.deltaPicOrderCnt0 = deltaPicOrderCnt0
                this.deltaPicOrderCnt1 = deltaPicOrderCnt1
                isComplete = true
                hasSliceType = true
            }

            val isISlice: Boolean
                get() = hasSliceType && (sliceType == SLICE_TYPE_ALL_I || sliceType == SLICE_TYPE_I)

            fun isFirstVclNalUnitOfPicture(other: SliceHeaderData): Boolean {
                if (!isComplete) {
                    return false
                }
                if (!other.isComplete) {
                    return true
                }
                // See ISO 14496-10 subsection 7.4.1.2.4.
                val spsData = this.spsData!!
                val otherSpsData = other.spsData!!
                return frameNum != other.frameNum || picParameterSetId != other.picParameterSetId || fieldPicFlag != other.fieldPicFlag || (bottomFieldFlagPresent
                        && other.bottomFieldFlagPresent
                        && bottomFieldFlag != other.bottomFieldFlag)
                        || (nalRefIdc != other.nalRefIdc && (nalRefIdc == 0 || other.nalRefIdc == 0))
                        || (spsData.picOrderCountType == 0 && otherSpsData.picOrderCountType == 0 && (picOrderCntLsb != other.picOrderCntLsb
                        || deltaPicOrderCntBottom != other.deltaPicOrderCntBottom))
                        || (spsData.picOrderCountType == 1 && otherSpsData.picOrderCountType == 1 && (deltaPicOrderCnt0 != other.deltaPicOrderCnt0
                        || deltaPicOrderCnt1 != other.deltaPicOrderCnt1))
                        || idrPicFlag != other.idrPicFlag || (idrPicFlag && idrPicId != other.idrPicId)
            }

            companion object {
                private const val SLICE_TYPE_I = 2
                private const val SLICE_TYPE_ALL_I = 7
            }
        }

        companion object {
            private const val DEFAULT_BUFFER_SIZE = 128
        }
    }
}
