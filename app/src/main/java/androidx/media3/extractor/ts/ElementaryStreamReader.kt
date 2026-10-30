
package androidx.media3.extractor.ts

import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator

interface ElementaryStreamReader {
    fun seek()

    fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator)

    fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int)

    @Throws(ParserException::class)
    fun consume(data: ParsableByteArray)

    fun packetFinished() {
        // Do nothing.
    }

    fun endOfInputReached() {
        // Do nothing.
    }
}
