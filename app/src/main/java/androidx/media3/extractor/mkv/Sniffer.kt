
package androidx.media3.extractor.mkv

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import java.io.IOException

internal class Sniffer {
    private val scratch: ParsableByteArray
    private var peekLength = 0

    init {
        scratch = ParsableByteArray(8)
    }

    @Throws(IOException::class)
    fun sniff(input: ExtractorInput): Boolean {
        val inputLength = input.length
        val bytesToSearch =
            (if (inputLength == C.LENGTH_UNSET.toLong() || inputLength > SEARCH_LENGTH)
                SEARCH_LENGTH
                    .toLong()
            else
                inputLength).toInt()
        // Find four bytes equal to ID_EBML near the start of the input.
        input.peekFully(scratch.data, 0, 4)
        var tag = scratch.readUnsignedInt()
        peekLength = 4
        while (tag != ID_EBML.toLong()) {
            if (++peekLength == bytesToSearch) {
                return false
            }
            input.peekFully(scratch.data, 0, 1)
            tag = (tag shl 8) and 0xFFFFFF00L
            tag = tag or (scratch.data[0].toInt() and 0xFF).toLong()
        }

        // Read the size of the EBML header and make sure it is within the stream.
        val headerSize = readUint(input)
        val headerStart = peekLength.toLong()
        if (headerSize == Long.MIN_VALUE
            || (inputLength != C.LENGTH_UNSET.toLong() && headerStart + headerSize >= inputLength)
        ) {
            return false
        }

        // Read the payload elements in the EBML header.
        while (peekLength < headerStart + headerSize) {
            val id = readUint(input)
            if (id == Long.MIN_VALUE) {
                return false
            }
            val size = readUint(input)
            if (size < 0 || size > Int.MAX_VALUE) {
                return false
            }
            if (size != 0L) {
                val sizeInt = size.toInt()
                input.advancePeekPosition(sizeInt)
                peekLength += sizeInt
            }
        }
        return peekLength.toLong() == headerStart + headerSize
    }

    @Throws(IOException::class)
    private fun readUint(input: ExtractorInput): Long {
        input.peekFully(scratch.data, 0, 1)
        var value = scratch.data[0].toInt() and 0xFF
        if (value == 0) {
            return Long.MIN_VALUE
        }
        var mask = 0x80
        var length = 0
        while ((value and mask) == 0) {
            mask = mask shr 1
            length++
        }
        value = value and mask.inv()
        input.peekFully(scratch.data, 1, length)
        for (i in 0..<length) {
            value = value shl 8
            value += scratch.data[i + 1].toInt() and 0xFF
        }
        peekLength += length + 1
        return value.toLong()
    }

    companion object {
        private const val SEARCH_LENGTH = 1024
        private const val ID_EBML = 0x1A45DFA3
    }
}
