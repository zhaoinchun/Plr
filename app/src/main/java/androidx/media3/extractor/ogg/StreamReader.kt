
package androidx.media3.extractor.ogg

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.Unseekable
import androidx.media3.extractor.TrackOutput
import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException

internal  abstract class StreamReader {
    internal class SetupData {
        var format:Format? = null
        var oggSeeker:OggSeeker? = null
    }

    private val oggPacket: OggPacket

    private var trackOutput:TrackOutput? = null
    private var extractorOutput:ExtractorOutput? = null
    private var oggSeeker:OggSeeker? = null
    private var targetGranule: Long = 0
    private var payloadStartPosition: Long = 0
    private var currentGranule: Long = 0
    private var state = 0
    private var sampleRate = 0
    private var setupData: SetupData
    private var lengthOfReadPacket: Long = 0
    private var seekMapSet = false
    private var formatSet = false

    init {
        oggPacket = OggPacket()
        setupData = SetupData()
    }

    fun init(output: ExtractorOutput, trackOutput: TrackOutput) {
        this.extractorOutput = output
        this.trackOutput = trackOutput
        reset(true)
    }

    protected open fun reset(headerData: Boolean) {
        if (headerData) {
            setupData = SetupData()
            payloadStartPosition = 0
            state = STATE_READ_HEADERS
        } else {
            state = STATE_SKIP_HEADERS
        }
        targetGranule = -1
        currentGranule = 0
    }

    fun seek(position: Long, timeUs: Long) {
        oggPacket.reset()
        if (position == 0L) {
            reset(!seekMapSet)
        } else {
            if (state != STATE_READ_HEADERS) {
                targetGranule = convertTimeToGranule(timeUs)
                oggSeeker!!.startSeek(targetGranule)
                state = STATE_READ_PAYLOAD
            }
        }
    }

    @Throws(IOException::class)
    fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        assertInitialized()
        when (state) {
            STATE_READ_HEADERS -> return readHeadersAndUpdateState(input)
            STATE_SKIP_HEADERS -> {
                input.skipFully(payloadStartPosition.toInt())
                state = STATE_READ_PAYLOAD
                return Extractor.RESULT_CONTINUE
            }

            STATE_READ_PAYLOAD -> {
                oggSeeker!!
                return readPayload(input, seekPosition)
            }

            STATE_END_OF_INPUT -> return C.RESULT_END_OF_INPUT
            else ->         // Never happens.
                throw IllegalStateException()
        }
    }

    private fun assertInitialized() {
        trackOutput!!
        extractorOutput!!
    }

    @Throws(IOException::class)
    private fun readHeaders(input: ExtractorInput): Boolean {
        while (true) {
            if (!oggPacket.populate(input)) {
                state = STATE_END_OF_INPUT
                return false
            }
            lengthOfReadPacket = input.position - payloadStartPosition

            if (readHeaders(oggPacket.payload, payloadStartPosition, setupData)) {
                payloadStartPosition = input.position
            } else {
                return true // Current packet is not a header, therefore all headers have been read.
            }
        }
    }

    @RequiresNonNull("trackOutput")
    @Throws(IOException::class)
    private fun readHeadersAndUpdateState(input: ExtractorInput): Int {
        if (!readHeaders(input)) {
            return Extractor.RESULT_END_OF_INPUT
        }

        sampleRate = setupData.format!!.sampleRate
        if (!formatSet) {
            trackOutput!!.format(setupData.format!!)
            formatSet = true
        }

        if (setupData.oggSeeker != null) {
            oggSeeker = setupData.oggSeeker
        } else if (input.length == C.LENGTH_UNSET.toLong()) {
            oggSeeker = UnseekableOggSeeker()
        } else {
            val firstPayloadPageHeader = oggPacket.pageHeader
            val isLastPage = (firstPayloadPageHeader.type and 0x04) != 0 // Type 4 is end of stream.
            oggSeeker =
                DefaultOggSeeker( 
                    this,
                    payloadStartPosition,
                    input.length,
                    (firstPayloadPageHeader.headerSize + firstPayloadPageHeader.bodySize).toLong(),
                    firstPayloadPageHeader.granulePosition,
                    isLastPage
                )
        }

        state = STATE_READ_PAYLOAD
        // First payload packet. Trim the payload array of the ogg packet after headers have been read.
        oggPacket.trimPayload()
        return Extractor.RESULT_CONTINUE
    }

    @RequiresNonNull("trackOutput", "oggSeeker", "extractorOutput")
    @Throws(IOException::class)
    private fun readPayload(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val position = oggSeeker!!.read(input)
        if (position >= 0) {
            seekPosition.position = position
            return Extractor.RESULT_SEEK
        } else if (position < -1) {
            onSeekEnd(-(position + 2))
        }

        if (!seekMapSet) {
            val seekMap = oggSeeker!!.createSeekMap()!!
            extractorOutput!!.seekMap(seekMap)
            trackOutput!!.durationUs(seekMap.durationUs)
            seekMapSet = true
        }

        if (lengthOfReadPacket > 0 || oggPacket.populate(input)) {
            lengthOfReadPacket = 0
            val payload = oggPacket.payload
            val granulesInPacket = preparePayload(payload)
            if (granulesInPacket >= 0 && currentGranule + granulesInPacket >= targetGranule) {
                // calculate time and send payload data to codec
                val timeUs = convertGranuleToTime(currentGranule)
                trackOutput!!.sampleData(payload, payload.limit())
                trackOutput!!.sampleMetadata(
                    timeUs,
                    C.BUFFER_FLAG_KEY_FRAME,
                    payload.limit(),
                    0,
                    null
                )
                targetGranule = -1
            }
            currentGranule += granulesInPacket
        } else {
            state = STATE_END_OF_INPUT
            return Extractor.RESULT_END_OF_INPUT
        }
        return Extractor.RESULT_CONTINUE
    }

    fun convertGranuleToTime(granule: Long): Long {
        return (granule * C.MICROS_PER_SECOND) / sampleRate
    }

    fun convertTimeToGranule(timeUs: Long): Long {
        return (sampleRate * timeUs) / C.MICROS_PER_SECOND
    }

    protected abstract fun preparePayload(packet: ParsableByteArray): Long

    @Throws(IOException::class)
    protected abstract fun readHeaders(
        packet: ParsableByteArray, position: Long, setupData: SetupData
    ): Boolean

    protected open fun onSeekEnd(currentGranule: Long) {
        this.currentGranule = currentGranule
    }

    private class UnseekableOggSeeker : OggSeeker {
        override fun read(input: ExtractorInput): Long {
            return -1
        }

        override fun startSeek(targetGranule: Long) {
            // Do nothing.
        }

        override fun createSeekMap(): SeekMap {
            return Unseekable(C.TIME_UNSET)
        }
    }

    companion object {
        private const val STATE_READ_HEADERS = 0
        private const val STATE_SKIP_HEADERS = 1
        private const val STATE_READ_PAYLOAD = 2
        private const val STATE_END_OF_INPUT = 3
    }
}
