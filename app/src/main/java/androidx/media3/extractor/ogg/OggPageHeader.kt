
package androidx.media3.extractor.ogg

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorUtil.peekFullyQuietly
import com.google.common.base.Preconditions
import java.io.IOException

internal class OggPageHeader {
    var revision: Int = 0
    var type: Int = 0

    var granulePosition: Long = 0

    var streamSerialNumber: Long = 0
    var pageSequenceNumber: Long = 0
    var pageChecksum: Long = 0
    var pageSegmentCount: Int = 0
    var headerSize: Int = 0
    var bodySize: Int = 0

    val laces: IntArray = IntArray(MAX_SEGMENT_COUNT)

    private val scratch: ParsableByteArray = ParsableByteArray(MAX_SEGMENT_COUNT)

    fun reset() {
        revision = 0
        type = 0
        granulePosition = 0
        streamSerialNumber = 0
        pageSequenceNumber = 0
        pageChecksum = 0
        pageSegmentCount = 0
        headerSize = 0
        bodySize = 0
    }

    @Throws(IOException::class)
    fun skipToNextPage(input: ExtractorInput, limit: Long = C.INDEX_UNSET.toLong()): Boolean {
        Preconditions.checkArgument(input.position === input.peekPosition)
        scratch.reset( CAPTURE_PATTERN_SIZE)
        while ((limit == C.INDEX_UNSET.toLong() || input.position + CAPTURE_PATTERN_SIZE < limit)
            && peekFullyQuietly(
                input, scratch.data, 0, CAPTURE_PATTERN_SIZE,  true
            )
        ) {
            scratch.setPosition(0)
            if (scratch.readUnsignedInt() == CAPTURE_PATTERN.toLong()) {
                input.resetPeekPosition()
                return true
            }
            // Advance one byte before looking for the capture pattern again.
            input.skipFully(1)
        }
        // Move the read & peek positions to limit or end-of-input, whichever is closer.
        while ((limit == C.INDEX_UNSET.toLong() || input.position < limit)
            && input.skip(1) != C.RESULT_END_OF_INPUT
        ) {
        }
        return false
    }

    @Throws(IOException::class)
    fun populate(input: ExtractorInput, quiet: Boolean): Boolean {
        reset()
        scratch.reset( EMPTY_PAGE_HEADER_SIZE)
        if (!peekFullyQuietly(input, scratch.data, 0, EMPTY_PAGE_HEADER_SIZE, quiet)
            || scratch.readUnsignedInt() != CAPTURE_PATTERN.toLong()
        ) {
            return false
        }

        revision = scratch.readUnsignedByte()
        if (revision != 0x00) {
            if (quiet) {
                return false
            } else {
                throw ParserException.createForUnsupportedContainerFeature(
                    "unsupported bit stream revision"
                )
            }
        }
        type = scratch.readUnsignedByte()

        granulePosition = scratch.readLittleEndianLong()
        streamSerialNumber = scratch.readLittleEndianUnsignedInt()
        pageSequenceNumber = scratch.readLittleEndianUnsignedInt()
        pageChecksum = scratch.readLittleEndianUnsignedInt()
        pageSegmentCount = scratch.readUnsignedByte()
        headerSize = EMPTY_PAGE_HEADER_SIZE + pageSegmentCount

        // calculate total size of header including laces
        scratch.reset( pageSegmentCount)
        if (!peekFullyQuietly(input, scratch.data, 0, pageSegmentCount, quiet)) {
            return false
        }
        for (i in 0..<pageSegmentCount) {
            laces[i] = scratch.readUnsignedByte()
            bodySize += laces[i]
        }

        return true
    }

    companion object {
        const val EMPTY_PAGE_HEADER_SIZE: Int = 27
        const val MAX_SEGMENT_COUNT: Int = 255
        val MAX_PAGE_PAYLOAD: Int = 255 * 255
        val MAX_PAGE_SIZE: Int = EMPTY_PAGE_HEADER_SIZE + MAX_SEGMENT_COUNT + MAX_PAGE_PAYLOAD

        private const val CAPTURE_PATTERN = 0x4f676753 // OggS
        private const val CAPTURE_PATTERN_SIZE = 4
    }
}
