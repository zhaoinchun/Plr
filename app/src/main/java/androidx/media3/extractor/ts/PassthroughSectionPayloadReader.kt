
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import com.google.common.base.Preconditions

class PassthroughSectionPayloadReader(sampleMimeType: String?, containerMimeType: String?) :
    SectionPayloadReader {
    private var format: Format
    private lateinit var timestampAdjuster:TimestampAdjuster
    private var output:TrackOutput? = null

    init {
        this.format =
            Format.Builder()
                .setContainerMimeType(containerMimeType)
                .setSampleMimeType(sampleMimeType)
                .build()
    }

    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    ) {
        this.timestampAdjuster = timestampAdjuster
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_METADATA)
        // Eagerly output an incomplete format (missing timestamp offset) to ensure source preparation
        // is not blocked waiting for potentially sparse metadata.
        output!!.format(format)
    }

    override fun consume(sectionData: ParsableByteArray) {
        assertInitialized()
        val sampleTimestampUs: Long = timestampAdjuster.lastAdjustedTimestampUs
        val subsampleOffsetUs: Long = timestampAdjuster.timestampOffsetUs
        if (sampleTimestampUs == C.TIME_UNSET || subsampleOffsetUs == C.TIME_UNSET) {
            // Don't output samples without a known sample timestamp and subsample offset.
            return
        }
        if (subsampleOffsetUs != format.subsampleOffsetUs) {
            format = format.buildUpon().setSubsampleOffsetUs(subsampleOffsetUs).build()
            output!!.format(format)
        }
        val sampleSize: Int = sectionData.bytesLeft()
        output!!.sampleData(sectionData, sampleSize)
        output!!.sampleMetadata(sampleTimestampUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
    }

    private fun assertInitialized() {
        timestampAdjuster!!
        output!!
    }
}
