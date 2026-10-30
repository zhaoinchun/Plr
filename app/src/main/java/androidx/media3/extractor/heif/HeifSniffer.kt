
package androidx.media3.extractor.heif

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.ExtractorInput
import java.io.IOException

internal object HeifSniffer {
    private const val BRAND_HEIC = 0x68656963

    @Throws(IOException::class)
    fun sniff(input: ExtractorInput, sniffMotionPhoto: Boolean): Boolean {
        val buffer = ParsableByteArray(Mp4Box.LONG_HEADER_SIZE)
        var firstAtom = true

        while (true) {
            var headerSize = Mp4Box.HEADER_SIZE
            buffer.reset(headerSize)
            if (!input.peekFully(
                    buffer.data,  
                    0,  
                    headerSize,  
                    true
                )
            ) {
                return false
            }

            var atomSize = buffer.readUnsignedInt()
            val atomType = buffer.readInt()

            if (atomSize == Mp4Box.DEFINES_LARGE_SIZE.toLong()) {
                headerSize = Mp4Box.LONG_HEADER_SIZE
                if (!input.peekFully(
                        buffer.data,  
                        Mp4Box.HEADER_SIZE,  
                        Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE,  
                        true
                    )
                ) {
                    return false
                }
                buffer.setLimit(headerSize)
                atomSize = buffer.readUnsignedLongToLong()
            }

            if (atomSize < headerSize) {
                return false
            }

            val atomDataSize = (atomSize - headerSize).toInt()
            if (firstAtom) {
                if (atomType != Mp4Box.TYPE_ftyp || atomDataSize < 8) {
                    return false
                }

                buffer.reset(4)
                input.peekFully(buffer.data,  0,  4)
                if (buffer.readInt() != BRAND_HEIC) {
                    return false
                }
                if (!sniffMotionPhoto) {
                    return true
                }
                input.advancePeekPosition(atomDataSize - 4)
                firstAtom = false
            } else if (atomType == Mp4Box.TYPE_mpvd) {
                return true
            } else if (atomDataSize != 0) {
                input.advancePeekPosition(atomDataSize)
            }
        }
    }
}
