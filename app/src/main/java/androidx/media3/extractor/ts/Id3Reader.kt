
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import kotlin.math.min

class Id3Reader(private val containerMimeType: String?) : ElementaryStreamReader {
    private val id3Header: ParsableByteArray

    private var output:TrackOutput? = null

    // State that should be reset on seek.
    private var writingSample = false

    // Per sample state that gets reset at the start of each sample.
    private var sampleTimeUs: Long
    private var sampleSize = 0
    private var sampleBytesRead = 0

    init {
        id3Header = ParsableByteArray(Id3Decoder.ID3_HEADER_LENGTH)
        sampleTimeUs = C.TIME_UNSET
    }

    override fun seek() {
        writingSample = false
        sampleTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_METADATA)
        output!!.format(
            Format.Builder()
                .setId(idGenerator.getFormatId())
                .setContainerMimeType(containerMimeType)
                .setSampleMimeType(MimeTypes.APPLICATION_ID3)
                .build()
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        if ((flags and TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
            return
        }
        writingSample = true
        sampleTimeUs = pesTimeUs
        sampleSize = 0
        sampleBytesRead = 0
    }

    override fun consume(data: ParsableByteArray) {
        // Asserts that createTracks has been called.
        val output = output!!
        if (!writingSample) {
            return
        }
        val bytesAvailable = data.bytesLeft()
        if (sampleBytesRead < Id3Decoder.ID3_HEADER_LENGTH) {
            // We're still reading the ID3 header.
            val headerBytesAvailable =
                min(bytesAvailable, Id3Decoder.ID3_HEADER_LENGTH - sampleBytesRead)
            System.arraycopy(
                data.data,
                data.getPosition(),
                id3Header.data,
                sampleBytesRead,
                headerBytesAvailable
            )
            if (sampleBytesRead + headerBytesAvailable == Id3Decoder.ID3_HEADER_LENGTH) {
                // We've finished reading the ID3 header. Extract the sample size.
                id3Header.setPosition(0)
                if ('I'.code != id3Header.readUnsignedByte() || 'D'.code != id3Header.readUnsignedByte() || '3'.code != id3Header.readUnsignedByte()) {
                    w(TAG, "Discarding invalid ID3 tag")
                    writingSample = false
                    return
                }
                id3Header.skipBytes(3) // version (2) + flags (1)
                sampleSize = Id3Decoder.ID3_HEADER_LENGTH + id3Header.readSynchSafeInt()
            }
        }
        // Write data to the output.
        val bytesToWrite = min(bytesAvailable, sampleSize - sampleBytesRead)
        output!!.sampleData(data, bytesToWrite)
        sampleBytesRead += bytesToWrite
    }

    override fun packetFinished() {
        // Asserts that createTracks has been called.
        val output = output!!
        if (!writingSample || sampleSize == 0 || sampleBytesRead != sampleSize) {
            return
        }
        // packetStarted method must be called before consuming samples.
        Preconditions.checkState(sampleTimeUs != C.TIME_UNSET)
        output!!.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
        writingSample = false
    }

    companion object {
        private const val TAG = "Id3Reader"
    }
}
