
package androidx.media3.extractor.ts

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ExtractorOutput

interface SectionPayloadReader {

    fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    )

    fun consume(sectionData: ParsableByteArray)
}
