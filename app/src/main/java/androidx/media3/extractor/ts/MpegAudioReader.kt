
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.min

class MpegAudioReader(
    language: String?,
    @RoleFlags roleFlags: @RoleFlags Int,
    containerMimeType: String?
) : ElementaryStreamReader {
    private val headerScratch: ParsableByteArray
    private val header: MpegAudioUtil.Header
    private val language: String?

    @RoleFlags
    private val roleFlags: @RoleFlags Int
    private val containerMimeType: String?

    private var output:TrackOutput? = null
    private var formatId:String? = null

    private var state: Int
    private var frameBytesRead = 0
    private var hasOutputFormat = false

    // Used when finding the frame header.
    private var lastByteWasFF = false

    // Parsed from the frame header.
    private var frameDurationUs: Long = 0
    private var frameSize = 0

    // The timestamp to attach to the next sample in the current packet.
    private var timeUs: Long

    constructor(containerMimeType: String?) : this(null,  0, containerMimeType)

    init {
        state = STATE_FINDING_HEADER
        // The first byte of an MPEG Audio frame header is always 0xFF.
        headerScratch = ParsableByteArray(4)
        headerScratch.data[0] = 0xFF.toByte()
        header = MpegAudioUtil.Header()
        timeUs = C.TIME_UNSET
        this.language = language
        this.roleFlags = roleFlags
        this.containerMimeType = containerMimeType
    }

    override fun seek() {
        state = STATE_FINDING_HEADER
        frameBytesRead = 0
        lastByteWasFF = false
        timeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        timeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        // Asserts that createTracks has been called.
        val output = output!!
        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_HEADER -> findHeader(data)
                STATE_READING_HEADER -> readHeaderRemainder(data)
                STATE_READING_FRAME -> readFrameRemainder(data)
                else -> throw IllegalStateException()
            }
        }
    }

    private fun findHeader(source: ParsableByteArray) {
        val data = source.data
        val startOffset = source.getPosition()
        val endOffset = source.limit()
        for (i in startOffset..<endOffset) {
            val byteIsFF = (data[i].toInt() and 0xFF) == 0xFF
            val found = lastByteWasFF && (data[i].toInt() and 0xE0) == 0xE0
            lastByteWasFF = byteIsFF
            if (found) {
                source.setPosition(i + 1)
                // Reset lastByteWasFF for next time.
                lastByteWasFF = false
                headerScratch.data[1] = data[i]
                frameBytesRead = 2
                state = STATE_READING_HEADER
                return
            }
        }
        source.setPosition(endOffset)
    }

    @RequiresNonNull("output")
    private fun readHeaderRemainder(source: ParsableByteArray) {
        val bytesToRead = min(source.bytesLeft(), HEADER_SIZE - frameBytesRead)
        source.readBytes(headerScratch.data, frameBytesRead, bytesToRead)
        frameBytesRead += bytesToRead
        if (frameBytesRead < HEADER_SIZE) {
            // We haven't read the whole header yet.
            return
        }

        headerScratch.setPosition(0)
        val parsedHeader = header.setForHeaderData(headerScratch.readInt())
        if (!parsedHeader) {
            // We thought we'd located a frame header, but we hadn't.
            frameBytesRead = 0
            state = STATE_READING_HEADER
            return
        }

        frameSize = header.frameSize
        if (!hasOutputFormat) {
            frameDurationUs = (C.MICROS_PER_SECOND * header.samplesPerFrame) / header.sampleRate
            val format =
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(header.mimeType)
                    .setMaxInputSize(MpegAudioUtil.MAX_FRAME_SIZE_BYTES)
                    .setChannelCount(header.channels)
                    .setSampleRate(header.sampleRate)
                    .setLanguage(language)
                    .setRoleFlags(roleFlags)
                    .build()
            output!!.format(format)
            hasOutputFormat = true
        }

        headerScratch.setPosition(0)
        output!!.sampleData(headerScratch, HEADER_SIZE)
        state = STATE_READING_FRAME
    }

    @RequiresNonNull("output")
    private fun readFrameRemainder(source: ParsableByteArray) {
        val bytesToRead = min(source.bytesLeft(), frameSize - frameBytesRead)
        output!!.sampleData(source, bytesToRead)
        frameBytesRead += bytesToRead
        if (frameBytesRead < frameSize) {
            // We haven't read the whole of the frame yet.
            return
        }

        // packetStarted method must be called before consuming samples.
        Preconditions.checkState(timeUs != C.TIME_UNSET)
        output!!.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null)
        timeUs += frameDurationUs
        frameBytesRead = 0
        state = STATE_FINDING_HEADER
    }

    companion object {
        private const val STATE_FINDING_HEADER = 0
        private const val STATE_READING_HEADER = 1
        private const val STATE_READING_FRAME = 2

        private const val HEADER_SIZE = 4
    }
}
