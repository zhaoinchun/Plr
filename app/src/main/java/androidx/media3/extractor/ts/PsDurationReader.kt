package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import java.io.IOException
import kotlin.math.min

internal class PsDurationReader {
    private val scrTimestampAdjuster: TimestampAdjuster
    private val packetBuffer: ParsableByteArray

    var isDurationReadFinished: Boolean = false
        private set
    private var isFirstScrValueRead = false
    private var isLastScrValueRead = false

    private var firstScrValue: Long
    private var lastScrValue: Long

    var durationUs: Long
        private set

    init {
        scrTimestampAdjuster = TimestampAdjuster( 0)
        firstScrValue = C.TIME_UNSET
        lastScrValue = C.TIME_UNSET
        durationUs = C.TIME_UNSET
        packetBuffer = ParsableByteArray()
    }

    fun getScrTimestampAdjuster(): TimestampAdjuster {
        return scrTimestampAdjuster
    }

    @Throws(IOException::class)
    fun readDuration(
        input: ExtractorInput, seekPositionHolder: PositionHolder
    ): @Extractor.ReadResult Int {
        if (!isLastScrValueRead) {
            return readLastScrValue(input, seekPositionHolder)
        }
        if (lastScrValue == C.TIME_UNSET) {
            return finishReadDuration(input)
        }
        if (!isFirstScrValueRead) {
            return readFirstScrValue(input, seekPositionHolder)
        }
        if (firstScrValue == C.TIME_UNSET) {
            return finishReadDuration(input)
        }

        val minScrPositionUs: Long = scrTimestampAdjuster.adjustTsTimestamp(firstScrValue)
        val maxScrPositionUs: Long =
            scrTimestampAdjuster.adjustTsTimestampGreaterThanPreviousTimestamp(lastScrValue)
        durationUs = maxScrPositionUs - minScrPositionUs
        return finishReadDuration(input)
    }

    private fun finishReadDuration(input: ExtractorInput): Int {
        packetBuffer.reset(Util.EMPTY_BYTE_ARRAY)
        this.isDurationReadFinished = true
        input.resetPeekPosition()
        return Extractor.RESULT_CONTINUE
    }

    @Throws(IOException::class)
    private fun readFirstScrValue(input: ExtractorInput, seekPositionHolder: PositionHolder): Int {
        val bytesToSearch = min(TIMESTAMP_SEARCH_BYTES.toLong(), input.length) as Int
        val searchStartPosition = 0
        if (input.position != searchStartPosition.toLong()) {
            seekPositionHolder.position = searchStartPosition.toLong()
            return Extractor.RESULT_SEEK
        }

        packetBuffer.reset(bytesToSearch)
        input.resetPeekPosition()
        input.peekFully(packetBuffer.data,  0, bytesToSearch)

        firstScrValue = readFirstScrValueFromBuffer(packetBuffer)
        isFirstScrValueRead = true
        return Extractor.RESULT_CONTINUE
    }

    private fun readFirstScrValueFromBuffer(packetBuffer: ParsableByteArray): Long {
        val searchStartPosition: Int = packetBuffer.getPosition()
        val searchEndPosition: Int = packetBuffer.limit()
        for (searchPosition in searchStartPosition..<searchEndPosition - 3) {
            val nextStartCode = peekIntAtPosition(packetBuffer.data, searchPosition)
            if (nextStartCode == PsExtractor.PACK_START_CODE) {
                packetBuffer.setPosition(searchPosition + 4)
                val scrValue: Long = readScrValueFromPack(packetBuffer)
                if (scrValue != C.TIME_UNSET) {
                    return scrValue
                }
            }
        }
        return C.TIME_UNSET
    }

