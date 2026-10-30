
package androidx.media3.extractor

import androidx.media3.common.ParserException
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.crc8
import androidx.media3.extractor.flac.FlacConstants
import java.io.IOException

object FlacFrameReader {
    private const val TAG = "FlacFrameReader"

    fun checkAndReadFrameHeader(
        data: ParsableByteArray,
        flacStreamMetadata: FlacStreamMetadata,
        frameStartMarker: Int,
        sampleNumberHolder: SampleNumberHolder
    ): Boolean {
        val frameStartPosition = data.getPosition()

        val frameHeaderBytes = data.readUnsignedInt()
        if (frameHeaderBytes ushr 16 != frameStartMarker.toLong()) {
            return false
        }

        val isBlockSizeVariable = (frameHeaderBytes ushr 16 and 1L) == 1L
        val blockSizeKey = (frameHeaderBytes shr 12 and 0xFL).toInt()
        val sampleRateKey = (frameHeaderBytes shr 8 and 0xFL).toInt()
        val channelAssignmentKey = (frameHeaderBytes shr 4 and 0xFL).toInt()
        val bitsPerSampleKey = (frameHeaderBytes shr 1 and 0x7L).toInt()
        val reservedBit = (frameHeaderBytes and 1L) == 1L
        return checkChannelAssignment(channelAssignmentKey, flacStreamMetadata)
                && checkBitsPerSample(bitsPerSampleKey, flacStreamMetadata)
                && !reservedBit && checkAndReadFirstSampleNumber(
            data, flacStreamMetadata, isBlockSizeVariable, sampleNumberHolder
        )
                && checkAndReadBlockSizeSamples(
            data, flacStreamMetadata, blockSizeKey, sampleNumberHolder.sampleNumber
        )
                && checkAndReadSampleRate(data, flacStreamMetadata, sampleRateKey)
                && checkAndReadCrc(data, frameStartPosition)
                && checkFirstSubframeHeaderFromPeek(data)
    }

    @Throws(IOException::class)
    fun checkFrameHeaderFromPeek(
        input: ExtractorInput,
        flacStreamMetadata: FlacStreamMetadata,
        frameStartMarker: Int,
        sampleNumberHolder: SampleNumberHolder
    ): Boolean {
        val originalPeekPosition = input.peekPosition

        // We will try and read the first subframe header following the frame header as well.
        val dataToCheck = FlacConstants.MAX_FRAME_HEADER_SIZE + 1
        val scratch = ParsableByteArray(dataToCheck)

        // Check the start marker first, before peeking the rest of the frame header.
        input.peekFully(scratch.data, 0, 2)
        val frameStart = scratch.peekChar().code
        if (frameStart != frameStartMarker) {
            input.resetPeekPosition()
            input.advancePeekPosition((originalPeekPosition - input.position).toInt())
            return false
        }

        var totalBytesPeeked = 2
        totalBytesPeeked += ExtractorUtil.peekToLength(input, scratch.data, 2, dataToCheck - 2)
        scratch.setLimit(totalBytesPeeked)

        input.resetPeekPosition()
        input.advancePeekPosition((originalPeekPosition - input.position).toInt())

        return checkAndReadFrameHeader(
            scratch, flacStreamMetadata, frameStartMarker, sampleNumberHolder
        )
    }

    @Throws(IOException::class)
    fun getFirstSampleNumber(
        input: ExtractorInput, flacStreamMetadata: FlacStreamMetadata
    ): Long {
        input.resetPeekPosition()
        input.advancePeekPosition(1)
        val blockingStrategyByte = ByteArray(1)
        input.peekFully(blockingStrategyByte, 0, 1)
        val isBlockSizeVariable = (blockingStrategyByte[0].toInt() and 1) == 1
        input.advancePeekPosition(2)

        val maxUtf8SampleNumberSize = if (isBlockSizeVariable) 7 else 6
        val scratch = ParsableByteArray(maxUtf8SampleNumberSize)
        val totalBytesPeeked =
            ExtractorUtil.peekToLength(input, scratch.data, 0, maxUtf8SampleNumberSize)
        scratch.setLimit(totalBytesPeeked)
        input.resetPeekPosition()

        val sampleNumberHolder = SampleNumberHolder()
        if (!checkAndReadFirstSampleNumber(
                scratch, flacStreamMetadata, isBlockSizeVariable, sampleNumberHolder
            )
        ) {
            throw ParserException.createForMalformedContainer( null,  
                null
            )
        }

        return sampleNumberHolder.sampleNumber
    }

