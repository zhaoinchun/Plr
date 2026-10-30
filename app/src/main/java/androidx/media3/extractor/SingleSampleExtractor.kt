
package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.mp4.Mp4Extractor
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException

class SingleSampleExtractor(
    private val fileSignature: Int,
    private val fileSignatureLength: Int,
    private val sampleMimeType: String?
) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [STATE_READING, STATE_ENDED])
    private annotation class State

    private var size = 0
    private var state: @State Int = 0
    private var extractorOutput:ExtractorOutput? = null
    private var trackOutput:TrackOutput? = null

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        Preconditions.checkState(fileSignature != C.INDEX_UNSET && fileSignatureLength != C.LENGTH_UNSET)
        val scratch = ParsableByteArray(fileSignatureLength)
        input.peekFully(scratch.data,  0, fileSignatureLength)
        return scratch.readUnsignedShort() == fileSignature
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        outputImageTrackAndSeekMap(sampleMimeType)
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        when (state) {
            STATE_READING -> {
                readSegment(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_ENDED -> return Extractor.RESULT_END_OF_INPUT
            else -> throw IllegalStateException()
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L || state == STATE_READING) {
            state = STATE_READING
            size = 0
        }
    }

    override fun release() {
        // Do Nothing
    }

    @Throws(IOException::class)
    private fun readSegment(input: ExtractorInput) {
        val result = trackOutput!!.sampleData(input, FIXED_READ_LENGTH,  true)
        if (result == C.RESULT_END_OF_INPUT) {
            state = STATE_ENDED
            val flags: @BufferFlags Int = C.BUFFER_FLAG_KEY_FRAME
            trackOutput!!.sampleMetadata( 
                0, flags, size,  0,  null
            )
            size = 0
        } else {
            size += result
        }
    }

    @RequiresNonNull("this.extractorOutput")
    private fun outputImageTrackAndSeekMap(sampleMimeType: String?) {
        trackOutput = extractorOutput!!.track(IMAGE_TRACK_ID, C.TRACK_TYPE_IMAGE)
        trackOutput!!.format(
            Format.Builder()
                .setContainerMimeType(sampleMimeType)
                .setSampleMimeType(sampleMimeType)
                .build()
        )
        extractorOutput!!.endTracks()
        extractorOutput!!.seekMap(SingleSampleSeekMap( C.TIME_UNSET))
        state = STATE_READING
    }

    companion object {
        private const val STATE_READING = 1
        private const val STATE_ENDED = 2

        const val IMAGE_TRACK_ID: Int = 1024

        private const val FIXED_READ_LENGTH = 1024
    }
}
