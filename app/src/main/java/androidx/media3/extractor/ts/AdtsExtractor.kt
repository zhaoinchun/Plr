
package androidx.media3.extractor.ts

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.Unseekable
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.EOFException
import java.io.IOException

class AdtsExtractor  constructor(flags: @Flags Int = 0) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_ENABLE_CONSTANT_BITRATE_SEEKING, FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS]
    )
    annotation class Flags

    private val flags: @Flags Int

    private val reader: AdtsReader
    private val packetBuffer: ParsableByteArray
    private val scratch: ParsableByteArray
    private val scratchBits: ParsableBitArray

    private var extractorOutput:ExtractorOutput? = null

    private var firstSampleTimestampUs: Long = 0
    private var firstFramePosition: Long
    private var averageFrameSize: Int
    private var hasCalculatedAverageFrameSize = false
    private var startedPacket = false
    private var hasOutputSeekMap = false

    init {
        var flags = flags
        if ((flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0) {
            flags = flags or FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
        }
        this.flags = flags
        reader = AdtsReader(true, MimeTypes.AUDIO_AAC)
        packetBuffer = ParsableByteArray(MAX_PACKET_SIZE)
        averageFrameSize = C.LENGTH_UNSET
        firstFramePosition = C.INDEX_UNSET.toLong()
        // Allocate scratch space for an ID3 header. The same buffer is also used to read 4 byte values.
        scratch = ParsableByteArray(Id3Decoder.ID3_HEADER_LENGTH)
        scratchBits = ParsableBitArray(scratch.data)
    }

    // Extractor implementation.
    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        // Skip any ID3 headers.
        val startPosition = peekId3Header(input)

        // Try to find four or more consecutive AAC audio frames, exceeding the MPEG TS packet size.
        var headerPosition = startPosition
        var totalValidFramesSize = 0
        var validFramesCount = 0
        while (true) {
            input.peekFully(scratch.data, 0, 2)
            scratch.setPosition(0)
            val syncBytes = scratch.readUnsignedShort()
            if (!AdtsReader.isAdtsSyncWord(syncBytes)) {
                // We didn't find an ADTS sync word. Start searching again from one byte further into the
                // start of the stream.
                validFramesCount = 0
                totalValidFramesSize = 0
                headerPosition++
                input.resetPeekPosition()
                input.advancePeekPosition(headerPosition)
            } else {
                if (++validFramesCount >= 4 && totalValidFramesSize > TsExtractor.TS_PACKET_SIZE) {
                    return true
                }

                // Skip the frame.
                input.peekFully(scratch.data, 0, 4)
                scratchBits.position=(14)
                val frameSize = scratchBits.readBits(13)
                if (frameSize <= 6) {
                    // The size is too small, so we're probably not reading an ADTS frame. Start searching
                    // again from one byte further into the start of the stream.
                    validFramesCount = 0
                    totalValidFramesSize = 0
                    headerPosition++
                    input.resetPeekPosition()
                    input.advancePeekPosition(headerPosition)
                } else {
                    input.advancePeekPosition(frameSize - 6)
                    totalValidFramesSize += frameSize
                }
            }
            if (headerPosition - startPosition >= MAX_SNIFF_BYTES) {
                return false
            }
        }
    }

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
        reader.createTracks(output, TrackIdGenerator(0, 1))
        output.endTracks()
    }

    override fun seek(position: Long, timeUs: Long) {
        startedPacket = false
        reader.seek()
        firstSampleTimestampUs = timeUs
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // Asserts that init has been called.
       val extractorOutput=extractorOutput!!

        val inputLength = input.length
        val canUseConstantBitrateSeeking =
            (flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0
                    || ((flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING) != 0
                    && inputLength != C.LENGTH_UNSET.toLong())
        if (canUseConstantBitrateSeeking) {
            calculateAverageFrameSize(input)
        }

        val bytesRead = input.read(packetBuffer.data, 0, MAX_PACKET_SIZE)
        val readEndOfStream = bytesRead == Extractor.RESULT_END_OF_INPUT
        maybeOutputSeekMap(inputLength, readEndOfStream)
        if (readEndOfStream) {
            return Extractor.RESULT_END_OF_INPUT
        }

        // Feed whatever data we have to the reader, regardless of whether the read finished or not.
        packetBuffer.setPosition(0)
        packetBuffer.setLimit(bytesRead)

        if (!startedPacket) {
            // Pass data to the reader as though it's contained within a single infinitely long packet.
            reader.packetStarted(
                firstSampleTimestampUs,
                TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR
            )
            startedPacket = true
        }
        // TODO: Make it possible for reader to consume the dataSource directly, so that it becomes
        // unnecessary to copy the data through packetBuffer.
        reader.consume(packetBuffer)
        return Extractor.RESULT_CONTINUE
    }

    @Throws(IOException::class)
    private fun peekId3Header(input: ExtractorInput): Int {
        var firstFramePosition = 0
        while (true) {
            input.peekFully(scratch.data,  0, Id3Decoder.ID3_HEADER_LENGTH)
            scratch.setPosition(0)
            if (scratch.readUnsignedInt24() != Id3Decoder.ID3_TAG) {
                break
            }
            scratch.skipBytes(3)
            val length = scratch.readSynchSafeInt()
            firstFramePosition += Id3Decoder.ID3_HEADER_LENGTH + length
            input.advancePeekPosition(length)
        }
        input.resetPeekPosition()
        input.advancePeekPosition(firstFramePosition)
        if (this.firstFramePosition == C.INDEX_UNSET.toLong()) {
            this.firstFramePosition = firstFramePosition.toLong()
        }
        return firstFramePosition
    }

    @RequiresNonNull("extractorOutput")
    private fun maybeOutputSeekMap(inputLength: Long, readEndOfStream: Boolean) {
        if (hasOutputSeekMap) {
            return
        }

        val useConstantBitrateSeeking =
            (flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING) != 0 && averageFrameSize > 0
        if (useConstantBitrateSeeking
            && reader.sampleDurationUs == C.TIME_UNSET && !readEndOfStream
        ) {
            // Wait for the sampleDurationUs to be available, or for the end of the stream to be reached,
            // before creating seek map.
            return
        }

        if (useConstantBitrateSeeking && reader.sampleDurationUs != C.TIME_UNSET) {
            extractorOutput!!.seekMap(
                getConstantBitrateSeekMap(
                    inputLength, (flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0
                )
            )
        } else {
            extractorOutput!!.seekMap(Unseekable(C.TIME_UNSET))
        }
        hasOutputSeekMap = true
    }

    @Throws(IOException::class)
    private fun calculateAverageFrameSize(input: ExtractorInput) {
        if (hasCalculatedAverageFrameSize) {
            return
        }
        averageFrameSize = C.LENGTH_UNSET
        input.resetPeekPosition()
        if (input.position == 0L) {
            // Skip any ID3 headers.
            peekId3Header(input)
        }

        var numValidFrames = 0
        var totalValidFramesSize: Long = 0
        try {
            while (input.peekFully(
                    scratch.data,  0,  2,  true
                )
            ) {
                scratch.setPosition(0)
                val syncBytes = scratch.readUnsignedShort()
                if (!AdtsReader.isAdtsSyncWord(syncBytes)) {
                    // Invalid sync byte pattern.
                    // Constant bit-rate seeking will probably fail for this stream.
                    numValidFrames = 0
                    break
                } else {
                    // Read the frame size.
                    if (!input.peekFully(
                            scratch.data,  
                            0,  
                            4,  
                            true
                        )
                    ) {
                        break
                    }
                    scratchBits.position=(14)
                    val currentFrameSize = scratchBits.readBits(13)
                    // Either the stream is malformed OR we're not parsing an ADTS stream.
                    if (currentFrameSize <= 6) {
                        hasCalculatedAverageFrameSize = true
                        throw ParserException.createForMalformedContainer(
                            "Malformed ADTS stream",  null
                        )
                    }
                    totalValidFramesSize += currentFrameSize.toLong()
                    if (++numValidFrames == NUM_FRAMES_FOR_AVERAGE_FRAME_SIZE) {
                        break
                    }
                    if (!input.advancePeekPosition(
                            currentFrameSize - 6,  
                            true
                        )
                    ) {
                        break
                    }
                }
            }
        } catch (e: EOFException) {
            // We reached the end of the input during a peekFully() or advancePeekPosition() operation.
            // This is OK, it just means the input has an incomplete ADTS frame at the end. Ideally
            // ExtractorInput would allow these operations to encounter end-of-input without throwing an
            // exception [internal: b/145586657].
        }
        input.resetPeekPosition()
        if (numValidFrames > 0) {
            averageFrameSize = (totalValidFramesSize / numValidFrames).toInt()
        } else {
            averageFrameSize = C.LENGTH_UNSET
        }
        hasCalculatedAverageFrameSize = true
    }

    private fun getConstantBitrateSeekMap(
        inputLength: Long,
        allowSeeksIfLengthUnknown: Boolean
    ): SeekMap {
        val bitrate: Int = getBitrateFromFrameSize(averageFrameSize, reader.sampleDurationUs)
        return ConstantBitrateSeekMap(
            inputLength, firstFramePosition, bitrate, averageFrameSize, allowSeeksIfLengthUnknown
        )
    }

    companion object {
        val FACTORY: ExtractorsFactory = object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(AdtsExtractor())
            }
        }

        const val FLAG_ENABLE_CONSTANT_BITRATE_SEEKING: Int = 1
        const val FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS: Int = 1 shl 1
        private const val MAX_PACKET_SIZE = 2 * 1024
        private const val MAX_SNIFF_BYTES = 8 * 1024
        private const val NUM_FRAMES_FOR_AVERAGE_FRAME_SIZE = 1000

        private fun getBitrateFromFrameSize(frameSize: Int, durationUsPerFrame: Long): Int {
            return ((frameSize * (C.BITS_PER_BYTE.toLong()) * C.MICROS_PER_SECOND) / durationUsPerFrame).toInt()
        }
    }
}
