package androidx.media3.common.util

import androidx.media3.common.util.Util.toLong
import androidx.media3.common.util.Util.toUnsignedLong
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CheckReturnValue
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.math.min

@CheckReturnValue
class ParsableBitArray {
    var data: ByteArray

    // The offset within the data, stored as the current byte offset, and the bit offset within that
    // byte (from 0 to 7).
    private var byteOffset = 0
    private var bitOffset = 0
    private var byteLimit = 0

    constructor() {
        data = Util.EMPTY_BYTE_ARRAY
    }

    constructor(data: ByteArray, limit: Int = data.size) {
        this.data = data
        byteLimit = limit
    }

    fun reset(parsableByteArray: ParsableByteArray) {
        reset(parsableByteArray.data, parsableByteArray.limit())
        this.position = parsableByteArray.getPosition() * 8
    }

    fun reset(data: ByteArray, limit: Int = data.size) {
        this.data = data
        byteOffset = 0
        bitOffset = 0
        byteLimit = limit
    }

    fun bitsLeft(): Int {
        return (byteLimit - byteOffset) * 8 - bitOffset
    }

    var position: Int
        get() = byteOffset * 8 + bitOffset
        set(position) {
            byteOffset = position / 8
            bitOffset = position - (byteOffset * 8)
            assertValidOffset()
        }

    val bytePosition: Int
        get() {
            Preconditions.checkState(bitOffset == 0)
            return byteOffset
        }

    fun skipBit() {
        if (++bitOffset == 8) {
            bitOffset = 0
            byteOffset++
        }
        assertValidOffset()
    }

    fun skipBits(numBits: Int) {
        val numBytes = numBits / 8
        byteOffset += numBytes
        bitOffset += numBits - (numBytes * 8)
        if (bitOffset > 7) {
            byteOffset++
            bitOffset -= 8
        }
        assertValidOffset()
    }

    fun readBit(): Boolean {
        val returnValue = (data[byteOffset].toInt() and (0x80 shr bitOffset)) != 0
        skipBit()
        return returnValue
    }

    fun readBits(numBits: Int): Int {
        if (numBits == 0) {
            return 0
        }
        var returnValue = 0
        bitOffset += numBits
        while (bitOffset > 8) {
            bitOffset -= 8
            returnValue = returnValue or ((data[byteOffset++].toInt() and 0xFF) shl bitOffset)
        }
        returnValue = returnValue or ((data[byteOffset].toInt() and 0xFF) shr (8 - bitOffset))
        returnValue = returnValue and (-0x1 ushr (32 - numBits))
        if (bitOffset == 8) {
            bitOffset = 0
            byteOffset++
        }
        assertValidOffset()
        return returnValue
    }

    fun readBitsToLong(numBits: Int): Long {
        if (numBits <= 32) {
            return toUnsignedLong(readBits(numBits))
        }
        return toLong(readBits(numBits - 32), readBits(32))
    }

    fun readBits(buffer: ByteArray, offset: Int, numBits: Int) {
        // Whole bytes.
        val to = offset + (numBits shr 3) 
        for (i in offset..<to) {
            buffer[i] = (data[byteOffset++].toInt() shl bitOffset).toByte()
            buffer[i] =
                (buffer[i].toInt() or ((data[byteOffset].toInt() and 0xFF) shr (8 - bitOffset))).toByte()
        }
        // Trailing bits.
        val bitsLeft = numBits and 7 
        if (bitsLeft == 0) {
            return
        }
        // Set bits that are going to be overwritten to 0.
        buffer[to] = (buffer[to].toInt() and (0xFF shr bitsLeft)).toByte()
        if (bitOffset + bitsLeft > 8) {
            // We read the rest of data[byteOffset] and increase byteOffset.
            buffer[to] =
                (buffer[to].toInt() or ((data[byteOffset++].toInt() and 0xFF) shl bitOffset)).toByte()
            bitOffset -= 8
        }
        bitOffset += bitsLeft
        val lastDataByteTrailingBits = (data[byteOffset].toInt() and 0xFF) shr (8 - bitOffset)
        buffer[to] =
            (buffer[to].toInt() or (lastDataByteTrailingBits shl (8 - bitsLeft))).toByte()
        if (bitOffset == 8) {
            bitOffset = 0
            byteOffset++
        }
        assertValidOffset()
    }

    fun byteAlign() {
        if (bitOffset == 0) {
            return
        }
        bitOffset = 0
        byteOffset++
        assertValidOffset()
    }

    fun readBytes(buffer: ByteArray, offset: Int, length: Int) {
        Preconditions.checkState(bitOffset == 0)
        System.arraycopy(data, byteOffset, buffer, offset, length)
        byteOffset += length
        assertValidOffset()
    }

    fun skipBytes(length: Int) {
        Preconditions.checkState(bitOffset == 0)
        byteOffset += length
        assertValidOffset()
    }

    fun readBytesAsString(length: Int, charset: Charset = StandardCharsets.UTF_8): String {
        val bytes = ByteArray(length)
        readBytes(bytes, 0, length)
        return String(bytes, charset)
    }

    fun putInt(value: Int, numBits: Int) {
        var value = value
        var remainingBitsToRead = numBits
        if (numBits < 32) {
            value = value and (1 shl numBits) - 1
        }
        val firstByteReadSize = min(8 - bitOffset, numBits)
        val firstByteRightPaddingSize = 8 - bitOffset - firstByteReadSize
        val firstByteBitmask = (0xFF00 shr bitOffset) or ((1 shl firstByteRightPaddingSize) - 1)
        data[byteOffset] = (data[byteOffset].toInt() and firstByteBitmask).toByte()
        val firstByteInputBits = value ushr (numBits - firstByteReadSize)
        data[byteOffset] =
            (data[byteOffset].toInt() or (firstByteInputBits shl firstByteRightPaddingSize)).toByte()
        remainingBitsToRead -= firstByteReadSize
        var currentByteIndex = byteOffset + 1
        while (remainingBitsToRead > 8) {
            data[currentByteIndex++] = (value ushr (remainingBitsToRead - 8)).toByte()
            remainingBitsToRead -= 8
        }
        val lastByteRightPaddingSize = 8 - remainingBitsToRead
        data[currentByteIndex] =
            (data[currentByteIndex].toInt() and ((1 shl lastByteRightPaddingSize) - 1)).toByte()
        val lastByteInput = value and ((1 shl remainingBitsToRead) - 1)
        data[currentByteIndex] =
            (data[currentByteIndex].toInt() or (lastByteInput shl lastByteRightPaddingSize)).toByte()
        skipBits(numBits)
        assertValidOffset()
    }

    private fun assertValidOffset() {
        // It is fine for position to be at the end of the array, but no further.
        Preconditions.checkState(
            byteOffset >= 0 && (byteOffset < byteLimit || (byteOffset == byteLimit && bitOffset == 0))
        )
    }
}
