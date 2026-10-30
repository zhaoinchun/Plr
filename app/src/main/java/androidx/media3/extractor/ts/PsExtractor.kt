
package androidx.media3.extractor.ts

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException

class PsExtractor  constructor(
    timestampAdjuster: TimestampAdjuster = TimestampAdjuster(
        0
    )
) : Extractor {
    private val timestampAdjuster: TimestampAdjuster
    private val psPayloadReaders: SparseArray<PesReader?> // Indexed by pid
    private val psPacketBuffer: ParsableByteArray
    private val durationReader: PsDurationReader

    private var foundAllTracks = false
    private var foundAudioTrack = false
    private var foundVideoTrack = false
    private var lastTrackPosition: Long = 0

    // Accessed only by the loading thread.
    private var psBinarySearchSeeker: PsBinarySearchSeeker? = null
    private lateinit var output:ExtractorOutput
    private var hasOutputSeekMap = false

    init {
        this.timestampAdjuster = timestampAdjuster
        psPacketBuffer = ParsableByteArray(4096)
        psPayloadReaders = SparseArray<PesReader?>()
        durationReader = PsDurationReader()
    }

    // Extractor implementation.
    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        val scratch = ByteArray(14)
        input.peekFully(scratch, 0, 14)

        // Verify the PACK_START_CODE for the first 4 bytes
        if (PACK_START_CODE
            != (((scratch[0].toInt() and 0xFF) shl 24)
                    or ((scratch[1].toInt() and 0xFF) shl 16)
                    or ((scratch[2].toInt() and 0xFF) shl 8)
                    or (scratch[3].toInt() and 0xFF))
        ) {
            return false
        }
        // Verify the 01xxx1xx marker on the 5th byte
        if ((scratch[4].toInt() and 0xC4) != 0x44) {
            return false
        }
        // Verify the xxxxx1xx marker on the 7th byte
        if ((scratch[6].toInt() and 0x04) != 0x04) {
            return false
        }
        // Verify the xxxxx1xx marker on the 9th byte
        if ((scratch[8].toInt() and 0x04) != 0x04) {
            return false
        }
        // Verify the xxxxxxx1 marker on the 10th byte
        if ((scratch[9].toInt() and 0x01) != 0x01) {
            return false
        }
        // Verify the xxxxxx11 marker on the 13th byte
        if ((scratch[12].toInt() and 0x03) != 0x03) {
            return false
        }
        // Read the stuffing length from the 14th byte (last 3 bits)
        val packStuffingLength = scratch[13].toInt() and 0x07
        input.advancePeekPosition(packStuffingLength)
        // Now check that the next 3 bytes are the beginning of an MPEG start code
        input.peekFully(scratch, 0, 3)
        return (PACKET_START_CODE_PREFIX
                == (((scratch[0].toInt() and 0xFF) shl 16) or ((scratch[1].toInt() and 0xFF) shl 8) or (scratch[2].toInt() and 0xFF)))
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun seek(position: Long, timeUs: Long) {
        // If the timestamp adjuster has not yet established a timestamp offset, we need to reset its
        // expected first sample timestamp to be the new seek position. Without this, the timestamp
        // adjuster would incorrectly establish its timestamp offset assuming that the first sample
        // after this seek corresponds to the start of the stream (or a previous seek position, if there
        // was one).
        var resetTimestampAdjuster = timestampAdjuster.timestampOffsetUs === C.TIME_UNSET
        if (!resetTimestampAdjuster) {
            val adjusterFirstSampleTimestampUs: Long = timestampAdjuster.getFirstSampleTimestampUs()
            // Also reset the timestamp adjuster if its offset was calculated based on a non-zero position
            // in the stream (other than the position being seeked to), since in this case the offset may
            // not be accurate.
            resetTimestampAdjuster =
                adjusterFirstSampleTimestampUs != C.TIME_UNSET && adjusterFirstSampleTimestampUs != 0L && adjusterFirstSampleTimestampUs != timeUs
        }
        if (resetTimestampAdjuster) {
            timestampAdjuster.reset(timeUs)
        }

        if (psBinarySearchSeeker != null) {
            psBinarySearchSeeker!!.setSeekTargetUs(timeUs)
        }
        for (i in 0..<psPayloadReaders.size()) {
            psPayloadReaders.valueAt(i)!!.seek()
        }
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // Asserts init has been called.
        val output = output!!

        val inputLength: Long = input.length
        val canReadDuration = inputLength != C.LENGTH_UNSET.toLong()
        if (canReadDuration && !durationReader.isDurationReadFinished) {
            return durationReader.readDuration(input, seekPosition)
        }
        maybeOutputSeekMap(inputLength)
        if (psBinarySearchSeeker != null && psBinarySearchSeeker!!.isSeeking) {
            return psBinarySearchSeeker!!.handlePendingSeek(input, seekPosition)
        }

        input.resetPeekPosition()
        val peekBytesLeft: Long =
            if (inputLength != C.LENGTH_UNSET.toLong()) inputLength - input.peekPosition else C.LENGTH_UNSET.toLong()
        if (peekBytesLeft != C.LENGTH_UNSET.toLong() && peekBytesLeft < 4) {
            onEndOfInput()
            return Extractor.RESULT_END_OF_INPUT
        }
        // First peek and check what type of start code is next.
        if (!input.peekFully(psPacketBuffer.data, 0, 4, true)) {
            onEndOfInput()
            return Extractor.RESULT_END_OF_INPUT
        }

        psPacketBuffer.setPosition(0)
        val nextStartCode = psPacketBuffer.readInt()
        if (nextStartCode == MPEG_PROGRAM_END_CODE) {
            onEndOfInput()
            return Extractor.RESULT_END_OF_INPUT
        } else if (nextStartCode == PACK_START_CODE) {
            // Now peek the rest of the pack_header.
            input.peekFully(psPacketBuffer.data, 0, 10)

            // We only care about the pack_stuffing_length in here, skip the first 77 bits.
            psPacketBuffer.setPosition(9)

            // Last 3 bits is the length.
            val packStuffingLength = psPacketBuffer.readUnsignedByte() and 0x07

            // Now skip the stuffing and the pack header.
            input.skipFully(packStuffingLength + 14)
            return Extractor.RESULT_CONTINUE
        } else if (nextStartCode == SYSTEM_HEADER_START_CODE) {
            // We just skip all this, but we need to get the length first.
            input.peekFully(psPacketBuffer.data, 0, 2)

            // Length is the next 2 bytes.
            psPacketBuffer.setPosition(0)
            val systemHeaderLength = psPacketBuffer.readUnsignedShort()
            input.skipFully(systemHeaderLength + 6)
            return Extractor.RESULT_CONTINUE
        } else if (((nextStartCode and -0x100) shr 8) != PACKET_START_CODE_PREFIX) {
            input.skipFully(1) // Skip bytes until we see a valid start code again.
            return Extractor.RESULT_CONTINUE
        }

        // We're at the start of a regular PES packet now.
        // Get the stream ID off the last byte of the start code.
        val streamId = nextStartCode and 0xFF

        // Check to see if we have this one in our map yet, and if not, then add it.
        var payloadReader = psPayloadReaders.get(streamId)
        if (!foundAllTracks) {
            if (payloadReader == null) {
                var elementaryStreamReader: ElementaryStreamReader? = null
                if (streamId == PRIVATE_STREAM_1) {
                    // Private stream, used for AC3 audio.
                    // NOTE: This may need further parsing to determine if its DTS, but that's likely only
                    // valid for DVDs.
                    elementaryStreamReader = Ac3Reader(MimeTypes.VIDEO_PS)
                    foundAudioTrack = true
                    lastTrackPosition = input.position
                } else if ((streamId and AUDIO_STREAM_MASK) == AUDIO_STREAM) {
                    elementaryStreamReader = MpegAudioReader(MimeTypes.VIDEO_PS)
                    foundAudioTrack = true
                    lastTrackPosition = input.position
                } else if ((streamId and VIDEO_STREAM_MASK) == VIDEO_STREAM) {
                    elementaryStreamReader = H262Reader(MimeTypes.VIDEO_PS)
                    foundVideoTrack = true
                    lastTrackPosition = input.position
                }
                if (elementaryStreamReader != null) {
                    val idGenerator: TsPayloadReader.TrackIdGenerator =
                        TsPayloadReader.TrackIdGenerator(streamId, MAX_STREAM_ID_PLUS_ONE)
                    elementaryStreamReader.createTracks(output, idGenerator)
                    payloadReader = PesReader(elementaryStreamReader, timestampAdjuster)
                    psPayloadReaders.put(streamId, payloadReader)
                }
            }
            val maxSearchPosition: Long =
                if (foundAudioTrack && foundVideoTrack)
                    lastTrackPosition + MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND
                else
                    MAX_SEARCH_LENGTH
            if (input.position > maxSearchPosition) {
                foundAllTracks = true
                output.endTracks()
            }
        }

        // The next 2 bytes are the length. Once we have that we can consume the complete packet.
        input.peekFully(psPacketBuffer.data, 0, 2)
        psPacketBuffer.setPosition(0)
        val payloadLength = psPacketBuffer.readUnsignedShort()
        val pesLength = payloadLength + 6

        if (payloadReader == null) {
            // Just skip this data.
            input.skipFully(pesLength)
        } else {
            psPacketBuffer.reset(pesLength)
            // Read the whole packet and the header for consumption.
            input.readFully(psPacketBuffer.data, 0, pesLength)
            psPacketBuffer.setPosition(6)
            payloadReader.consume(psPacketBuffer)
            psPacketBuffer.setLimit(psPacketBuffer.capacity())
        }

        return Extractor.RESULT_CONTINUE
    }

    // Internals.
    private fun onEndOfInput() {
        for (i in 0..<psPayloadReaders.size()) {
            psPayloadReaders.valueAt(i)!!.consumeEndOfInput()
        }
    }

    @RequiresNonNull("output")
    private fun maybeOutputSeekMap(inputLength: Long) {
        if (!hasOutputSeekMap) {
            hasOutputSeekMap = true
            if (durationReader.durationUs != C.TIME_UNSET) {
                psBinarySearchSeeker =
                    PsBinarySearchSeeker(
                        durationReader.getScrTimestampAdjuster(),
                        durationReader.durationUs,
                        inputLength
                    )
                output.seekMap(psBinarySearchSeeker!!.getSeekMap())
            } else {
                output.seekMap(SeekMap.Unseekable(durationReader.durationUs))
            }
        }
    }

    private class PesReader(
        private val pesPayloadReader: ElementaryStreamReader,
        timestampAdjuster: TimestampAdjuster
    ) {
        private val timestampAdjuster: TimestampAdjuster
        private val pesScratch: ParsableBitArray

        private var ptsFlag = false
        private var dtsFlag = false
        private var seenFirstDts = false
        private var extendedHeaderLength = 0
        private var timeUs: Long = 0

        init {
            this.timestampAdjuster = timestampAdjuster
            pesScratch = ParsableBitArray(ByteArray(PES_SCRATCH_SIZE))
        }

        fun seek() {
            seenFirstDts = false
            pesPayloadReader.seek()
        }

        @Throws(ParserException::class)
        fun consume(data: ParsableByteArray) {
            data.readBytes(pesScratch.data, 0, 3)
            pesScratch.position=(0)
            parseHeader()
            data.readBytes(pesScratch.data, 0, extendedHeaderLength)
            pesScratch.position=(0)
            parseHeaderExtension()
            pesPayloadReader.packetStarted(
                timeUs,
                TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR
            )
            pesPayloadReader.consume(data)
            // We always have complete PES packets with program stream.
            pesPayloadReader.packetFinished()
        }

        fun consumeEndOfInput() {
            pesPayloadReader.endOfInputReached()
        }

        fun parseHeader() {
            // Note: see ISO/IEC 13818-1, section 2.4.3.6 for detailed information on the format of
            // the header.
            // First 8 bits are skipped: '10' (2), PES_scrambling_control (2), PES_priority (1),
            // data_alignment_indicator (1), copyright (1), original_or_copy (1)
            pesScratch.skipBits(8)
            ptsFlag = pesScratch.readBit()
            dtsFlag = pesScratch.readBit()
            // ESCR_flag (1), ES_rate_flag (1), DSM_trick_mode_flag (1),
            // additional_copy_info_flag (1), PES_CRC_flag (1), PES_extension_flag (1)
            pesScratch.skipBits(6)
            extendedHeaderLength = pesScratch.readBits(8)
        }

        fun parseHeaderExtension() {
            timeUs = 0
            if (ptsFlag) {
                pesScratch.skipBits(4) // '0010' or '0011'
                var pts = pesScratch.readBits(3).toLong() shl 30
                pesScratch.skipBits(1) // marker_bit
                pts = pts or (pesScratch.readBits(15) shl 15).toLong()
                pesScratch.skipBits(1) // marker_bit
                pts = pts or pesScratch.readBits(15).toLong()
                pesScratch.skipBits(1) // marker_bit
                if (!seenFirstDts && dtsFlag) {
                    pesScratch.skipBits(4) // '0011'
                    var dts = pesScratch.readBits(3).toLong() shl 30
                    pesScratch.skipBits(1) // marker_bit
                    dts = dts or (pesScratch.readBits(15) shl 15).toLong()
                    pesScratch.skipBits(1) // marker_bit
                    dts = dts or pesScratch.readBits(15).toLong()
                    pesScratch.skipBits(1) // marker_bit
                    // Subsequent PES packets may have earlier presentation timestamps than this one, but they
                    // should all be greater than or equal to this packet's decode timestamp. We feed the
                    // decode timestamp to the adjuster here so that in the case that this is the first to be
                    // fed, the adjuster will be able to compute an offset to apply such that the adjusted
                    // presentation timestamps of all future packets are non-negative.
                    timestampAdjuster.adjustTsTimestamp(dts)
                    seenFirstDts = true
                }
                timeUs = timestampAdjuster.adjustTsTimestamp(pts)
            }
        }

        companion object {
            private const val PES_SCRATCH_SIZE = 64
        }
    }

    companion object {
        val FACTORY: ExtractorsFactory =
            object :ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> {
                    return  arrayOf<Extractor>(PsExtractor())
                }
            }

        const val PACK_START_CODE: Int = 0x000001BA

        const val SYSTEM_HEADER_START_CODE: Int = 0x000001BB

        const val PACKET_START_CODE_PREFIX: Int = 0x000001

        const val MPEG_PROGRAM_END_CODE: Int = 0x000001B9
        private const val MAX_STREAM_ID_PLUS_ONE = 0x100

        // Max search length for first audio and video track in input data.
        private val MAX_SEARCH_LENGTH = (1024 * 1024).toLong()

        // Max search length for additional audio and video tracks in input data after at least one audio
        // and video track has been found.
        private val MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND = (8 * 1024).toLong()

        const val PRIVATE_STREAM_1: Int = 0xBD
        const val AUDIO_STREAM: Int = 0xC0
        const val AUDIO_STREAM_MASK: Int = 0xE0
        const val VIDEO_STREAM: Int = 0xE0
        const val VIDEO_STREAM_MASK: Int = 0xF0
    }
}
