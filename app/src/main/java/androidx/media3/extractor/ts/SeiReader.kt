
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

class SeiReader(
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
                    override fun consume(
                        presentationTimeUs: Long,
                        buffer: ParsableByteArray?
                    ) {
                        CeaUtil.consume(
                            presentationTimeUs,
                            buffer!!,
                            outputs
                        )
                    }

                })
    }

    fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
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
            val formatId =
                if (channelFormat.id != null) channelFormat.id else idGenerator.getFormatId()
            output!!.format(
                Format.Builder()
                    .setId(formatId)
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

    fun setReorderingQueueSize(reorderingQueueSize: Int) {
        reorderingBufferQueue.maxSize = reorderingQueueSize
    }

    fun consume(pesTimeUs: Long, seiBuffer: ParsableByteArray) {
        reorderingBufferQueue.add(pesTimeUs, seiBuffer)
    }

    fun flush() {
        reorderingBufferQueue.flush()
    }

    fun clear() {
        reorderingBufferQueue.flush()
    }
}
