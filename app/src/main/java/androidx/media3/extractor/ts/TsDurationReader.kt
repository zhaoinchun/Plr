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

internal class TsDurationReader(private val timestampSearchBytes: Int) {
    private val pcrTimestampAdjuster: TimestampAdjuster
    private val packetBuffer: ParsableByteArray

    var isDurationReadFinished: Boolean = false
        private set
    private var isFirstPcrValueRead = false
    private var isLastPcrValueRead = false

    private var firstPcrValue: Long
    private var lastPcrValue: Long

    var durationUs: Long
        private set

    init {
        pcrTimestampAdjuster = TimestampAdjuster( 0)
        firstPcrValue = C.TIME_UNSET
        lastPcrValue = C.TIME_UNSET
        durationUs = C.TIME_UNSET
        packetBuffer = ParsableByteArray()
    }

    @Throws(IOException::class)
    fun readDuration(
        input: ExtractorInput, seekPositionHolder: PositionHolder, pcrPid: Int
    ): @Extractor.ReadResult Int {
        if (pcrPid <= 0) {
            return finishReadDuration(input)
        }
        if (!isLastPcrValueRead) {
            return readLastPcrValue(input, seekPositionHolder, pcrPid)
        }
        if (lastPcrValue == C.TIME_UNSET) {
            return finishReadDuration(input)
        }
        if (!isFirstPcrValueRead) {
            return readFirstPcrValue(input, seekPositionHolder, pcrPid)
        }
        if (firstPcrValue == C.TIME_UNSET) {
            return finishReadDuration(input)
        }

        val minPcrPositionUs: Long = pcrTimestampAdjuster.adjustTsTimestamp(firstPcrValue)
        val maxPcrPositionUs: Long =
            pcrTimestampAdjuster.adjustTsTimestampGreaterThanPreviousTimestamp(lastPcrValue)
        durationUs = maxPcrPositionUs - minPcrPositionUs
        return finishReadDuration(input)
    }

    fun getPcrTimestampAdjuster(): TimestampAdjuster {
        return pcrTimestampAdjuster
    }

    private fun finishReadDuration(input: ExtractorInput): Int {
        packetBuffer.reset(Util.EMPTY_BYTE_ARRAY)
        this.isDurationReadFinished = true
        input.resetPeekPosition()
        return Extractor.RESULT_CONTINUE
    }

    @Throws(IOException::class)
    private fun readFirstPcrValue(
        input: ExtractorInput,
        seekPositionHolder: PositionHolder,
        pcrPid: Int
    ): Int {
        val bytesToSearch = min(timestampSearchBytes.toLong(), input.length) as Int
        val searchStartPosition = 0
        if (input.position != searchStartPosition.toLong()) {
            seekPositionHolder.position = searchStartPosition.toLong()
            return Extractor.RESULT_SEEK
        }

        packetBuffer.reset(bytesToSearch)
        input.resetPeekPosition()
        input.peekFully(packetBuffer.data,  0, bytesToSearch)

        firstPcrValue = readFirstPcrValueFromBuffer(packetBuffer, pcrPid)
        isFirstPcrValueRead = true
        return Extractor.RESULT_CONTINUE
    }

    private fun readFirstPcrValueFromBuffer(packetBuffer: ParsableByteArray, pcrPid: Int): Long {
        val searchStartPosition: Int = packetBuffer.getPosition()
        val searchEndPosition: Int = packetBuffer.limit()
        for (searchPosition in searchStartPosition..<searchEndPosition) {
            if (packetBuffer.data[searchPosition].toInt() != TsExtractor.TS_SYNC_BYTE) {
                continue
            }
            val pcrValue = TsUtil.readPcrFromPacket(packetBuffer, searchPosition, pcrPid)
            if (pcrValue != C.TIME_UNSET) {
                return pcrValue
            }
        }
        return C.TIME_UNSET
    }

    @Throws(IOException::class)
    private fun readLastPcrValue(
        input: ExtractorInput,
        seekPositionHolder: PositionHolder,
        pcrPid: Int
    ): Int {
        val inputLength: Long = input.length
        val bytesToSearch = min(timestampSearchBytes.toLong(), inputLength).toInt()
        val searchStartPosition = inputLength - bytesToSearch
        if (input.position !== searchStartPosition) {
            seekPositionHolder.position = searchStartPosition
            return Extractor.RESULT_SEEK
        }

        packetBuffer.reset(bytesToSearch)
        input.resetPeekPosition()
        input.peekFully(packetBuffer.data,  0, bytesToSearch)

        lastPcrValue = readLastPcrValueFromBuffer(packetBuffer, pcrPid)
        isLastPcrValueRead = true
        return Extractor.RESULT_CONTINUE
    }

    private fun readLastPcrValueFromBuffer(packetBuffer: ParsableByteArray, pcrPid: Int): Long {
        val searchStartPosition: Int = packetBuffer.getPosition()
        val searchEndPosition: Int = packetBuffer.limit()
        // We start searching 'TsExtractor.TS_PACKET_SIZE' bytes from the end to prevent trying to read
        // from an incomplete TS packet.
        for (searchPosition in searchEndPosition - TsExtractor.TS_PACKET_SIZE downTo searchStartPosition) {
            if (!TsUtil.isStartOfTsPacket(
                    packetBuffer.data, searchStartPosition, searchEndPosition, searchPosition
                )
            ) {
                continue
            }
            val pcrValue = TsUtil.readPcrFromPacket(packetBuffer, searchPosition, pcrPid)
            if (pcrValue != C.TIME_UNSET) {
                return pcrValue
            }
        }
        return C.TIME_UNSET
    }

    companion object {
        private const val TAG = "TsDurationReader"
    }
}
