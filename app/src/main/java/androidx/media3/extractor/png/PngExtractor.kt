
package androidx.media3.extractor.png

import androidx.media3.common.MimeTypes

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SingleSampleExtractor
import java.io.IOException

class PngExtractor : Extractor {
    private val imageExtractor: SingleSampleExtractor

    init {
        imageExtractor =
            SingleSampleExtractor(
                PNG_FILE_SIGNATURE, PNG_FILE_SIGNATURE_LENGTH, MimeTypes.IMAGE_PNG
            )
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return imageExtractor.sniff(input)
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
        // See PNG (Portable Network Graphics) Specification, Version 1.2, Section 12.12 and Section 3.1.
        private const val PNG_FILE_SIGNATURE = 0x8950
        private const val PNG_FILE_SIGNATURE_LENGTH = 2
    }
}
