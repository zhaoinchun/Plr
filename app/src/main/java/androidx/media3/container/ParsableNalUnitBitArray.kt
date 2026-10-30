package androidx.media3.container

import com.google.common.base.Preconditions

class ParsableNalUnitBitArray(data: ByteArray, offset: Int, limit: Int) {
    private var data: ByteArray = byteArrayOf()
    private var byteLimit = 0

    // The start offset which defines the range of data to be considered.
    private var startOffset = 0

    // The byte offset is never equal to the offset of the 3rd byte in a subsequence [0, 0, 3].
    private var byteOffset = 0
    private var bitOffset = 0

    init {
        reset(data, offset, limit)
    }

    fun reset(data: ByteArray, offset: Int, limit: Int) {
        this.data = data
        startOffset = offset
        byteOffset = offset
        byteLimit = limit
        bitOffset = 0
        assertValidOffset()
    }

    fun skipBit() {
        if (++bitOffset == 8) {
            bitOffset = 0
            byteOffset += if (shouldSkipByte(byteOffset + 1)) 2 else 1
        }
        assertValidOffset()
    }

    fun skipBits(numBits: Int) {
        val oldByteOffset = byteOffset
        val numBytes = numBits / 8
        byteOffset += numBytes
        bitOffset += numBits - (numBytes * 8)
        if (bitOffset > 7) {
            byteOffset++
            bitOffset -= 8
        }
        var i = oldByteOffset + 1
        while (i <= byteOffset) {
            if (shouldSkipByte(i)) {
                // Skip the byte and move forward to check three bytes ahead.
                byteOffset++
                i += 2
            }
            i++
        }
        assertValidOffset()
    }

    fun byteAlign() {
        if (bitOffset > 0) {
            skipBits(8 - bitOffset)
        }
    }

    fun canReadBits(numBits: Int): Boolean {
        val oldByteOffset = byteOffset
        val numBytes = numBits / 8
        var newByteOffset = byteOffset + numBytes
        var newBitOffset = bitOffset + numBits - (numBytes * 8)
        if (newBitOffset > 7) {
            newByteOffset++
            newBitOffset -= 8
        }
        var i = oldByteOffset + 1
        while (i <= newByteOffset && newByteOffset <= byteLimit) {
            if (shouldSkipByte(i)) {
                // Skip the byte and move forward to check three bytes ahead.
                newByteOffset++
                i += 2
            }
            i++
        }
        return newByteOffset < byteLimit || (newByteOffset == byteLimit && newBitOffset == 0)
    }

    fun readBit(): Boolean {
        val returnValue = (data[byteOffset].toInt() and (0x80 shr bitOffset)) != 0
        skipBit()
        return returnValue
    }

    fun readBits(numBits: Int): Int {
        var returnValue = 0
        bitOffset += numBits
        while (bitOffset > 8) {
            bitOffset -= 8
            returnValue = returnValue or ((data[byteOffset].toInt() and 0xFF) shl bitOffset)
            byteOffset += if (shouldSkipByte(byteOffset + 1)) 2 else 1
        }
        returnValue = returnValue or ((data[byteOffset].toInt() and 0xFF) shr (8 - bitOffset))
        returnValue = returnValue and (-0x1 ushr (32 - numBits))
        if (bitOffset == 8) {
            bitOffset = 0
            byteOffset += if (shouldSkipByte(byteOffset + 1)) 2 else 1
        }
        assertValidOffset()
        return returnValue
    }

    fun canReadExpGolombCodedNum(): Boolean {
        val initialByteOffset = byteOffset
        val initialBitOffset = bitOffset
        var leadingZeros = 0
        while (byteOffset < byteLimit && !readBit()) {
            leadingZeros++
        }
        val hitLimit = byteOffset == byteLimit
        byteOffset = initialByteOffset
        bitOffset = initialBitOffset
        return !hitLimit && canReadBits(leadingZeros * 2 + 1)
    }

    fun readUnsignedExpGolombCodedInt(): Int {
        return readExpGolombCodeNum()
    }

    fun readSignedExpGolombCodedInt(): Int {
        val codeNum = readExpGolombCodeNum()
        return (if ((codeNum % 2) == 0) -1 else 1) * ((codeNum + 1) / 2)
    }

    private fun readExpGolombCodeNum(): Int {
        var leadingZeros = 0
        while (!readBit()) {
            leadingZeros++
        }
        return (1 shl leadingZeros) - 1 + (if (leadingZeros > 0) readBits(leadingZeros) else 0)
    }

    private fun shouldSkipByte(offset: Int): Boolean {
        // Do not read outside of [startOffset, byteLimit) bounds.
        return startOffset <= offset - 2 && offset < byteLimit && data[offset] == 0x03.toByte() && data[offset - 2] == 0x00.toByte() && data[offset - 1] == 0x00.toByte()
    }

    private fun assertValidOffset() {
        // It is fine for position to be at the end of the array, but no further.
        Preconditions.checkState(
            byteOffset >= 0 && (byteOffset < byteLimit || (byteOffset == byteLimit && bitOffset == 0))
        )
    }
}
