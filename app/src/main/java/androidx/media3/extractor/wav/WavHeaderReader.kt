
package androidx.media3.extractor.wav

import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.common.util.WavUtil
import androidx.media3.common.util.WavUtil.TYPE_WAVE_FORMAT_EXTENSIBLE
import androidx.media3.extractor.ExtractorInput
import com.google.common.base.Preconditions
import java.io.IOException

internal object WavHeaderReader {
    private const val TAG = "WavHeaderReader"
    private val WAVEEXT_SUBFORMAT = byteArrayOf(
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x10.toByte(), 0x00.toByte(), 0x80.toByte(), 0x00.toByte(),
        0x00.toByte(), 0xAA.toByte(), 0x00.toByte(), 0x38.toByte(),
        0x9B.toByte(), 0x71.toByte()
    )
    private val AMBISONIC_SUBFORMAT = byteArrayOf(
        0x00.toByte(), 0x00.toByte(), 0x21.toByte(), 0x07.toByte(),
        0xD3.toByte(), 0x11.toByte(), 0x86.toByte(), 0x44.toByte(),
        0xC8.toByte(), 0xC1.toByte(), 0xCA.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte()
    )

    @Throws(IOException::class)
    fun checkFileType(input: ExtractorInput): Boolean {
        val scratch: ParsableByteArray = ParsableByteArray(ChunkHeader.SIZE_IN_BYTES)
        val chunkHeader: ChunkHeader = ChunkHeader.peek(input, scratch)
        if (chunkHeader.id != WavUtil.RIFF_FOURCC && chunkHeader.id != WavUtil.RF64_FOURCC) {
            return false
        }

        input.peekFully(scratch.data, 0, 4)
        scratch.setPosition(0)
        val formType: Int = scratch.readInt()
        if (formType != WavUtil.WAVE_FOURCC) {
            Log.e(TAG, "Unsupported form type: " + formType)
            return false
        }

        return true
    }

    @Throws(IOException::class)
    fun readRf64SampleDataSize(input: ExtractorInput): Long {
        val scratch: ParsableByteArray = ParsableByteArray(ChunkHeader.SIZE_IN_BYTES)
        val chunkHeader: ChunkHeader = ChunkHeader.peek(input, scratch)
        if (chunkHeader.id != WavUtil.DS64_FOURCC) {
            input.resetPeekPosition()
            return C.LENGTH_UNSET.toLong()
        }
        input.advancePeekPosition(8) // RIFF size
        scratch.setPosition(0)
        input.peekFully(scratch.data, 0, 8)
        val sampleDataSize: Long = scratch.readLittleEndianLong()
        input.skipFully(ChunkHeader.SIZE_IN_BYTES + chunkHeader.size.toInt())
        return sampleDataSize
    }

