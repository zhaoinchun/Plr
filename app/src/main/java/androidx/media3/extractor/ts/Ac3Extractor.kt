
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Ac3Util.parseAc3SyncframeSize
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap.Unseekable
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import java.io.IOException

class Ac3Extractor : Extractor {
    private val reader: Ac3Reader
    private val sampleData: ParsableByteArray

    private var startedPacket = false

    init {
        reader = Ac3Reader(MimeTypes.AUDIO_AC3)
        sampleData = ParsableByteArray(MAX_SYNC_FRAME_SIZE)
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        // Skip any ID3 headers.
        val scratch = ParsableByteArray(Id3Decoder.ID3_HEADER_LENGTH)
        var startPosition = 0
        while (true) {
            input.peekFully(scratch.data,  0, Id3Decoder.ID3_HEADER_LENGTH)
            scratch.setPosition(0)
            if (scratch.readUnsignedInt24() != Id3Decoder.ID3_TAG) {
                break
            }
            scratch.skipBytes(3) // version, flags
            val length = scratch.readSynchSafeInt()
            startPosition += 10 + length
            input.advancePeekPosition(length)
        }
        input.resetPeekPosition()
        input.advancePeekPosition(startPosition)

        var headerPosition = startPosition
        var validFramesCount = 0
        while (true) {
            input.peekFully(scratch.data, 0, 6)
            scratch.setPosition(0)
            val syncBytes = scratch.readUnsignedShort()
            if (syncBytes != AC3_SYNC_WORD) {
                validFramesCount = 0
                input.resetPeekPosition()
                if (++headerPosition - startPosition >= MAX_SNIFF_BYTES) {
                    return false
                }
                input.advancePeekPosition(headerPosition)
            } else {
                if (++validFramesCount >= 4) {
                    return true
                }
                val frameSize = parseAc3SyncframeSize(scratch.data)
                if (frameSize == C.LENGTH_UNSET) {
                    return false
                }
                input.advancePeekPosition(frameSize - 6)
            }
        }
    }

    override fun init(output: ExtractorOutput) {
        reader.createTracks(output, TrackIdGenerator(0, 1))
        output.endTracks()
        output.seekMap(Unseekable(C.TIME_UNSET))
    }

    override fun seek(position: Long, timeUs: Long) {
        startedPacket = false
        reader.seek()
    }

    override fun release() {
        // Do nothing.
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val bytesRead = input.read(sampleData.data, 0, MAX_SYNC_FRAME_SIZE)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            return Extractor.RESULT_END_OF_INPUT
        }

        // Feed whatever data we have to the reader, regardless of whether the read finished or not.
        sampleData.setPosition(0)
        sampleData.setLimit(bytesRead)

        if (!startedPacket) {
            // Pass data to the reader as though it's contained within a single infinitely long packet.
            reader.packetStarted( 0,
                TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR
            )
            startedPacket = true
        }
        // TODO: Make it possible for the reader to consume the dataSource directly, so that it becomes
        // unnecessary to copy the data through packetBuffer.
        reader.consume(sampleData)
        return Extractor.RESULT_CONTINUE
    }

    companion object {
        val FACTORY: ExtractorsFactory = object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(Ac3Extractor())
            }
        }

        private val MAX_SNIFF_BYTES = 8 * 1024

        private const val AC3_SYNC_WORD = 0x0B77
        private const val MAX_SYNC_FRAME_SIZE = 2786
    }
}
