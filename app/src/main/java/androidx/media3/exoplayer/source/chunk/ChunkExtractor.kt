package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import java.io.IOException

interface ChunkExtractor {
    interface Factory {
        fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): Factory {
            return this
        }

        fun experimentalParseSubtitlesDuringExtraction(
            parseSubtitlesDuringExtraction: Boolean
        ): Factory {
            return this
        }

        fun experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
        ): Factory {
            return this
        }

        fun getOutputTextFormat(sourceFormat: Format): Format {
            return sourceFormat
        }

        fun createProgressiveMediaExtractor(
            primaryTrackType: @C.TrackType Int,
            representationFormat: Format,
            enableEventMessageTrack: Boolean,
            closedCaptionFormats: List<Format>,
            playerEmsgTrackOutput: TrackOutput?,
            playerId: PlayerId
        ): ChunkExtractor?
    }

    interface TrackOutputProvider {
        fun track(id: Int, type: @C.TrackType Int): TrackOutput
    }

    val chunkIndex: ChunkIndex?
    val sampleFormats: Array<Format>?

    fun init(trackOutputProvider: TrackOutputProvider?, startTimeUs: Long, endTimeUs: Long)
    fun release()

    @Throws(IOException::class)
    fun read(input: ExtractorInput): Boolean
}
