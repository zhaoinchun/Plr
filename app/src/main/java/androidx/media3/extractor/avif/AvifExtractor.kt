package androidx.media3.extractor.avif

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SingleSampleExtractor
import java.io.IOException

class AvifExtractor : Extractor {
    private val scratch: ParsableByteArray
    private val imageExtractor: SingleSampleExtractor

    init {
        scratch = ParsableByteArray(FILE_SIGNATURE_SEGMENT_LENGTH)
        imageExtractor =
            SingleSampleExtractor( 
                C.INDEX_UNSET,  
                C.LENGTH_UNSET,
                MimeTypes.IMAGE_AVIF
            )
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        input.advancePeekPosition(4)
        return readAndCompareFourBytes(input, AVIF_FILE_SIGNATURE_PART_1)
                && readAndCompareFourBytes(input, AVIF_FILE_SIGNATURE_PART_2)
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

    @Throws(IOException::class)
    private fun readAndCompareFourBytes(input: ExtractorInput, bytesToCompare: Int): Boolean {
        scratch.reset( FILE_SIGNATURE_SEGMENT_LENGTH)
        input.peekFully(scratch.data,  0, FILE_SIGNATURE_SEGMENT_LENGTH)
        return scratch.readUnsignedInt() == bytesToCompare.toLong()
    }

    companion object {
        // Specification reference: ISO/IEC 23008-12:2022
        private const val AVIF_FILE_SIGNATURE_PART_1 = 0x66747970
        private const val AVIF_FILE_SIGNATURE_PART_2 = 0x61766966
        private const val FILE_SIGNATURE_SEGMENT_LENGTH = 4
    }
}
