
package androidx.media3.extractor.ogg

import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorUtil.skipFullyQuietly
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekPoint
import com.google.common.base.Preconditions
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger

internal class DefaultOggSeeker(
    streamReader: StreamReader,
    payloadStartPosition: Long,
    payloadEndPosition: Long,
    firstPayloadPageSize: Long,
    firstPayloadPageGranulePosition: Long,
    firstPayloadPageIsLastPage: Boolean
) : OggSeeker {
    private val pageHeader: OggPageHeader
    private val payloadStartPosition: Long
    private val payloadEndPosition: Long
    private val streamReader: StreamReader

    private var state = 0
    private var totalGranules: Long = 0
    private var positionBeforeSeekToEnd: Long = 0
    private var targetGranule: Long = 0

    private var start: Long = 0
    private var end: Long = 0
    private var startGranule: Long = 0
    private var endGranule: Long = 0

    init {
        Preconditions.checkArgument(payloadStartPosition >= 0 && payloadEndPosition > payloadStartPosition)
        this.streamReader = streamReader
        this.payloadStartPosition = payloadStartPosition
        this.payloadEndPosition = payloadEndPosition
        if (firstPayloadPageSize == payloadEndPosition - payloadStartPosition
            || firstPayloadPageIsLastPage
        ) {
            totalGranules = firstPayloadPageGranulePosition
            state = STATE_IDLE
        } else {
            state = STATE_SEEK_TO_END
        }
        pageHeader = OggPageHeader()
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput): Long {
        when (state) {
            STATE_IDLE -> return -1
            STATE_SEEK_TO_END -> {
                positionBeforeSeekToEnd = input.position
                state = STATE_READ_LAST_PAGE
                // Seek to the end just before the last page of stream to get the duration.
                val lastPageSearchPosition: Long =
                    payloadEndPosition - OggPageHeader.MAX_PAGE_SIZE
                if (lastPageSearchPosition > positionBeforeSeekToEnd) {
                    return lastPageSearchPosition
                }
                totalGranules = readGranuleOfLastPage(input)
                state = STATE_IDLE
                return positionBeforeSeekToEnd
            }

            STATE_READ_LAST_PAGE -> {
                totalGranules = readGranuleOfLastPage(input)
                state = STATE_IDLE
                return positionBeforeSeekToEnd
            }

            STATE_SEEK -> {
                val position = getNextSeekPosition(input)
                if (position != C.INDEX_UNSET.toLong()) {
                    return position
                }
                state = STATE_SKIP
                skipToPageOfTargetGranule(input)
                state = STATE_IDLE
                return -(startGranule + 2)
            }

            STATE_SKIP -> {
                skipToPageOfTargetGranule(input)
                state = STATE_IDLE
                return -(startGranule + 2)
            }

            else ->         // Never happens.
                throw IllegalStateException()
        }
    }

    override fun createSeekMap(): SeekMap? {
        return if (totalGranules != 0L) OggSeekMap() else null
    }

    override fun startSeek(targetGranule: Long) {
        this.targetGranule = constrainValue(targetGranule, 0, totalGranules - 1)
        state = STATE_SEEK
        start = payloadStartPosition
        end = payloadEndPosition
        startGranule = 0
        endGranule = totalGranules
    }

    @Throws(IOException::class)
    private fun getNextSeekPosition(input: ExtractorInput): Long {
        if (start == end) {
            return C.INDEX_UNSET.toLong()
        }

        val currentPosition = input.position
        if (!pageHeader.skipToNextPage(input, end)) {
            if (start == currentPosition) {
                throw IOException("No ogg page can be found.")
            }
            return start
        }

        pageHeader.populate(input,  false)
        input.resetPeekPosition()

        val granuleDistance = targetGranule - pageHeader.granulePosition
        val pageSize = pageHeader.headerSize + pageHeader.bodySize
        if (0 <= granuleDistance && granuleDistance < MATCH_RANGE) {
            return C.INDEX_UNSET.toLong()
        }

        if (granuleDistance < 0) {
            end = currentPosition
            endGranule = pageHeader.granulePosition
        } else {
            start = input.position + pageSize
            startGranule = pageHeader.granulePosition
        }

        if (end - start < MATCH_BYTE_RANGE) {
            end = start
            return start
        }

        val offset = pageSize * (if (granuleDistance <= 0) 2L else 1L)
        val nextPosition =
            (input.position
                    - offset
                    + (granuleDistance * (end - start) / (endGranule - startGranule)))
        return constrainValue(nextPosition, start, end - 1)
    }

    @Throws(IOException::class)
    private fun skipToPageOfTargetGranule(input: ExtractorInput) {
        while (true) {
            // If pageHeader.skipToNextPage fails to find a page it will advance input.position to the
            // end of the file, so pageHeader.populate will throw EOFException (because quiet=false).
            pageHeader.skipToNextPage(input)
            pageHeader.populate(input,  false)
            if (pageHeader.granulePosition > targetGranule) {
                break
            }
            input.skipFully(pageHeader.headerSize + pageHeader.bodySize)
            start = input.position
            startGranule = pageHeader.granulePosition
        }
        input.resetPeekPosition()
    }

    @VisibleForTesting
    @Throws(IOException::class)
    fun readGranuleOfLastPage(input: ExtractorInput): Long {
        pageHeader.reset()
        if (!pageHeader.skipToNextPage(input)) {
            throw EOFException()
        }
        pageHeader.populate(input,  false)
        input.skipFully(pageHeader.headerSize + pageHeader.bodySize)
        var granulePosition = pageHeader.granulePosition
        while ((pageHeader.type and 0x04) != 0x04 && pageHeader.skipToNextPage(input)
            && input.position < payloadEndPosition
        ) {
            val hasPopulated = pageHeader.populate(input,  true)
            if (!hasPopulated || !skipFullyQuietly(
                    input,
                    pageHeader.headerSize + pageHeader.bodySize
                )
            ) {
                // The input file contains a partial page at the end. Ignore it and return the granule
                // position of the last complete page.
                return granulePosition
            }
            granulePosition = pageHeader.granulePosition
        }
        return granulePosition
    }

    private inner class OggSeekMap : SeekMap {
        override val isSeekable: Boolean
            get() = true

        override fun getSeekPoints(timeUs: Long): SeekPoints {
            val targetGranule = streamReader.convertTimeToGranule(timeUs)
            var estimatedPosition: Long =
                (payloadStartPosition // Use BigInteger arithmetic to avoid long overflow
                        // https://github.com/androidx/media/issues/391
                        + BigInteger.valueOf(targetGranule)
                    .multiply(BigInteger.valueOf(payloadEndPosition - payloadStartPosition))
                    .divide(BigInteger.valueOf(totalGranules))
                    .toLong()
                        - DEFAULT_OFFSET)
            estimatedPosition =
                constrainValue(estimatedPosition, payloadStartPosition, payloadEndPosition - 1)
            return SeekPoints(SeekPoint(timeUs, estimatedPosition))
        }

        override val durationUs: Long
            get() = streamReader.convertGranuleToTime(totalGranules)
    }

    companion object {
        private const val MATCH_RANGE = 72000
        private const val MATCH_BYTE_RANGE = 100000
        private const val DEFAULT_OFFSET = 30000

        private const val STATE_SEEK_TO_END = 0
        private const val STATE_READ_LAST_PAGE = 1
        private const val STATE_SEEK = 2
        private const val STATE_SKIP = 3
        private const val STATE_IDLE = 4
    }
}
