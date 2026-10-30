
package androidx.media3.extractor.flv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.TrackOutput

internal class VideoTagPayloadReader(output: TrackOutput) : TagPayloadReader(output) {
    // Temporary arrays.
    private val nalStartCode: ParsableByteArray
    private val nalLength: ParsableByteArray
    private var nalUnitLengthFieldLength = 0

    // State variables.
    private var hasOutputFormat = false
    private var hasOutputKeyframe = false
    private var frameType = 0

    init {
        nalStartCode = ParsableByteArray(NalUnitUtil.NAL_START_CODE)
        nalLength = ParsableByteArray(4)
    }

    override fun seek() {
        hasOutputKeyframe = false
    }

    @Throws(UnsupportedFormatException::class)
    override fun parseHeader(data: ParsableByteArray): Boolean {
        val header = data.readUnsignedByte()
        val frameType = (header shr 4) and 0x0F
        val videoCodec = (header and 0x0F)
        // Support just H.264 encoded content.
        if (videoCodec != VIDEO_CODEC_AVC) {
            throw UnsupportedFormatException("Video format not supported: " + videoCodec)
        }
        this.frameType = frameType
        return (frameType != VIDEO_FRAME_VIDEO_INFO)
    }

    @Throws(ParserException::class)
    override fun parsePayload(data: ParsableByteArray, timeUs: Long): Boolean {
        var timeUs = timeUs
        val packetType = data.readUnsignedByte()
        val compositionTimeMs = data.readInt24()

        timeUs += compositionTimeMs * 1000L
        // Parse avc sequence header in case this was not done before.
        if (packetType == AVC_PACKET_TYPE_SEQUENCE_HEADER && !hasOutputFormat) {
            val videoSequence = ParsableByteArray(ByteArray(data.bytesLeft()))
            data.readBytes(videoSequence.data, 0, data.bytesLeft())
            val avcConfig = AvcConfig.parse(videoSequence)
            nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength
            // Construct and output the format.
            val format =
                Format.Builder()
                    .setContainerMimeType(MimeTypes.VIDEO_FLV)
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setCodecs(avcConfig.codecs)
                    .setWidth(avcConfig.width)
                    .setHeight(avcConfig.height)
                    .setPixelWidthHeightRatio(avcConfig.pixelWidthHeightRatio)
                    .setInitializationData(avcConfig.initializationData)
                    .build()
            output.format(format)
            hasOutputFormat = true
            return false
        } else if (packetType == AVC_PACKET_TYPE_AVC_NALU && hasOutputFormat) {
            val isKeyframe = frameType == VIDEO_FRAME_KEYFRAME
            if (!hasOutputKeyframe && !isKeyframe) {
                return false
            }
            // TODO: Deduplicate with Mp4Extractor.
            // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
            // they're only 1 or 2 bytes long.
            val nalLengthData = nalLength.data
            nalLengthData[0] = 0
            nalLengthData[1] = 0
            nalLengthData[2] = 0
            val nalUnitLengthFieldLengthDiff = 4 - nalUnitLengthFieldLength
            // NAL units are length delimited, but the decoder requires start code delimited units.
            // Loop until we've written the sample to the track output, replacing length delimiters with
            // start codes as we encounter them.
            var bytesWritten = 0
            var bytesToWrite: Int
            while (data.bytesLeft() > 0) {
                // Read the NAL length so that we know where we find the next one.
                data.readBytes(
                    nalLength.data,
                    nalUnitLengthFieldLengthDiff,
                    nalUnitLengthFieldLength
                )
                nalLength.setPosition(0)
                bytesToWrite = nalLength.readUnsignedIntToInt()

                // Write a start code for the current NAL unit.
                nalStartCode.setPosition(0)
                output.sampleData(nalStartCode, 4)
                bytesWritten += 4

                // Write the payload of the NAL unit.
                output.sampleData(data, bytesToWrite)
                bytesWritten += bytesToWrite
            }
            output.sampleMetadata(
                timeUs, if (isKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0, bytesWritten, 0, null
            )
            hasOutputKeyframe = true
            return true
        } else {
            return false
        }
    }

    companion object {
        // Video codec.
        private const val VIDEO_CODEC_AVC = 7

        // Frame types.
        private const val VIDEO_FRAME_KEYFRAME = 1
        private const val VIDEO_FRAME_VIDEO_INFO = 5

        // Packet types.
        private const val AVC_PACKET_TYPE_SEQUENCE_HEADER = 0
        private const val AVC_PACKET_TYPE_AVC_NALU = 1
    }
}
