package androidx.media3.container

import com.google.common.base.Preconditions
import kotlin.math.min

class VorbisBitArray(data: ByteArray) {
    private val data: ByteArray?
    private val byteLimit: Int

    private var byteOffset = 0
    private var bitOffset = 0

    init {
        this.data = data
        byteLimit = data.size
    }

    fun reset() {
        byteOffset = 0
        bitOffset = 0
    }

    fun readBit(): Boolean {
        val returnValue = (((data!![byteOffset].toInt() and 0xFF) shr bitOffset) and 0x01) == 1
        skipBits(1)
        return returnValue
    }

    fun readBits(numBits: Int): Int {
        var tempByteOffset = byteOffset
        var bitsRead = min(numBits, 8 - bitOffset)
        var returnValue =
            ((data!![tempByteOffset++].toInt() and 0xFF) shr bitOffset) and (0xFF shr (8 - bitsRead))
        while (bitsRead < numBits) {
            returnValue = returnValue or ((data[tempByteOffset++].toInt() and 0xFF) shl bitsRead)
            bitsRead += 8
        }
        returnValue = returnValue and (-0x1 ushr (32 - numBits))
        skipBits(numBits)
        return returnValue
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

    var position: Int
        get() = byteOffset * 8 + bitOffset
        set(position) {
            byteOffset = position / 8
            bitOffset = position - (byteOffset * 8)
            assertValidOffset()
        }

    fun bitsLeft(): Int {
        return (byteLimit - byteOffset) * 8 - bitOffset
    }

    private fun assertValidOffset() {
        // It is fine for position to be at the end of the array, but no further.
        Preconditions.checkState(
            byteOffset >= 0 && (byteOffset < byteLimit || (byteOffset == byteLimit && bitOffset == 0))
        )
    }
}
