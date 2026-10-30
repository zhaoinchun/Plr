package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.ReorderingBufferQueue
import androidx.media3.extractor.CeaUtil
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

internal class UserDataReader(
    private val closedCaptionFormats: List<Format>,
    private val containerMimeType: String?
) {
    private val outputs: Array<TrackOutput?>
    private val reorderingBufferQueue: ReorderingBufferQueue

    init {
        outputs = arrayOfNulls<TrackOutput>(closedCaptionFormats.size)
        reorderingBufferQueue =
            ReorderingBufferQueue(
                object :ReorderingBufferQueue.OutputConsumer {
                    override fun consume(presentationTimeUs: Long, buffer: ParsableByteArray?) {
                        CeaUtil.consumeCcData(
                            presentationTimeUs,
                            buffer!!,
                            outputs
                        )
                    }

                })
        // H.262 doesn't provide a convenient 'max reordering queue size' value, so we hard-code 3
        // as it seems unlikely to see more consecutive B-frames than this.
        reorderingBufferQueue.maxSize = 3
    }

    fun createTracks(
        extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator
    ) {
        for (i in outputs.indices) {
            idGenerator.generateNewId()
            val output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT)
            val channelFormat = closedCaptionFormats[i]
            val channelMimeType = channelFormat.sampleMimeType
            Preconditions.checkArgument(
                MimeTypes.APPLICATION_CEA608 == channelMimeType
                        || MimeTypes.APPLICATION_CEA708 == channelMimeType,
                "Invalid closed caption MIME type provided: %s",
                channelMimeType
            )
            output!!.format(
                Format.Builder()
                    .setId(idGenerator.getFormatId())
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(channelMimeType)
                    .setSelectionFlags(channelFormat.selectionFlags)
                    .setLanguage(channelFormat.language)
                    .setAccessibilityChannel(channelFormat.accessibilityChannel)
                    .setInitializationData(channelFormat.initializationData)
                    .build()
            )
            outputs[i] = output
        }
    }

    fun consume(pesTimeUs: Long, userDataPayload: ParsableByteArray) {
        if (userDataPayload.bytesLeft() < 9) {
            return
        }
        val userDataStartCode = userDataPayload.readInt()
        val userDataIdentifier = userDataPayload.readInt()
        val userDataTypeCode = userDataPayload.readUnsignedByte()
        if (userDataStartCode == USER_DATA_START_CODE && userDataIdentifier == CeaUtil.USER_DATA_IDENTIFIER_GA94 && userDataTypeCode == CeaUtil.USER_DATA_TYPE_CODE_MPEG_CC) {
            reorderingBufferQueue.add(pesTimeUs, userDataPayload)
        }
    }

    companion object {
        private const val USER_DATA_START_CODE = 0x0001B2
    }
}
