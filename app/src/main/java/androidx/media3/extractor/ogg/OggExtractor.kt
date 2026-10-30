
package androidx.media3.extractor.ogg

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.min

class OggExtractor : Extractor {
    private lateinit var output:ExtractorOutput
    private var streamReader:StreamReader? = null
    private var streamReaderInitialized = false

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        try {
            return sniffInternal(input)
        } catch (e: ParserException) {
            return false
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun seek(position: Long, timeUs: Long) {
        if (streamReader != null) {
            streamReader!!.seek(position, timeUs)
        }
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // Check that init has been called.
        val output =  output!!
        if (streamReader == null) {
            if (!sniffInternal(input)) {
                throw ParserException.createForMalformedContainer(
                    "Failed to determine bitstream type",  null
                )
            }
            input.resetPeekPosition()
        }
        if (!streamReaderInitialized) {
            val trackOutput = output!!.track(0, C.TRACK_TYPE_AUDIO)
            output!!.endTracks()
            streamReader!!.init(output, trackOutput)
            streamReaderInitialized = true
        }
        return streamReader!!.read(input, seekPosition)
    }

    @Throws(IOException::class)
    private fun sniffInternal(input: ExtractorInput): Boolean {
        val header = OggPageHeader()
        if (!header.populate(input, true) || (header.type and 0x02) != 0x02) {
            return false
        }

        val length = min(header.bodySize, MAX_VERIFICATION_BYTES)
        val scratch = ParsableByteArray(length)
        input.peekFully(scratch.data, 0, length)

        if (FlacReader.verifyBitstreamType(resetPosition(scratch))) {
            streamReader = FlacReader()
        } else if (VorbisReader.verifyBitstreamType(resetPosition(scratch))) {
            streamReader = VorbisReader()
        } else if (OpusReader.verifyBitstreamType(resetPosition(scratch))) {
            streamReader = OpusReader()
        } else {
            return false
        }
        return true
    }

    companion object {
        val FACTORY: ExtractorsFactory = object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(OggExtractor())
            }
        }

        private const val MAX_VERIFICATION_BYTES = 8

        private fun resetPosition(scratch: ParsableByteArray): ParsableByteArray {
            scratch.setPosition(0)
            return scratch
        }
    }
}
