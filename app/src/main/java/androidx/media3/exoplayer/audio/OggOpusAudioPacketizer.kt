package androidx.media3.exoplayer.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Util.crc32
import androidx.media3.container.OpusUtil.parsePacketAudioSampleCount
import androidx.media3.decoder.DecoderInputBuffer
import com.google.common.base.Preconditions
import com.google.common.primitives.UnsignedBytes
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OggOpusAudioPacketizer {
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var pageSequenceNumber = FIRST_AUDIO_SAMPLE_PAGE_SEQUENCE_NUMBER
    private var granulePosition = 0

    fun packetize(inputBuffer: DecoderInputBuffer, initializationData: List<ByteArray>) {
        if (inputBuffer.data!!.limit() - inputBuffer.data!!.position() == 0) {
            return
        }
        val providedOggIdHeaderPayloadBytes =
            if (pageSequenceNumber == FIRST_AUDIO_SAMPLE_PAGE_SEQUENCE_NUMBER
                && (initializationData.size == 1 || initializationData.size == 3)
            )
                initializationData[0]
            else
                null
        outputBuffer = packetizeInternal(inputBuffer.data!!, providedOggIdHeaderPayloadBytes)
        inputBuffer.clear()
        inputBuffer.ensureSpaceForWrite(outputBuffer.remaining())
        inputBuffer.data!!.put(outputBuffer)
        inputBuffer.flip()
    }

    fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        granulePosition = 0
        pageSequenceNumber = FIRST_AUDIO_SAMPLE_PAGE_SEQUENCE_NUMBER
    }

    private fun packetizeInternal(
        inputBuffer: ByteBuffer, providedOggIdHeaderPayloadBytes: ByteArray?
    ): ByteBuffer {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val inputBufferSize = limit - position

        // inputBufferSize divisible by 255 requires extra '0' terminating lacing value
        val numSegments = (inputBufferSize + 255) / 255
        val headerSize = 27 + numSegments

        var outputPacketSize = headerSize + inputBufferSize

        // If first audio sample in stream, then the packetizer will add Ogg ID Header and Comment
        // Header Pages. Include additional page lengths in buffer size calculation.
        var oggIdHeaderPageSize = 0
        if (pageSequenceNumber == FIRST_AUDIO_SAMPLE_PAGE_SEQUENCE_NUMBER) {
            oggIdHeaderPageSize =
                if (providedOggIdHeaderPayloadBytes != null)
                    OGG_PACKET_HEADER_LENGTH + providedOggIdHeaderPayloadBytes.size
                else
                    OGG_DEFAULT_ID_HEADER_PAGE.size
            outputPacketSize += oggIdHeaderPageSize + OGG_DEFAULT_COMMENT_HEADER_PAGE.size
        }

        // Resample the little endian input and update the output buffers.
        val buffer = replaceOutputBuffer(outputPacketSize)

        // If first audio sample in stream then insert Ogg ID Header and Comment Header Pages
        if (pageSequenceNumber == FIRST_AUDIO_SAMPLE_PAGE_SEQUENCE_NUMBER) {
            if (providedOggIdHeaderPayloadBytes != null) {
                writeOggIdHeaderPage(buffer, providedOggIdHeaderPayloadBytes)
            } else {
                // Write default Ogg ID Header Payload
                buffer.put(OGG_DEFAULT_ID_HEADER_PAGE)
            }
            buffer.put(OGG_DEFAULT_COMMENT_HEADER_PAGE)
        }

        // granule_position
        val numSamples = parsePacketAudioSampleCount(inputBuffer)
        granulePosition += numSamples

        writeOggPacketHeader(
            buffer,
            granulePosition.toLong(),
            pageSequenceNumber,
            numSegments,  
            false
        )

        // Segment_table
        var bytesLeft = inputBufferSize
        for (i in 0..<numSegments) {
            if (bytesLeft >= 255) {
                buffer.put(255.toByte())
                bytesLeft -= 255
            } else {
                buffer.put(bytesLeft.toByte())
                bytesLeft = 0
            }
        }

        // Write Opus audio data
        for (i in position..<limit) {
            buffer.put(inputBuffer[i])
        }

        inputBuffer.position(inputBuffer.limit())
        buffer.flip()

        val checksum: Int
        if (pageSequenceNumber == FIRST_AUDIO_SAMPLE_PAGE_SEQUENCE_NUMBER) {
            checksum =
                crc32(
                    buffer.array(),  
                    (buffer.arrayOffset()
                            + oggIdHeaderPageSize
                            + OGG_DEFAULT_COMMENT_HEADER_PAGE.size),  
                    buffer.limit() - buffer.position(),  
                    0
                )
            buffer.putInt(
                oggIdHeaderPageSize + OGG_DEFAULT_COMMENT_HEADER_PAGE.size + CHECKSUM_INDEX,
                checksum
            )
        } else {
            checksum =
                crc32(
                    buffer.array(),  
                    buffer.arrayOffset(),  
                    buffer.limit() - buffer.position(),  
                    0
                )
            buffer.putInt(CHECKSUM_INDEX, checksum)
        }

        // Increase pageSequenceNumber for next packet
        pageSequenceNumber++
        return buffer
    }

    private fun writeOggIdHeaderPage(buffer: ByteBuffer, idHeaderPayloadBytes: ByteArray) {
        writeOggPacketHeader(buffer, 0, 0, 1, true)
        buffer.put(UnsignedBytes.checkedCast(idHeaderPayloadBytes.size.toLong()))
        buffer.put(idHeaderPayloadBytes)
        val checksum =
            crc32(
                buffer.array(),
                buffer.arrayOffset(),
                OGG_PACKET_HEADER_LENGTH + idHeaderPayloadBytes.size,
                0
            )
        buffer.putInt( CHECKSUM_INDEX, checksum)
        buffer.position(OGG_PACKET_HEADER_LENGTH + idHeaderPayloadBytes.size)
    }

    private fun writeOggPacketHeader(
        byteBuffer: ByteBuffer,
        granulePosition: Long,
        pageSequenceNumber: Int,
        numberPageSegments: Int,
        isIdHeaderPacket: Boolean
    ) {
        // Capture Pattern for Ogg Page [OggS]
        byteBuffer.put('O'.code.toByte())
        byteBuffer.put('g'.code.toByte())
        byteBuffer.put('g'.code.toByte())
        byteBuffer.put('S'.code.toByte())

        // StreamStructure Version
        byteBuffer.put(0.toByte())

        // Header-type
        byteBuffer.put(if (isIdHeaderPacket) 0x02.toByte() else 0x00.toByte())

        // Granule_position
        byteBuffer.putLong(granulePosition)

        // bitstream_serial_number
        byteBuffer.putInt(SERIAL_NUMBER)

        // Page_sequence_number
        byteBuffer.putInt(pageSequenceNumber)

        // CRC_checksum
        // Will be overwritten with calculated checksum after rest of page is written to buffer.
        byteBuffer.putInt(0)

        // Number_page_segments
        byteBuffer.put(UnsignedBytes.checkedCast(numberPageSegments.toLong()))
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    companion object {
        private const val CHECKSUM_INDEX = 22

        private const val FIRST_AUDIO_SAMPLE_PAGE_SEQUENCE_NUMBER = 2

        private const val OGG_PACKET_HEADER_LENGTH = 28
        private const val SERIAL_NUMBER = 0
        private val OGG_DEFAULT_ID_HEADER_PAGE = byteArrayOf(
            79,
            103,
            103,
            83,
            0,
            2,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            28,
            -43,
            -59,
            -9,
            1,
            19,
            79,
            112,
            117,
            115,
            72,
            101,
            97,
            100,
            1,
            2,
            56,
            1,
            -128,
            -69,
            0,
            0,
            0,
            0,
            0
        )
        private val OGG_DEFAULT_COMMENT_HEADER_PAGE = byteArrayOf(
            79, 103, 103, 83, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 11, -103, 87,
            83, 1, 16, 79, 112, 117, 115, 84, 97, 103, 115, 0, 0, 0, 0, 0, 0, 0, 0
        )
    }
}
