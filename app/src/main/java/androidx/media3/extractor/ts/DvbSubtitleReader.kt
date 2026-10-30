
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.DvbSubtitleInfo
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

class DvbSubtitleReader(
    private val subtitleInfos: MutableList<DvbSubtitleInfo>,
    private val containerMimeType: String?
) : ElementaryStreamReader {
    private val outputs: Array<TrackOutput?>

    private var writingSample = false
    private var bytesToCheck = 0
    private var sampleBytesWritten = 0
    private var sampleTimeUs: Long

    init {
        outputs = Array<TrackOutput?>(subtitleInfos.size){null}
        sampleTimeUs = C.TIME_UNSET
    }

    override fun seek() {
        writingSample = false
        sampleTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        for (i in outputs.indices) {
            val subtitleInfo = subtitleInfos[i]
            idGenerator.generateNewId()
            val output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT)
            output!!.format(
                Format.Builder()
                    .setId(idGenerator.getFormatId())
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(MimeTypes.APPLICATION_DVBSUBS)
                    .setInitializationData(mutableListOf<ByteArray>(subtitleInfo.initializationData))
                    .setLanguage(subtitleInfo.language)
                    .build()
            )
            outputs[i] = output
        }
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        if ((flags and TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
            return
        }
        writingSample = true
        sampleTimeUs = pesTimeUs
        sampleBytesWritten = 0
        bytesToCheck = 2
    }

    override fun packetFinished() {
        if (writingSample) {
            // packetStarted method must be called before reading sample.
            Preconditions.checkState(sampleTimeUs != C.TIME_UNSET)
            for (output in outputs) {
                output?.sampleMetadata(
                    sampleTimeUs,
                    C.BUFFER_FLAG_KEY_FRAME,
                    sampleBytesWritten,
                    0,
                    null
                )
            }
            writingSample = false
        }
    }

    override fun consume(data: ParsableByteArray) {
        if (writingSample) {
            if (bytesToCheck == 2 && !checkNextByte(data, 0x20)) {
                // Failed to check data_identifier
                return
            }
            if (bytesToCheck == 1 && !checkNextByte(data, 0x00)) {
                // Check and discard the subtitle_stream_id
                return
            }
            val dataPosition = data.getPosition()
            val bytesAvailable = data.bytesLeft()
            for (output in outputs) {
                data.setPosition(dataPosition)
                output?.sampleData(data, bytesAvailable)
            }
            sampleBytesWritten += bytesAvailable
        }
    }

    private fun checkNextByte(data: ParsableByteArray, expectedValue: Int): Boolean {
        if (data.bytesLeft() == 0) {
            return false
        }
        if (data.readUnsignedByte() != expectedValue) {
            writingSample = false
        }
        bytesToCheck--
        return writingSample
    }
}