    fun readFrameBlockSizeSamplesFromKey(data: ParsableByteArray, blockSizeKey: Int): Int {
        when (blockSizeKey) {
            1 -> return 192
            2, 3, 4, 5 -> return 576 shl (blockSizeKey - 2)
            6 -> return data.readUnsignedByte() + 1
            7 -> return data.readUnsignedShort() + 1
            8, 9, 10, 11, 12, 13, 14, 15 -> return 256 shl (blockSizeKey - 8)
            else -> return -1
        }
    }

    private fun checkChannelAssignment(
        channelAssignmentKey: Int, flacStreamMetadata: FlacStreamMetadata
    ): Boolean {
        if (channelAssignmentKey <= 7) {
            return channelAssignmentKey == flacStreamMetadata.channels - 1
        } else if (channelAssignmentKey <= 10) {
            return flacStreamMetadata.channels == 2
        } else {
            return false
        }
    }

    private fun checkBitsPerSample(
        bitsPerSampleKey: Int, flacStreamMetadata: FlacStreamMetadata
    ): Boolean {
        if (bitsPerSampleKey == 0) {
            return true
        }
        return bitsPerSampleKey == flacStreamMetadata.bitsPerSampleLookupKey
    }

    private fun checkAndReadFirstSampleNumber(
        data: ParsableByteArray,
        flacStreamMetadata: FlacStreamMetadata,
        isBlockSizeVariable: Boolean,
        sampleNumberHolder: SampleNumberHolder
    ): Boolean {
        val utf8Value: Long
        try {
            utf8Value = data.readUtf8EncodedLong()
        } catch (e: NumberFormatException) {
            return false
        }
        val sampleNumber =
            if (isBlockSizeVariable) utf8Value else utf8Value * flacStreamMetadata.maxBlockSizeSamples
        if (flacStreamMetadata.totalSamples != 0L && sampleNumber > flacStreamMetadata.totalSamples) {
            return false
        }
        sampleNumberHolder.sampleNumber = sampleNumber
        return true
    }

    private fun checkAndReadBlockSizeSamples(
        data: ParsableByteArray,
        flacStreamMetadata: FlacStreamMetadata,
        blockSizeKey: Int,
        firstSampleNumber: Long
    ): Boolean {
        val blockSizeSamples = readFrameBlockSizeSamplesFromKey(data, blockSizeKey)
        val isMaybeLastBlock =
            flacStreamMetadata.totalSamples == 0L
                    || firstSampleNumber + blockSizeSamples >= flacStreamMetadata.totalSamples
        return blockSizeSamples != -1 && (isMaybeLastBlock || blockSizeSamples >= flacStreamMetadata.minBlockSizeSamples)
                && blockSizeSamples <= flacStreamMetadata.maxBlockSizeSamples
    }

    private fun checkAndReadSampleRate(
        data: ParsableByteArray, flacStreamMetadata: FlacStreamMetadata, sampleRateKey: Int
    ): Boolean {
        val expectedSampleRate = flacStreamMetadata.sampleRate
        if (sampleRateKey == 0) {
            return true
        } else if (sampleRateKey <= 11) {
            return sampleRateKey == flacStreamMetadata.sampleRateLookupKey
        } else if (sampleRateKey == 12) {
            return data.readUnsignedByte() * 1000 == expectedSampleRate
        } else if (sampleRateKey <= 14) {
            var sampleRate = data.readUnsignedShort()
            if (sampleRateKey == 14) {
                sampleRate *= 10
            }
            return sampleRate == expectedSampleRate
        } else {
            return false
        }
    }

    private fun checkAndReadCrc(data: ParsableByteArray, frameStartPosition: Int): Boolean {
        val crc = data.readUnsignedByte()
        val frameEndPosition = data.getPosition()
        val expectedCrc =
            crc8(data.data, frameStartPosition, frameEndPosition - 1,  0)
        return crc == expectedCrc
    }

    private fun checkFirstSubframeHeaderFromPeek(data: ParsableByteArray): Boolean {
        if (data.bytesLeft() == 0) {
            // The provided data doesn't include the subframe header, so we can't check it.
            return true
        }
        val subframeHeader = data.peekUnsignedByte()
        // reserved bit
        if ((subframeHeader and 0x80) != 0) {
            return false
        }
        val subframeType = (subframeHeader and 0x7E) shr 1
        if ((subframeType >= 2 && subframeType <= 7) || (subframeType >= 13 && subframeType <= 31)) {
            i(TAG, "Ignoring frame where first subframe has a reserved type: " + subframeType)
            return false
        }
        return true
    }

    class SampleNumberHolder {
        var sampleNumber: Long = 0
    }
}