    @Throws(IOException::class)
    fun readFormat(input: ExtractorInput): WavFormat {
        // Allocate a scratch buffer large enough to store the format chunk.
        val scratch: ParsableByteArray = ParsableByteArray(16)
        // Skip chunks until we find the format chunk.
        val chunkHeader = skipToChunk( WavUtil.FMT_FOURCC, input, scratch)
        Preconditions.checkState(chunkHeader.size >= 16)
        input.peekFully(scratch.data, 0, 16)
        scratch.setPosition(0)
        var audioFormatType: Int = scratch.readLittleEndianUnsignedShort()
        val numChannels: Int = scratch.readLittleEndianUnsignedShort()
        val frameRateHz: Int = scratch.readLittleEndianUnsignedIntToInt()
        val averageBytesPerSecond: Int = scratch.readLittleEndianUnsignedIntToInt()
        val blockSize: Int = scratch.readLittleEndianUnsignedShort()
        val bitsPerSample: Int = scratch.readLittleEndianUnsignedShort()

        val bytesLeft = chunkHeader.size.toInt() - 16
        val extraData: ByteArray?
        var channelMask = 0
        if (bytesLeft > 0) {
            extraData = ByteArray(bytesLeft)
            input.peekFully(extraData, 0, bytesLeft)
            if (audioFormatType == TYPE_WAVE_FORMAT_EXTENSIBLE && bytesLeft == 24) {
                val extensionScratch: ParsableByteArray = ParsableByteArray(extraData)
                extensionScratch.readLittleEndianUnsignedShort() // read cbSize
                val validBitsPerSample: Int = extensionScratch.readLittleEndianUnsignedShort()
                if (validBitsPerSample != 0 && validBitsPerSample != bitsPerSample) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        ("validBits ( "
                                + validBitsPerSample
                                + ")  != bitsPerSample( "
                                + bitsPerSample
                                + ") are not supported")
                    )
                }
                channelMask = extensionScratch.readLittleEndianUnsignedIntToInt()
                if (!WavUtil.isChannelMaskValid(channelMask, numChannels)) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        ("Channel mask "
                                + channelMask
                                + " is invalid or does not match channel count "
                                + numChannels)
                    )
                }
                audioFormatType = extensionScratch.readLittleEndianUnsignedShort()
                val extensionString = ByteArray(14)
                extensionScratch.readBytes(extensionString, 0, 14)
                if (!extensionString.contentEquals(WAVEEXT_SUBFORMAT)
                    && !extensionString.contentEquals(AMBISONIC_SUBFORMAT)
                ) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "invalid wav format extension guid"
                    )
                }
            }
        } else {
            extraData = Util.EMPTY_BYTE_ARRAY
        }

        input.skipFully((input.peekPosition - input.position) as Int)
        return WavFormat(
            audioFormatType,
            numChannels,
            frameRateHz,
            averageBytesPerSecond,
            blockSize,
            bitsPerSample,
            extraData,
            channelMask
        )
    }

    @Throws(IOException::class)
    fun skipToSampleData(input: ExtractorInput): Pair<Long, Long> {
        // Make sure the peek position is set to the read position before we peek the first header.
        input.resetPeekPosition()

        val scratch: ParsableByteArray = ParsableByteArray(ChunkHeader.SIZE_IN_BYTES)
        // Skip all chunks until we find the data header.
        val chunkHeader = skipToChunk( WavUtil.DATA_FOURCC, input, scratch)
        // Skip past the "data" header.
        input.skipFully(ChunkHeader.SIZE_IN_BYTES)

        val dataStartPosition: Long = input.position
        return Pair.create<Long, Long>(dataStartPosition, chunkHeader.size)
    }

    @Throws(IOException::class)
    private fun skipToChunk(
        chunkId: Int, input: ExtractorInput, scratch: ParsableByteArray
    ): ChunkHeader {
        var chunkHeader: ChunkHeader = ChunkHeader.peek(input, scratch)
        while (chunkHeader.id != chunkId) {
            Log.w(TAG, "Ignoring unknown WAV chunk: " + chunkHeader.id)
            var bytesToSkip: Long = ChunkHeader.SIZE_IN_BYTES + chunkHeader.size
            // According to the RIFF specification, if a chunk's body size is odd, it's followed by a
            // padding byte of value 0. This ensures each chunk occupies an even number of bytes in the
            // file. The padding byte isn't included in the size field.
            if (chunkHeader.size % 2 != 0L) {
                bytesToSkip++ // padding present if size is odd, skip it.
            }
            if (bytesToSkip > Int.MAX_VALUE) {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Chunk is too large (~2GB+) to skip; id: " + chunkHeader.id
                )
            }
            input.skipFully(bytesToSkip.toInt())
            chunkHeader = ChunkHeader.peek(input, scratch)
        }
        return chunkHeader
    }

    private class ChunkHeader(
        
        val id: Int,
        
        val size: Long
    ) {
        companion object {
            
            const val SIZE_IN_BYTES: Int = 8

            @Throws(IOException::class)
            fun peek(input: ExtractorInput, scratch: ParsableByteArray): ChunkHeader {
                input.peekFully(scratch.data,  0,  SIZE_IN_BYTES)
                scratch.setPosition(0)

                val id: Int = scratch.readInt()
                val size: Long = scratch.readLittleEndianUnsignedInt()

                return ChunkHeader(id, size)
            }
        }
    }
}
