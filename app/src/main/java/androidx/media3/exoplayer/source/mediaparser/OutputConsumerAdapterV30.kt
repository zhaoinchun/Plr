package androidx.media3.exoplayer.source.mediaparser

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaParser
import android.media.MediaParser.InputReader
import android.media.MediaParser.TrackData
import android.util.Pair
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.NoOpExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern

@RequiresApi(30)
class OutputConsumerAdapterV30(
    private val primaryTrackManifestFormat: Format? = null,
    private val primaryTrackType: @C.TrackType Int = C.TRACK_TYPE_NONE,
    private val expectDummySeekMap: Boolean = false
) : MediaParser.OutputConsumer {
    private val trackOutputs = mutableListOf<TrackOutput?>()
    private val trackFormats = mutableListOf<Format?>()
    private val lastReceivedCryptoInfos = mutableListOf<MediaCodec.CryptoInfo?>()
    private val lastOutputCryptoDatas = mutableListOf<TrackOutput.CryptoData?>()
    private val scratchDataReaderAdapter = DataReaderAdapter()
    private var extractorOutput: ExtractorOutput = NoOpExtractorOutput()
    var dummySeekMap: MediaParser.SeekMap? = null
        private set
    private var lastSeekMap: MediaParser.SeekMap? = null
    private var containerMimeType: String? = null
    var chunkIndex: ChunkIndex? = null
        private set
    private var timestampAdjuster: TimestampAdjuster? = null
    private var muxedCaptionFormats = listOf<Format>()
    private var primaryTrackIndex = 0
    private var sampleTimestampUpperLimitFilterUs = C.TIME_UNSET
    private var tracksFoundCalled = false
    private var tracksEnded = false
    private var seekingDisabled = false

    fun setSampleTimestampUpperLimitFilterUs(sampleTimestampUpperLimitFilterUs: Long) {
        this.sampleTimestampUpperLimitFilterUs = sampleTimestampUpperLimitFilterUs
    }

    fun setTimestampAdjuster(timestampAdjuster: TimestampAdjuster?) {
        this.timestampAdjuster = timestampAdjuster
    }

    fun setExtractorOutput(extractorOutput: ExtractorOutput) {
        this.extractorOutput = extractorOutput
    }

    fun setMuxedCaptionFormats(muxedCaptionFormats: List<Format>) {
        this.muxedCaptionFormats = muxedCaptionFormats
    }

    fun disableSeeking() {
        seekingDisabled = true
    }

    fun getSeekPoints(seekTimeUs: Long): Pair<MediaParser.SeekPoint, MediaParser.SeekPoint> {
        return lastSeekMap?.getSeekPoints(seekTimeUs) ?: SEEK_POINT_PAIR_START
    }

    fun setSelectedParserName(parserName: String) {
        containerMimeType = getMimeType(parserName)
    }

    val sampleFormats: Array<Format>?
        get() {
            if (!tracksFoundCalled) {
                return null
            }
            val sampleFormats =
                arrayOfNulls<Format>(trackFormats.size)
            for (i in trackFormats.indices) {
                sampleFormats[i] = trackFormats[i]!!
            }
            return sampleFormats as Array<Format>
        }

    // MediaParser.OutputConsumer implementation.
    override fun onTrackCountFound(numberOfTracks: Int) {
        tracksFoundCalled = true
        maybeEndTracks()
    }

    override fun onSeekMapFound(seekMap: MediaParser.SeekMap) {
        if (expectDummySeekMap && dummySeekMap == null) {
            // This is a dummy seek map.
            dummySeekMap = seekMap
        } else {
            lastSeekMap = seekMap
            val durationUs = seekMap.getDurationMicros()
            extractorOutput.seekMap(
                if (seekingDisabled)
                    SeekMap.Unseekable(
                        if (durationUs != MediaParser.SeekMap.UNKNOWN_DURATION.toLong()) durationUs else C.TIME_UNSET
                    )
                else
                    SeekMapAdapter(seekMap)
            )
        }
    }

    override fun onTrackDataFound(trackIndex: Int, trackData: TrackData) {
        if (maybeObtainChunkIndex(trackData.mediaFormat)) {
            // The MediaFormat contains a chunk index. It does not contain anything else.
            return
        }

        ensureSpaceForTrackIndex(trackIndex)
        var trackOutput = trackOutputs.get(trackIndex)
        if (trackOutput == null) {
            val trackTypeString = trackData.mediaFormat.getString(MEDIA_FORMAT_KEY_TRACK_TYPE)
            val trackType: Int =
                toTrackTypeConstant(
                    trackTypeString ?: trackData.mediaFormat.getString(MediaFormat.KEY_MIME)
                )
            if (trackType == primaryTrackType) {
                primaryTrackIndex = trackIndex
            }
            trackOutput = extractorOutput.track(trackIndex, trackType)
            trackOutputs[trackIndex] = trackOutput
            if (trackTypeString != null) {
                // The MediaFormat includes the track type string, so it cannot include any other keys, as
                // per the android.media.mediaparser.eagerlyExposeTrackType parameter documentation.
                return
            }
        }

        if (trackData.mediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
            trackOutput.durationUs(trackData.mediaFormat.getLong(MediaFormat.KEY_DURATION))
        }

        val format = toExoPlayerFormat(trackData)
        trackOutput.format(
            if (primaryTrackManifestFormat != null && trackIndex == primaryTrackIndex)
                format.withManifestFormatInfo(primaryTrackManifestFormat)
            else
                format
        )
        trackFormats.set(trackIndex, format)
        maybeEndTracks()
    }

    @Throws(IOException::class)
    override fun onSampleDataFound(trackIndex: Int, sampleData: InputReader) {
        ensureSpaceForTrackIndex(trackIndex)
        scratchDataReaderAdapter.input = sampleData
        var trackOutput = trackOutputs[trackIndex]
        if (trackOutput == null) {
            trackOutput = extractorOutput.track(trackIndex, C.TRACK_TYPE_UNKNOWN)
            trackOutputs[trackIndex] = trackOutput
        }
        trackOutput.sampleData(
            scratchDataReaderAdapter, sampleData.length.toInt(), true
        )
    }

    override fun onSampleCompleted(
        trackIndex: Int,
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoInfo: MediaCodec.CryptoInfo?
    ) {
        var timeUs = timeUs
        if (sampleTimestampUpperLimitFilterUs != C.TIME_UNSET
            && timeUs >= sampleTimestampUpperLimitFilterUs
        ) {
            // Ignore this sample.
            return
        } else if (timestampAdjuster != null) {
            timeUs = timestampAdjuster!!.adjustSampleTimestamp(timeUs)
        }
        trackOutputs[trackIndex]!!.sampleMetadata(
            timeUs,
            flags,
            size,
            offset,
            toExoPlayerCryptoData(trackIndex, cryptoInfo)
        )
    }

    // Private methods.
    private fun maybeObtainChunkIndex(mediaFormat: MediaFormat): Boolean {
        val chunkIndexSizesByteBuffer =
            mediaFormat.getByteBuffer(MEDIA_FORMAT_KEY_CHUNK_INDEX_SIZES) ?: return false
        
        val chunkIndexSizes = chunkIndexSizesByteBuffer.asIntBuffer()
        val chunkIndexOffsets =
            mediaFormat.getByteBuffer(MEDIA_FORMAT_KEY_CHUNK_INDEX_OFFSETS)!!.asLongBuffer()
        val chunkIndexDurationsUs =
            mediaFormat.getByteBuffer(MEDIA_FORMAT_KEY_CHUNK_INDEX_DURATIONS)!!.asLongBuffer()
        val chunkIndexTimesUs =
            mediaFormat.getByteBuffer(MEDIA_FORMAT_KEY_CHUNK_INDEX_TIMES)!!.asLongBuffer()
        val sizes = IntArray(chunkIndexSizes.remaining())
        val offsets = LongArray(chunkIndexOffsets.remaining())
        val durationsUs = LongArray(chunkIndexDurationsUs.remaining())
        val timesUs = LongArray(chunkIndexTimesUs.remaining())
        chunkIndexSizes.get(sizes)
        chunkIndexOffsets.get(offsets)
        chunkIndexDurationsUs.get(durationsUs)
        chunkIndexTimesUs.get(timesUs)
        this.chunkIndex = ChunkIndex(sizes, offsets, durationsUs, timesUs)
        extractorOutput.seekMap(this.chunkIndex!!)
        return true
    }

    private fun ensureSpaceForTrackIndex(trackIndex: Int) {
        for (i in trackOutputs.size..trackIndex) {
            trackOutputs.add(null)
            trackFormats.add(null)
            lastReceivedCryptoInfos.add(null)
            lastOutputCryptoDatas.add(null)
        }
    }

    private fun toExoPlayerCryptoData(
        trackIndex: Int,
        cryptoInfo: MediaCodec.CryptoInfo?
    ): TrackOutput.CryptoData? {
        if (cryptoInfo == null) {
            return null
        }

        val lastReceivedCryptoInfo = lastReceivedCryptoInfos[trackIndex]
        val cryptoDataToOutput: TrackOutput.CryptoData?
        // MediaParser keeps identity and value equality aligned for efficient comparison.
        if (lastReceivedCryptoInfo == cryptoInfo) {
            // They match, we can reuse the last one we created.
            cryptoDataToOutput = lastOutputCryptoDatas[trackIndex]!!
        } else {
            // They don't match, we create a new CryptoData.
            var encryptedBlocks: Int
            var clearBlocks: Int
            try {
                val matcher: Matcher = REGEX_CRYPTO_INFO_PATTERN.matcher(cryptoInfo.toString())
                matcher.find()
                encryptedBlocks = matcher.group(1)!!.toInt()
                clearBlocks = matcher.group(2)!!.toInt()
            } catch (e: RuntimeException) {
                // Should never happen.
                e(TAG, "Unexpected error while parsing CryptoInfo: $cryptoInfo", e)
                // Assume no-pattern encryption.
                encryptedBlocks = 0
                clearBlocks = 0
            }
            cryptoDataToOutput =
                TrackOutput.CryptoData(
                    cryptoInfo.mode,
                    cryptoInfo.key,
                    encryptedBlocks,
                    clearBlocks
                )
            lastReceivedCryptoInfos[trackIndex] = cryptoInfo
            lastOutputCryptoDatas[trackIndex] = cryptoDataToOutput
        }
        return cryptoDataToOutput
    }

    private fun maybeEndTracks() {
        if (!tracksFoundCalled || tracksEnded) {
            return
        }
        val size = trackOutputs.size
        for (i in 0..<size) {
            if (trackOutputs[i] == null) {
                return
            }
        }
        extractorOutput.endTracks()
        tracksEnded = true
    }

    private fun toExoPlayerFormat(trackData: TrackData): Format {
        val mediaFormat = trackData.mediaFormat
        val mediaFormatMimeType = mediaFormat.getString(MediaFormat.KEY_MIME)
        val mediaFormatAccessibilityChannel =
            mediaFormat.getInteger(
                MediaFormat.KEY_CAPTION_SERVICE_NUMBER, Format.NO_VALUE
            )
        val formatBuilder =
            Format.Builder()
                .setDrmInitData(
                    toExoPlayerDrmInitData(
                        mediaFormat.getString("crypto-mode-fourcc"), trackData.drmInitData
                    )
                )
                .setContainerMimeType(containerMimeType)
                .setPeakBitrate(
                    mediaFormat.getInteger(
                        MediaFormat.KEY_BIT_RATE, Format.NO_VALUE
                    )
                )
                .setChannelCount(
                    mediaFormat.getInteger(
                        MediaFormat.KEY_CHANNEL_COUNT, Format.NO_VALUE
                    )
                )
                .setColorInfo(MediaFormatUtil.getColorInfo(mediaFormat))
                .setSampleMimeType(mediaFormatMimeType)
                .setCodecs(mediaFormat.getString(MediaFormat.KEY_CODECS_STRING))
                .setFrameRate(
                    mediaFormat.getFloat(
                        MediaFormat.KEY_FRAME_RATE, Format.NO_VALUE.toFloat()
                    )
                )
                .setWidth(
                    mediaFormat.getInteger(
                        MediaFormat.KEY_WIDTH,
                        Format.NO_VALUE
                    )
                )
                .setHeight(
                    mediaFormat.getInteger(
                        MediaFormat.KEY_HEIGHT,
                        Format.NO_VALUE
                    )
                )
                .setInitializationData(getInitializationData(mediaFormat))
                .setLanguage(mediaFormat.getString(MediaFormat.KEY_LANGUAGE))
                .setMaxInputSize(
                    mediaFormat.getInteger(
                        MediaFormat.KEY_MAX_INPUT_SIZE, Format.NO_VALUE
                    )
                )
                .setPcmEncoding(
                    mediaFormat.getInteger("exo-pcm-encoding", Format.NO_VALUE)
                )
                .setRotationDegrees(
                    mediaFormat.getInteger(MediaFormat.KEY_ROTATION, 0)
                )
                .setSampleRate(
                    mediaFormat.getInteger(
                        MediaFormat.KEY_SAMPLE_RATE, Format.NO_VALUE
                    )
                )
                .setSelectionFlags(getSelectionFlags(mediaFormat))
                .setEncoderDelay(
                    mediaFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
                )
                .setEncoderPadding(
                    mediaFormat.getInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
                )
                .setPixelWidthHeightRatio(
                    mediaFormat.getFloat("pixel-width-height-ratio-float", 1f)
                )
                .setSubsampleOffsetUs(
                    mediaFormat.getLong(
                        "subsample-offset-us-long",
                        Format.OFFSET_SAMPLE_RELATIVE
                    )
                )
                .setAccessibilityChannel(mediaFormatAccessibilityChannel)
        for (i in muxedCaptionFormats.indices) {
            val muxedCaptionFormat = muxedCaptionFormats[i]
            if (muxedCaptionFormat.sampleMimeType == mediaFormatMimeType
                && muxedCaptionFormat.accessibilityChannel == mediaFormatAccessibilityChannel
            ) {
                // The track's format matches this muxedCaptionFormat, so we apply the manifest format
                // information to the track.
                formatBuilder
                    .setLanguage(muxedCaptionFormat.language)
                    .setRoleFlags(muxedCaptionFormat.roleFlags)
                    .setSelectionFlags(muxedCaptionFormat.selectionFlags)
                    .setLabel(muxedCaptionFormat.label)
                    .setLabels(muxedCaptionFormat.labels)
                    .setMetadata(muxedCaptionFormat.metadata)
                break
            }
        }
        return formatBuilder.build()
    }

    private class SeekMapAdapter(private val adaptedSeekMap: MediaParser.SeekMap) : SeekMap {
        override val isSeekable: Boolean
            get() = adaptedSeekMap.isSeekable

        override val durationUs: Long
            get() {
                val durationMicros = adaptedSeekMap.getDurationMicros()
                return if (durationMicros != MediaParser.SeekMap.UNKNOWN_DURATION.toLong()) durationMicros else C.TIME_UNSET
            }

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val seekPoints =
                adaptedSeekMap.getSeekPoints(timeUs)
            val exoPlayerSeekPoints: SeekMap.SeekPoints
            if (seekPoints.first === seekPoints.second) {
                exoPlayerSeekPoints =
                    SeekMap.SeekPoints(asExoPlayerSeekPoint(seekPoints.first!!))
            } else {
                exoPlayerSeekPoints =
                    SeekMap.SeekPoints(
                        asExoPlayerSeekPoint(seekPoints.first!!),
                        asExoPlayerSeekPoint(seekPoints.second!!)
                    )
            }
            return exoPlayerSeekPoints
        }

        companion object {
            private fun asExoPlayerSeekPoint(seekPoint: MediaParser.SeekPoint): SeekPoint {
                return SeekPoint(seekPoint.timeMicros, seekPoint.position)
            }
        }
    }

    private class DataReaderAdapter : DataReader {
        var input: InputReader? = null

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return input!!.read(buffer, offset, length)
        }
    }

    companion object {
        private const val TAG = "OConsumerAdapterV30"

        val SEEK_POINT_PAIR_START: Pair<MediaParser.SeekPoint, MediaParser.SeekPoint> =
            Pair.create<MediaParser.SeekPoint, MediaParser.SeekPoint>(
                MediaParser.SeekPoint.START,
                MediaParser.SeekPoint.START
            )
        private const val MEDIA_FORMAT_KEY_TRACK_TYPE = "track-type-string"
        private const val MEDIA_FORMAT_KEY_CHUNK_INDEX_SIZES = "chunk-index-int-sizes"
        private const val MEDIA_FORMAT_KEY_CHUNK_INDEX_OFFSETS = "chunk-index-long-offsets"
        private const val MEDIA_FORMAT_KEY_CHUNK_INDEX_DURATIONS = "chunk-index-long-us-durations"
        private const val MEDIA_FORMAT_KEY_CHUNK_INDEX_TIMES = "chunk-index-long-us-times"
        private val REGEX_CRYPTO_INFO_PATTERN: Pattern =
            Pattern.compile("pattern \\(encrypt: (\\d+), skip: (\\d+)\\)")

        private fun toTrackTypeConstant(string: String?): @C.TrackType Int {
            if (string == null) {
                return C.TRACK_TYPE_UNKNOWN
            }
            return when (string) {
                "audio" -> C.TRACK_TYPE_AUDIO
                "video" -> C.TRACK_TYPE_VIDEO
                "text" -> C.TRACK_TYPE_TEXT
                "metadata" -> C.TRACK_TYPE_METADATA
                "unknown" -> C.TRACK_TYPE_UNKNOWN
                else ->         // Must be a MIME type.
                    getTrackType(string)
            }
        }

        private fun toExoPlayerDrmInitData(
            schemeType: String?, drmInitData: android.media.DrmInitData?
        ): DrmInitData? {
            if (drmInitData == null) {
                return null
            }
            val schemeDatas =
                arrayOfNulls<DrmInitData.SchemeData>(drmInitData.schemeInitDataCount)
            for (i in schemeDatas.indices) {
                val schemeInitData = drmInitData.getSchemeInitDataAt(i)
                schemeDatas[i] =
                    DrmInitData.SchemeData(
                        schemeInitData.uuid,
                        schemeInitData.mimeType,
                        schemeInitData.data
                    )
            }
            return DrmInitData(schemeType, schemeDatas as Array<DrmInitData.SchemeData>)
        }

        private fun getSelectionFlags(mediaFormat: MediaFormat): @C.SelectionFlags Int {
            var selectionFlags = 0
            selectionFlags = selectionFlags or
                    getFlag(
                        mediaFormat,
                        MediaFormat.KEY_IS_AUTOSELECT,
                        C.SELECTION_FLAG_AUTOSELECT
                    )
            selectionFlags = selectionFlags or
                    getFlag(
                        mediaFormat,
                        MediaFormat.KEY_IS_DEFAULT,
                        C.SELECTION_FLAG_DEFAULT
                    )
            selectionFlags = selectionFlags or
                    getFlag(
                        mediaFormat,
                        MediaFormat.KEY_IS_FORCED_SUBTITLE,
                        C.SELECTION_FLAG_FORCED
                    )
            return selectionFlags
        }

        private fun getFlag(mediaFormat: MediaFormat, key: String, returnValueIfPresent: Int): Int {
            return if (mediaFormat.getInteger(
                    key,
                    0
                ) != 0
            ) returnValueIfPresent else 0
        }

        private fun getInitializationData(mediaFormat: MediaFormat): MutableList<ByteArray> {
            val initData = ArrayList<ByteArray>()
            var i = 0
            while (true) {
                val byteBuffer = mediaFormat.getByteBuffer("csd-" + i++) ?: break
                initData.add(MediaFormatUtil.getArray(byteBuffer))
            }
            return initData
        }

        private fun getMimeType(parserName: String): String {
            when (parserName) {
                MediaParser.PARSER_NAME_MATROSKA -> return MimeTypes.VIDEO_WEBM
                MediaParser.PARSER_NAME_FMP4, MediaParser.PARSER_NAME_MP4 -> return MimeTypes.VIDEO_MP4
                MediaParser.PARSER_NAME_MP3 -> return MimeTypes.AUDIO_MPEG
                MediaParser.PARSER_NAME_ADTS -> return MimeTypes.AUDIO_AAC
                MediaParser.PARSER_NAME_AC3 -> return MimeTypes.AUDIO_AC3
                MediaParser.PARSER_NAME_TS -> return MimeTypes.VIDEO_MP2T
                MediaParser.PARSER_NAME_FLV -> return MimeTypes.VIDEO_FLV
                MediaParser.PARSER_NAME_OGG -> return MimeTypes.AUDIO_OGG
                MediaParser.PARSER_NAME_PS -> return MimeTypes.VIDEO_PS
                MediaParser.PARSER_NAME_WAV -> return MimeTypes.AUDIO_RAW
                MediaParser.PARSER_NAME_AMR -> return MimeTypes.AUDIO_AMR
                MediaParser.PARSER_NAME_AC4 -> return MimeTypes.AUDIO_AC4
                MediaParser.PARSER_NAME_FLAC -> return MimeTypes.AUDIO_FLAC
                else -> throw IllegalArgumentException("Illegal parser name: $parserName")
            }
        }
    }
}
