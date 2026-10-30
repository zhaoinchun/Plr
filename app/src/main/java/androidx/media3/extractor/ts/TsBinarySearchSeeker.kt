package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.ExtractorInput
import java.io.IOException
import kotlin.math.min

internal class TsBinarySearchSeeker(
    pcrTimestampAdjuster: TimestampAdjuster,
    streamDurationUs: Long,
    inputLength: Long,
    pcrPid: Int,
    timestampSearchBytes: Int
) : BinarySearchSeeker(
    BinarySearchSeeker.DefaultSeekTimestampConverter(),
    TsPcrSeeker(pcrPid, pcrTimestampAdjuster, timestampSearchBytes),
    streamDurationUs,  
    0,  
    streamDurationUs + 1,  
    0,  
    inputLength,  
    TsExtractor.TS_PACKET_SIZE.toLong(),
    MINIMUM_SEARCH_RANGE_BYTES
) {
    private class TsPcrSeeker(
        private val pcrPid: Int,
        pcrTimestampAdjuster: TimestampAdjuster,
        timestampSearchBytes: Int
    ) : TimestampSeeker {
        private val pcrTimestampAdjuster: TimestampAdjuster
        private val packetBuffer: ParsableByteArray
        private val timestampSearchBytes: Int

        init {
            this.pcrTimestampAdjuster = pcrTimestampAdjuster
            this.timestampSearchBytes = timestampSearchBytes
            packetBuffer = ParsableByteArray()
        }

        @Throws(IOException::class)
        override fun searchForTimestamp(
            input: ExtractorInput,
            targetTimestamp: Long
        ): TimestampSearchResult {
            val inputPosition: Long = input.position
            val bytesToSearch = min(timestampSearchBytes.toLong(), input.length - inputPosition) as Int

            packetBuffer.reset(bytesToSearch)
            input.peekFully(packetBuffer.data,  0, bytesToSearch)

            return searchForPcrValueInBuffer(packetBuffer, targetTimestamp, inputPosition)
        }

        fun searchForPcrValueInBuffer(
            packetBuffer: ParsableByteArray, targetPcrTimeUs: Long, bufferStartOffset: Long
        ): TimestampSearchResult {
            val limit: Int = packetBuffer.limit()

            var startOfLastPacketPosition = C.INDEX_UNSET.toLong()
            var endOfLastPacketPosition = C.INDEX_UNSET.toLong()
            var lastPcrTimeUsInRange = C.TIME_UNSET

            while (packetBuffer.bytesLeft() >= TsExtractor.TS_PACKET_SIZE) {
                val startOfPacket =
                    TsUtil.findSyncBytePosition(
                        packetBuffer.data,
                        packetBuffer.getPosition(),
                        limit
                    )
                val endOfPacket: Int = startOfPacket + TsExtractor.TS_PACKET_SIZE
                if (endOfPacket > limit) {
                    break
                }
                val pcrValue = TsUtil.readPcrFromPacket(packetBuffer, startOfPacket, pcrPid)
                if (pcrValue != C.TIME_UNSET) {
                    val pcrTimeUs: Long = pcrTimestampAdjuster.adjustTsTimestamp(pcrValue)
                    if (pcrTimeUs > targetPcrTimeUs) {
                        if (lastPcrTimeUsInRange == C.TIME_UNSET) {
                            // First PCR timestamp is already over target.
                            return TimestampSearchResult.overestimatedResult(
                                pcrTimeUs,
                                bufferStartOffset
                            )
                        } else {
                            // Last PCR timestamp < target timestamp < this timestamp.
                            return TimestampSearchResult.targetFoundResult(
                                bufferStartOffset + startOfLastPacketPosition
                            )
                        }
                    } else if (pcrTimeUs + SEEK_TOLERANCE_US > targetPcrTimeUs) {
                        val startOfPacketInStream = bufferStartOffset + startOfPacket
                        return TimestampSearchResult.targetFoundResult(startOfPacketInStream)
                    }

                    lastPcrTimeUsInRange = pcrTimeUs
                    startOfLastPacketPosition = startOfPacket.toLong()
                }
                packetBuffer.setPosition(endOfPacket)
                endOfLastPacketPosition = endOfPacket.toLong()
            }

            if (lastPcrTimeUsInRange != C.TIME_UNSET) {
                val endOfLastPacketPositionInStream = bufferStartOffset + endOfLastPacketPosition
                return TimestampSearchResult.underestimatedResult(
                    lastPcrTimeUsInRange, endOfLastPacketPositionInStream
                )
            } else {
                return TimestampSearchResult.NO_TIMESTAMP_IN_RANGE_RESULT
            }
        }

        override fun onSeekFinished() {
            packetBuffer.reset(Util.EMPTY_BYTE_ARRAY)
        }
    }

    companion object {
        private const val SEEK_TOLERANCE_US: Long = 100000
        private val MINIMUM_SEARCH_RANGE_BYTES: Int = 5 * TsExtractor.TS_PACKET_SIZE
    }
}
