
package androidx.media3.extractor.webp

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SingleSampleExtractor
import java.io.IOException

class WebpExtractor : Extractor {
    private val scratch: ParsableByteArray
    private val imageExtractor: SingleSampleExtractor

    init {
        scratch = ParsableByteArray(FILE_SIGNATURE_SEGMENT_LENGTH)
        imageExtractor = SingleSampleExtractor(C.INDEX_UNSET, C.LENGTH_UNSET, MimeTypes.IMAGE_WEBP)
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        // The full file signature for the format webp is RIFF????WEBP.
        scratch.reset( FILE_SIGNATURE_SEGMENT_LENGTH)
        input.peekFully(scratch.data,  0, FILE_SIGNATURE_SEGMENT_LENGTH)
        if (scratch.readUnsignedInt() != RIFF_FILE_SIGNATURE.toLong()) {
            return false
        }

        input.advancePeekPosition(FILE_SIGNATURE_SEGMENT_LENGTH)
        scratch.reset( FILE_SIGNATURE_SEGMENT_LENGTH)

        input.peekFully(scratch.data,  0, FILE_SIGNATURE_SEGMENT_LENGTH)
        return scratch.readUnsignedInt() == WEBP_FILE_SIGNATURE.toLong()
    }

    override fun init(output: ExtractorOutput) {
        imageExtractor.init(output)
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        return imageExtractor.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        imageExtractor.seek(position, timeUs)
    }

    override fun release() {
        // Do nothing.
    }

    companion object {
        // Documentation reference:
        // https://developers.google.com/speed/webp/docs/riff_container#webp_file_header
        private const val FILE_SIGNATURE_SEGMENT_LENGTH = 4
        private const val RIFF_FILE_SIGNATURE = 0x52494646
        private const val WEBP_FILE_SIGNATURE = 0x57454250
    }
}
