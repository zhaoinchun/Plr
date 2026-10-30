package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.ExtractorInput
import java.io.IOException
import kotlin.math.min

internal class PsBinarySearchSeeker(
    scrTimestampAdjuster: TimestampAdjuster,
    streamDurationUs: Long,
    inputLength: Long
) : BinarySearchSeeker(
    DefaultSeekTimestampConverter(),
    PsScrSeeker(scrTimestampAdjuster),
    streamDurationUs,  
    0,  
    streamDurationUs + 1,  
    0,  
    inputLength,  
    TsExtractor.TS_PACKET_SIZE.toLong(),
    MINIMUM_SEARCH_RANGE_BYTES
) {
    private class PsScrSeeker(scrTimestampAdjuster: TimestampAdjuster) : TimestampSeeker {
        private val scrTimestampAdjuster: TimestampAdjuster
        private val packetBuffer: ParsableByteArray

        init {
            this.scrTimestampAdjuster = scrTimestampAdjuster
            packetBuffer = ParsableByteArray()
        }

        @Throws(IOException::class)
        override fun searchForTimestamp(
            input: ExtractorInput,
            targetTimestamp: Long
        ): TimestampSearchResult {
            val inputPosition: Long = input.position
            val bytesToSearch = min(TIMESTAMP_SEARCH_BYTES.toLong(), input.length - inputPosition) as Int

            packetBuffer.reset(bytesToSearch)
            input.peekFully(packetBuffer.data,  0, bytesToSearch)

            return searchForScrValueInBuffer(packetBuffer, targetTimestamp, inputPosition)
        }

        override fun onSeekFinished() {
            packetBuffer.reset(Util.EMPTY_BYTE_ARRAY)
        }

        fun searchForScrValueInBuffer(
            packetBuffer: ParsableByteArray, targetScrTimeUs: Long, bufferStartOffset: Long
        ): TimestampSearchResult {
            var startOfLastPacketPosition = C.INDEX_UNSET
            var endOfLastPacketPosition = C.INDEX_UNSET
            var lastScrTimeUsInRange = C.TIME_UNSET

            while (packetBuffer.bytesLeft() >= 4) {
                val nextStartCode: Int =
                    peekIntAtPosition(packetBuffer.data, packetBuffer.getPosition())
                if (nextStartCode != PsExtractor.PACK_START_CODE) {
                    packetBuffer.skipBytes(1)
                    continue
                } else {
                    packetBuffer.skipBytes(4)
                }

                // We found a pack.
                val scrValue: Long = PsDurationReader.readScrValueFromPack(packetBuffer)
                if (scrValue != C.TIME_UNSET) {
                    val scrTimeUs: Long = scrTimestampAdjuster.adjustTsTimestamp(scrValue)
                    if (scrTimeUs > targetScrTimeUs) {
                        if (lastScrTimeUsInRange == C.TIME_UNSET) {
                            // First SCR timestamp is already over target.
                            return TimestampSearchResult.overestimatedResult(
                                scrTimeUs,
                                bufferStartOffset
                            )
                        } else {
                            // Last SCR timestamp < target timestamp < this timestamp.
                            return TimestampSearchResult.targetFoundResult(
                                bufferStartOffset + startOfLastPacketPosition
                            )
                        }
                    } else if (scrTimeUs + SEEK_TOLERANCE_US > targetScrTimeUs) {
                        val startOfPacketInStream: Long =
                            bufferStartOffset + packetBuffer.getPosition()
                        return TimestampSearchResult.targetFoundResult(startOfPacketInStream)
                    }

                    lastScrTimeUsInRange = scrTimeUs
                    startOfLastPacketPosition = packetBuffer.getPosition()
                }
                skipToEndOfCurrentPack(packetBuffer)
                endOfLastPacketPosition = packetBuffer.getPosition()
            }

            if (lastScrTimeUsInRange != C.TIME_UNSET) {
                val endOfLastPacketPositionInStream = bufferStartOffset + endOfLastPacketPosition
                return TimestampSearchResult.underestimatedResult(
                    lastScrTimeUsInRange, endOfLastPacketPositionInStream
                )
            } else {
                return TimestampSearchResult.NO_TIMESTAMP_IN_RANGE_RESULT
            }
        }

        companion object {
            
            private fun skipToEndOfCurrentPack(packetBuffer: ParsableByteArray) {
                val limit: Int = packetBuffer.limit()

                if (packetBuffer.bytesLeft() < 10) {
                    // We require at least 9 bytes for pack header to read SCR value + 1 byte for pack_stuffing
                    // length.
                    packetBuffer.setPosition(limit)
                    return
                }
                packetBuffer.skipBytes(9)

                val packStuffingLength: Int = packetBuffer.readUnsignedByte() and 0x07
                if (packetBuffer.bytesLeft() < packStuffingLength) {
                    packetBuffer.setPosition(limit)
                    return
                }
                packetBuffer.skipBytes(packStuffingLength)

                if (packetBuffer.bytesLeft() < 4) {
                    packetBuffer.setPosition(limit)
                    return
                }

                var nextStartCode: Int =
                    peekIntAtPosition(packetBuffer.data, packetBuffer.getPosition())
                if (nextStartCode == PsExtractor.SYSTEM_HEADER_START_CODE) {
                    packetBuffer.skipBytes(4)
                    val systemHeaderLength: Int = packetBuffer.readUnsignedShort()
                    if (packetBuffer.bytesLeft() < systemHeaderLength) {
                        packetBuffer.setPosition(limit)
                        return
                    }
                    packetBuffer.skipBytes(systemHeaderLength)
                }

                // Find the position of the next PACK_START_CODE or MPEG_PROGRAM_END_CODE, which is right
                // after the end position of this pack.
                // If we couldn't find these codes within the buffer, return the buffer limit, or return
                // the first position which PES packets pattern does not match (some malformed packets).
                while (packetBuffer.bytesLeft() >= 4) {
                    nextStartCode =
                        peekIntAtPosition(packetBuffer.data, packetBuffer.getPosition())
                    if (nextStartCode == PsExtractor.PACK_START_CODE
                        || nextStartCode == PsExtractor.MPEG_PROGRAM_END_CODE
                    ) {
                        break
                    }
                    if (nextStartCode ushr 8 != PsExtractor.PACKET_START_CODE_PREFIX) {
                        break
                    }
                    packetBuffer.skipBytes(4)

                    if (packetBuffer.bytesLeft() < 2) {
                        // 2 bytes for PES_packet length.
                        packetBuffer.setPosition(limit)
                        return
                    }
                    val pesPacketLength: Int = packetBuffer.readUnsignedShort()
                    packetBuffer.setPosition(
                        min(packetBuffer.limit(), packetBuffer.getPosition() + pesPacketLength)
                    )
                }
            }
        }
    }

    companion object {
        private const val SEEK_TOLERANCE_US: Long = 100000
        private const val MINIMUM_SEARCH_RANGE_BYTES = 1000
        private const val TIMESTAMP_SEARCH_BYTES = 20000

        private fun peekIntAtPosition(data: ByteArray, position: Int): Int {
            return ((data[position].toInt() and 0xFF) shl 24 or ((data[position + 1].toInt() and 0xFF) shl 16
                    ) or ((data[position + 2].toInt() and 0xFF) shl 8
                    ) or (data[position + 3].toInt() and 0xFF))
        }
    }
}
