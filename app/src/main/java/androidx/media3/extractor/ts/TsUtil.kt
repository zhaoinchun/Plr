
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray

object TsUtil {
    
    fun isStartOfTsPacket(data: ByteArray, start: Int, limit: Int, searchPosition: Int): Boolean {
        var consecutiveSyncByteCount = 0
        for (i in -4..4) {
            val currentPosition: Int = searchPosition + i * TsExtractor.TS_PACKET_SIZE
            if (currentPosition < start || currentPosition >= limit || data[currentPosition].toInt() != TsExtractor.TS_SYNC_BYTE) {
                consecutiveSyncByteCount = 0
            } else if (++consecutiveSyncByteCount == 5) {
                return true
            }
        }
        return false
    }

    fun findSyncBytePosition(data: ByteArray, startPosition: Int, limitPosition: Int): Int {
        var position = startPosition
        while (position < limitPosition && data[position].toInt() != TsExtractor.TS_SYNC_BYTE) {
            position++
        }
        return position
    }

    fun readPcrFromPacket(
        packetBuffer: ParsableByteArray, startOfPacket: Int, pcrPid: Int
    ): Long {
        packetBuffer.setPosition(startOfPacket)
        if (packetBuffer.bytesLeft() < 5) {
            // Header = 4 bytes, adaptationFieldLength = 1 byte.
            return C.TIME_UNSET
        }
        // Note: See ISO/IEC 13818-1, section 2.4.3.2 for details of the header format.
        val tsPacketHeader = packetBuffer.readInt()
        if ((tsPacketHeader and 0x800000) != 0) {
            // transport_error_indicator != 0 means there are uncorrectable errors in this packet.
            return C.TIME_UNSET
        }
        val pid = (tsPacketHeader and 0x1FFF00) shr 8
        if (pid != pcrPid) {
            return C.TIME_UNSET
        }
        val adaptationFieldExists = (tsPacketHeader and 0x20) != 0
        if (!adaptationFieldExists) {
            return C.TIME_UNSET
        }

        val adaptationFieldLength = packetBuffer.readUnsignedByte()
        if (adaptationFieldLength >= 7 && packetBuffer.bytesLeft() >= 7) {
            val flags = packetBuffer.readUnsignedByte()
            val pcrFlagSet = (flags and 0x10) == 0x10
            if (pcrFlagSet) {
                val pcrBytes = ByteArray(6)
                packetBuffer.readBytes(pcrBytes,  0, pcrBytes.size)
                return readPcrValueFromPcrBytes(pcrBytes)
            }
        }
        return C.TIME_UNSET
    }

    private fun readPcrValueFromPcrBytes(pcrBytes: ByteArray): Long {
        return ((pcrBytes[0].toLong() and 0xFFL) shl 25 or ((pcrBytes[1].toLong() and 0xFFL) shl 17
                ) or ((pcrBytes[2].toLong() and 0xFFL) shl 9
                ) or ((pcrBytes[3].toLong() and 0xFFL) shl 1
                ) or ((pcrBytes[4].toLong() and 0xFFL) shr 7))
    }
}
