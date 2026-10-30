
package androidx.media3.extractor.ogg

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorUtil.readFullyQuietly
import androidx.media3.extractor.ExtractorUtil.skipFullyQuietly
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.max

internal class OggPacket {
    
    val pageHeader: OggPageHeader = OggPageHeader()

    val payload: ParsableByteArray =
        ParsableByteArray(ByteArray(OggPageHeader.MAX_PAGE_PAYLOAD), 0)

    private var currentSegmentIndex = C.INDEX_UNSET
    private var segmentCount = 0
    private var populated = false

    fun reset() {
        pageHeader.reset()
        payload.reset( 0)
        currentSegmentIndex = C.INDEX_UNSET
        populated = false
    }

    @Throws(IOException::class)
    fun populate(input: ExtractorInput): Boolean {
        Preconditions.checkState(input != null)

        if (populated) {
            populated = false
            payload.reset( 0)
        }

        while (!populated) {
            if (currentSegmentIndex < 0) {
                // We're at the start of a page.
                if (!pageHeader.skipToNextPage(input) || !pageHeader.populate(
                        input,  
                        true
                    )
                ) {
                    return false
                }
                var segmentIndex = 0
                var bytesToSkip = pageHeader.headerSize
                if ((pageHeader.type and 0x01) == 0x01 && payload.limit() == 0) {
                    // After seeking, the first packet may be the remainder
                    // part of a continued packet which has to be discarded.
                    bytesToSkip += calculatePacketSize(segmentIndex)
                    segmentIndex += segmentCount
                }
                if (!skipFullyQuietly(input, bytesToSkip)) {
                    return false
                }
                currentSegmentIndex = segmentIndex
            }

            val size = calculatePacketSize(currentSegmentIndex)
            val segmentIndex = currentSegmentIndex + segmentCount
            if (size > 0) {
                payload.ensureCapacity(payload.limit() + size)
                if (!readFullyQuietly(input, payload.data, payload.limit(), size)) {
                    return false
                }
                payload.setLimit(payload.limit() + size)
                populated = pageHeader.laces[segmentIndex - 1] != 255
            }
            // Advance now since we are sure reading didn't throw an exception.
            currentSegmentIndex =
                if (segmentIndex == pageHeader.pageSegmentCount) C.INDEX_UNSET else segmentIndex
        }
        return true
    }

    fun trimPayload() {
        if (payload.data.size == OggPageHeader.MAX_PAGE_PAYLOAD) {
            return
        }
        payload.reset(
            payload.data.copyOf(
                max(
                    OggPageHeader.MAX_PAGE_PAYLOAD,
                    payload.limit()
                )
            ),  
            payload.limit()
        )
    }

    private fun calculatePacketSize(startSegmentIndex: Int): Int {
        segmentCount = 0
        var size = 0
        while (startSegmentIndex + segmentCount < pageHeader.pageSegmentCount) {
            val segmentLength = pageHeader.laces[startSegmentIndex + segmentCount++]
            size += segmentLength
            if (segmentLength != 255) {
                // packets end at first lace < 255
                break
            }
        }
        return size
    }
}
