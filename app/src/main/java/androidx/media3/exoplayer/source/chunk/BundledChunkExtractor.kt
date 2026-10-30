package androidx.media3.exoplayer.source.chunk

import android.util.SparseArray
import androidx.core.util.size
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isMatroska
import androidx.media3.common.MimeTypes.isText
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.ChunkIndexProvider
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.png.PngExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Preconditions
import java.io.IOException

class BundledChunkExtractor(
    private val extractor: Extractor,
    private val primaryTrackType: @C.TrackType Int,
    private val primaryTrackManifestFormat: Format?,
    private val primaryTrackManifestFormatInfoMerger: ManifestFormatMerger = ManifestFormatMerger.DEFAULT
) : ExtractorOutput, ChunkExtractor {
    fun interface ManifestFormatMerger {
        fun merge(sampleFormat: Format, primaryTrackManifestFormat: Format?): Format

        companion object {
            val DEFAULT: ManifestFormatMerger =
                ManifestFormatMerger { sampleFormat: Format, manifestFormat: Format? ->
                    if (manifestFormat != null)
                        sampleFormat.withManifestFormatInfo(manifestFormat)
                    else
                        sampleFormat
                }
        }
    }

    class Factory : ChunkExtractor.Factory {
        private var subtitleParserFactory: SubtitleParser.Factory
        private var parseSubtitlesDuringExtraction = false
        private var codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int

        init {
            subtitleParserFactory = DefaultSubtitleParserFactory()
            codecsToParseWithinGopSampleDependencies =
                C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265
        }

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

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
        ): Factory {
            this.codecsToParseWithinGopSampleDependencies = codecsToParseWithinGopSampleDependencies
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
            val containerMimeType = representationFormat.containerMimeType
            val extractor: Extractor?
            if (isText(containerMimeType)) {
                if (!parseSubtitlesDuringExtraction) {
                    // Subtitles will be parsed after decoding
                    return null
                } else {
                    extractor =
                        SubtitleExtractor(
                            subtitleParserFactory.create(representationFormat)!!,
                            representationFormat
                        )
                }
            } else if (isMatroska(containerMimeType)) {
                var flags: @MatroskaExtractor.Flags Int =
                    MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES
                if (!parseSubtitlesDuringExtraction) {
                    flags = flags or MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
                }
                extractor = MatroskaExtractor(subtitleParserFactory, flags)
            } else if (containerMimeType == MimeTypes.IMAGE_JPEG) {
                extractor = JpegExtractor(JpegExtractor.FLAG_READ_IMAGE)
            } else if (containerMimeType == MimeTypes.IMAGE_PNG) {
                extractor = PngExtractor()
            } else {
                var flags: @FragmentedMp4Extractor.Flags Int = 0
                if (enableEventMessageTrack) {
                    flags = flags or FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK
                }
                if (!parseSubtitlesDuringExtraction) {
                    flags = flags or FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA
                }
                flags = flags or
                        FragmentedMp4Extractor.codecsToParseWithinGopSampleDependenciesAsFlags(
                            codecsToParseWithinGopSampleDependencies
                        )
                extractor =
                    FragmentedMp4Extractor(
                        subtitleParserFactory,
                        flags,
                        null,
                        null,
                        closedCaptionFormats,
                        playerEmsgTrackOutput
                    )
            }
            return BundledChunkExtractor(extractor, primaryTrackType, representationFormat)
        }
    }

    private val bindingTrackOutputs = SparseArray<BindingTrackOutput?>()
    private var extractorInitialized = false
    private var trackOutputProvider: TrackOutputProvider? = null
    private var endTimeUs: Long = 0
    private var seekMap: SeekMap? = null
    override var sampleFormats: Array<Format>? = null

    override val chunkIndex: ChunkIndex?
        get() {
            return when (seekMap) {
                is ChunkIndex -> seekMap as ChunkIndex
                is ChunkIndexProvider -> (seekMap as ChunkIndexProvider).chunkIndex
                else -> null
            }
        }

    override fun init(
        trackOutputProvider: TrackOutputProvider?, startTimeUs: Long, endTimeUs: Long
    ) {
        this.trackOutputProvider = trackOutputProvider
        this.endTimeUs = endTimeUs
        if (!extractorInitialized) {
            extractor.init(this)
            if (startTimeUs != C.TIME_UNSET) {
                extractor.seek(0, startTimeUs)
            }
            extractorInitialized = true
        } else {
            extractor.seek(0, if (startTimeUs == C.TIME_UNSET) 0 else startTimeUs)
            for (i in 0..<bindingTrackOutputs.size) {
                bindingTrackOutputs.valueAt(i)!!.bind(trackOutputProvider, endTimeUs)
            }
        }
    }

    override fun release() {
        extractor.release()
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput): Boolean {
        val result = extractor.read(input, POSITION_HOLDER)
        Preconditions.checkState(result != Extractor.RESULT_SEEK)
        return result == Extractor.RESULT_CONTINUE
    }

    // ExtractorOutput implementation.
    override fun track(id: Int, type: Int): TrackOutput {
        var bindingTrackOutput = bindingTrackOutputs.get(id)
        if (bindingTrackOutput == null) {
            // Assert that if we're seeing a new track we have not seen endTracks.
            Preconditions.checkState(sampleFormats == null)
            bindingTrackOutput =
                BindingTrackOutput(
                    id,
                    type,
                    if (type == primaryTrackType) primaryTrackManifestFormat else null,
                    primaryTrackManifestFormatInfoMerger
                )
            bindingTrackOutput.bind(trackOutputProvider, endTimeUs)
            bindingTrackOutputs.put(id, bindingTrackOutput)
        }
        return bindingTrackOutput
    }

    override fun endTracks() {
        val sampleFormats = arrayOfNulls<Format>(bindingTrackOutputs.size)
        for (i in 0..<bindingTrackOutputs.size) {
            sampleFormats[i] = bindingTrackOutputs.valueAt(i)!!.sampleFormat!!
        }
        this.sampleFormats = sampleFormats as Array<Format>
    }

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    // Internal logic.
    private class BindingTrackOutput(
        private val id: Int,
        private val type: Int,
        private val manifestFormat: Format?,
        private val manifestFormatMerger: ManifestFormatMerger
    ) : TrackOutput {
        private val fakeTrackOutput = DiscardingTrackOutput()
        var sampleFormat: Format? = null
        private var trackOutput: TrackOutput? = null
        private var endTimeUs: Long = 0

        fun bind(trackOutputProvider: TrackOutputProvider?, endTimeUs: Long) {
            if (trackOutputProvider == null) {
                trackOutput = fakeTrackOutput
                return
            }
            this.endTimeUs = endTimeUs
            trackOutput = trackOutputProvider.track(id, type)
            if (sampleFormat != null) {
                trackOutput!!.format(sampleFormat!!)
            }
        }

        override fun format(format: Format) {
            sampleFormat = manifestFormatMerger.merge(format, manifestFormat)
            trackOutput!!.format(sampleFormat!!)
        }

        @Throws(IOException::class)
        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: @TrackOutput.SampleDataPart Int
        ): Int {
            return trackOutput!!.sampleData(input, length, allowEndOfInput)
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: @TrackOutput.SampleDataPart Int
        ) {
            trackOutput!!.sampleData(data, length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: @C.BufferFlags Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            if (endTimeUs != C.TIME_UNSET && timeUs >= endTimeUs) {
                trackOutput = fakeTrackOutput
            }
            trackOutput!!.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }

    companion object {
        @Deprecated(
            """{@link Factory} is mutable, so a static instance is not safe. Instantiate a new
        {@link Factory} instead."""
        )
        val FACTORY: Factory = Factory()
        private val POSITION_HOLDER = PositionHolder()
    }
}
