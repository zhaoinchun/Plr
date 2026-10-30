
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil.buildHevcCodecString
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.NalUnitUtil.clearPrefixFlags
import androidx.media3.container.NalUnitUtil.findNalUnit
import androidx.media3.container.NalUnitUtil.getH265NalUnitType
import androidx.media3.container.NalUnitUtil.parseH265SpsNalUnit
import androidx.media3.container.NalUnitUtil.unescapeStream
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull

class H265Reader(private val seiReader: SeiReader, private val containerMimeType: String?) :
    ElementaryStreamReader {
    private var formatId:String? = null
    private var output:TrackOutput? = null
    private var sampleReader:SampleReader? = null

    // State that should not be reset on seek.
    private var hasOutputFormat = false

    // State that should be reset on seek.
    private val prefixFlags: BooleanArray
    private val vps: NalUnitTargetBuffer
    private val sps: NalUnitTargetBuffer
    private val pps: NalUnitTargetBuffer
    private val prefixSei: NalUnitTargetBuffer
    private val suffixSei: NalUnitTargetBuffer
    private var totalBytesWritten: Long = 0

    // Per packet state that gets reset at the start of each packet.
    private var pesTimeUs: Long

    // Scratch variables to avoid allocations.
    private val seiWrapper: ParsableByteArray

    init {
        prefixFlags = BooleanArray(3)
        vps = NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_VPS, 128)
        sps = NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_SPS, 128)
        pps = NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_PPS, 128)
        prefixSei = NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI, 128)
        suffixSei = NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_SUFFIX_SEI, 128)
        pesTimeUs = C.TIME_UNSET
        seiWrapper = ParsableByteArray()
    }

    override fun seek() {
        totalBytesWritten = 0
        pesTimeUs = C.TIME_UNSET
        clearPrefixFlags(prefixFlags)
        vps.reset()
        sps.reset()
        pps.reset()
        prefixSei.reset()
        suffixSei.reset()
        seiReader.clear()
        if (sampleReader != null) {
            sampleReader!!.reset()
        }
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO)
        sampleReader = H265Reader.SampleReader(output!!)
        seiReader.createTracks(extractorOutput, idGenerator)
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        // TODO (Internal b/32267012): Consider using random access indicator.
        this.pesTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        assertTracksCreated()

        while (data.bytesLeft() > 0) {
            var offset = data.getPosition()
            val limit = data.limit()
            val dataArray = data.data

            // Append the data to the buffer.
            totalBytesWritten += data.bytesLeft().toLong()
            output!!.sampleData(data, data.bytesLeft())

            // Scan the appended data, processing NAL units as they are encountered
            while (offset < limit) {
                var nalUnitOffset = findNalUnit(dataArray, offset, limit, prefixFlags)

                if (nalUnitOffset == limit) {
                    // We've scanned to the end of the data without finding the start of another NAL unit.
                    nalUnitData(dataArray, offset, limit)
                    return
                }

                // We've seen the start of a NAL unit of the following type.
                val nalUnitType = getH265NalUnitType(dataArray, nalUnitOffset)

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
                startNalUnit(absolutePosition, bytesWrittenPastPosition, nalUnitType, pesTimeUs)
                // Continue scanning the data.
                offset = nalUnitOffset + prefixSize
            }
        }
    }

    override fun endOfInputReached() {
        assertTracksCreated()
        seiReader.flush()
        // Simulate end of current NAL unit and start an unspecified one to trigger output of current
        // sample
        endNalUnit(totalBytesWritten, 0, 0, pesTimeUs)
        startNalUnit(totalBytesWritten, 0, NalUnitUtil.H265_NAL_UNIT_TYPE_UNSPECIFIED, pesTimeUs)
    }

    @RequiresNonNull("sampleReader")
    private fun startNalUnit(position: Long, offset: Int, nalUnitType: Int, pesTimeUs: Long) {
        sampleReader!!.startNalUnit(position, offset, nalUnitType, pesTimeUs, hasOutputFormat)
        if (!hasOutputFormat) {
            vps.startNalUnit(nalUnitType)
            sps.startNalUnit(nalUnitType)
            pps.startNalUnit(nalUnitType)
        }
        prefixSei.startNalUnit(nalUnitType)
        suffixSei.startNalUnit(nalUnitType)
    }

    @RequiresNonNull("sampleReader")
    private fun nalUnitData(dataArray: ByteArray, offset: Int, limit: Int) {
        sampleReader!!.readNalUnitData(dataArray, offset, limit)
        if (!hasOutputFormat) {
            vps.appendToNalUnit(dataArray, offset, limit)
            sps.appendToNalUnit(dataArray, offset, limit)
            pps.appendToNalUnit(dataArray, offset, limit)
        }
        prefixSei.appendToNalUnit(dataArray, offset, limit)
        suffixSei.appendToNalUnit(dataArray, offset, limit)
    }

    @RequiresNonNull("output", "sampleReader")
    private fun endNalUnit(position: Long, offset: Int, discardPadding: Int, pesTimeUs: Long) {
        sampleReader!!.endNalUnit(position, offset, hasOutputFormat)
        if (!hasOutputFormat) {
            vps.endNalUnit(discardPadding)
            sps.endNalUnit(discardPadding)
            pps.endNalUnit(discardPadding)
            if (vps.isCompleted && sps.isCompleted && pps.isCompleted) {
                val format: Format = parseMediaFormat(formatId, vps, sps, pps, containerMimeType)
                output!!.format(format)
                Preconditions.checkState(format.maxNumReorderSamples != Format.NO_VALUE)
                seiReader.setReorderingQueueSize(format.maxNumReorderSamples)
                hasOutputFormat = true
            }
        }
        if (prefixSei.endNalUnit(discardPadding)) {
            val unescapedLength = unescapeStream(prefixSei.nalData, prefixSei.nalLength)
            seiWrapper.reset(prefixSei.nalData, unescapedLength)

            // Skip the NAL prefix and type.
            seiWrapper.skipBytes(5)
            seiReader.consume(pesTimeUs, seiWrapper)
        }
        if (suffixSei.endNalUnit(discardPadding)) {
            val unescapedLength = unescapeStream(suffixSei.nalData, suffixSei.nalLength)
            seiWrapper.reset(suffixSei.nalData, unescapedLength)

            // Skip the NAL prefix and type.
            seiWrapper.skipBytes(5)
            seiReader.consume(pesTimeUs, seiWrapper)
        }
    }

    private fun assertTracksCreated() {
        output!!
        sampleReader!!
    }

    private class SampleReader(private val output: TrackOutput) {
        // Per NAL unit state. A sample consists of one or more NAL units.
        private var nalUnitPosition: Long = 0
        private var nalUnitHasKeyframeData = false
        private var nalUnitBytesRead = 0
        private var nalUnitTimeUs: Long = 0
        private var lookingForFirstSliceFlag = false
        private var isFirstSlice = false
        private var isFirstPrefixNalUnit = false

        // Per sample state that gets reset at the start of each sample.
        private var readingSample = false
        private var readingPrefix = false
        private var samplePosition: Long = 0
        private var sampleTimeUs: Long = 0
        private var sampleIsKeyframe = false

        fun reset() {
            lookingForFirstSliceFlag = false
            isFirstSlice = false
            isFirstPrefixNalUnit = false
            readingSample = false
            readingPrefix = false
        }

        fun startNalUnit(
            position: Long, offset: Int, nalUnitType: Int, pesTimeUs: Long, hasOutputFormat: Boolean
        ) {
            isFirstSlice = false
            isFirstPrefixNalUnit = false
            nalUnitTimeUs = pesTimeUs
            nalUnitBytesRead = 0
            nalUnitPosition = position

            if (!isVclBodyNalUnit(nalUnitType)) {
                if (readingSample && !readingPrefix) {
                    if (hasOutputFormat) {
                        outputSample(offset)
                    }
                    readingSample = false
                }
                if (isPrefixNalUnit(nalUnitType)) {
                    isFirstPrefixNalUnit = !readingPrefix
                    readingPrefix = true
                }
            }

            // Look for the first slice flag if this NAL unit contains a slice_segment_layer_rbsp.
            nalUnitHasKeyframeData =
                (nalUnitType >= NalUnitUtil.H265_NAL_UNIT_TYPE_BLA_W_LP
                        && nalUnitType <= NalUnitUtil.H265_NAL_UNIT_TYPE_CRA)
            lookingForFirstSliceFlag =
                nalUnitHasKeyframeData || nalUnitType <= NalUnitUtil.H265_NAL_UNIT_TYPE_RASL_R
        }

        fun readNalUnitData(data: ByteArray, offset: Int, limit: Int) {
            if (lookingForFirstSliceFlag) {
                val headerOffset: Int = offset + FIRST_SLICE_FLAG_OFFSET - nalUnitBytesRead
                if (headerOffset < limit) {
                    isFirstSlice = (data[headerOffset].toInt() and 0x80) != 0
                    lookingForFirstSliceFlag = false
                } else {
                    nalUnitBytesRead += limit - offset
                }
            }
        }

        fun endNalUnit(position: Long, offset: Int, hasOutputFormat: Boolean) {
            if (readingPrefix && isFirstSlice) {
                // This sample has parameter sets. Reset the key-frame flag based on the first slice.
                sampleIsKeyframe = nalUnitHasKeyframeData
                readingPrefix = false
            } else if (isFirstPrefixNalUnit || isFirstSlice) {
                // This NAL unit is at the start of a new sample (access unit).
                if (hasOutputFormat && readingSample) {
                    // Output the sample ending before this NAL unit.
                    val nalUnitLength = (position - nalUnitPosition).toInt()
                    outputSample(offset + nalUnitLength)
                }
                samplePosition = nalUnitPosition
                sampleTimeUs = nalUnitTimeUs
                sampleIsKeyframe = nalUnitHasKeyframeData
                readingSample = true
            }
        }

        fun outputSample(offset: Int) {
            if (sampleTimeUs == C.TIME_UNSET || nalUnitPosition == samplePosition) {
                return
            }
            val flags: @BufferFlags Int = if (sampleIsKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0
            val size = (nalUnitPosition - samplePosition).toInt()
            output.sampleMetadata(sampleTimeUs, flags, size, offset, null)
        }

        companion object {
            
            private const val FIRST_SLICE_FLAG_OFFSET = 2

            private fun isPrefixNalUnit(nalUnitType: Int): Boolean {
                return (NalUnitUtil.H265_NAL_UNIT_TYPE_VPS <= nalUnitType
                        && nalUnitType <= NalUnitUtil.H265_NAL_UNIT_TYPE_AUD)
                        || nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI
            }

            private fun isVclBodyNalUnit(nalUnitType: Int): Boolean {
                return nalUnitType < NalUnitUtil.H265_NAL_UNIT_TYPE_VPS
                        || nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_SUFFIX_SEI
            }
        }
    }

    companion object {
        private fun parseMediaFormat(
            formatId: String?,
            vps: NalUnitTargetBuffer,
            sps: NalUnitTargetBuffer,
            pps: NalUnitTargetBuffer,
            containerMimeType: String?
        ): Format {
            // Build codec-specific data.
            val csdData = ByteArray(vps.nalLength + sps.nalLength + pps.nalLength)
            System.arraycopy(vps.nalData, 0, csdData, 0, vps.nalLength)
            System.arraycopy(sps.nalData, 0, csdData, vps.nalLength, sps.nalLength)
            System.arraycopy(pps.nalData, 0, csdData, vps.nalLength + sps.nalLength, pps.nalLength)

            // Skip the 3-byte NAL unit start code synthesised by the NalUnitTargetBuffer constructor.
            val spsData =
                parseH265SpsNalUnit(
                    sps.nalData,  3, sps.nalLength,  null
                )

            var codecs: String? = null
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
            return Format.Builder()
                .setId(formatId)
                .setContainerMimeType(containerMimeType)
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(codecs)
                .setWidth(spsData.width)
                .setHeight(spsData.height)
                .setDecodedWidth(spsData.decodedWidth)
                .setDecodedHeight(spsData.decodedHeight)
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
                .setMaxNumReorderSamples(spsData.maxNumReorderPics)
                .setMaxSubLayers(spsData.maxSubLayersMinus1 + 1)
                .setInitializationData(mutableListOf(csdData))
                .build()
        }
    }
}