    @Throws(IOException::class)
    private fun readLastScrValue(input: ExtractorInput, seekPositionHolder: PositionHolder): Int {
        val inputLength: Long = input.length
        val bytesToSearch = min(TIMESTAMP_SEARCH_BYTES.toLong(), inputLength).toInt()
        val searchStartPosition = inputLength - bytesToSearch
        if (input.position !== searchStartPosition) {
            seekPositionHolder.position = searchStartPosition
            return Extractor.RESULT_SEEK
        }

        packetBuffer.reset(bytesToSearch)
        input.resetPeekPosition()
        input.peekFully(packetBuffer.data,  0, bytesToSearch)

        lastScrValue = readLastScrValueFromBuffer(packetBuffer)
        isLastScrValueRead = true
        return Extractor.RESULT_CONTINUE
    }

    private fun readLastScrValueFromBuffer(packetBuffer: ParsableByteArray): Long {
        val searchStartPosition: Int = packetBuffer.getPosition()
        val searchEndPosition: Int = packetBuffer.limit()
        for (searchPosition in searchEndPosition - 4 downTo searchStartPosition) {
            val nextStartCode = peekIntAtPosition(packetBuffer.data, searchPosition)
            if (nextStartCode == PsExtractor.PACK_START_CODE) {
                packetBuffer.setPosition(searchPosition + 4)
                val scrValue: Long = readScrValueFromPack(packetBuffer)
                if (scrValue != C.TIME_UNSET) {
                    return scrValue
                }
            }
        }
        return C.TIME_UNSET
    }

    private fun peekIntAtPosition(data: ByteArray, position: Int): Int {
        return ((data[position].toInt() and 0xFF) shl 24 or ((data[position + 1].toInt() and 0xFF) shl 16
                ) or ((data[position + 2].toInt() and 0xFF) shl 8
                ) or (data[position + 3].toInt() and 0xFF))
    }

    companion object {
        private const val TAG = "PsDurationReader"

        private const val TIMESTAMP_SEARCH_BYTES = 20000

        fun readScrValueFromPack(packetBuffer: ParsableByteArray): Long {
            val originalPosition: Int = packetBuffer.getPosition()
            if (packetBuffer.bytesLeft() < 9) {
                // We require at 9 bytes for pack header to read scr value
                return C.TIME_UNSET
            }
            val scrBytes = ByteArray(9)
            packetBuffer.readBytes(scrBytes,  0, scrBytes.size)
            packetBuffer.setPosition(originalPosition)
            if (!checkMarkerBits(scrBytes)) {
                return C.TIME_UNSET
            }
            return readScrValueFromPackHeader(scrBytes)
        }

        private fun checkMarkerBits(scrBytes: ByteArray): Boolean {
            // Verify the 01xxx1xx marker on the 0th byte
            if ((scrBytes[0].toInt() and 0xC4) != 0x44) {
                return false
            }
            // 1st byte belongs to scr field.
            // Verify the xxxxx1xx marker on the 2nd byte
            if ((scrBytes[2].toInt() and 0x04) != 0x04) {
                return false
            }
            // 3rd byte belongs to scr field.
            // Verify the xxxxx1xx marker on the 4rd byte
            if ((scrBytes[4].toInt() and 0x04) != 0x04) {
                return false
            }
            // Verify the xxxxxxx1 marker on the 5th byte
            if ((scrBytes[5].toInt() and 0x01) != 0x01) {
                return false
            }
            // 6th and 7th bytes belongs to program_max_rate field.
            // Verify the xxxxxx11 marker on the 8th byte
            return (scrBytes[8].toInt() and 0x03) == 0x03
        }

        private fun readScrValueFromPackHeader(scrBytes: ByteArray): Long {
            return (((scrBytes[0].toLong() and 56L) shr 3) shl 30 or ((scrBytes[0].toLong() and 3L) shl 28
                    ) or ((scrBytes[1].toLong() and 0xFFL) shl 20
                    ) or (((scrBytes[2].toLong() and 248L) shr 3) shl 15
                    ) or ((scrBytes[2].toLong() and 3L) shl 13
                    ) or ((scrBytes[3].toLong() and 0xFFL) shl 5
                    ) or ((scrBytes[4].toLong() and 248L) shr 3))
        }
    }
}
