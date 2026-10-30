
package androidx.media3.extractor.mkv

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import java.io.IOException

internal class VarintReader {
    private val scratch: ByteArray

    private var state = 0

    var lastLength: Int = 0
        private set

    init {
        scratch = ByteArray(8)
    }

    fun reset() {
        state = STATE_BEGIN_READING
        this.lastLength = 0
    }

    @Throws(IOException::class)
    fun readUnsignedVarint(
        input: ExtractorInput,
        allowEndOfInput: Boolean,
        removeLengthMask: Boolean,
        maximumAllowedLength: Int
    ): Long {
        if (state == STATE_BEGIN_READING) {
            // Read the first byte to establish the length.
            if (!input.readFully(scratch, 0, 1, allowEndOfInput)) {
                return C.RESULT_END_OF_INPUT.toLong()
            }
            val firstByte = scratch[0].toInt() and 0xFF
            this.lastLength = parseUnsignedVarintLength(firstByte)
            check(this.lastLength != C.LENGTH_UNSET) { "No valid varint length mask found" }
            state = STATE_READ_CONTENTS
        }

        if (this.lastLength > maximumAllowedLength) {
            state = STATE_BEGIN_READING
            return C.RESULT_MAX_LENGTH_EXCEEDED.toLong()
        }

        if (this.lastLength != 1) {
            // Read the remaining bytes.
            input.readFully(scratch, 1, this.lastLength - 1)
        }

        state = STATE_BEGIN_READING
        return assembleVarint(scratch, this.lastLength, removeLengthMask)
    }

    companion object {
        private const val STATE_BEGIN_READING = 0
        private const val STATE_READ_CONTENTS = 1
        private val VARINT_LENGTH_MASKS =
            longArrayOf(0x80L, 0x40L, 0x20L, 0x10L, 0x08L, 0x04L, 0x02L, 0x01L)
        fun parseUnsignedVarintLength(firstByte: Int): Int {
            var varIntLength = C.LENGTH_UNSET
            for (i in VARINT_LENGTH_MASKS.indices) {
                if ((VARINT_LENGTH_MASKS[i] and firstByte.toLong()) != 0L) {
                    varIntLength = i + 1
                    break
                }
            }
            return varIntLength
        }

        fun assembleVarint(
            varintBytes: ByteArray, varintLength: Int, removeLengthMask: Boolean
        ): Long {
            var varint = varintBytes[0].toLong() and 0xFFL
            if (removeLengthMask) {
                varint = varint and VARINT_LENGTH_MASKS[varintLength - 1].inv()
            }
            for (i in 1..<varintLength) {
                varint = (varint shl 8) or (varintBytes[i].toLong() and 0xFFL)
            }
            return varint
        }
    }
}
