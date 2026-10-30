package androidx.media3.extractor.flac

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.FlacFrameReader
import androidx.media3.extractor.FlacFrameReader.SampleNumberHolder
import androidx.media3.extractor.FlacMetadataReader
import androidx.media3.extractor.FlacMetadataReader.FlacStreamMetadataHolder
import androidx.media3.extractor.FlacMetadataReader.checkAndPeekStreamMarker
import androidx.media3.extractor.FlacMetadataReader.peekId3Metadata
import androidx.media3.extractor.FlacMetadataReader.readId3Metadata
import androidx.media3.extractor.FlacMetadataReader.readMetadataBlock
import androidx.media3.extractor.FlacSeekTableSeekMap
import androidx.media3.extractor.FlacStreamMetadata
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.Unseekable
import androidx.media3.extractor.TrackOutput
import com.google.common.base.Preconditions

import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class FlacExtractor  constructor(flags: Int = 0) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(flag = true, value = [FLAG_DISABLE_ID3_METADATA, FLAG_DISABLE_ARTWORK_METADATA])
    annotation class Flags

    // LINT.ThenChange(../../../../../../../../decoder_flac/src/main/java/androidx/media3/decoder/flac/FlacExtractor.java)

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_READ_ID3_METADATA, STATE_GET_STREAM_MARKER_AND_INFO_BLOCK_BYTES, STATE_READ_STREAM_MARKER, STATE_READ_METADATA_BLOCKS, STATE_GET_FRAME_START_MARKER, STATE_READ_FRAMES
        ]
    )
    private annotation class State

    private val streamMarkerAndInfoBlock: ByteArray
    private val buffer: ParsableByteArray
    private val id3MetadataDisabled: Boolean
    private val ignoreArtwork: Boolean

    private val sampleNumberHolder: SampleNumberHolder

    private var extractorOutput:ExtractorOutput? = null
    private var trackOutput:TrackOutput? = null

    private var state: @State Int
    private var id3Metadata: Metadata? = null
    private var flacStreamMetadata:FlacStreamMetadata? = null
    private var minFrameSize = 0
    private var frameStartMarker = 0
    private var binarySearchSeeker:FlacBinarySearchSeeker? = null
    private var currentFrameBytesWritten = 0
    private var currentFrameFirstSampleNumber: Long = 0

    init {
        streamMarkerAndInfoBlock =
            ByteArray(FlacConstants.STREAM_MARKER_SIZE + FlacConstants.STREAM_INFO_BLOCK_SIZE)
        buffer = ParsableByteArray(ByteArray(BUFFER_LENGTH),  0)
        id3MetadataDisabled = (flags and FLAG_DISABLE_ID3_METADATA) != 0
        ignoreArtwork = (flags and FLAG_DISABLE_ARTWORK_METADATA) != 0
        sampleNumberHolder = SampleNumberHolder()
        state = STATE_READ_ID3_METADATA
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        val unused =
            peekId3Metadata(
                input,  false,  false
            )
        return checkAndPeekStreamMarker(input)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track( 0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        when (state) {
            STATE_READ_ID3_METADATA -> {
                readId3Metadata(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_GET_STREAM_MARKER_AND_INFO_BLOCK_BYTES -> {
                getStreamMarkerAndInfoBlockBytes(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READ_STREAM_MARKER -> {
                readStreamMarker(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READ_METADATA_BLOCKS -> {
                readMetadataBlocks(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_GET_FRAME_START_MARKER -> {
                getFrameStartMarker(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READ_FRAMES -> return readFrames(input, seekPosition)
            else -> throw IllegalStateException()
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            state = STATE_READ_ID3_METADATA
        } else if (binarySearchSeeker != null) {
            binarySearchSeeker!!.setSeekTargetUs(timeUs)
        }
        currentFrameFirstSampleNumber = (if (timeUs == 0L) 0 else SAMPLE_NUMBER_UNKNOWN).toLong()
        currentFrameBytesWritten = 0
        buffer.reset( 0)
    }

    override fun release() {
        // Do nothing.
    }

    // Private methods.
    @Throws(IOException::class)
    private fun readId3Metadata(input: ExtractorInput) {
        id3Metadata =
            readId3Metadata(
                input,  !id3MetadataDisabled, ignoreArtwork
            )
        state = STATE_GET_STREAM_MARKER_AND_INFO_BLOCK_BYTES
    }

    @Throws(IOException::class)
    private fun getStreamMarkerAndInfoBlockBytes(input: ExtractorInput) {
        input.peekFully(streamMarkerAndInfoBlock, 0, streamMarkerAndInfoBlock.size)
        input.resetPeekPosition()
        state = STATE_READ_STREAM_MARKER
    }

    @Throws(IOException::class)
    private fun readStreamMarker(input: ExtractorInput) {
        FlacMetadataReader.readStreamMarker(input)
        state = STATE_READ_METADATA_BLOCKS
    }

    @Throws(IOException::class)
    private fun readMetadataBlocks(input: ExtractorInput) {
        var isLastMetadataBlock = false
        val metadataHolder =
            FlacStreamMetadataHolder(flacStreamMetadata)
        while (!isLastMetadataBlock) {
            isLastMetadataBlock =
                readMetadataBlock(input, metadataHolder, ignoreArtwork)
            // Save the current metadata in case an exception occurs.
            flacStreamMetadata = metadataHolder.flacStreamMetadata!!
        }

       val flacStreamMetadata =  flacStreamMetadata!!
        minFrameSize = max(flacStreamMetadata!!.minFrameSize, FlacConstants.MIN_FRAME_HEADER_SIZE)
        val format = flacStreamMetadata!!.getFormat(streamMarkerAndInfoBlock, id3Metadata)
        trackOutput!!
            .format(format.buildUpon().setContainerMimeType(MimeTypes.AUDIO_FLAC).build())
        trackOutput!!.durationUs(flacStreamMetadata!!.durationUs)

        state = STATE_GET_FRAME_START_MARKER
    }

    @Throws(IOException::class)
    private fun getFrameStartMarker(input: ExtractorInput) {
        frameStartMarker = FlacMetadataReader.getFrameStartMarker(input)
        extractorOutput!!.seekMap(
            getSeekMap( 
                input.position,  
                input.length
            )
        )

        state = STATE_READ_FRAMES
    }

    @Throws(IOException::class)
    private fun readFrames(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        // Handle pending binary search seek if necessary.
        if (binarySearchSeeker != null && binarySearchSeeker!!.isSeeking) {
            return binarySearchSeeker!!.handlePendingSeek(input, seekPosition)
        }

        // Set current frame first sample number if it became unknown after seeking.
        if (currentFrameFirstSampleNumber == SAMPLE_NUMBER_UNKNOWN.toLong()) {
            currentFrameFirstSampleNumber =
                FlacFrameReader.getFirstSampleNumber(input, flacStreamMetadata!!)
            return Extractor.RESULT_CONTINUE
        }

        // Copy more bytes into the buffer.
        val currentLimit = buffer.limit()
        var foundEndOfInput = false
        if (currentLimit < BUFFER_LENGTH) {
            val bytesRead =
                input.read(
                    buffer.data,  
                    currentLimit,  
                    BUFFER_LENGTH - currentLimit
                )
            foundEndOfInput = bytesRead == C.RESULT_END_OF_INPUT
            if (!foundEndOfInput) {
                buffer.setLimit(currentLimit + bytesRead)
            } else if (buffer.bytesLeft() == 0) {
                outputSampleMetadata()
                return Extractor.RESULT_END_OF_INPUT
            }
        }

        // Search for a frame.
        val positionBeforeFindingAFrame = buffer.getPosition()

        // Skip frame search on the bytes within the minimum frame size.
        if (currentFrameBytesWritten < minFrameSize) {
            buffer.skipBytes(min(minFrameSize - currentFrameBytesWritten, buffer.bytesLeft()))
        }

        val nextFrameFirstSampleNumber = findFrame(buffer, foundEndOfInput)
        val numberOfFrameBytes = buffer.getPosition() - positionBeforeFindingAFrame
        buffer.setPosition(positionBeforeFindingAFrame)
        trackOutput!!.sampleData(buffer, numberOfFrameBytes)
        currentFrameBytesWritten += numberOfFrameBytes

        // Frame found.
        if (nextFrameFirstSampleNumber != SAMPLE_NUMBER_UNKNOWN.toLong()) {
            outputSampleMetadata()
            currentFrameBytesWritten = 0
            currentFrameFirstSampleNumber = nextFrameFirstSampleNumber
        }

        val remainingBufferCapacity = buffer.data.size - buffer.limit()
        if (buffer.bytesLeft() < FlacConstants.MAX_FRAME_HEADER_SIZE
            && remainingBufferCapacity < FlacConstants.MAX_FRAME_HEADER_SIZE
        ) {
            // We're running out of bytes to read before buffer.limit, and the next frame header may not
            // fit in the rest of buffer.data beyond buffer.limit, so we move the bytes between
            // buffer.position and buffer.limit to the start of buffer.data, and reset the position and
            // limit.
            val bytesLeft = buffer.bytesLeft()
            System.arraycopy(
                buffer.data,
                buffer.getPosition(),
                buffer.data,  
                0,
                bytesLeft
            )
            buffer.setPosition(0)
            buffer.setLimit(bytesLeft)
        }

        return Extractor.RESULT_CONTINUE
    }

    private fun getSeekMap(firstFramePosition: Long, streamLength: Long): SeekMap {
        val flacStreamMetadata = flacStreamMetadata!!
        if (flacStreamMetadata!!.seekTable != null
            && flacStreamMetadata!!.seekTable!!.pointSampleNumbers!!.size > 0
        ) {
            return FlacSeekTableSeekMap(flacStreamMetadata!!, firstFramePosition)
        } else if (streamLength != C.LENGTH_UNSET.toLong() && flacStreamMetadata!!.totalSamples > 0) {
            binarySearchSeeker =
                FlacBinarySearchSeeker(
                    flacStreamMetadata!!, frameStartMarker, firstFramePosition, streamLength
                )
            return binarySearchSeeker!!.getSeekMap()
        } else {
            return Unseekable(flacStreamMetadata!!.durationUs)
        }
    }

    private fun findFrame(data: ParsableByteArray, foundEndOfInput: Boolean): Long {
        var frameOffset = data.getPosition()
        while (frameOffset <= data.limit() - FlacConstants.MAX_FRAME_HEADER_SIZE) {
            data.setPosition(frameOffset)
            if (FlacFrameReader.checkAndReadFrameHeader(
                    data, flacStreamMetadata!!, frameStartMarker, sampleNumberHolder
                )
            ) {
                data.setPosition(frameOffset)
                return sampleNumberHolder.sampleNumber
            }
            frameOffset++
        }

        if (foundEndOfInput) {
            // Verify whether there is a frame of size < MAX_FRAME_HEADER_SIZE at the end of the stream by
            // checking at every position at a distance between MAX_FRAME_HEADER_SIZE and minFrameSize
            // from the buffer limit if it corresponds to a valid frame header.
            // At every offset, the different possibilities are:
            // 1. The current offset indicates the start of a valid frame header. In this case, consider
            //    that a frame has been found and stop searching.
            // 2. A frame starting at the current offset would be invalid. In this case, keep looking for
            //    a valid frame header.
            // 3. The current offset could be the start of a valid frame header, but there is not enough
            //    bytes remaining to complete the header. As the end of the file has been reached, this
            //    means that the current offset does not correspond to a new frame and that the last bytes
            //    of the last frame happen to be a valid partial frame header. This case can occur in two
            //    ways:
            //    3.1. An attempt to read past the buffer is made when reading the potential frame header.
            //    3.2. Reading the potential frame header does not exceed the buffer size, but exceeds the
            //         buffer limit.
            // Note that the third case is very unlikely. It never happens if the end of the input has not
            // been reached as it is always made sure that the buffer has at least MAX_FRAME_HEADER_SIZE
            // bytes available when reading a potential frame header.
            while (frameOffset <= data.limit() - minFrameSize) {
                data.setPosition(frameOffset)
                var frameFound: Boolean
                try {
                    frameFound =
                        FlacFrameReader.checkAndReadFrameHeader(
                            data, flacStreamMetadata!!, frameStartMarker, sampleNumberHolder
                        )
                } catch (e: IndexOutOfBoundsException) {
                    // Case 3.1.
                    frameFound = false
                }
                if (data.getPosition() > data.limit()) {
                    // TODO: Remove (and update above comments) once [Internal ref: b/147657250] is fixed.
                    // Case 3.2.
                    frameFound = false
                }
                if (frameFound) {
                    // Case 1.
                    data.setPosition(frameOffset)
                    return sampleNumberHolder.sampleNumber
                }
                frameOffset++
            }
            // The end of the frame is the end of the file.
            data.setPosition(data.limit())
        } else {
            data.setPosition(frameOffset)
        }

        return SAMPLE_NUMBER_UNKNOWN.toLong()
    }

    private fun outputSampleMetadata() {
        val timeUs: Long =
            (currentFrameFirstSampleNumber
                    * C.MICROS_PER_SECOND
                    / flacStreamMetadata!!.sampleRate)
        trackOutput!!
            .sampleMetadata(
                timeUs,
                C.BUFFER_FLAG_KEY_FRAME,
                currentFrameBytesWritten,  
                0,  
                null
            )
    }

    companion object {
        val FACTORY: ExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(FlacExtractor())
            }
        }
        const val FLAG_DISABLE_ID3_METADATA: Int = 1
        const val FLAG_DISABLE_ARTWORK_METADATA: Int = 1 shl 1

        private const val STATE_READ_ID3_METADATA = 0
        private const val STATE_GET_STREAM_MARKER_AND_INFO_BLOCK_BYTES = 1
        private const val STATE_READ_STREAM_MARKER = 2
        private const val STATE_READ_METADATA_BLOCKS = 3
        private const val STATE_GET_FRAME_START_MARKER = 4
        private const val STATE_READ_FRAMES = 5
        private val BUFFER_LENGTH = 32 * 1024
        private val SAMPLE_NUMBER_UNKNOWN = -1
    }
}
