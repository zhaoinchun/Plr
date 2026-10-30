package androidx.media3.container

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OpusUtil {
    const val SAMPLE_RATE: Int = 48000
    val MAX_BYTES_PER_SECOND: Int = 510 * 1000 / 8 // See RFC 6716. Section 2.1.1

    private const val DEFAULT_SEEK_PRE_ROLL_SAMPLES = 3840
    private const val FULL_CODEC_INITIALIZATION_DATA_BUFFER_COUNT = 3

    fun getChannelCount(header: ByteArray): Int {
        return header[9].toInt() and 0xFF
    }

    fun buildInitializationData(header: ByteArray): MutableList<ByteArray> {
        val preSkipSamples = getPreSkipSamples(header)
        val preSkipNanos = sampleCountToNanoseconds(preSkipSamples.toLong())
        val seekPreRollNanos = sampleCountToNanoseconds(DEFAULT_SEEK_PRE_ROLL_SAMPLES.toLong())

        val initializationData: MutableList<ByteArray> =
            ArrayList<ByteArray>(FULL_CODEC_INITIALIZATION_DATA_BUFFER_COUNT)
        initializationData.add(header)
        initializationData.add(buildNativeOrderByteArray(preSkipNanos))
        initializationData.add(buildNativeOrderByteArray(seekPreRollNanos))
        return initializationData
    }

    fun parseOggPacketAudioSampleCount(buffer: ByteBuffer): Int {
        // RFC 3433 section 6 - The Ogg page format.
        val preAudioPacketByteCount = parseOggPacketForPreAudioSampleByteCount(buffer)
        val numPageSegments = buffer.get( 26 + preAudioPacketByteCount).toInt()
        // Skip Ogg header + segment table.
        val indexFirstOpusPacket = 27 + numPageSegments + preAudioPacketByteCount
        val packetDurationUs =
            getPacketDurationUs(
                buffer.get(indexFirstOpusPacket),
                if (buffer.limit() - indexFirstOpusPacket > 1) buffer.get(indexFirstOpusPacket + 1) else 0
            )
        return (packetDurationUs * SAMPLE_RATE / C.MICROS_PER_SECOND).toInt()
    }

    fun parseOggPacketForPreAudioSampleByteCount(buffer: ByteBuffer): Int {
        // Parse Ogg Packet Type from Header at index 5
        if ((buffer.get( 5).toInt() and 0x02) == 0) {
            // Ogg Page packet header type is not beginning of logical stream. Must be an Audio page.
            return 0
        }
        // ID Header Page size is Ogg packet header size + sum(lacing values: 1..number_page_segments).
        var idHeaderPageSize = 28
        val idHeaderPageNumOfSegments = buffer.get( 26).toInt()
        for (i in 0..<idHeaderPageNumOfSegments) {
            idHeaderPageSize += buffer.get( 27 + i).toInt()
        }
        // Comment Header Page size is Ogg packet header size + sum(lacing values:
        // 1..number_page_segments).
        var commentHeaderPageSize = 28
        val commentHeaderPageSizeNumOfSegments =
            buffer.get( idHeaderPageSize + 26).toInt()
        for (i in 0..<commentHeaderPageSizeNumOfSegments) {
            commentHeaderPageSize += buffer.get( idHeaderPageSize + 27 + i).toInt()
        }
        return idHeaderPageSize + commentHeaderPageSize
    }

    fun parsePacketAudioSampleCount(buffer: ByteBuffer): Int {
        val packetDurationUs =
            getPacketDurationUs(buffer.get(0), if (buffer.limit() > 1) buffer.get(1) else 0)
        return (packetDurationUs * SAMPLE_RATE / C.MICROS_PER_SECOND).toInt()
    }

    fun getPacketDurationUs(buffer: ByteArray): Long {
        return getPacketDurationUs(buffer[0], if (buffer.size > 1) buffer[1] else 0)
    }

    fun getPreSkipSamples(header: ByteArray): Int {
        return ((header[11].toInt() and 0xFF) shl 8) or (header[10].toInt() and 0xFF)
    }

    fun needToDecodeOpusFrame(startTimeUs: Long, frameTimeUs: Long): Boolean {
        // Divide by 1000 in rhs value to convert nanoseconds to microseconds.
        return ((startTimeUs - frameTimeUs)
                <= (sampleCountToNanoseconds(DEFAULT_SEEK_PRE_ROLL_SAMPLES.toLong()) / 1000))
    }

    private fun getPacketDurationUs(packetByte0: Byte, packetByte1: Byte): Long {
        // See RFC6716, Sections 3.1 and 3.2.
        val toc = packetByte0.toInt() and 0xFF
        val frames: Int
        when (toc and 0x3) {
            0 -> frames = 1
            1, 2 -> frames = 2
            else -> frames = packetByte1.toInt() and 0x3F
        }

        val config = toc shr 3
        val length = config and 0x3
        val frameDurationUs: Int
        if (config >= 16) {
            frameDurationUs = 2500 shl length
        } else if (config >= 12) {
            frameDurationUs = 10000 shl (length and 0x1)
        } else if (length == 3) {
            frameDurationUs = 60000
        } else {
            frameDurationUs = 10000 shl length
        }
        return frames.toLong() * frameDurationUs
    }

    private fun buildNativeOrderByteArray(value: Long): ByteArray {
        return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()
    }

    private fun sampleCountToNanoseconds(sampleCount: Long): Long {
        return (sampleCount * C.NANOS_PER_SECOND) / SAMPLE_RATE
    }
}
