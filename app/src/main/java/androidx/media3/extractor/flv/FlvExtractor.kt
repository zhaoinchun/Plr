
package androidx.media3.extractor.flv

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap.Unseekable
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import kotlin.math.max

class FlvExtractor : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_READING_FLV_HEADER, STATE_SKIPPING_TO_TAG_HEADER, STATE_READING_TAG_HEADER, STATE_READING_TAG_DATA
        ]
    )
    private annotation class States

    private val scratch: ParsableByteArray
    private val headerBuffer: ParsableByteArray
    private val tagHeaderBuffer: ParsableByteArray
    private val tagData: ParsableByteArray
    private val metadataReader: ScriptTagPayloadReader

    private var extractorOutput:ExtractorOutput? = null
    private var state: @States Int
    private var outputFirstSample = false
    private var mediaTagTimestampOffsetUs: Long = 0
    private var bytesToNextTagHeader = 0
    private var tagType = 0
    private var tagDataSize = 0
    private var tagTimestampUs: Long = 0
    private var outputSeekMap = false
    private var audioReader:AudioTagPayloadReader? = null
    private var videoReader:VideoTagPayloadReader? = null

    init {
        scratch = ParsableByteArray(4)
        headerBuffer = ParsableByteArray(FLV_HEADER_SIZE)
        tagHeaderBuffer = ParsableByteArray(FLV_TAG_HEADER_SIZE)
        tagData = ParsableByteArray()
        metadataReader = ScriptTagPayloadReader()
        state = STATE_READING_FLV_HEADER
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        // Check if file starts with "FLV" tag
        input.peekFully(scratch.data, 0, 3)
        scratch.setPosition(0)
        if (scratch.readUnsignedInt24() != FLV_TAG) {
            return false
        }

        // Checking reserved flags are set to 0
        input.peekFully(scratch.data, 0, 2)
        scratch.setPosition(0)
        if ((scratch.readUnsignedShort() and 0xFA) != 0) {
            return false
        }

        // Read data offset
        input.peekFully(scratch.data, 0, 4)
        scratch.setPosition(0)
        val dataOffset = scratch.readInt()

        input.resetPeekPosition()
        input.advancePeekPosition(dataOffset)

        // Checking first "previous tag size" is set to 0
        input.peekFully(scratch.data, 0, 4)
        scratch.setPosition(0)

        return scratch.readInt() == 0
    }

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            state = STATE_READING_FLV_HEADER
            outputFirstSample = false
        } else {
            state = STATE_READING_TAG_HEADER
        }
        bytesToNextTagHeader = 0
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // Asserts that init has been called.
        extractorOutput!!
        while (true) {
            when (state) {
                STATE_READING_FLV_HEADER -> if (!readFlvHeader(input)) {
                    return Extractor.RESULT_END_OF_INPUT
                }

                STATE_SKIPPING_TO_TAG_HEADER -> skipToTagHeader(input)
                STATE_READING_TAG_HEADER -> if (!readTagHeader(input)) {
                    return Extractor.RESULT_END_OF_INPUT
                }

                STATE_READING_TAG_DATA -> if (readTagData(input)) {
                    return Extractor.RESULT_CONTINUE
                }

                else ->           // Never happens.
                    throw IllegalStateException()
            }
        }
    }

    @RequiresNonNull("extractorOutput")
    @Throws(IOException::class)
    private fun readFlvHeader(input: ExtractorInput): Boolean {
        if (!input.readFully(headerBuffer.data, 0, FLV_HEADER_SIZE, true)) {
            // We've reached the end of the stream.
            return false
        }

        headerBuffer.setPosition(0)
        headerBuffer.skipBytes(4)
        val flags = headerBuffer.readUnsignedByte()
        val hasAudio = (flags and 0x04) != 0
        val hasVideo = (flags and 0x01) != 0
        if (hasAudio && audioReader == null) {
            audioReader =
                AudioTagPayloadReader(extractorOutput!!.track(TAG_TYPE_AUDIO, C.TRACK_TYPE_AUDIO))
        }
        if (hasVideo && videoReader == null) {
            videoReader =
                VideoTagPayloadReader(extractorOutput!!.track(TAG_TYPE_VIDEO, C.TRACK_TYPE_VIDEO))
        }
        extractorOutput!!.endTracks()

        // We need to skip any additional content in the FLV header, plus the 4 byte previous tag size.
        bytesToNextTagHeader = headerBuffer.readInt() - FLV_HEADER_SIZE + 4
        state = STATE_SKIPPING_TO_TAG_HEADER
        return true
    }

    @Throws(IOException::class)
    private fun skipToTagHeader(input: ExtractorInput) {
        input.skipFully(bytesToNextTagHeader)
        bytesToNextTagHeader = 0
        state = STATE_READING_TAG_HEADER
    }

    @Throws(IOException::class)
    private fun readTagHeader(input: ExtractorInput): Boolean {
        if (!input.readFully(tagHeaderBuffer.data, 0, FLV_TAG_HEADER_SIZE, true)) {
            // We've reached the end of the stream.
            return false
        }

        tagHeaderBuffer.setPosition(0)
        tagType = tagHeaderBuffer.readUnsignedByte()
        tagDataSize = tagHeaderBuffer.readUnsignedInt24()
        tagTimestampUs = tagHeaderBuffer.readUnsignedInt24().toLong()
        tagTimestampUs =
            ((tagHeaderBuffer.readUnsignedByte() shl 24).toLong() or tagTimestampUs) * 1000L
        tagHeaderBuffer.skipBytes(3) // streamId
        state = STATE_READING_TAG_DATA
        return true
    }

    @RequiresNonNull("extractorOutput")
    @Throws(IOException::class)
    private fun readTagData(input: ExtractorInput): Boolean {
        var wasConsumed = true
        var wasSampleOutput = false
        val timestampUs = this.currentTimestampUs
        if (tagType == TAG_TYPE_AUDIO && audioReader != null) {
            ensureReadyForMediaOutput()
            wasSampleOutput = audioReader!!.consume(prepareTagData(input), timestampUs)
        } else if (tagType == TAG_TYPE_VIDEO && videoReader != null) {
            ensureReadyForMediaOutput()
            wasSampleOutput = videoReader!!.consume(prepareTagData(input), timestampUs)
        } else if (tagType == TAG_TYPE_SCRIPT_DATA && !outputSeekMap) {
            wasSampleOutput = metadataReader.consume(prepareTagData(input), timestampUs)
            val durationUs = metadataReader.durationUs
            if (durationUs != C.TIME_UNSET) {
                extractorOutput!!.seekMap(
                    IndexSeekMap(
                        metadataReader.keyFrameTagPositions.toList(),
                        metadataReader.keyFrameTimesUs.toList(),
                        durationUs
                    )
                )
                outputSeekMap = true
            }
        } else {
            input.skipFully(tagDataSize)
            wasConsumed = false
        }
        if (!outputFirstSample && wasSampleOutput) {
            outputFirstSample = true
            mediaTagTimestampOffsetUs =
                if (metadataReader.durationUs == C.TIME_UNSET) -tagTimestampUs else 0
        }
        bytesToNextTagHeader = 4 // There's a 4 byte previous tag size before the next header.
        state = STATE_SKIPPING_TO_TAG_HEADER
        return wasConsumed
    }

    @Throws(IOException::class)
    private fun prepareTagData(input: ExtractorInput): ParsableByteArray {
        if (tagDataSize > tagData.capacity()) {
            tagData.reset(ByteArray(max(tagData.capacity() * 2, tagDataSize)), 0)
        } else {
            tagData.setPosition(0)
        }
        tagData.setLimit(tagDataSize)
        input.readFully(tagData.data, 0, tagDataSize)
        return tagData
    }

    @RequiresNonNull("extractorOutput")
    private fun ensureReadyForMediaOutput() {
        if (!outputSeekMap) {
            extractorOutput!!.seekMap(Unseekable(C.TIME_UNSET))
            outputSeekMap = true
        }
    }

    private val currentTimestampUs: Long
        get() = if (outputFirstSample)
            (mediaTagTimestampOffsetUs + tagTimestampUs)
        else
            (if (metadataReader.durationUs == C.TIME_UNSET) 0 else tagTimestampUs)

    companion object {
        
        val FACTORY: ExtractorsFactory = object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(FlvExtractor())
            }
        }

        private const val STATE_READING_FLV_HEADER = 1
        private const val STATE_SKIPPING_TO_TAG_HEADER = 2
        private const val STATE_READING_TAG_HEADER = 3
        private const val STATE_READING_TAG_DATA = 4

        // Header sizes.
        private const val FLV_HEADER_SIZE = 9
        private const val FLV_TAG_HEADER_SIZE = 11

        // Tag types.
        private const val TAG_TYPE_AUDIO = 8
        private const val TAG_TYPE_VIDEO = 9
        private const val TAG_TYPE_SCRIPT_DATA = 18

        // FLV container identifier.
        private const val FLV_TAG = 0x00464c56
    }
}
