package androidx.media3.exoplayer.source.chunk

import android.annotation.SuppressLint
import android.media.MediaFormat
import android.media.MediaParser
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isMatroska
import androidx.media3.common.MimeTypes.isText

import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider
import androidx.media3.exoplayer.source.mediaparser.InputReaderAdapterV30
import androidx.media3.exoplayer.source.mediaparser.MediaParserUtil
import androidx.media3.exoplayer.source.mediaparser.OutputConsumerAdapterV30
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException

@RequiresApi(30)
class MediaParserChunkExtractor @SuppressLint("WrongConstant") constructor(
    primaryTrackType: @C.TrackType Int,
    manifestFormat: Format,
    closedCaptionFormats: List<Format>,
    playerId: PlayerId
) : ChunkExtractor {
    class Factory : ChunkExtractor.Factory {
        private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
        private var parseSubtitlesDuringExtraction = false

        override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): Factory {
            this.subtitleParserFactory = subtitleParserFactory
            return this
        }

        override fun experimentalParseSubtitlesDuringExtraction(
            parseSubtitlesDuringExtraction: Boolean
        ): Factory {
            this.parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
            return this
        }

        override fun getOutputTextFormat(sourceFormat: Format): Format {
            if (parseSubtitlesDuringExtraction && subtitleParserFactory.supportsFormat(sourceFormat)) {
                return sourceFormat
                    .buildUpon()
                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setCueReplacementBehavior(
                        subtitleParserFactory.getCueReplacementBehavior(sourceFormat)
                    )
                    .setCodecs(
                        sourceFormat.sampleMimeType
                                + (if (sourceFormat.codecs != null) " " + sourceFormat.codecs else "")
                    )
                    .setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE)
                    .build()
            } else {
                return sourceFormat
            }
        }

        override fun createProgressiveMediaExtractor(
            primaryTrackType: @C.TrackType Int,
            representationFormat: Format,
            enableEventMessageTrack: Boolean,
            closedCaptionFormats: List<Format>,
            playerEmsgTrackOutput: TrackOutput?,
            playerId: PlayerId
        ): ChunkExtractor? {
            if (!isText(representationFormat.containerMimeType)) {
                // Container is either Matroska or Fragmented MP4.
                return MediaParserChunkExtractor(
                    primaryTrackType, representationFormat, closedCaptionFormats, playerId
                )
            } else {
                if (!parseSubtitlesDuringExtraction) {
                    // Subtitles will be parsed after decoding
                    return null
                } else {
                    return BundledChunkExtractor(
                        SubtitleExtractor(
                            subtitleParserFactory.create(representationFormat)!!,
                            representationFormat
                        ),
                        primaryTrackType,
                        representationFormat
                    )
                }
            }
        }
    }

    private val outputConsumerAdapter = OutputConsumerAdapterV30(
        manifestFormat, primaryTrackType, true
    )
    private val inputReaderAdapter = InputReaderAdapterV30()
    private val mediaParser: MediaParser
    private val trackOutputProviderAdapter = TrackOutputProviderAdapter()
    private val discardingTrackOutput = DiscardingTrackOutput()
    private var pendingSeekUs = C.TIME_UNSET
    private var trackOutputProvider: TrackOutputProvider? = null
    override var sampleFormats: Array<Format>? = null

    init {
        val mimeType = manifestFormat.containerMimeType!!
        val parserName =
            if (isMatroska(mimeType))
                MediaParser.PARSER_NAME_MATROSKA
            else
                MediaParser.PARSER_NAME_FMP4
        outputConsumerAdapter.setSelectedParserName(parserName)
        mediaParser = MediaParser.createByName(parserName, outputConsumerAdapter)
        mediaParser.setParameter(MediaParser.PARAMETER_MATROSKA_DISABLE_CUES_SEEKING, true)
        mediaParser.setParameter(MediaParserUtil.PARAMETER_IN_BAND_CRYPTO_INFO, true)
        mediaParser.setParameter(MediaParserUtil.PARAMETER_INCLUDE_SUPPLEMENTAL_DATA, true)
        mediaParser.setParameter(MediaParserUtil.PARAMETER_EAGERLY_EXPOSE_TRACK_TYPE, true)
        mediaParser.setParameter(MediaParserUtil.PARAMETER_EXPOSE_DUMMY_SEEK_MAP, true)
        mediaParser.setParameter(MediaParserUtil.PARAMETER_EXPOSE_CHUNK_INDEX_AS_MEDIA_FORMAT, true)
        mediaParser.setParameter(
            MediaParserUtil.PARAMETER_OVERRIDE_IN_BAND_CAPTION_DECLARATIONS,
            true
        )
        val closedCaptionMediaFormats = ArrayList<MediaFormat?>()
        for (i in closedCaptionFormats.indices) {
            closedCaptionMediaFormats.add(
                MediaParserUtil.toCaptionsMediaFormat(closedCaptionFormats[i]!!)
            )
        }
        mediaParser.setParameter(
            MediaParserUtil.PARAMETER_EXPOSE_CAPTION_FORMATS,
            closedCaptionMediaFormats
        )
        if (Build.VERSION.SDK_INT >= 31) {
            MediaParserUtil.setLogSessionIdOnMediaParser(mediaParser, playerId)
        }
        outputConsumerAdapter.setMuxedCaptionFormats(closedCaptionFormats)
    }

    // ChunkExtractor implementation.
    override fun init(
        trackOutputProvider: TrackOutputProvider?, startTimeUs: Long, endTimeUs: Long
    ) {
        this.trackOutputProvider = trackOutputProvider
        outputConsumerAdapter.setSampleTimestampUpperLimitFilterUs(endTimeUs)
        outputConsumerAdapter.setExtractorOutput(trackOutputProviderAdapter)
        pendingSeekUs = startTimeUs
    }

    override fun release() {
        mediaParser.release()
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput): Boolean {
        maybeExecutePendingSeek()
        inputReaderAdapter.setDataReader(input, input.length)
        return mediaParser.advance(inputReaderAdapter)
    }

    override val chunkIndex: ChunkIndex?
        get() = outputConsumerAdapter.chunkIndex

    // Internal methods.
    private fun maybeExecutePendingSeek() {
        val dummySeekMap = outputConsumerAdapter.dummySeekMap
        if (pendingSeekUs != C.TIME_UNSET && dummySeekMap != null) {
            mediaParser.seek(dummySeekMap.getSeekPoints(pendingSeekUs).first)
            pendingSeekUs = C.TIME_UNSET
        }
    }

    // Internal classes.
    private inner class TrackOutputProviderAdapter : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            return if (trackOutputProvider != null)
                trackOutputProvider!!.track(id, type)
            else
                discardingTrackOutput
        }

        override fun endTracks() {
            // Imitate BundledChunkExtractor behavior, which captures a sample format snapshot when
            // endTracks is called.
            sampleFormats = outputConsumerAdapter.sampleFormats
        }

        override fun seekMap(seekMap: SeekMap) {
            // Do nothing.
        }
    }

    companion object {
        // Maximum TAG length is 23 characters.
        private const val TAG = "MediaPrsrChunkExtractor"

        @Deprecated(
            """{@link ChunkExtractor.Factory} is mutable, so a static instance is not safe.
        Instantiate a new {@link Factory} instead."""
        )
        val FACTORY: ChunkExtractor.Factory = Factory()
    }
}
