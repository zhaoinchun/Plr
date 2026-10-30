
package androidx.media3.extractor.bmp

import androidx.media3.common.MimeTypes

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SingleSampleExtractor
import java.io.IOException

class BmpExtractor : Extractor {
    private val imageExtractor: SingleSampleExtractor

    init {
        imageExtractor =
            SingleSampleExtractor(
                BMP_FILE_SIGNATURE, BMP_FILE_SIGNATURE_LENGTH, MimeTypes.IMAGE_BMP
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
        private const val BMP_FILE_SIGNATURE_LENGTH = 2
        private const val BMP_FILE_SIGNATURE = 0x424D
    }
}
