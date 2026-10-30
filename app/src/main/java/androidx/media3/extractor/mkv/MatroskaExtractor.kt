package androidx.media3.extractor.mkv

import android.util.LongSparseArray
import android.util.Pair
import android.util.SparseArray
import androidx.annotation.CallSuper
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.common.util.WavUtil
import androidx.media3.container.DolbyVisionConfig
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.Av1Config
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.ChunkIndexProvider
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.HevcConfig
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.TrueHdSampleRechunker
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.metadata.ThumbnailMetadata
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Collections
import java.util.Objects
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import androidx.core.util.size

class MatroskaExtractor internal constructor(
    private val reader: EbmlReader,
    flags: @Flags Int,
    subtitleParserFactory: SubtitleParser.Factory
) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(flag = true, value = [FLAG_DISABLE_SEEK_FOR_CUES, FLAG_EMIT_RAW_SUBTITLE_DATA])
    annotation class Flags

    private val varintReader: VarintReader
    private val tracks: SparseArray<Track>
    private val chapters: LongSparseArray<ChapterEntry>
    private val seekForCuesEnabled: Boolean
    private val parseSubtitlesDuringExtraction: Boolean
    private val subtitleParserFactory: SubtitleParser.Factory

    // Temporary arrays.
    private val nalStartCode: ParsableByteArray
    private val nalLength: ParsableByteArray
    private val scratch: ParsableByteArray
    private val vorbisNumPageSamples: ParsableByteArray
    private val seekEntryIdBytes: ParsableByteArray
    private val sampleStrippedBytes: ParsableByteArray
    private val subtitleSample: ParsableByteArray
    private val encryptionInitializationVector: ParsableByteArray
    private val encryptionSubsampleData: ParsableByteArray
    private val supplementalData: ParsableByteArray
    private var encryptionSubsampleDataBuffer: ByteBuffer? = null

    private var segmentContentSize: Long = 0L
    private var segmentContentPosition = C.INDEX_UNSET.toLong()
    private var timecodeScale = C.TIME_UNSET
    private var durationTimecode = C.TIME_UNSET
    private var durationUs = C.TIME_UNSET
    private var isWebm = false
    private var pendingEndTracks: Boolean

    // The chapter corresponding to the current EditionEntry element, or null.
    private var currentChapter: ChapterEntry? = null

    // The track corresponding to the current TrackEntry element, or null.
    private var currentTrack: Track? = null

    // Whether a seek map has been sent to the output.
    private var sentSeekMap = false

    // Master seek entry related elements.
    private var seekEntryId = 0
    private var seekEntryPosition: Long = 0

    // Cue related elements.
    private val perTrackCues: SparseArray<MutableList<MatroskaSeekMap.CuePointData>?>
    private var inCuesElement = false
    private var currentCueTimeUs = C.TIME_UNSET
    private var currentCueTrackNumber = C.INDEX_UNSET
    private var currentCueClusterPosition = C.INDEX_UNSET.toLong()
    private var currentCueRelativePosition = C.INDEX_UNSET.toLong()
    private var primarySeekTrackNumber = C.INDEX_UNSET
    private var seekForCues = false
    private var cuesContentPosition = C.INDEX_UNSET.toLong()
    private var seekPositionAfterBuildingCues = C.INDEX_UNSET.toLong()
    private var tracksContentPosition = C.INDEX_UNSET.toLong()
    private var seekForTracks = false
    private var seekPositionAfterReadingTracks = C.INDEX_UNSET.toLong()
    private var readTracks = false
    private var clusterTimecodeUs = C.TIME_UNSET

    // Reading state.
    private var haveOutputSample = false

    // Block reading state.
    private var blockState = 0
    private var blockTimeUs: Long = 0
    private var blockDurationUs: Long = 0
    private var blockSampleIndex = 0
    private var blockSampleCount = 0
    private var blockSampleSizes: IntArray
    private var blockTrackNumber = 0
    private var blockTrackNumberLength = 0
    private var blockFlags: @C.BufferFlags Int = 0
    private var blockAdditionalId = 0
    private var blockHasReferenceBlock = false
    private var blockGroupDiscardPaddingNs: Long = 0

    // Sample writing state.
    private var sampleBytesRead = 0
    private var sampleBytesWritten = 0
    private var sampleCurrentNalBytesRemaining = 0
    private var sampleEncodingHandled = false
    private var sampleSignalByteRead = false
    private var samplePartitionCountRead = false
    private var samplePartitionCount = 0
    private var sampleSignalByte: Byte = 0
    private var sampleInitializationVectorRead = false

    // Extractor outputs.
    private lateinit var extractorOutput: ExtractorOutput

    @Deprecated("Use {@link #MatroskaExtractor(SubtitleParser.Factory)} instead.")
    constructor() : this(
        DefaultEbmlReader(),
        FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED
    )

    @Deprecated("Use {@link #MatroskaExtractor(SubtitleParser.Factory, int)} instead.")
    constructor(flags: @Flags Int) : this(
        DefaultEbmlReader(),
        flags or FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED
    )

    constructor(subtitleParserFactory: SubtitleParser.Factory?) : this(
        DefaultEbmlReader(),
        0,
        subtitleParserFactory!!
    )

    constructor(subtitleParserFactory: SubtitleParser.Factory?, flags: @Flags Int) : this(
        DefaultEbmlReader(),
        flags,
        subtitleParserFactory!!
    )

    init {
        this.reader.init(InnerEbmlProcessor())
        this.subtitleParserFactory = subtitleParserFactory
        this.perTrackCues = SparseArray<MutableList<MatroskaSeekMap.CuePointData>?>()
        seekForCuesEnabled = (flags and FLAG_DISABLE_SEEK_FOR_CUES) == 0
        parseSubtitlesDuringExtraction = (flags and FLAG_EMIT_RAW_SUBTITLE_DATA) == 0
        varintReader = VarintReader()
        chapters = LongSparseArray<ChapterEntry>()
        tracks = SparseArray<Track>()
        scratch = ParsableByteArray(4)
        vorbisNumPageSamples = ParsableByteArray(ByteBuffer.allocate(4).putInt(-1).array())
        seekEntryIdBytes = ParsableByteArray(4)
        nalStartCode = ParsableByteArray(NalUnitUtil.NAL_START_CODE)
        nalLength = ParsableByteArray(4)
        sampleStrippedBytes = ParsableByteArray()
        subtitleSample = ParsableByteArray()
        encryptionInitializationVector = ParsableByteArray(ENCRYPTION_IV_SIZE)
        encryptionSubsampleData = ParsableByteArray()
        supplementalData = ParsableByteArray()
        blockSampleSizes = IntArray(1)
        pendingEndTracks = true
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return Sniffer().sniff(input)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput =
            if (parseSubtitlesDuringExtraction)
                SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            else
                output
    }

    @CallSuper
    override fun seek(position: Long, timeUs: Long) {
        clusterTimecodeUs = C.TIME_UNSET
        blockState = BLOCK_STATE_START
        reader.reset()
        varintReader.reset()
        resetWriteSampleData()
        inCuesElement = false
        currentCueTimeUs = C.TIME_UNSET
        currentCueTrackNumber = C.INDEX_UNSET
        currentCueClusterPosition = C.INDEX_UNSET.toLong()
        currentCueRelativePosition = C.INDEX_UNSET.toLong()
        // To prevent creating duplicate cue points on a re-parse, clear any existing cue data if the
        // seek map has not yet been sent. Once sent, the cue data is considered final, and subsequent
        // Cues elements will be ignored by the parsing logic.
        if (!sentSeekMap) {
            perTrackCues.clear()
        }
        for (i in 0..<tracks.size()) {
            tracks.valueAt(i).reset()
        }
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        haveOutputSample = false
        var continueReading = true
        while (continueReading && !haveOutputSample) {
            continueReading = reader.read(input)
            if (continueReading
                && (maybeSeekForTracks(seekPosition, input.position)
                        || maybeSeekForCues(seekPosition, input.position))
            ) {
                return Extractor.RESULT_SEEK
            }
        }
        if (!continueReading) {
            for (i in 0..<tracks.size()) {
                val track = tracks.valueAt(i)
                track.assertOutputInitialized()
                track.outputPendingSampleMetadata()
            }
            return Extractor.RESULT_END_OF_INPUT
        }
        return Extractor.RESULT_CONTINUE
    }

    @CallSuper
    protected fun getElementType(id: Int): @EbmlProcessor.ElementType Int {
        when (id) {
            ID_EBML, ID_SEGMENT, ID_CHAPTERS, ID_EDITION_ENTRY, ID_CHAPTER_ATOM, ID_CHAPTER_TRACK, ID_CHAPTER_DISPLAY, ID_SEEK_HEAD, ID_SEEK, ID_INFO, ID_CLUSTER, ID_TRACKS, ID_TRACK_ENTRY, ID_BLOCK_ADDITION_MAPPING, ID_AUDIO, ID_VIDEO, ID_CONTENT_ENCODINGS, ID_CONTENT_ENCODING, ID_CONTENT_COMPRESSION, ID_CONTENT_ENCRYPTION, ID_CONTENT_ENCRYPTION_AES_SETTINGS, ID_CUES, ID_CUE_POINT, ID_CUE_TRACK_POSITIONS, ID_BLOCK_GROUP, ID_BLOCK_ADDITIONS, ID_BLOCK_MORE, ID_PROJECTION, ID_COLOUR, ID_MASTERING_METADATA -> return EbmlProcessor.ELEMENT_TYPE_MASTER
            ID_EBML_READ_VERSION, ID_DOC_TYPE_READ_VERSION, ID_SEEK_POSITION, ID_TIMECODE_SCALE, ID_TIME_CODE, ID_BLOCK_DURATION, ID_PIXEL_WIDTH, ID_PIXEL_HEIGHT, ID_DISPLAY_WIDTH, ID_DISPLAY_HEIGHT, ID_DISPLAY_UNIT, ID_TRACK_NUMBER, ID_TRACK_UID, ID_TRACK_TYPE, ID_CHAPTER_FLAG_HIDDEN, ID_CHAPTER_TIME_START, ID_CHAPTER_TIME_END, ID_CHAPTER_UID, ID_CHAPTER_TRACK_UID, ID_FLAG_DEFAULT, ID_FLAG_FORCED, ID_DEFAULT_DURATION, ID_MAX_BLOCK_ADDITION_ID, ID_BLOCK_ADD_ID_TYPE, ID_CODEC_DELAY, ID_SEEK_PRE_ROLL, ID_DISCARD_PADDING, ID_CHANNELS, ID_AUDIO_BIT_DEPTH, ID_CONTENT_ENCODING_ORDER, ID_CONTENT_ENCODING_SCOPE, ID_CONTENT_COMPRESSION_ALGORITHM, ID_CONTENT_ENCRYPTION_ALGORITHM, ID_CONTENT_ENCRYPTION_AES_SETTINGS_CIPHER_MODE, ID_CUE_TIME, ID_CUE_CLUSTER_POSITION, ID_CUE_RELATIVE_POSITION, ID_CUE_TRACK, ID_REFERENCE_BLOCK, ID_STEREO_MODE, ID_COLOUR_BITS_PER_CHANNEL, ID_COLOUR_RANGE, ID_COLOUR_TRANSFER, ID_COLOUR_PRIMARIES, ID_MAX_CLL, ID_MAX_FALL, ID_PROJECTION_TYPE, ID_BLOCK_ADD_ID -> return EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT

            ID_DOC_TYPE, ID_NAME, ID_CODEC_ID, ID_LANGUAGE, ID_CHAP_STRING, ID_CHAP_LANGUAGE -> return EbmlProcessor.ELEMENT_TYPE_STRING
            ID_SEEK_ID, ID_BLOCK_ADD_ID_EXTRA_DATA, ID_CONTENT_COMPRESSION_SETTINGS, ID_CONTENT_ENCRYPTION_KEY_ID, ID_SIMPLE_BLOCK, ID_BLOCK, ID_CODEC_PRIVATE, ID_PROJECTION_PRIVATE, ID_BLOCK_ADDITIONAL -> return EbmlProcessor.ELEMENT_TYPE_BINARY
            ID_DURATION, ID_SAMPLING_FREQUENCY, ID_PRIMARY_R_CHROMATICITY_X, ID_PRIMARY_R_CHROMATICITY_Y, ID_PRIMARY_G_CHROMATICITY_X, ID_PRIMARY_G_CHROMATICITY_Y, ID_PRIMARY_B_CHROMATICITY_X, ID_PRIMARY_B_CHROMATICITY_Y, ID_WHITE_POINT_CHROMATICITY_X, ID_WHITE_POINT_CHROMATICITY_Y, ID_LUMNINANCE_MAX, ID_LUMNINANCE_MIN, ID_PROJECTION_POSE_YAW, ID_PROJECTION_POSE_PITCH, ID_PROJECTION_POSE_ROLL -> return EbmlProcessor.ELEMENT_TYPE_FLOAT
            else -> return EbmlProcessor.ELEMENT_TYPE_UNKNOWN
        }
    }

    @CallSuper
    protected fun isLevel1Element(id: Int): Boolean {
        return id == ID_SEGMENT_INFO || id == ID_CHAPTERS || id == ID_CLUSTER || id == ID_CUES || id == ID_TRACKS
    }

    @CallSuper
    @Throws(ParserException::class)
    protected fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        assertInitialized()
        when (id) {
            ID_SEGMENT -> {
                if (segmentContentPosition != C.INDEX_UNSET.toLong() && segmentContentPosition != contentPosition) {
                    throw ParserException.createForMalformedContainer(
                        "Multiple Segment elements not supported", null
                    )
                }
                segmentContentPosition = contentPosition
                segmentContentSize = contentSize
            }

            ID_SEEK -> {
                seekEntryId = UNSET_ENTRY_ID
                seekEntryPosition = C.INDEX_UNSET.toLong()
            }

            ID_CUES -> if (!sentSeekMap) {
                inCuesElement = true
            }

            ID_CUE_POINT -> if (!sentSeekMap) {
                assertInCues(id)
                currentCueTimeUs = C.TIME_UNSET
            }

            ID_CUE_TRACK_POSITIONS -> if (!sentSeekMap) {
                assertInCues(id)
                currentCueTrackNumber = C.INDEX_UNSET
                currentCueClusterPosition = C.INDEX_UNSET.toLong()
                currentCueRelativePosition = C.INDEX_UNSET.toLong()
            }

            ID_CLUSTER -> {
                if (tracksContentPosition != C.INDEX_UNSET.toLong() && !readTracks) {
                    seekForTracks = true
                }
                if (!sentSeekMap) {
                    // We need to build cues before parsing the cluster.
                    if (seekForCuesEnabled && cuesContentPosition != C.INDEX_UNSET.toLong()) {
                        // We know where the Cues element is located. Seek to request it.
                        seekForCues = true
                    } else {
                        // We don't know where the Cues element is located. It's most likely omitted. Allow
                        // playback, but disable seeking.
                        extractorOutput.seekMap(SeekMap.Unseekable(durationUs))
                        sentSeekMap = true
                    }
                }
            }

            ID_BLOCK_GROUP -> {
                blockHasReferenceBlock = false
                blockGroupDiscardPaddingNs = 0L
            }

            ID_CONTENT_ENCODING -> {}
            ID_CONTENT_ENCRYPTION -> getCurrentTrack(id)!!.hasContentEncryption = true
            ID_CHAPTER_ATOM -> currentChapter = ChapterEntry()
            ID_CHAPTER_DISPLAY -> {
                getCurrentChapter(id)!!.currentDisplayString = null
                getCurrentChapter(id)!!.currentDisplayLanguage = null
            }

            ID_TRACK_ENTRY -> {
                currentTrack = Track()
                currentTrack!!.isWebm = isWebm
            }

            else -> {}
        }
    }

    @CallSuper
    @Throws(ParserException::class)
    protected fun endMasterElement(id: Int) {
        assertInitialized()
        when (id) {
            ID_SEGMENT_INFO -> {
                if (timecodeScale == C.TIME_UNSET) {
                    // timecodeScale was omitted. Use the default value.
                    timecodeScale = 1000000
                }
                if (durationTimecode != C.TIME_UNSET) {
                    durationUs = scaleTimecodeToUs(durationTimecode)
                }
            }

            ID_SEEK -> {
                if (seekEntryId == UNSET_ENTRY_ID || seekEntryPosition == C.INDEX_UNSET.toLong()) {
                    throw ParserException.createForMalformedContainer(
                        "Mandatory element SeekID or SeekPosition not found", null
                    )
                }
                if (seekEntryId == ID_CUES) {
                    cuesContentPosition = seekEntryPosition
                } else if (seekEntryId == ID_TRACKS) {
                    tracksContentPosition = seekEntryPosition
                }
            }

            ID_CUES -> if (!sentSeekMap) {
                var hasAnyCues = false
                run {
                    var i = 0
                    while (i < perTrackCues.size()) {
                        if (!perTrackCues.valueAt(i)!!.isEmpty()) {
                            hasAnyCues = true
                            break
                        }
                        i++
                    }
                }
                if (!hasAnyCues || durationUs == C.TIME_UNSET) {
                    // Cues are missing, empty, or duration is unknown.
                    extractorOutput.seekMap(SeekMap.Unseekable(durationUs))
                } else {
                    var i = 0
                    while (i < perTrackCues.size()) {
                        Collections.sort<MatroskaSeekMap.CuePointData?>(perTrackCues.valueAt(i))
                        i++
                    }
                    val seekMap =
                        MatroskaSeekMap(
                            perTrackCues,
                            durationUs,
                            primarySeekTrackNumber,
                            segmentContentPosition,
                            segmentContentSize
                        )
                    extractorOutput.seekMap(seekMap)
                }
                sentSeekMap = true
                inCuesElement = false
                var i = 0
                while (i < tracks.size()) {
                    val track = tracks.valueAt(i)
                    if (!track.waitingForDtsAnalysis) {
                        updateTrackFormatWithMetadata(track)
                        track.assertOutputInitialized()
                        track.output?.format(track.format!!)
                    }
                    i++
                }
                maybeEndTracks()
            }

            ID_CUE_TRACK_POSITIONS -> if (!sentSeekMap) {
                assertInCues(id)
                if (currentCueTimeUs != C.TIME_UNSET && currentCueTrackNumber != C.INDEX_UNSET && currentCueClusterPosition != C.INDEX_UNSET.toLong()) {
                    var trackCues: MutableList<MatroskaSeekMap.CuePointData>? =
                        perTrackCues.get(currentCueTrackNumber)
                    if (trackCues == null) {
                        trackCues = ArrayList<MatroskaSeekMap.CuePointData>()
                        perTrackCues.put(currentCueTrackNumber, trackCues)
                    }
                    trackCues.add(
                        MatroskaSeekMap.CuePointData(
                            currentCueTimeUs,
                            segmentContentPosition + currentCueClusterPosition,
                            currentCueRelativePosition
                        )
                    )
                }
            }

            ID_CHAPTER_ATOM -> {
                val chapter = currentChapter!!
                if (chapter.uid != 0L) {
                    chapters.put(chapter.uid, chapter)
                }
                currentChapter = null
            }

            ID_CHAPTER_DISPLAY -> {
                val chapterEntry = currentChapter!!
                if (chapterEntry.chapString == null && chapterEntry.currentDisplayString != null) {
                    chapterEntry.chapString = chapterEntry.currentDisplayString
                    if (chapterEntry.currentDisplayLanguage != null) {
                        chapterEntry.chapLanguage = chapterEntry.currentDisplayLanguage
                    }
                }
            }

            ID_EDITION_ENTRY -> {
                var i = 0
                while (i < tracks.size()) {
                    updateTrackFormatWithMetadata(tracks.valueAt(i))
                    i++
                }
            }

            ID_BLOCK_GROUP -> {
                if (blockState != BLOCK_STATE_DATA) {
                    // We've skipped this block (due to incompatible track number).
                    return
                }
                val track = tracks.get(blockTrackNumber)
                track.assertOutputInitialized()
                if (blockGroupDiscardPaddingNs > 0L && CODEC_ID_OPUS == track.codecId) {
                    // For Opus, attach DiscardPadding to the block group samples as supplemental data.
                    supplementalData.reset(
                        ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putLong(blockGroupDiscardPaddingNs)
                            .array()
                    )
                }

                // Commit sample metadata.
                var sampleOffset = 0
                run {
                    var i = 0
                    while (i < blockSampleCount) {
                        sampleOffset += blockSampleSizes[i]
                        i++
                    }
                }
                var i = 0
                while (i < blockSampleCount) {
                    val sampleTimeUs = blockTimeUs + (i * track.defaultSampleDurationNs) / 1000
                    var sampleFlags = blockFlags
                    if (i == 0 && !blockHasReferenceBlock) {
                        // If the ReferenceBlock element was not found in this block, then the first frame is a
                        // keyframe.
                        sampleFlags = sampleFlags or C.BUFFER_FLAG_KEY_FRAME
                    }
                    val sampleSize = blockSampleSizes[i]
                    sampleOffset -= sampleSize // The offset is to the end of the sample.
                    commitSampleToOutput(track, sampleTimeUs, sampleFlags, sampleSize, sampleOffset)
                    i++
                }
                blockState = BLOCK_STATE_START
            }

            ID_CONTENT_ENCODING -> {
                assertInTrackEntry(id)
                if (currentTrack!!.hasContentEncryption) {
                    if (currentTrack!!.cryptoData == null) {
                        throw ParserException.createForMalformedContainer(
                            "Encrypted Track found but ContentEncKeyID was not found",
                            null
                        )
                    }
                    currentTrack!!.drmInitData =
                        DrmInitData(
                            mutableListOf(
                                DrmInitData.SchemeData(
                                    C.UUID_NIL,
                                    MimeTypes.VIDEO_WEBM,
                                    currentTrack!!.cryptoData!!.encryptionKey
                                )
                            )
                        )
                }
            }

            ID_CONTENT_ENCODINGS -> {
                assertInTrackEntry(id)
                if (currentTrack!!.hasContentEncryption && currentTrack!!.sampleStrippedBytes != null) {
                    throw ParserException.createForMalformedContainer(
                        "Combining encryption and compression is not supported", null
                    )
                }
            }

            ID_TRACK_ENTRY -> {
                val currentTrack = this.currentTrack!!
                if (currentTrack.codecId == null) {
                    throw ParserException.createForMalformedContainer(
                        "CodecId is missing in TrackEntry element", null
                    )
                } else {
                    if (Companion.isCodecSupported(currentTrack.codecId!!)) {
                        currentTrack.initializeFormat(currentTrack.number)
                        currentTrack.output =
                            extractorOutput.track(currentTrack.number, currentTrack.type)
                        tracks.put(currentTrack.number, currentTrack)
                    }
                }
                this.currentTrack = null
            }

            ID_TRACKS -> {
                if (tracks.size() == 0) {
                    throw ParserException.createForMalformedContainer(
                        "No valid tracks were found", null
                    )
                }

                // Determine the track to use for default seeking.
                var defaultVideoTrackNumber = C.INDEX_UNSET
                var firstVideoTrackNumber = C.INDEX_UNSET
                var defaultAudioTrackNumber = C.INDEX_UNSET
                var firstAudioTrackNumber = C.INDEX_UNSET

                // If we're not going to seek for cues, or we have already processed them, output the
                // formats immediately.
                val maybeSendFormatsEarly =
                    !seekForCuesEnabled || cuesContentPosition == C.INDEX_UNSET.toLong() || sentSeekMap

                var i = 0
                while (i < tracks.size()) {
                    val trackItem = tracks.valueAt(i)

                    val trackType: @C.TrackType Int = trackItem.type
                    if (trackType == C.TRACK_TYPE_VIDEO) {
                        if (trackItem.flagDefault) {
                            defaultVideoTrackNumber = trackItem.number
                        }
                        if (firstVideoTrackNumber == C.INDEX_UNSET) {
                            firstVideoTrackNumber = trackItem.number
                        }
                    } else if (trackType == C.TRACK_TYPE_AUDIO) {
                        if (trackItem.flagDefault) {
                            defaultAudioTrackNumber = trackItem.number
                        }
                        if (firstAudioTrackNumber == C.INDEX_UNSET) {
                            firstAudioTrackNumber = trackItem.number
                        }
                    }

                    if (maybeSendFormatsEarly) {
                        if (!trackItem.waitingForDtsAnalysis) {
                            updateTrackFormatWithMetadata(trackItem)
                            trackItem.assertOutputInitialized()
                            trackItem.output?.format(trackItem.format!!)
                        }
                    }
                    i++
                }

                if (defaultVideoTrackNumber != C.INDEX_UNSET) {
                    primarySeekTrackNumber = defaultVideoTrackNumber
                } else if (firstVideoTrackNumber != C.INDEX_UNSET) {
                    primarySeekTrackNumber = firstVideoTrackNumber
                } else if (defaultAudioTrackNumber != C.INDEX_UNSET) {
                    primarySeekTrackNumber = defaultAudioTrackNumber
                } else if (firstAudioTrackNumber != C.INDEX_UNSET) {
                    primarySeekTrackNumber = firstAudioTrackNumber
                } else {
                    primarySeekTrackNumber =
                        if (tracks.size() > 0) tracks.valueAt(0).number else C.INDEX_UNSET
                }

                readTracks = true
                if (maybeSendFormatsEarly) {
                    maybeEndTracks()
                }
            }

            else -> {}
        }
    }

    @CallSuper
    @Throws(ParserException::class)
    protected fun integerElement(id: Int, value: Long) {
        when (id) {
            ID_EBML_READ_VERSION ->         // Validate that EBMLReadVersion is supported. This extractor only supports v1.
                if (value != 1L) {
                    throw ParserException.createForMalformedContainer(
                        "EBMLReadVersion " + value + " not supported", null
                    )
                }

            ID_DOC_TYPE_READ_VERSION ->         // Validate that DocTypeReadVersion is supported. This extractor only supports up to v2.
                if (value < 1 || value > 2) {
                    throw ParserException.createForMalformedContainer(
                        "DocTypeReadVersion " + value + " not supported", null
                    )
                }

            ID_SEEK_POSITION ->         // Seek Position is the relative offset beginning from the Segment. So to get absolute
                // offset from the beginning of the file, we need to add segmentContentPosition to it.
                seekEntryPosition = value + segmentContentPosition

            ID_TIMECODE_SCALE -> timecodeScale = value
            ID_CHAPTER_UID -> getCurrentChapter(id)!!.uid = value
            ID_CHAPTER_TIME_START -> getCurrentChapter(id)!!.timeStartNs = value
            ID_CHAPTER_TIME_END -> getCurrentChapter(id)!!.timeEndNs = value
            ID_CHAPTER_FLAG_HIDDEN -> getCurrentChapter(id)!!.flagHidden = value == 1L
            ID_CHAPTER_TRACK_UID -> getCurrentChapter(id)!!.trackUid = value
            ID_PIXEL_WIDTH -> getCurrentTrack(id)!!.width = value.toInt()
            ID_PIXEL_HEIGHT -> getCurrentTrack(id)!!.height = value.toInt()
            ID_DISPLAY_WIDTH -> getCurrentTrack(id)!!.displayWidth = value.toInt()
            ID_DISPLAY_HEIGHT -> getCurrentTrack(id)!!.displayHeight = value.toInt()
            ID_DISPLAY_UNIT -> getCurrentTrack(id)!!.displayUnit = value.toInt()
            ID_TRACK_NUMBER -> getCurrentTrack(id)!!.number = value.toInt()
            ID_TRACK_UID -> getCurrentTrack(id)!!.uid = value
            ID_FLAG_DEFAULT -> getCurrentTrack(id)!!.flagDefault = value == 1L
            ID_FLAG_FORCED -> getCurrentTrack(id)!!.flagForced = value == 1L
            ID_TRACK_TYPE -> {
                val matroskaTrackType = value.toInt()
                when (matroskaTrackType) {
                    1 -> getCurrentTrack(id)!!.type = C.TRACK_TYPE_VIDEO
                    2 -> getCurrentTrack(id)!!.type = C.TRACK_TYPE_AUDIO
                    17 -> getCurrentTrack(id)!!.type = C.TRACK_TYPE_TEXT
                    33 -> getCurrentTrack(id)!!.type = C.TRACK_TYPE_METADATA
                    else -> getCurrentTrack(id)!!.type = C.TRACK_TYPE_UNKNOWN
                }
            }

            ID_DEFAULT_DURATION -> getCurrentTrack(id)!!.defaultSampleDurationNs = value.toInt()
            ID_MAX_BLOCK_ADDITION_ID -> getCurrentTrack(id)!!.maxBlockAdditionId = value.toInt()
            ID_BLOCK_ADD_ID_TYPE -> getCurrentTrack(id)?.blockAddIdType = value.toInt()
            ID_CODEC_DELAY -> getCurrentTrack(id)!!.codecDelayNs = value
            ID_SEEK_PRE_ROLL -> getCurrentTrack(id)!!.seekPreRollNs = value
            ID_DISCARD_PADDING -> blockGroupDiscardPaddingNs = value
            ID_CHANNELS -> getCurrentTrack(id)!!.channelCount = value.toInt()
            ID_AUDIO_BIT_DEPTH -> getCurrentTrack(id)!!.audioBitDepth = value.toInt()
            ID_REFERENCE_BLOCK -> blockHasReferenceBlock = true
            ID_CONTENT_ENCODING_ORDER ->         // This extractor only supports one ContentEncoding element and hence the order has to be 0.
                if (value != 0L) {
                    throw ParserException.createForMalformedContainer(
                        "ContentEncodingOrder " + value + " not supported", null
                    )
                }

            ID_CONTENT_ENCODING_SCOPE ->         // This extractor only supports the scope of all frames.
                if (value != 1L) {
                    throw ParserException.createForMalformedContainer(
                        "ContentEncodingScope " + value + " not supported", null
                    )
                }

            ID_CONTENT_COMPRESSION_ALGORITHM ->         // This extractor only supports header stripping.
                if (value != 3L) {
                    throw ParserException.createForMalformedContainer(
                        "ContentCompAlgo " + value + " not supported", null
                    )
                }

            ID_CONTENT_ENCRYPTION_ALGORITHM ->         // Only the value 5 (AES) is allowed according to the WebM specification.
                if (value != 5L) {
                    throw ParserException.createForMalformedContainer(
                        "ContentEncAlgo " + value + " not supported", null
                    )
                }

            ID_CONTENT_ENCRYPTION_AES_SETTINGS_CIPHER_MODE ->         // Only the value 1 is allowed according to the WebM specification.
                if (value != 1L) {
                    throw ParserException.createForMalformedContainer(
                        "AESSettingsCipherMode " + value + " not supported", null
                    )
                }

            ID_CUE_TIME -> if (!sentSeekMap) {
                assertInCues(id)
                currentCueTimeUs = scaleTimecodeToUs(value)
            }

            ID_CUE_TRACK -> if (!sentSeekMap) {
                assertInCues(id)
                currentCueTrackNumber = value.toInt()
            }

            ID_CUE_CLUSTER_POSITION -> if (!sentSeekMap) {
                assertInCues(id)
                if (currentCueClusterPosition == C.INDEX_UNSET.toLong()) {
                    currentCueClusterPosition = value
                }
            }

            ID_CUE_RELATIVE_POSITION -> if (!sentSeekMap) {
                assertInCues(id)
                if (currentCueRelativePosition == C.INDEX_UNSET.toLong()) {
                    currentCueRelativePosition = value
                }
            }

            ID_TIME_CODE -> clusterTimecodeUs = scaleTimecodeToUs(value)
            ID_BLOCK_DURATION -> blockDurationUs = scaleTimecodeToUs(value)
            ID_STEREO_MODE -> {
                val layout = value.toInt()
                assertInTrackEntry(id)
                when (layout) {
                    0 -> currentTrack!!.stereoMode = C.STEREO_MODE_MONO
                    1 -> currentTrack!!.stereoMode = C.STEREO_MODE_LEFT_RIGHT
                    3 -> currentTrack!!.stereoMode = C.STEREO_MODE_TOP_BOTTOM
                    15 -> currentTrack!!.stereoMode = C.STEREO_MODE_STEREO_MESH
                    else -> {}
                }
            }

            ID_COLOUR_PRIMARIES -> {
                assertInTrackEntry(id)
                val colorSpace: Int = ColorInfo.isoColorPrimariesToColorSpace(value.toInt())
                if (colorSpace != Format.NO_VALUE) {
                    currentTrack!!.containerColorSpace = colorSpace
                }
            }

            ID_COLOUR_TRANSFER -> {
                assertInTrackEntry(id)
                val colorTransfer: Int =
                    ColorInfo.isoTransferCharacteristicsToColorTransfer(value.toInt())
                if (colorTransfer != Format.NO_VALUE) {
                    currentTrack!!.containerColorTransfer = colorTransfer
                }
            }

            ID_COLOUR_BITS_PER_CHANNEL -> {
                assertInTrackEntry(id)
                currentTrack!!.bitsPerChannel = value.toInt()
            }

            ID_COLOUR_RANGE -> {
                assertInTrackEntry(id)
                when (value.toInt()) {
                    1 -> currentTrack!!.containerColorRange = C.COLOR_RANGE_LIMITED
                    2 -> currentTrack!!.containerColorRange = C.COLOR_RANGE_FULL
                    else -> {}
                }
            }

            ID_MAX_CLL -> getCurrentTrack(id)!!.maxContentLuminance = value.toInt()
            ID_MAX_FALL -> getCurrentTrack(id)!!.maxFrameAverageLuminance = value.toInt()
            ID_PROJECTION_TYPE -> {
                assertInTrackEntry(id)
                when (value.toInt()) {
                    0 -> currentTrack!!.projectionType = C.PROJECTION_RECTANGULAR
                    1 -> currentTrack!!.projectionType = C.PROJECTION_EQUIRECTANGULAR
                    2 -> currentTrack!!.projectionType = C.PROJECTION_CUBEMAP
                    3 -> currentTrack!!.projectionType = C.PROJECTION_MESH
                    else -> {}
                }
            }

            ID_BLOCK_ADD_ID -> blockAdditionalId = value.toInt()
            else -> {}
        }
    }

    @CallSuper
    @Throws(ParserException::class)
    protected fun floatElement(id: Int, value: Double) {
        when (id) {
            ID_DURATION -> durationTimecode = value.toLong()
            ID_SAMPLING_FREQUENCY -> getCurrentTrack(id)!!.sampleRate = value.toInt()
            ID_PRIMARY_R_CHROMATICITY_X -> getCurrentTrack(id)!!.primaryRChromaticityX =
                value.toFloat()

            ID_PRIMARY_R_CHROMATICITY_Y -> getCurrentTrack(id)!!.primaryRChromaticityY =
                value.toFloat()

            ID_PRIMARY_G_CHROMATICITY_X -> getCurrentTrack(id)!!.primaryGChromaticityX =
                value.toFloat()

            ID_PRIMARY_G_CHROMATICITY_Y -> getCurrentTrack(id)!!.primaryGChromaticityY =
                value.toFloat()

            ID_PRIMARY_B_CHROMATICITY_X -> getCurrentTrack(id)!!.primaryBChromaticityX =
                value.toFloat()

            ID_PRIMARY_B_CHROMATICITY_Y -> getCurrentTrack(id)!!.primaryBChromaticityY =
                value.toFloat()

            ID_WHITE_POINT_CHROMATICITY_X -> getCurrentTrack(id)!!.whitePointChromaticityX =
                value.toFloat()

            ID_WHITE_POINT_CHROMATICITY_Y -> getCurrentTrack(id)!!.whitePointChromaticityY =
                value.toFloat()

            ID_LUMNINANCE_MAX -> getCurrentTrack(id)!!.maxMasteringLuminance = value.toFloat()
            ID_LUMNINANCE_MIN -> getCurrentTrack(id)!!.minMasteringLuminance = value.toFloat()
            ID_PROJECTION_POSE_YAW -> getCurrentTrack(id)!!.projectionPoseYaw = value.toFloat()
            ID_PROJECTION_POSE_PITCH -> getCurrentTrack(id)!!.projectionPosePitch = value.toFloat()
            ID_PROJECTION_POSE_ROLL -> getCurrentTrack(id)!!.projectionPoseRoll = value.toFloat()
            else -> {}
        }
    }

    @CallSuper
    @Throws(ParserException::class)
    protected fun stringElement(id: Int, value: String) {
        when (id) {
            ID_DOC_TYPE -> {
                // Validate that DocType is supported.
                if (DOC_TYPE_WEBM != value && DOC_TYPE_MATROSKA != value) {
                    throw ParserException.createForMalformedContainer(
                        "DocType " + value + " not supported", null
                    )
                }
                isWebm = value == DOC_TYPE_WEBM
            }

            ID_CHAP_STRING -> getCurrentChapter(id)!!.currentDisplayString = value
            ID_CHAP_LANGUAGE -> getCurrentChapter(id)!!.currentDisplayLanguage = value
            ID_NAME -> getCurrentTrack(id)!!.name = value
            ID_CODEC_ID -> getCurrentTrack(id)!!.codecId = value
            ID_LANGUAGE -> getCurrentTrack(id)?.language = value
            else -> {}
        }
    }

    @CallSuper
    @Throws(IOException::class)
    protected fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        when (id) {
            ID_SEEK_ID -> {
                Arrays.fill(seekEntryIdBytes.data, 0.toByte())
                input.readFully(seekEntryIdBytes.data, 4 - contentSize, contentSize)
                seekEntryIdBytes.setPosition(0)
                seekEntryId = seekEntryIdBytes.readUnsignedInt().toInt()
            }

            ID_BLOCK_ADD_ID_EXTRA_DATA -> handleBlockAddIDExtraData(
                getCurrentTrack(id)!!,
                input,
                contentSize
            )

            ID_CODEC_PRIVATE -> {
                assertInTrackEntry(id)
                currentTrack!!.codecPrivate = ByteArray(contentSize)
                input.readFully(currentTrack!!.codecPrivate!!, 0, contentSize)
            }

            ID_PROJECTION_PRIVATE -> {
                assertInTrackEntry(id)
                currentTrack!!.projectionData = ByteArray(contentSize)
                input.readFully(currentTrack!!.projectionData!!, 0, contentSize)
            }

            ID_CONTENT_COMPRESSION_SETTINGS -> {
                assertInTrackEntry(id)
                // This extractor only supports header stripping, so the payload is the stripped bytes.
                currentTrack!!.sampleStrippedBytes = ByteArray(contentSize)
                input.readFully(currentTrack!!.sampleStrippedBytes!!, 0, contentSize)
            }

            ID_CONTENT_ENCRYPTION_KEY_ID -> {
                val encryptionKey = ByteArray(contentSize)
                input.readFully(encryptionKey, 0, contentSize)
                getCurrentTrack(id)!!.cryptoData =
                    TrackOutput.CryptoData(
                        C.CRYPTO_MODE_AES_CTR, encryptionKey, 0, 0
                    ) // We assume patternless AES-CTR.
            }

            ID_SIMPLE_BLOCK, ID_BLOCK -> {
                // Please refer to http://www.matroska.org/technical/specs/index.html#simpleblock_structure
                // and http://matroska.org/technical/specs/index.html#block_structure
                // for info about how data is organized in SimpleBlock and Block elements respectively. They
                // differ only in the way flags are specified.
                if (blockState == BLOCK_STATE_START) {
                    blockTrackNumber =
                        varintReader.readUnsignedVarint(input, false, true, 8).toInt()
                    blockTrackNumberLength = varintReader.lastLength
                    blockDurationUs = C.TIME_UNSET
                    blockState = BLOCK_STATE_HEADER
                    scratch.reset(0)
                }

                val track = tracks.get(blockTrackNumber)

                // Ignore the block if we don't know about the track to which it belongs.
                if (track == null) {
                    input.skipFully(contentSize - blockTrackNumberLength)
                    blockState = BLOCK_STATE_START
                    return
                }

                track.assertOutputInitialized()

                if (blockState == BLOCK_STATE_HEADER) {
                    // Read the relative timecode (2 bytes) and flags (1 byte).
                    readScratch(input, 3)
                    val lacing = (scratch.data[2].toInt() and 0x06) shr 1
                    if (lacing == LACING_NONE) {
                        blockSampleCount = 1
                        blockSampleSizes = ensureArrayCapacity(blockSampleSizes, 1)
                        blockSampleSizes[0] = contentSize - blockTrackNumberLength - 3
                    } else {
                        // Read the sample count (1 byte).
                        readScratch(input, 4)
                        blockSampleCount = (scratch.data[3].toInt() and 0xFF) + 1
                        blockSampleSizes = ensureArrayCapacity(blockSampleSizes, blockSampleCount)
                        if (lacing == LACING_FIXED_SIZE) {
                            val blockLacingSampleSize =
                                (contentSize - blockTrackNumberLength - 4) / blockSampleCount
                            Arrays.fill(
                                blockSampleSizes,
                                0,
                                blockSampleCount,
                                blockLacingSampleSize
                            )
                        } else if (lacing == LACING_XIPH) {
                            var totalSamplesSize = 0
                            var headerSize = 4
                            var sampleIndex = 0
                            while (sampleIndex < blockSampleCount - 1) {
                                blockSampleSizes[sampleIndex] = 0
                                var byteValue: Int
                                do {
                                    readScratch(input, ++headerSize)
                                    byteValue = scratch.data[headerSize - 1].toInt() and 0xFF
                                    blockSampleSizes[sampleIndex] += byteValue
                                } while (byteValue == 0xFF)
                                totalSamplesSize += blockSampleSizes[sampleIndex]
                                sampleIndex++
                            }
                            blockSampleSizes[blockSampleCount - 1] =
                                contentSize - blockTrackNumberLength - headerSize - totalSamplesSize
                        } else if (lacing == LACING_EBML) {
                            var totalSamplesSize = 0
                            var headerSize = 4
                            var sampleIndex = 0
                            while (sampleIndex < blockSampleCount - 1) {
                                blockSampleSizes[sampleIndex] = 0
                                readScratch(input, ++headerSize)
                                if (scratch.data[headerSize - 1].toInt() == 0) {
                                    throw ParserException.createForMalformedContainer(
                                        "No valid varint length mask found", null
                                    )
                                }
                                var readValue: Long = 0
                                var i = 0
                                while (i < 8) {
                                    val lengthMask = 1 shl (7 - i)
                                    if ((scratch.data[headerSize - 1].toInt() and lengthMask) != 0) {
                                        var readPosition = headerSize - 1
                                        headerSize += i
                                        readScratch(input, headerSize)
                                        readValue =
                                            ((scratch.data[readPosition++].toInt() and 0xFF) and lengthMask.inv()).toLong()
                                        while (readPosition < headerSize) {
                                            readValue = readValue shl 8
                                            readValue =
                                                readValue or (scratch.data[readPosition++].toInt() and 0xFF).toLong()
                                        }
                                        // The first read value is the first size. Later values are signed offsets.
                                        if (sampleIndex > 0) {
                                            readValue -= (1L shl (6 + i * 7)) - 1
                                        }
                                        break
                                    }
                                    i++
                                }
                                if (readValue < Int.MIN_VALUE || readValue > Int.MAX_VALUE) {
                                    throw ParserException.createForMalformedContainer(
                                        "EBML lacing sample size out of range.", null
                                    )
                                }
                                val intReadValue = readValue.toInt()
                                blockSampleSizes[sampleIndex] =
                                    if (sampleIndex == 0)
                                        intReadValue
                                    else
                                        blockSampleSizes[sampleIndex - 1] + intReadValue
                                totalSamplesSize += blockSampleSizes[sampleIndex]
                                sampleIndex++
                            }
                            blockSampleSizes[blockSampleCount - 1] =
                                contentSize - blockTrackNumberLength - headerSize - totalSamplesSize
                        } else {
                            // Lacing is always in the range 0--3.
                            throw ParserException.createForMalformedContainer(
                                "Unexpected lacing value: " + lacing, null
                            )
                        }
                    }

                    val timecode =
                        (scratch.data[0].toInt() shl 8) or (scratch.data[1].toInt() and 0xFF)
                    blockTimeUs = clusterTimecodeUs + scaleTimecodeToUs(timecode.toLong())
                    val isKeyframe =
                        track.type == C.TRACK_TYPE_AUDIO
                                || (id == ID_SIMPLE_BLOCK && (scratch.data[2].toInt() and 0x80) == 0x80)
                    blockFlags = if (isKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0
                    blockState = BLOCK_STATE_DATA
                    blockSampleIndex = 0
                }

                if (id == ID_SIMPLE_BLOCK) {
                    // For SimpleBlock, we can write sample data and immediately commit the corresponding
                    // sample metadata.
                    while (blockSampleIndex < blockSampleCount) {
                        val sampleSize =
                            writeSampleData(
                                input,
                                track,
                                blockSampleSizes[blockSampleIndex],
                                false
                            )
                        val sampleTimeUs =
                            blockTimeUs + (blockSampleIndex * track.defaultSampleDurationNs) / 1000
                        commitSampleToOutput(
                            track,
                            sampleTimeUs,
                            blockFlags,
                            sampleSize,
                            0
                        )
                        blockSampleIndex++
                    }
                    blockState = BLOCK_STATE_START
                } else {
                    // For Block, we need to wait until the end of the BlockGroup element before committing
                    // sample metadata. This is so that we can handle ReferenceBlock (which can be used to
                    // infer whether the first sample in the block is a keyframe), and BlockAdditions (which
                    // can contain additional sample data to append) contained in the block group. Just output
                    // the sample data, storing the final sample sizes for when we commit the metadata.
                    while (blockSampleIndex < blockSampleCount) {
                        blockSampleSizes[blockSampleIndex] =
                            writeSampleData(
                                input,
                                track,
                                blockSampleSizes[blockSampleIndex],
                                true
                            )
                        blockSampleIndex++
                    }
                }
            }

            ID_BLOCK_ADDITIONAL -> {
                if (blockState != BLOCK_STATE_DATA) {
                    return
                }
                handleBlockAdditionalData(
                    tracks.get(blockTrackNumber), blockAdditionalId, input, contentSize
                )
            }

            else -> throw ParserException.createForMalformedContainer(
                "Unexpected id: " + id, null
            )
        }
    }

    @Throws(IOException::class)
    protected fun handleBlockAddIDExtraData(track: Track, input: ExtractorInput, contentSize: Int) {
        if (track.blockAddIdType == BLOCK_ADD_ID_TYPE_DVVC
            || track.blockAddIdType == BLOCK_ADD_ID_TYPE_DVCC
        ) {
            track.dolbyVisionConfigBytes = ByteArray(contentSize)
            input.readFully(track.dolbyVisionConfigBytes!!, 0, contentSize)
        } else {
            // Unhandled BlockAddIDExtraData.
            input.skipFully(contentSize)
        }
    }

    @Throws(IOException::class)
    protected fun handleBlockAdditionalData(
        track: Track, blockAdditionalId: Int, input: ExtractorInput, contentSize: Int
    ) {
        if (blockAdditionalId == BLOCK_ADDITIONAL_ID_VP9_ITU_T_35
            && CODEC_ID_VP9 == track.codecId
        ) {
            supplementalData.reset(contentSize)
            input.readFully(supplementalData.data, 0, contentSize)
        } else {
            // Unhandled block additional data.
            input.skipFully(contentSize)
        }
    }

    @Throws(ParserException::class)
    private fun assertInEditionEntry(id: Int) {
        if (currentChapter == null) {
            throw ParserException.createForMalformedContainer(
                "Element $id must be in an EditionEntry", null
            )
        }
    }

    @Throws(ParserException::class)
    private fun assertInTrackEntry(id: Int) {
        if (currentTrack == null) {
            throw ParserException.createForMalformedContainer(
                "Element $id must be in a TrackEntry", null
            )
        }
    }

    @Throws(ParserException::class)
    private fun assertInCues(id: Int) {
        if (!inCuesElement) {
            throw ParserException.createForMalformedContainer(
                "Element " + id + " must be in a Cues", null
            )
        }
    }

    @Throws(ParserException::class)
    protected fun getCurrentChapter(currentElementId: Int): ChapterEntry? {
        assertInEditionEntry(currentElementId)
        return currentChapter
    }

    @Throws(ParserException::class)
    protected fun getCurrentTrack(currentElementId: Int): Track? {
        assertInTrackEntry(currentElementId)
        return currentTrack
    }

    @RequiresNonNull("#1.output")
    private fun commitSampleToOutput(
        track: Track, timeUs: Long, flags: @C.BufferFlags Int, size: Int, offset: Int
    ) {
        var size = size
        if (track.trueHdSampleRechunker != null) {
            track.trueHdSampleRechunker?.sampleMetadata(
                track.output!!, timeUs, flags, size, offset, track.cryptoData
            )
        } else {
            if (CODEC_ID_SUBRIP == track.codecId
                || CODEC_ID_ASS == track.codecId
                || CODEC_ID_SSA == track.codecId
                || CODEC_ID_VTT == track.codecId
            ) {
                if (blockSampleCount > 1) {
                    Log.w(TAG, "Skipping subtitle sample in laced block.")
                } else if (blockDurationUs == C.TIME_UNSET) {
                    Log.w(TAG, "Skipping subtitle sample with no duration.")
                } else {
                    Companion.setSubtitleEndTime(
                        track.codecId!!,
                        blockDurationUs,
                        subtitleSample.data
                    )
                    // The Matroska spec doesn't clearly define whether subtitle samples are null-terminated
                    // or the sample should instead be sized precisely. We truncate the sample at a null-byte
                    // to gracefully handle null-terminated strings followed by garbage bytes.
                    for (i in subtitleSample.getPosition()..<subtitleSample.limit()) {
                        if (subtitleSample.data[i].toInt() == 0) {
                            subtitleSample.setLimit(i)
                            break
                        }
                    }
                    // Note: If we ever want to support DRM protected subtitles then we'll need to output the
                    // appropriate encryption data here.
                    track.output?.sampleData(subtitleSample, subtitleSample.limit())
                    size += subtitleSample.limit()
                }
            }

            if ((flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA) != 0) {
                if (blockSampleCount > 1) {
                    // There were multiple samples in the block. Appending the additional data to the last
                    // sample doesn't make sense. Skip instead.
                    supplementalData.reset(0)
                } else {
                    // Append supplemental data.
                    val supplementalDataSize = supplementalData.limit()
                    track.output?.sampleData(
                        supplementalData,
                        supplementalDataSize,
                        TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL
                    )
                    size += supplementalDataSize
                }
            }
            track.output?.sampleMetadata(timeUs, flags, size, offset, track.cryptoData)
        }
        haveOutputSample = true
    }

    @Throws(IOException::class)
    private fun readScratch(input: ExtractorInput, requiredLength: Int) {
        if (scratch.limit() >= requiredLength) {
            return
        }
        if (scratch.capacity() < requiredLength) {
            scratch.ensureCapacity(max(scratch.capacity() * 2, requiredLength))
        }
        input.readFully(scratch.data, scratch.limit(), requiredLength - scratch.limit())
        scratch.setLimit(requiredLength)
    }

    @RequiresNonNull("#2.output")
    @Throws(IOException::class)
    private fun writeSampleData(
        input: ExtractorInput,
        track: Track,
        size: Int,
        isBlockGroup: Boolean
    ): Int {
        var size = size
        if (CODEC_ID_SUBRIP == track.codecId) {
            writeSubtitleSampleData(input, SUBRIP_PREFIX, size)
            return finishWriteSampleData()
        } else if (CODEC_ID_ASS == track.codecId || CODEC_ID_SSA == track.codecId) {
            writeSubtitleSampleData(input, SSA_PREFIX, size)
            return finishWriteSampleData()
        } else if (CODEC_ID_VTT == track.codecId) {
            writeSubtitleSampleData(input, VTT_PREFIX, size)
            return finishWriteSampleData()
        }

        if (track.waitingForDtsAnalysis) {
            track.format = DtsUtil.updateFormatWithDtsHdInfo(input, size, track.format!!)
            track.output?.format(track.format!!)
            track.waitingForDtsAnalysis = false
            maybeEndTracks()
        }

        val output: TrackOutput = track.output!!
        if (!sampleEncodingHandled) {
            if (track.hasContentEncryption) {
                // If the sample is encrypted, read its encryption signal byte and set the IV size.
                // Clear the encrypted flag.
                blockFlags = blockFlags and C.BUFFER_FLAG_ENCRYPTED.inv()
                if (!sampleSignalByteRead) {
                    input.readFully(scratch.data, 0, 1)
                    sampleBytesRead++
                    if ((scratch.data[0].toInt() and 0x80) == 0x80) {
                        throw ParserException.createForMalformedContainer(
                            "Extension bit is set in signal byte", null
                        )
                    }
                    sampleSignalByte = scratch.data[0]
                    sampleSignalByteRead = true
                }
                val isEncrypted = (sampleSignalByte.toInt() and 0x01) == 0x01
                if (isEncrypted) {
                    val hasSubsampleEncryption = (sampleSignalByte.toInt() and 0x02) == 0x02
                    blockFlags = blockFlags or C.BUFFER_FLAG_ENCRYPTED
                    if (!sampleInitializationVectorRead) {
                        input.readFully(
                            encryptionInitializationVector.data,
                            0,
                            ENCRYPTION_IV_SIZE
                        )
                        sampleBytesRead += ENCRYPTION_IV_SIZE
                        sampleInitializationVectorRead = true
                        // Write the signal byte, containing the IV size and the subsample encryption flag.
                        scratch.data[0] =
                            (ENCRYPTION_IV_SIZE or (if (hasSubsampleEncryption) 0x80 else 0x00)).toByte()
                        scratch.setPosition(0)
                        output.sampleData(scratch, 1, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION)
                        sampleBytesWritten++
                        // Write the IV.
                        encryptionInitializationVector.setPosition(0)
                        output.sampleData(
                            encryptionInitializationVector,
                            ENCRYPTION_IV_SIZE,
                            TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
                        )
                        sampleBytesWritten += ENCRYPTION_IV_SIZE
                    }
                    if (hasSubsampleEncryption) {
                        if (!samplePartitionCountRead) {
                            input.readFully(scratch.data, 0, 1)
                            sampleBytesRead++
                            scratch.setPosition(0)
                            samplePartitionCount = scratch.readUnsignedByte()
                            samplePartitionCountRead = true
                        }
                        val samplePartitionDataSize = samplePartitionCount * 4
                        scratch.reset(samplePartitionDataSize)
                        input.readFully(scratch.data, 0, samplePartitionDataSize)
                        sampleBytesRead += samplePartitionDataSize
                        val subsampleCount = (1 + (samplePartitionCount / 2)).toShort()
                        val subsampleDataSize = 2 + 6 * subsampleCount
                        if (encryptionSubsampleDataBuffer == null
                            || encryptionSubsampleDataBuffer!!.capacity() < subsampleDataSize
                        ) {
                            encryptionSubsampleDataBuffer = ByteBuffer.allocate(subsampleDataSize)
                        }
                        encryptionSubsampleDataBuffer!!.position(0)
                        encryptionSubsampleDataBuffer!!.putShort(subsampleCount)
                        // Loop through the partition offsets and write out the data in the way ExoPlayer
                        // wants it (ISO 23001-7 Part 7):
                        //   2 bytes - sub sample count.
                        //   for each sub sample:
                        //     2 bytes - clear data size.
                        //     4 bytes - encrypted data size.
                        var partitionOffset = 0
                        for (i in 0..<samplePartitionCount) {
                            val previousPartitionOffset = partitionOffset
                            partitionOffset = scratch.readUnsignedIntToInt()
                            if ((i % 2) == 0) {
                                encryptionSubsampleDataBuffer!!.putShort(
                                    (partitionOffset - previousPartitionOffset).toShort()
                                )
                            } else {
                                encryptionSubsampleDataBuffer!!.putInt(partitionOffset - previousPartitionOffset)
                            }
                        }
                        val finalPartitionSize = size - sampleBytesRead - partitionOffset
                        if ((samplePartitionCount % 2) == 1) {
                            encryptionSubsampleDataBuffer!!.putInt(finalPartitionSize)
                        } else {
                            encryptionSubsampleDataBuffer!!.putShort(finalPartitionSize.toShort())
                            encryptionSubsampleDataBuffer!!.putInt(0)
                        }
                        encryptionSubsampleData.reset(
                            encryptionSubsampleDataBuffer!!.array(),
                            subsampleDataSize
                        )
                        output.sampleData(
                            encryptionSubsampleData,
                            subsampleDataSize,
                            TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
                        )
                        sampleBytesWritten += subsampleDataSize
                    }
                }
            } else if (track.sampleStrippedBytes != null) {
                // If the sample has header stripping, prepare to read/output the stripped bytes first.
                sampleStrippedBytes.reset(
                    track.sampleStrippedBytes!!,
                    track.sampleStrippedBytes!!.size
                )
            }

            if (track.samplesHaveSupplementalData(isBlockGroup)) {
                blockFlags = blockFlags or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA
                supplementalData.reset(0)
                // If there is supplemental data, the structure of the sample data is:
                // encryption data (if any) || sample size (4 bytes) || sample data || supplemental data
                val sampleSize = size + sampleStrippedBytes.limit() - sampleBytesRead
                scratch.reset(4)
                scratch.data[0] = ((sampleSize shr 24) and 0xFF).toByte()
                scratch.data[1] = ((sampleSize shr 16) and 0xFF).toByte()
                scratch.data[2] = ((sampleSize shr 8) and 0xFF).toByte()
                scratch.data[3] = (sampleSize and 0xFF).toByte()
                output.sampleData(scratch, 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
                sampleBytesWritten += 4
            }

            sampleEncodingHandled = true
        }
        size += sampleStrippedBytes.limit()

        if (CODEC_ID_H264 == track.codecId || CODEC_ID_H265 == track.codecId) {
            // TODO: Deduplicate with Mp4Extractor.

            // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
            // they're only 1 or 2 bytes long.

            val nalLengthData = nalLength.data
            nalLengthData[0] = 0
            nalLengthData[1] = 0
            nalLengthData[2] = 0
            val nalUnitLengthFieldLength = track.nalUnitLengthFieldLength
            val nalUnitLengthFieldLengthDiff = 4 - track.nalUnitLengthFieldLength
            // NAL units are length delimited, but the decoder requires start code delimited units.
            // Loop until we've written the sample to the track output, replacing length delimiters with
            // start codes as we encounter them.
            while (sampleBytesRead < size) {
                if (sampleCurrentNalBytesRemaining == 0) {
                    // Read the NAL length so that we know where we find the next one.
                    writeToTarget(
                        input, nalLengthData, nalUnitLengthFieldLengthDiff, nalUnitLengthFieldLength
                    )
                    sampleBytesRead += nalUnitLengthFieldLength
                    nalLength.setPosition(0)
                    sampleCurrentNalBytesRemaining = nalLength.readUnsignedIntToInt()
                    // Write a start code for the current NAL unit.
                    nalStartCode.setPosition(0)
                    output.sampleData(nalStartCode, 4)
                    sampleBytesWritten += 4
                } else {
                    // Write the payload of the NAL unit.
                    val bytesWritten = writeToOutput(input, output, sampleCurrentNalBytesRemaining)
                    sampleBytesRead += bytesWritten
                    sampleBytesWritten += bytesWritten
                    sampleCurrentNalBytesRemaining -= bytesWritten
                }
            }
        } else {
            if (track.trueHdSampleRechunker != null) {
                Preconditions.checkState(sampleStrippedBytes.limit() == 0)
                track.trueHdSampleRechunker?.startSample(input)
            }
            while (sampleBytesRead < size) {
                val bytesWritten = writeToOutput(input, output, size - sampleBytesRead)
                sampleBytesRead += bytesWritten
                sampleBytesWritten += bytesWritten
            }
        }

        if (CODEC_ID_VORBIS == track.codecId) {
            // Vorbis decoder in android MediaCodec [1] expects the last 4 bytes of the sample to be the
            // number of samples in the current page. This definition holds good only for Ogg and
            // irrelevant for Matroska. So we always set this to -1 (the decoder will ignore this value if
            // we set it to -1). The android platform media extractor [2] does the same.
            // [1]
            // https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/codecs/vorbis/dec/SoftVorbis.cpp#314
            // [2]
            // https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/NuMediaExtractor.cpp#474
            vorbisNumPageSamples.setPosition(0)
            output.sampleData(vorbisNumPageSamples, 4)
            sampleBytesWritten += 4
        }

        return finishWriteSampleData()
    }

    private fun finishWriteSampleData(): Int {
        val sampleSize = sampleBytesWritten
        resetWriteSampleData()
        return sampleSize
    }

    private fun resetWriteSampleData() {
        sampleBytesRead = 0
        sampleBytesWritten = 0
        sampleCurrentNalBytesRemaining = 0
        sampleEncodingHandled = false
        sampleSignalByteRead = false
        samplePartitionCountRead = false
        samplePartitionCount = 0
        sampleSignalByte = 0.toByte()
        sampleInitializationVectorRead = false
        sampleStrippedBytes.reset(0)
    }

    @Throws(IOException::class)
    private fun writeSubtitleSampleData(input: ExtractorInput, samplePrefix: ByteArray, size: Int) {
        val sizeWithPrefix = samplePrefix.size + size
        if (subtitleSample.capacity() < sizeWithPrefix) {
            // Initialize subripSample to contain the required prefix and have space to hold a subtitle
            // twice as long as this one.
            subtitleSample.reset(samplePrefix.copyOf(sizeWithPrefix + size))
        } else {
            System.arraycopy(samplePrefix, 0, subtitleSample.data, 0, samplePrefix.size)
        }
        input.readFully(subtitleSample.data, samplePrefix.size, size)
        subtitleSample.setPosition(0)
        subtitleSample.setLimit(sizeWithPrefix)
        // Defer writing the data to the track output. We need to modify the sample data by setting
        // the correct end timecode, which we might not have yet.
    }

    @Throws(IOException::class)
    private fun writeToTarget(input: ExtractorInput, target: ByteArray, offset: Int, length: Int) {
        val pendingStrippedBytes = min(length, sampleStrippedBytes.bytesLeft())
        input.readFully(target, offset + pendingStrippedBytes, length - pendingStrippedBytes)
        if (pendingStrippedBytes > 0) {
            sampleStrippedBytes.readBytes(target, offset, pendingStrippedBytes)
        }
    }

    @Throws(IOException::class)
    private fun writeToOutput(input: ExtractorInput, output: TrackOutput, length: Int): Int {
        val bytesWritten: Int
        val strippedBytesLeft = sampleStrippedBytes.bytesLeft()
        if (strippedBytesLeft > 0) {
            bytesWritten = min(length, strippedBytesLeft)
            output.sampleData(sampleStrippedBytes, bytesWritten)
        } else {
            bytesWritten = output.sampleData(input, length, false)
        }
        return bytesWritten
    }

    private fun maybeSeekForCues(seekPosition: PositionHolder, currentPosition: Long): Boolean {
        if (seekForCues) {
            seekPositionAfterBuildingCues = currentPosition
            seekPosition.position = cuesContentPosition
            seekForCues = false
            return true
        }
        // After parsing Cues, seek back to original position if available. We will not do this unless
        // we seeked to get to the Cues in the first place.
        if (sentSeekMap && seekPositionAfterBuildingCues != C.INDEX_UNSET.toLong()) {
            seekPosition.position = seekPositionAfterBuildingCues
            seekPositionAfterBuildingCues = C.INDEX_UNSET.toLong()
            return true
        }
        return false
    }

    private fun maybeSeekForTracks(seekPosition: PositionHolder, currentPosition: Long): Boolean {
        if (seekForTracks) {
            seekPositionAfterReadingTracks = currentPosition
            seekPosition.position = tracksContentPosition
            seekForTracks = false
            return true
        }
        if (readTracks && seekPositionAfterReadingTracks != C.INDEX_UNSET.toLong()) {
            seekPosition.position = seekPositionAfterReadingTracks
            seekPositionAfterReadingTracks = C.INDEX_UNSET.toLong()
            return true
        }
        return false
    }

    @Throws(ParserException::class)
    private fun scaleTimecodeToUs(unscaledTimecode: Long): Long {
        if (timecodeScale == C.TIME_UNSET) {
            throw ParserException.createForMalformedContainer(
                "Can't scale timecode prior to timecodeScale being set.", null
            )
        }
        return Util.scaleLargeTimestamp(unscaledTimecode, timecodeScale, 1000)
    }

    private fun assertInitialized() {
        extractorOutput
    }

    private fun maybeEndTracks() {
        if (!pendingEndTracks) {
            return
        }
        if (tracksContentPosition != C.INDEX_UNSET.toLong() && !readTracks) {
            return
        }
        for (i in 0..<tracks.size) {
            if (tracks.valueAt(i).waitingForDtsAnalysis) {
                return
            }
        }
        extractorOutput.endTracks()
        pendingEndTracks = false
    }

    private fun updateTrackFormatWithMetadata(track: Track) {
        track.maybeAddChaptersMetadata(chapters)
        track.maybeAddThumbnailMetadata(
            perTrackCues, durationUs, segmentContentPosition, segmentContentSize
        )
    }

    private inner class InnerEbmlProcessor : EbmlProcessor {
        override fun getElementType(id: Int): @EbmlProcessor.ElementType Int {
            return this@MatroskaExtractor.getElementType(id)
        }

        override fun isLevel1Element(id: Int): Boolean {
            return this@MatroskaExtractor.isLevel1Element(id)
        }

        @Throws(ParserException::class)
        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
            this@MatroskaExtractor.startMasterElement(id, contentPosition, contentSize)
        }

        @Throws(ParserException::class)
        override fun endMasterElement(id: Int) {
            this@MatroskaExtractor.endMasterElement(id)
        }

        @Throws(ParserException::class)
        override fun integerElement(id: Int, value: Long) {
            this@MatroskaExtractor.integerElement(id, value)
        }

        @Throws(ParserException::class)
        override fun floatElement(id: Int, value: Double) {
            this@MatroskaExtractor.floatElement(id, value)
        }

        @Throws(ParserException::class)
        override fun stringElement(id: Int, value: String) {
            this@MatroskaExtractor.stringElement(id, value)
        }

        @Throws(IOException::class)
        override fun binaryElement(id: Int, contentsSize: Int, input: ExtractorInput) {
            this@MatroskaExtractor.binaryElement(id, contentsSize, input)
        }
    }

    protected class ChapterEntry {
        var uid: Long = 0
        var timeStartNs: Long
        var timeEndNs: Long
        var flagHidden: Boolean = false
        var trackUid: Long = 0
        var chapString: String? = null
        var chapLanguage: String? = null
        var currentDisplayString: String? = null
        var currentDisplayLanguage: String? = null

        init {
            timeStartNs = C.TIME_UNSET
            timeEndNs = C.TIME_UNSET
        }
    }

    protected class Track {
        // Common elements.
        var isWebm: Boolean = false
        var name: String? = null
        var codecId: String? = null
        var number: Int = 0
        var uid: Long = 0
        var type: @C.TrackType Int = 0
        var defaultSampleDurationNs: Int = 0
        var maxBlockAdditionId: Int = 0
        var blockAddIdType = 0
        var hasContentEncryption: Boolean = false
        var sampleStrippedBytes: ByteArray? = null
        var cryptoData: TrackOutput.CryptoData? = null
        var codecPrivate: ByteArray? = null
        var drmInitData: DrmInitData? = null

        // Video elements.
        var width: Int = Format.NO_VALUE
        var height: Int = Format.NO_VALUE
        var bitsPerChannel: Int = Format.NO_VALUE
        var displayWidth: Int = Format.NO_VALUE
        var displayHeight: Int = Format.NO_VALUE
        var displayUnit: Int = DISPLAY_UNIT_PIXELS
        var projectionType: @C.Projection Int = Format.NO_VALUE
        var projectionPoseYaw: Float = 0f
        var projectionPosePitch: Float = 0f
        var projectionPoseRoll: Float = 0f
        var projectionData: ByteArray? = null
        var stereoMode: @C.StereoMode Int = Format.NO_VALUE
        var containerColorSpace: @C.ColorSpace Int = Format.NO_VALUE
        var containerColorTransfer: @C.ColorTransfer Int = Format.NO_VALUE
        var containerColorRange: @C.ColorRange Int = Format.NO_VALUE
        var maxContentLuminance: Int = DEFAULT_MAX_CLL
        var maxFrameAverageLuminance: Int = DEFAULT_MAX_FALL
        var primaryRChromaticityX: Float = Format.NO_VALUE.toFloat()
        var primaryRChromaticityY: Float = Format.NO_VALUE.toFloat()
        var primaryGChromaticityX: Float = Format.NO_VALUE.toFloat()
        var primaryGChromaticityY: Float = Format.NO_VALUE.toFloat()
        var primaryBChromaticityX: Float = Format.NO_VALUE.toFloat()
        var primaryBChromaticityY: Float = Format.NO_VALUE.toFloat()
        var whitePointChromaticityX: Float = Format.NO_VALUE.toFloat()
        var whitePointChromaticityY: Float = Format.NO_VALUE.toFloat()
        var maxMasteringLuminance: Float = Format.NO_VALUE.toFloat()
        var minMasteringLuminance: Float = Format.NO_VALUE.toFloat()
        var dolbyVisionConfigBytes: ByteArray? = null

        // Audio elements. Initially set to their default values.
        var channelCount: Int = 1
        var audioBitDepth: Int = Format.NO_VALUE
        var channelMask: Int = Format.NO_VALUE
        var sampleRate: Int = 8000
        var codecDelayNs: Long = 0
        var seekPreRollNs: Long = 0
        var trueHdSampleRechunker: TrueHdSampleRechunker? = null
        var waitingForDtsAnalysis: Boolean = false
        private var chaptersMetadataAdded = false
        private var thumbnailMetadataAdded = false

        // Text elements.
        var flagForced: Boolean = false

        // Common track elements.
        var flagDefault: Boolean = true
        var language: String? = "eng"

        // Set when the output is initialized. nalUnitLengthFieldLength is only set for H264/H265.
        var output: TrackOutput? = null
        var format: Format? = null
        var nalUnitLengthFieldLength: Int = 0

        @RequiresNonNull("codecId")
        @Throws(ParserException::class)
        fun initializeFormat(trackId: Int) {
            var mimeType: String?
            var maxInputSize = Format.NO_VALUE
            var pcmEncoding: @C.PcmEncoding Int = Format.NO_VALUE
            var initializationData: List<ByteArray>? = null
            var codecs: String? = null
            var bitstreamColorSpace: @C.ColorSpace Int = Format.NO_VALUE
            var bitstreamColorTransfer: @C.ColorTransfer Int = Format.NO_VALUE
            var bitstreamColorRange: @C.ColorRange Int = Format.NO_VALUE
            var bitstreamLumaBitdepth = Format.NO_VALUE
            var bitstreamChromaBitdepth = Format.NO_VALUE
            l@
            when (codecId) {
                CODEC_ID_VP8 -> mimeType = MimeTypes.VIDEO_VP8
                CODEC_ID_VP9 -> {
                    mimeType = MimeTypes.VIDEO_VP9
                    initializationData = codecPrivate?.let { listOf(it) } ?: listOf()
                }

                CODEC_ID_AV1 -> {
                    mimeType = MimeTypes.VIDEO_AV1
                    if (codecPrivate != null) {
                        initializationData = listOf(codecPrivate!!)
                        val av1Config: Av1Config? = Av1Config.parse(codecPrivate!!)
                        if (av1Config != null) {
                            bitstreamColorSpace = av1Config.colorSpace
                            bitstreamColorTransfer = av1Config.colorTransfer
                            bitstreamColorRange = av1Config.colorRange
                            bitstreamLumaBitdepth = av1Config.bitdepth
                            bitstreamChromaBitdepth = av1Config.bitdepth
                            codecs = av1Config.codecs
                        }
                    }

                }

                CODEC_ID_MPEG2 -> mimeType = MimeTypes.VIDEO_MPEG2
                CODEC_ID_MPEG4_SP, CODEC_ID_MPEG4_ASP, CODEC_ID_MPEG4_AP -> {
                    mimeType = MimeTypes.VIDEO_MP4V
                    initializationData = codecPrivate?.let { mutableListOf(it) }
                }

                CODEC_ID_H264 -> {
                    mimeType = MimeTypes.VIDEO_H264
                    val avcConfig: AvcConfig =
                        AvcConfig.parse(ParsableByteArray(getCodecPrivate(codecId)))
                    initializationData = avcConfig.initializationData
                    nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength
                    codecs = avcConfig.codecs
                    bitstreamColorSpace = avcConfig.colorSpace
                    bitstreamColorTransfer = avcConfig.colorTransfer
                    bitstreamColorRange = avcConfig.colorRange
                    bitstreamLumaBitdepth = avcConfig.bitdepthLuma
                    bitstreamChromaBitdepth = avcConfig.bitdepthChroma
                }

                CODEC_ID_H265 -> {
                    mimeType = MimeTypes.VIDEO_H265
                    val hevcConfig: HevcConfig =
                        HevcConfig.parse(ParsableByteArray(getCodecPrivate(codecId)))
                    initializationData = hevcConfig.initializationData
                    nalUnitLengthFieldLength = hevcConfig.nalUnitLengthFieldLength
                    codecs = hevcConfig.codecs
                    bitstreamColorSpace = hevcConfig.colorSpace
                    bitstreamColorTransfer = hevcConfig.colorTransfer
                    bitstreamColorRange = hevcConfig.colorRange
                    bitstreamLumaBitdepth = hevcConfig.bitdepthLuma
                    bitstreamChromaBitdepth = hevcConfig.bitdepthChroma
                }

                CODEC_ID_FOURCC -> {
                    val pair: Pair<String?, MutableList<ByteArray>?> =
                        parseFourCcPrivate(ParsableByteArray(getCodecPrivate(codecId)))
                    mimeType = pair.first
                    initializationData = pair.second
                }

                CODEC_ID_THEORA ->           // TODO: This can be set to the real mimeType if/when we work out what initializationData
                    // should be set to for this case.
                    mimeType = MimeTypes.VIDEO_UNKNOWN

                CODEC_ID_VORBIS -> {
                    mimeType = MimeTypes.AUDIO_VORBIS
                    maxInputSize = VORBIS_MAX_INPUT_SIZE
                    initializationData = parseVorbisCodecPrivate(getCodecPrivate(codecId)!!)
                }

                CODEC_ID_OPUS -> {
                    mimeType = MimeTypes.AUDIO_OPUS
                    maxInputSize = OPUS_MAX_INPUT_SIZE
                    initializationData = ArrayList<ByteArray>(3)
                    initializationData.add(getCodecPrivate(codecId))
                    initializationData.add(
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(codecDelayNs)
                            .array()
                    )
                    initializationData.add(
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(seekPreRollNs)
                            .array()
                    )
                }

                CODEC_ID_AAC -> {
                    mimeType = MimeTypes.AUDIO_AAC
                    initializationData = mutableListOf<ByteArray>(getCodecPrivate(codecId))
                    val aacConfig: AacUtil.Config = AacUtil.parseAudioSpecificConfig(codecPrivate!!)
                    // Update sampleRate and channelCount from the AudioSpecificConfig initialization data,
                    // which is more reliable. See [Internal: b/10903778].
                    sampleRate = aacConfig.sampleRateHz
                    channelCount = aacConfig.channelCount
                    codecs = aacConfig.codecs
                }

                CODEC_ID_MP2 -> {
                    mimeType = MimeTypes.AUDIO_MPEG_L2
                    maxInputSize = MpegAudioUtil.MAX_FRAME_SIZE_BYTES
                }

                CODEC_ID_MP3 -> {
                    mimeType = MimeTypes.AUDIO_MPEG
                    maxInputSize = MpegAudioUtil.MAX_FRAME_SIZE_BYTES
                }

                CODEC_ID_AC3 -> mimeType = MimeTypes.AUDIO_AC3
                CODEC_ID_E_AC3 -> mimeType = MimeTypes.AUDIO_E_AC3
                CODEC_ID_TRUEHD -> {
                    mimeType = MimeTypes.AUDIO_TRUEHD
                    trueHdSampleRechunker = TrueHdSampleRechunker()
                }

                CODEC_ID_DTS -> {
                    mimeType = MimeTypes.AUDIO_DTS // temporary
                    waitingForDtsAnalysis = true
                }

                CODEC_ID_DTS_EXPRESS -> mimeType = MimeTypes.AUDIO_DTS_EXPRESS
                CODEC_ID_DTS_LOSSLESS -> mimeType = MimeTypes.AUDIO_DTS_HD
                CODEC_ID_FLAC -> {
                    mimeType = MimeTypes.AUDIO_FLAC
                    initializationData = mutableListOf<ByteArray>(getCodecPrivate(codecId))
                    pcmEncoding = Util.getPcmEncoding(audioBitDepth)
                    if (pcmEncoding == C.ENCODING_INVALID) {
                        pcmEncoding = Format.NO_VALUE
                    }
                }

                CODEC_ID_ALAC -> {
                    mimeType = MimeTypes.AUDIO_ALAC
                    initializationData = mutableListOf<ByteArray>(getCodecPrivate(codecId))
                    pcmEncoding = Util.getPcmEncoding(audioBitDepth)
                    if (pcmEncoding == C.ENCODING_INVALID) {
                        pcmEncoding = Format.NO_VALUE
                    }
                }

                CODEC_ID_ACM -> {
                    mimeType = MimeTypes.AUDIO_RAW
                    if (parseMsAcmCodecPrivate(ParsableByteArray(getCodecPrivate(codecId)))) {
                        pcmEncoding = Util.getPcmEncoding(audioBitDepth)
                        if (pcmEncoding == C.ENCODING_INVALID) {
                            pcmEncoding = Format.NO_VALUE
                            mimeType = MimeTypes.AUDIO_UNKNOWN
                            Log.w(
                                TAG,
                                ("Unsupported PCM bit depth: "
                                        + audioBitDepth
                                        + ". Setting mimeType to "
                                        + mimeType)
                            )
                        }
                    } else {
                        mimeType = MimeTypes.AUDIO_UNKNOWN
                        Log.w(TAG, "Non-PCM MS/ACM is unsupported. Setting mimeType to " + mimeType)
                    }
                }

                CODEC_ID_PCM_INT_LIT -> {
                    mimeType = MimeTypes.AUDIO_RAW
                    pcmEncoding = Util.getPcmEncoding(audioBitDepth)
                    if (pcmEncoding == C.ENCODING_INVALID) {
                        pcmEncoding = Format.NO_VALUE
                        mimeType = MimeTypes.AUDIO_UNKNOWN
                        Log.w(
                            TAG,
                            ("Unsupported little endian PCM bit depth: "
                                    + audioBitDepth
                                    + ". Setting mimeType to "
                                    + mimeType)
                        )
                    }
                }

                CODEC_ID_PCM_INT_BIG -> {
                    mimeType = MimeTypes.AUDIO_RAW
                    pcmEncoding = Util.getPcmEncoding(audioBitDepth, ByteOrder.BIG_ENDIAN)
                    if (pcmEncoding == C.ENCODING_INVALID) {
                        pcmEncoding = Format.NO_VALUE
                        mimeType = MimeTypes.AUDIO_UNKNOWN
                        Log.w(
                            TAG,
                            ("Unsupported big endian PCM bit depth: "
                                    + audioBitDepth
                                    + ". Setting mimeType to "
                                    + mimeType)
                        )
                    }
                }

                CODEC_ID_PCM_FLOAT -> {
                    mimeType = MimeTypes.AUDIO_RAW
                    pcmEncoding = Util.getFloatPcmEncoding(audioBitDepth)
                    if (pcmEncoding == C.ENCODING_INVALID) {
                        pcmEncoding = Format.NO_VALUE
                        mimeType = MimeTypes.AUDIO_UNKNOWN
                        Log.w(
                            TAG,
                            ("Unsupported floating point PCM bit depth: "
                                    + audioBitDepth
                                    + ". Setting mimeType to "
                                    + mimeType)
                        )
                    }
                }

                CODEC_ID_SUBRIP -> mimeType = MimeTypes.APPLICATION_SUBRIP
                CODEC_ID_ASS, CODEC_ID_SSA -> {
                    mimeType = MimeTypes.TEXT_SSA
                    initializationData = listOf(SSA_DIALOGUE_FORMAT, getCodecPrivate(codecId))
                }

                CODEC_ID_VTT -> mimeType = MimeTypes.TEXT_VTT
                CODEC_ID_VOBSUB -> {
                    mimeType = MimeTypes.APPLICATION_VOBSUB
                    initializationData = listOf(getCodecPrivate(codecId))
                }

                CODEC_ID_PGS -> mimeType = MimeTypes.APPLICATION_PGS
                CODEC_ID_DVBSUB -> {
                    mimeType = MimeTypes.APPLICATION_DVBSUBS
                    // Init data: composition_page (2), ancillary_page (2)
                    val initializationDataBytes = ByteArray(4)
                    System.arraycopy(getCodecPrivate(codecId), 0, initializationDataBytes, 0, 4)
                    initializationData = listOf(initializationDataBytes)
                }

                else -> throw ParserException.createForMalformedContainer(
                    "Unrecognized codec identifier.", null
                )
            }

            if (dolbyVisionConfigBytes != null) {
                val dolbyVisionConfig: DolbyVisionConfig? =
                    DolbyVisionConfig.parse(ParsableByteArray(this.dolbyVisionConfigBytes!!))
                if (dolbyVisionConfig != null) {
                    codecs = dolbyVisionConfig.codecs
                    mimeType = MimeTypes.VIDEO_DOLBY_VISION
                }
            }

            @C.SelectionFlags var selectionFlags: @C.SelectionFlags Int = 0
            selectionFlags = selectionFlags or if (flagDefault) C.SELECTION_FLAG_DEFAULT else 0
            selectionFlags = selectionFlags or if (flagForced) C.SELECTION_FLAG_FORCED else 0

            val formatBuilder = Format.Builder()
            // TODO: Consider reading the name elements of the tracks and, if present, incorporating them
            // into the trackId passed when creating the formats.
            if (MimeTypes.isAudio(mimeType)) {
                formatBuilder
                    .setChannelCount(channelCount)
                    .setChannelMask(channelMask)
                    .setSampleRate(sampleRate)
                    .setPcmEncoding(pcmEncoding)
            } else if (MimeTypes.isVideo(mimeType)) {
                if (displayUnit == DISPLAY_UNIT_PIXELS) {
                    displayWidth = if (displayWidth == Format.NO_VALUE) width else displayWidth
                    displayHeight = if (displayHeight == Format.NO_VALUE) height else displayHeight
                }
                var pixelWidthHeightRatio = Format.NO_VALUE.toFloat()
                if (displayWidth != Format.NO_VALUE && displayHeight != Format.NO_VALUE) {
                    pixelWidthHeightRatio =
                        ((height * displayWidth).toFloat()) / (width * displayHeight)
                }

                val colorSpace: @C.ColorSpace Int
                val colorTransfer: @C.ColorTransfer Int
                val colorRange: @C.ColorRange Int

                // Bitstream color space/transfer override container values as a block.
                if (bitstreamColorSpace != Format.NO_VALUE || bitstreamColorTransfer != Format.NO_VALUE) {
                    colorSpace = bitstreamColorSpace
                    colorTransfer = bitstreamColorTransfer
                    colorRange = bitstreamColorRange
                } else if (bitstreamColorRange != Format.NO_VALUE
                    && this.containerColorRange == Format.NO_VALUE
                ) {
                    colorSpace = this.containerColorSpace
                    colorTransfer = this.containerColorTransfer
                    colorRange = bitstreamColorRange
                } else {
                    colorSpace = this.containerColorSpace
                    colorTransfer = this.containerColorTransfer
                    colorRange = this.containerColorRange
                }

                // Set default luma and chroma bit depths to 8 as old codecs might not even signal them.
                val lumaBitdepth =
                    if (bitstreamLumaBitdepth != Format.NO_VALUE)
                        bitstreamLumaBitdepth
                    else
                        (if (this.bitsPerChannel != Format.NO_VALUE) this.bitsPerChannel else 8)
                val chromaBitdepth =
                    if (bitstreamChromaBitdepth != Format.NO_VALUE)
                        bitstreamChromaBitdepth
                    else
                        (if (this.bitsPerChannel != Format.NO_VALUE) this.bitsPerChannel else 8)

                val hdrStaticInfo = this.hdrStaticInfo
                val colorInfo: ColorInfo =
                    ColorInfo.Builder()
                        .setColorSpace(colorSpace)
                        .setColorRange(colorRange)
                        .setColorTransfer(colorTransfer)
                        .setHdrStaticInfo(hdrStaticInfo)
                        .setLumaBitdepth(lumaBitdepth)
                        .setChromaBitdepth(chromaBitdepth)
                        .build()
                var rotationDegrees = Format.NO_VALUE

                if (name != null && TRACK_NAME_TO_ROTATION_DEGREES.containsKey(name)) {
                    rotationDegrees = TRACK_NAME_TO_ROTATION_DEGREES.get(name)!!
                }
                if (projectionType == C.PROJECTION_RECTANGULAR && projectionPoseYaw.compareTo(0f) == 0 && projectionPosePitch.compareTo(
                        0f
                    ) == 0
                ) {
                    // The range of projectionPoseRoll is [-180, 180].
                    if (projectionPoseRoll.compareTo(0f) == 0) {
                        rotationDegrees = 0
                    } else if (projectionPoseRoll.compareTo(90f) == 0) {
                        rotationDegrees = 90
                    } else if (projectionPoseRoll.compareTo(-180f) == 0
                        || projectionPoseRoll.compareTo(180f) == 0
                    ) {
                        rotationDegrees = 180
                    } else if (projectionPoseRoll.compareTo(-90f) == 0) {
                        rotationDegrees = 270
                    }
                }
                formatBuilder
                    .setWidth(width)
                    .setHeight(height)
                    .setPixelWidthHeightRatio(pixelWidthHeightRatio)
                    .setRotationDegrees(rotationDegrees)
                    .setProjectionData(projectionData)
                    .setStereoMode(stereoMode)
                    .setColorInfo(colorInfo)
            } else if (MimeTypes.APPLICATION_SUBRIP == mimeType
                || MimeTypes.TEXT_SSA == mimeType
                || MimeTypes.TEXT_VTT == mimeType
                || MimeTypes.APPLICATION_VOBSUB == mimeType
                || MimeTypes.APPLICATION_PGS == mimeType
                || MimeTypes.APPLICATION_DVBSUBS == mimeType
            ) {
            } else {
                throw ParserException.createForMalformedContainer(
                    "Unexpected MIME type.", null
                )
            }

            if (name != null && !TRACK_NAME_TO_ROTATION_DEGREES.containsKey(name)) {
                formatBuilder.setLabel(name)
            }

            format =
                formatBuilder
                    .setId(trackId)
                    .setContainerMimeType(if (isWebm) MimeTypes.VIDEO_WEBM else MimeTypes.VIDEO_MATROSKA)
                    .setSampleMimeType(mimeType)
                    .setMaxInputSize(maxInputSize)
                    .setLanguage(language)
                    .setSelectionFlags(selectionFlags)
                    .setInitializationData(initializationData)
                    .setCodecs(codecs)
                    .setDrmInitData(drmInitData)
                    .build()
        }

        @RequiresNonNull("output")
        fun outputPendingSampleMetadata() {
            trueHdSampleRechunker?.outputPendingSampleMetadata(output!!, cryptoData)
        }

        fun reset() {
            trueHdSampleRechunker?.reset()
        }

        fun samplesHaveSupplementalData(isBlockGroup: Boolean): Boolean {
            if (CODEC_ID_OPUS == codecId) {
                // At the end of a BlockGroup, a positive DiscardPadding value will be written out as
                // supplemental data for Opus codec. Otherwise (i.e. DiscardPadding <= 0) supplemental data
                // size will be 0.
                return isBlockGroup
            }
            return maxBlockAdditionId > 0
        }

        private val hdrStaticInfo: ByteArray?
            get() {
                // Are all fields present.
                if (primaryRChromaticityX == Format.NO_VALUE.toFloat() || primaryRChromaticityY == Format.NO_VALUE.toFloat() || primaryGChromaticityX == Format.NO_VALUE.toFloat() || primaryGChromaticityY == Format.NO_VALUE.toFloat() || primaryBChromaticityX == Format.NO_VALUE.toFloat() || primaryBChromaticityY == Format.NO_VALUE.toFloat() || whitePointChromaticityX == Format.NO_VALUE.toFloat() || whitePointChromaticityY == Format.NO_VALUE.toFloat() || maxMasteringLuminance == Format.NO_VALUE.toFloat() || minMasteringLuminance == Format.NO_VALUE.toFloat()) {
                    return null
                }

                val hdrStaticInfoData = ByteArray(25)
                val hdrStaticInfo =
                    ByteBuffer.wrap(hdrStaticInfoData)
                        .order(ByteOrder.LITTLE_ENDIAN)
                hdrStaticInfo.put(0.toByte()) // Type.
                hdrStaticInfo.putShort(
                    ((primaryRChromaticityX * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort(
                    ((primaryRChromaticityY * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort(
                    ((primaryGChromaticityX * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort(
                    ((primaryGChromaticityY * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort(
                    ((primaryBChromaticityX * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort(
                    ((primaryBChromaticityY * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort(
                    ((whitePointChromaticityX * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort(
                    ((whitePointChromaticityY * MAX_CHROMATICITY) + 0.5f).toInt()
                        .toShort()
                )
                hdrStaticInfo.putShort((maxMasteringLuminance + 0.5f).toInt().toShort())
                hdrStaticInfo.putShort((minMasteringLuminance + 0.5f).toInt().toShort())
                hdrStaticInfo.putShort(maxContentLuminance.toShort())
                hdrStaticInfo.putShort(maxFrameAverageLuminance.toShort())
                return hdrStaticInfoData
            }

        fun maybeAddThumbnailMetadata(
            perTrackCues: SparseArray<MutableList<MatroskaSeekMap.CuePointData>?>,
            durationUs: Long,
            segmentContentPosition: Long,
            segmentContentSize: Long
        ) {
            if (type != C.TRACK_TYPE_VIDEO) {
                return
            }

            val cuePoints: MutableList<MatroskaSeekMap.CuePointData>? = perTrackCues.get(number)
            if (thumbnailMetadataAdded || cuePoints == null || cuePoints.isEmpty()) {
                return
            }

            val thumbnailTimestampUs: Long =
                findBestThumbnailPresentationTimeUs(
                    cuePoints, durationUs, segmentContentPosition, segmentContentSize
                )

            if (thumbnailTimestampUs != C.TIME_UNSET) {
                val existingMetadata = format!!.metadata
                val thumbnailMetadata = ThumbnailMetadata(thumbnailTimestampUs)
                val newMetadata =
                    if (existingMetadata == null)
                        Metadata(listOf(thumbnailMetadata))
                    else
                        existingMetadata.copyWithAppendedEntries(listOf(thumbnailMetadata))
                format = format!!.buildUpon().setMetadata(newMetadata).build()
                thumbnailMetadataAdded = true
            }
        }

        fun maybeAddChaptersMetadata(chapterEntries: LongSparseArray<ChapterEntry>) {
            if (chaptersMetadataAdded || chapterEntries.size() == 0) {
                return
            }
            val chapters: MutableList<Chapter> = ArrayList<Chapter>(chapterEntries.size())
            for (i in 0..<chapterEntries.size()) {
                val chapterEntry = chapterEntries.valueAt(i)
                // Check if the chapter is tied to a specific track (and whether it's *this* track).
                if (chapterEntry.trackUid == 0L || chapterEntry.trackUid == uid) {
                    val chapter =
                        Chapter.Builder()
                            .setStartTimeMs(Util.nsToMs(chapterEntry.timeStartNs))
                            .setEndTimeMs(Util.nsToMs(chapterEntry.timeEndNs))
                            .setHidden(chapterEntry.flagHidden)
                    if (chapterEntry.chapString != null) {
                        chapter.setTitle(
                            Label(
                                chapterEntry.chapLanguage,
                                chapterEntry.chapString!!
                            )
                        )
                    }
                    chapters.add(chapter.build())
                }
            }
            if (!chapters.isEmpty()) {
                format = format!!.buildUpon()
                    .setMetadata(
                        if (format!!.metadata != null)
                            format!!.metadata!!.copyWithAppendedEntries(chapters)
                        else
                            Metadata(chapters)
                    )
                    .build()
                chaptersMetadataAdded = true
            }
        }

        @Throws(ParserException::class)
        private fun parseMsAcmCodecPrivate(buffer: ParsableByteArray): Boolean {
            try {
                val formatTag: Int = buffer.readLittleEndianUnsignedShort()
                if (formatTag == WAVE_FORMAT_PCM) {
                    return true
                } else if (formatTag == WAVE_FORMAT_EXTENSIBLE) {
                    buffer.setPosition(WAVE_FORMAT_SIZE + 2) // unionSamples(2)
                    val wavChannelMask: Int = buffer.readLittleEndianUnsignedIntToInt()
                    if (WavUtil.isChannelMaskValid(wavChannelMask, channelCount)) {
                        channelMask = WavUtil.mapToAudioFormatChannelMask(wavChannelMask)
                    }
                    return buffer.readLong() == WAVE_SUBFORMAT_PCM.getMostSignificantBits()
                            && buffer.readLong() == WAVE_SUBFORMAT_PCM.getLeastSignificantBits()
                } else {
                    return false
                }
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw ParserException.createForMalformedContainer(
                    "Error parsing MS/ACM codec private", null
                )
            }
        }

        fun assertOutputInitialized() {
            output!!
        }

        @Throws(ParserException::class)
        private fun getCodecPrivate(codecId: String?): ByteArray {
            if (codecPrivate == null) {
                throw ParserException.createForMalformedContainer(
                    "Missing CodecPrivate for codec " + codecId, null
                )
            }
            return codecPrivate!!
        }

        companion object {
            private const val DISPLAY_UNIT_PIXELS = 0
            private const val MAX_CHROMATICITY = 50000 // Defined in CTA-861.3.
            private const val DEFAULT_MAX_CLL = 1000 // nits.
            private const val DEFAULT_MAX_FALL = 200 // nits.

            private fun findBestThumbnailPresentationTimeUs(
                cuePoints: MutableList<MatroskaSeekMap.CuePointData>,
                durationUs: Long,
                segmentContentPosition: Long,
                segmentContentSize: Long
            ): Long {
                if (cuePoints.isEmpty()) {
                    return C.TIME_UNSET
                }

                var maxBitrate = 0.0
                var bestCueIndex = -1
                val scanLimit = min(cuePoints.size, MAX_CHUNKS_TO_SCAN_FOR_THUMBNAIL)

                for (i in 0..<scanLimit) {
                    val cue: MatroskaSeekMap.CuePointData = cuePoints[i]

                    if (cue.timeUs > MAX_DURATION_US_TO_SCAN_FOR_THUMBNAIL) {
                        break
                    }

                    val bytesBetweenCues: Long
                    val durationBetweenCuesUs: Long

                    if (i < cuePoints.size - 1) {
                        val nextCue: MatroskaSeekMap.CuePointData = cuePoints.get(i + 1)
                        bytesBetweenCues =
                            ((nextCue.clusterPosition + nextCue.relativePosition)
                                    - (cue.clusterPosition + cue.relativePosition))
                        durationBetweenCuesUs = nextCue.timeUs - cue.timeUs
                    } else {
                        // Last cue point
                        bytesBetweenCues =
                            ((segmentContentPosition + segmentContentSize)
                                    - (cue.clusterPosition + cue.relativePosition))
                        durationBetweenCuesUs = durationUs - cue.timeUs
                    }

                    if (durationBetweenCuesUs > 0) {
                        // This is an approximation of the bitrate for thumbnail heuristic.
                        val bitrate = bytesBetweenCues.toDouble() / durationBetweenCuesUs
                        if (bitrate > maxBitrate) {
                            maxBitrate = bitrate
                            bestCueIndex = i
                        }
                    }
                }

                return if (bestCueIndex == -1) C.TIME_UNSET else cuePoints.get(bestCueIndex).timeUs
            }

            @Throws(ParserException::class)
            private fun parseFourCcPrivate(
                buffer: ParsableByteArray
            ): Pair<String?, MutableList<ByteArray>?> {
                try {
                    buffer.skipBytes(16) // size(4), width(4), height(4), planes(2), bitcount(2).
                    val compression: Long = buffer.readLittleEndianUnsignedInt()
                    if (compression == FOURCC_COMPRESSION_DIVX.toLong()) {
                        return Pair<String?, MutableList<ByteArray>?>(MimeTypes.VIDEO_DIVX, null)
                    } else if (compression == FOURCC_COMPRESSION_H263.toLong()) {
                        return Pair<String?, MutableList<ByteArray>?>(MimeTypes.VIDEO_H263, null)
                    } else if (compression == FOURCC_COMPRESSION_VC1.toLong()) {
                        // Search for the initialization data from the end of the BITMAPINFOHEADER. The last 20
                        // bytes of which are: sizeImage(4), xPel/m (4), yPel/m (4), clrUsed(4), clrImportant(4).
                        val startOffset: Int = buffer.getPosition() + 20
                        val bufferData: ByteArray = buffer.data
                        for (offset in startOffset..<bufferData.size - 4) {
                            if (bufferData[offset].toInt() == 0x00 && bufferData[offset + 1].toInt() == 0x00 && bufferData[offset + 2].toInt() == 0x01 && bufferData[offset + 3].toInt() == 0x0F) {
                                // We've found the initialization data.
                                val initializationData =
                                    Arrays.copyOfRange(bufferData, offset, bufferData.size)
                                return Pair<String?, MutableList<ByteArray>?>(
                                    MimeTypes.VIDEO_VC1,
                                    mutableListOf<ByteArray>(initializationData)
                                )
                            }
                        }
                        throw ParserException.createForMalformedContainer(
                            "Failed to find FourCC VC1 initialization data", null
                        )
                    }
                } catch (e: ArrayIndexOutOfBoundsException) {
                    throw ParserException.createForMalformedContainer(
                        "Error parsing FourCC private data", null
                    )
                }

                Log.w(TAG, "Unknown FourCC. Setting mimeType to " + MimeTypes.VIDEO_UNKNOWN)
                return Pair<String?, MutableList<ByteArray>?>(MimeTypes.VIDEO_UNKNOWN, null)
            }

            @Throws(ParserException::class)
            private fun parseVorbisCodecPrivate(codecPrivate: ByteArray): MutableList<ByteArray> {
                try {
                    if (codecPrivate[0].toInt() != 0x02) {
                        throw ParserException.createForMalformedContainer(
                            "Error parsing vorbis codec private", null
                        )
                    }
                    var offset = 1
                    var vorbisInfoLength = 0
                    while ((codecPrivate[offset].toInt() and 0xFF) == 0xFF) {
                        vorbisInfoLength += 0xFF
                        offset++
                    }
                    vorbisInfoLength += codecPrivate[offset++].toInt() and 0xFF

                    var vorbisSkipLength = 0
                    while ((codecPrivate[offset].toInt() and 0xFF) == 0xFF) {
                        vorbisSkipLength += 0xFF
                        offset++
                    }
                    vorbisSkipLength += codecPrivate[offset++].toInt() and 0xFF

                    if (codecPrivate[offset].toInt() != 0x01) {
                        throw ParserException.createForMalformedContainer(
                            "Error parsing vorbis codec private", null
                        )
                    }
                    val vorbisInfo = ByteArray(vorbisInfoLength)
                    System.arraycopy(codecPrivate, offset, vorbisInfo, 0, vorbisInfoLength)
                    offset += vorbisInfoLength
                    if (codecPrivate[offset].toInt() != 0x03) {
                        throw ParserException.createForMalformedContainer(
                            "Error parsing vorbis codec private", null
                        )
                    }
                    offset += vorbisSkipLength
                    if (codecPrivate[offset].toInt() != 0x05) {
                        throw ParserException.createForMalformedContainer(
                            "Error parsing vorbis codec private", null
                        )
                    }
                    val vorbisBooks = ByteArray(codecPrivate.size - offset)
                    System.arraycopy(
                        codecPrivate,
                        offset,
                        vorbisBooks,
                        0,
                        codecPrivate.size - offset
                    )
                    val initializationData: MutableList<ByteArray> = ArrayList<ByteArray>(2)
                    initializationData.add(vorbisInfo)
                    initializationData.add(vorbisBooks)
                    return initializationData
                } catch (e: ArrayIndexOutOfBoundsException) {
                    throw ParserException.createForMalformedContainer(
                        "Error parsing vorbis codec private", null
                    )
                }
            }
        }
    }

    class MatroskaSeekMap(
        private val perTrackCues: SparseArray<MutableList<MatroskaSeekMap.CuePointData>?>,
        override val durationUs: Long,
        private val primarySeekTrackNumber: Int,
        segmentContentPosition: Long,
        segmentContentSize: Long
    ) : TrackAwareSeekMap, ChunkIndexProvider {
        override var chunkIndex: ChunkIndex?

        init {
            this.chunkIndex =
                buildChunkIndex(
                    perTrackCues,
                    durationUs,
                    primarySeekTrackNumber,
                    segmentContentPosition,
                    segmentContentSize
                )
        }

        override val isSeekable: Boolean
            get() =// The media is seekable overall only if the primary seek track has cue points.
                isSeekable(primarySeekTrackNumber)

        override fun isSeekable(trackId: Int): Boolean {
            val cuePoints = perTrackCues.get(trackId)
            return cuePoints != null && !cuePoints.isEmpty()
        }

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (chunkIndex != null) {
                return chunkIndex!!.getSeekPoints(timeUs)
            }
            return SeekMap.SeekPoints(SeekPoint.START)
        }

        override fun getSeekPoints(timeUs: Long, trackId: Int): SeekMap.SeekPoints {
            var cuePoints = perTrackCues.get(trackId)
            if ((cuePoints == null || cuePoints.isEmpty()) && trackId != primarySeekTrackNumber) {
                cuePoints = perTrackCues.get(primarySeekTrackNumber)
            }
            if (cuePoints == null || cuePoints.isEmpty()) {
                return SeekMap.SeekPoints(SeekPoint.START)
            }

            val bestIndex =
                Util.binarySearchFloor(
                    cuePoints,
                    MatroskaSeekMap.CuePointData(
                        timeUs,
                        C.INDEX_UNSET.toLong(),
                        C.INDEX_UNSET.toLong()
                    ),
                    true,
                    false
                )

            if (bestIndex != -1) {
                val bestCue = cuePoints.get(bestIndex)
                val firstPoint = SeekPoint(bestCue.timeUs, bestCue.clusterPosition)

                if (bestCue.timeUs < timeUs && bestIndex + 1 < cuePoints.size) {
                    val nextCue = cuePoints.get(bestIndex + 1)
                    val secondPoint = SeekPoint(nextCue.timeUs, nextCue.clusterPosition)
                    return SeekMap.SeekPoints(firstPoint, secondPoint)
                } else {
                    return SeekMap.SeekPoints(firstPoint)
                }
            } else {
                val firstCue = cuePoints[0]
                return SeekMap.SeekPoints(SeekPoint(firstCue.timeUs, firstCue.clusterPosition))
            }
        }

        class CuePointData(
            val timeUs: Long,
            val clusterPosition: Long,
            val relativePosition: Long
        ) : Comparable<CuePointData> {
            override fun compareTo(other: CuePointData): Int {
                return timeUs.compareTo(other.timeUs)
            }

            override fun equals(obj: Any?): Boolean {
                if (this === obj) {
                    return true
                }
                if (obj !is CuePointData) {
                    return false
                }
                val other = obj
                return this.timeUs == other.timeUs && this.clusterPosition == other.clusterPosition && this.relativePosition == other.relativePosition
            }

            override fun hashCode(): Int {
                return Objects.hash(timeUs, clusterPosition, relativePosition)
            }
        }

        companion object {
            private fun buildChunkIndex(
                perTrackCues: SparseArray<MutableList<CuePointData>?>,
                durationUs: kotlin.Long,
                primarySeekTrackNumber: Int,
                segmentContentPosition: kotlin.Long,
                segmentContentSize: kotlin.Long
            ): ChunkIndex? {
                val primaryTrackCuePoints = perTrackCues.get(primarySeekTrackNumber)
                if (primaryTrackCuePoints == null || primaryTrackCuePoints.isEmpty()) {
                    return null
                }

                val cuePointsSize = primaryTrackCuePoints.size
                var sizes = IntArray(cuePointsSize)
                var offsets = LongArray(cuePointsSize)
                var durationsUs = LongArray(cuePointsSize)
                var timesUs = LongArray(cuePointsSize)

                for (i in 0..<cuePointsSize) {
                    val cue = primaryTrackCuePoints[i]
                    timesUs[i] = cue.timeUs
                    offsets[i] = cue.clusterPosition
                }

                for (i in 0..<cuePointsSize - 1) {
                    sizes[i] = (offsets[i + 1] - offsets[i]).toInt()
                    durationsUs[i] = timesUs[i + 1] - timesUs[i]
                }

                // Start from the last cue point and move backward until a valid duration is found.
                var lastValidIndex = cuePointsSize - 1
                while (lastValidIndex > 0 && timesUs[lastValidIndex] >= durationUs) {
                    lastValidIndex--
                }

                // Calculate sizes and durations for the last valid index
                sizes[lastValidIndex] =
                    (segmentContentPosition + segmentContentSize - offsets[lastValidIndex]).toInt()
                durationsUs[lastValidIndex] = durationUs - timesUs[lastValidIndex]

                // If trailing cue points were found, truncate the arrays to the last valid index.
                if (lastValidIndex < cuePointsSize - 1) {
                    Log.w(
                        TAG,
                        "Discarding trailing cue points with timestamps greater than total duration."
                    )
                    sizes = sizes.copyOf(lastValidIndex + 1)
                    offsets = offsets.copyOf(lastValidIndex + 1)
                    durationsUs = durationsUs.copyOf(lastValidIndex + 1)
                    timesUs = timesUs.copyOf(lastValidIndex + 1)
                }

                return ChunkIndex(sizes, offsets, durationsUs, timesUs)
            }
        }
    }

    companion object {
        fun newFactory(subtitleParserFactory: SubtitleParser.Factory?): ExtractorsFactory {
            return object : ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> {
                    return arrayOf<Extractor>(MatroskaExtractor(subtitleParserFactory))
                }
            }
        }

        const val FLAG_DISABLE_SEEK_FOR_CUES: Int = 1
        const val FLAG_EMIT_RAW_SUBTITLE_DATA: Int = 1 shl 1 // 2

        @Deprecated("Use {@link #newFactory(SubtitleParser.Factory)} instead.")
        val FACTORY: ExtractorsFactory =
            object : ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> {
                    return arrayOf<Extractor>(
                        MatroskaExtractor(
                            SubtitleParser.Factory.UNSUPPORTED,
                            FLAG_EMIT_RAW_SUBTITLE_DATA
                        )
                    )
                }
            }

        private const val TAG = "MatroskaExtractor"

        private val UNSET_ENTRY_ID = -1

        private const val BLOCK_STATE_START = 0
        private const val BLOCK_STATE_HEADER = 1
        private const val BLOCK_STATE_DATA = 2

        private const val DOC_TYPE_MATROSKA = "matroska"
        private const val DOC_TYPE_WEBM = "webm"
        private const val CODEC_ID_VP8 = "V_VP8"
        private const val CODEC_ID_VP9 = "V_VP9"
        private const val CODEC_ID_AV1 = "V_AV1"
        private const val CODEC_ID_MPEG2 = "V_MPEG2"
        private const val CODEC_ID_MPEG4_SP = "V_MPEG4/ISO/SP"
        private const val CODEC_ID_MPEG4_ASP = "V_MPEG4/ISO/ASP"
        private const val CODEC_ID_MPEG4_AP = "V_MPEG4/ISO/AP"
        private const val CODEC_ID_H264 = "V_MPEG4/ISO/AVC"
        private const val CODEC_ID_H265 = "V_MPEGH/ISO/HEVC"
        private const val CODEC_ID_FOURCC = "V_MS/VFW/FOURCC"
        private const val CODEC_ID_THEORA = "V_THEORA"
        private const val CODEC_ID_VORBIS = "A_VORBIS"
        private const val CODEC_ID_OPUS = "A_OPUS"
        private const val CODEC_ID_AAC = "A_AAC"
        private const val CODEC_ID_MP2 = "A_MPEG/L2"
        private const val CODEC_ID_MP3 = "A_MPEG/L3"
        private const val CODEC_ID_AC3 = "A_AC3"
        private const val CODEC_ID_E_AC3 = "A_EAC3"
        private const val CODEC_ID_TRUEHD = "A_TRUEHD"
        private const val CODEC_ID_DTS = "A_DTS"
        private const val CODEC_ID_DTS_EXPRESS = "A_DTS/EXPRESS"
        private const val CODEC_ID_DTS_LOSSLESS = "A_DTS/LOSSLESS"
        private const val CODEC_ID_FLAC = "A_FLAC"
        private const val CODEC_ID_ALAC = "A_ALAC"
        private const val CODEC_ID_ACM = "A_MS/ACM"
        private const val CODEC_ID_PCM_INT_LIT = "A_PCM/INT/LIT"
        private const val CODEC_ID_PCM_INT_BIG = "A_PCM/INT/BIG"
        private const val CODEC_ID_PCM_FLOAT = "A_PCM/FLOAT/IEEE"
        private const val CODEC_ID_SUBRIP = "S_TEXT/UTF8"
        private const val CODEC_ID_ASS = "S_TEXT/ASS"
        private const val CODEC_ID_SSA = "S_TEXT/SSA"
        private const val CODEC_ID_VTT = "S_TEXT/WEBVTT"
        private const val CODEC_ID_VOBSUB = "S_VOBSUB"
        private const val CODEC_ID_PGS = "S_HDMV/PGS"
        private const val CODEC_ID_DVBSUB = "S_DVBSUB"

        private const val VORBIS_MAX_INPUT_SIZE = 8192
        private const val OPUS_MAX_INPUT_SIZE = 5760
        private const val ENCRYPTION_IV_SIZE = 8

        private const val ID_EBML = 0x1A45DFA3
        private const val ID_EBML_READ_VERSION = 0x42F7
        private const val ID_DOC_TYPE = 0x4282
        private const val ID_DOC_TYPE_READ_VERSION = 0x4285
        private const val ID_SEGMENT = 0x18538067
        private const val ID_SEGMENT_INFO = 0x1549A966
        private const val ID_CHAPTERS = 0x1043A770
        private const val ID_EDITION_ENTRY = 0x45B9
        private const val ID_CHAPTER_FLAG_HIDDEN = 0x98
        private const val ID_CHAPTER_ATOM = 0xB6
        private const val ID_CHAPTER_UID = 0x73C4
        private const val ID_CHAPTER_TIME_START = 0x91
        private const val ID_CHAPTER_TIME_END = 0x92
        private const val ID_CHAPTER_TRACK = 0x8F
        private const val ID_CHAPTER_TRACK_UID = 0x89
        private const val ID_CHAPTER_DISPLAY = 0x80
        private const val ID_CHAP_STRING = 0x85
        private const val ID_CHAP_LANGUAGE = 0x437C
        private const val ID_SEEK_HEAD = 0x114D9B74
        private const val ID_SEEK = 0x4DBB
        private const val ID_SEEK_ID = 0x53AB
        private const val ID_SEEK_POSITION = 0x53AC
        private const val ID_INFO = 0x1549A966
        private const val ID_TIMECODE_SCALE = 0x2AD7B1
        private const val ID_DURATION = 0x4489
        private const val ID_CLUSTER = 0x1F43B675
        private const val ID_TIME_CODE = 0xE7
        private const val ID_SIMPLE_BLOCK = 0xA3
        private const val ID_BLOCK_GROUP = 0xA0
        private const val ID_BLOCK = 0xA1
        private const val ID_BLOCK_DURATION = 0x9B
        private const val ID_BLOCK_ADDITIONS = 0x75A1
        private const val ID_BLOCK_MORE = 0xA6
        private const val ID_BLOCK_ADD_ID = 0xEE
        private const val ID_BLOCK_ADDITIONAL = 0xA5
        private const val ID_REFERENCE_BLOCK = 0xFB
        private const val ID_TRACKS = 0x1654AE6B
        private const val ID_TRACK_ENTRY = 0xAE
        private const val ID_TRACK_NUMBER = 0xD7
        private const val ID_TRACK_UID = 0x73C5
        private const val ID_TRACK_TYPE = 0x83
        private const val ID_FLAG_DEFAULT = 0x88
        private const val ID_FLAG_FORCED = 0x55AA
        private const val ID_DEFAULT_DURATION = 0x23E383
        private const val ID_MAX_BLOCK_ADDITION_ID = 0x55EE
        private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4
        private const val ID_BLOCK_ADD_ID_TYPE = 0x41E7
        private const val ID_BLOCK_ADD_ID_EXTRA_DATA = 0x41ED
        private const val ID_NAME = 0x536E
        private const val ID_CODEC_ID = 0x86
        private const val ID_CODEC_PRIVATE = 0x63A2
        private const val ID_CODEC_DELAY = 0x56AA
        private const val ID_SEEK_PRE_ROLL = 0x56BB
        private const val ID_DISCARD_PADDING = 0x75A2
        private const val ID_VIDEO = 0xE0
        private const val ID_PIXEL_WIDTH = 0xB0
        private const val ID_PIXEL_HEIGHT = 0xBA
        private const val ID_DISPLAY_WIDTH = 0x54B0
        private const val ID_DISPLAY_HEIGHT = 0x54BA
        private const val ID_DISPLAY_UNIT = 0x54B2
        private const val ID_AUDIO = 0xE1
        private const val ID_CHANNELS = 0x9F
        private const val ID_AUDIO_BIT_DEPTH = 0x6264
        private const val ID_SAMPLING_FREQUENCY = 0xB5
        private const val ID_CONTENT_ENCODINGS = 0x6D80
        private const val ID_CONTENT_ENCODING = 0x6240
        private const val ID_CONTENT_ENCODING_ORDER = 0x5031
        private const val ID_CONTENT_ENCODING_SCOPE = 0x5032
        private const val ID_CONTENT_COMPRESSION = 0x5034
        private const val ID_CONTENT_COMPRESSION_ALGORITHM = 0x4254
        private const val ID_CONTENT_COMPRESSION_SETTINGS = 0x4255
        private const val ID_CONTENT_ENCRYPTION = 0x5035
        private const val ID_CONTENT_ENCRYPTION_ALGORITHM = 0x47E1
        private const val ID_CONTENT_ENCRYPTION_KEY_ID = 0x47E2
        private const val ID_CONTENT_ENCRYPTION_AES_SETTINGS = 0x47E7
        private const val ID_CONTENT_ENCRYPTION_AES_SETTINGS_CIPHER_MODE = 0x47E8
        private const val ID_CUES = 0x1C53BB6B
        private const val ID_CUE_POINT = 0xBB
        private const val ID_CUE_TIME = 0xB3
        private const val ID_CUE_TRACK = 0xF7
        private const val ID_CUE_TRACK_POSITIONS = 0xB7
        private const val ID_CUE_CLUSTER_POSITION = 0xF1
        private const val ID_CUE_RELATIVE_POSITION = 0xF0
        private const val ID_LANGUAGE = 0x22B59C
        private const val ID_PROJECTION = 0x7670
        private const val ID_PROJECTION_TYPE = 0x7671
        private const val ID_PROJECTION_PRIVATE = 0x7672
        private const val ID_PROJECTION_POSE_YAW = 0x7673
        private const val ID_PROJECTION_POSE_PITCH = 0x7674
        private const val ID_PROJECTION_POSE_ROLL = 0x7675
        private const val ID_STEREO_MODE = 0x53B8
        private const val ID_COLOUR = 0x55B0
        private const val ID_COLOUR_RANGE = 0x55B9
        private const val ID_COLOUR_BITS_PER_CHANNEL = 0x55B2
        private const val ID_COLOUR_TRANSFER = 0x55BA
        private const val ID_COLOUR_PRIMARIES = 0x55BB
        private const val ID_MAX_CLL = 0x55BC
        private const val ID_MAX_FALL = 0x55BD
        private const val ID_MASTERING_METADATA = 0x55D0
        private const val ID_PRIMARY_R_CHROMATICITY_X = 0x55D1
        private const val ID_PRIMARY_R_CHROMATICITY_Y = 0x55D2
        private const val ID_PRIMARY_G_CHROMATICITY_X = 0x55D3
        private const val ID_PRIMARY_G_CHROMATICITY_Y = 0x55D4
        private const val ID_PRIMARY_B_CHROMATICITY_X = 0x55D5
        private const val ID_PRIMARY_B_CHROMATICITY_Y = 0x55D6
        private const val ID_WHITE_POINT_CHROMATICITY_X = 0x55D7
        private const val ID_WHITE_POINT_CHROMATICITY_Y = 0x55D8
        private const val ID_LUMNINANCE_MAX = 0x55D9
        private const val ID_LUMNINANCE_MIN = 0x55DA

        private const val BLOCK_ADDITIONAL_ID_VP9_ITU_T_35 = 4

        private const val BLOCK_ADD_ID_TYPE_DVCC = 0x64766343

        private const val BLOCK_ADD_ID_TYPE_DVVC = 0x64767643

        private const val LACING_NONE = 0
        private const val LACING_XIPH = 1
        private const val LACING_FIXED_SIZE = 2
        private const val LACING_EBML = 3

        private const val FOURCC_COMPRESSION_DIVX = 0x58564944
        private const val FOURCC_COMPRESSION_H263 = 0x33363248
        private const val FOURCC_COMPRESSION_VC1 = 0x31435657

        private const val MAX_CHUNKS_TO_SCAN_FOR_THUMBNAIL = 20

        private const val MAX_DURATION_US_TO_SCAN_FOR_THUMBNAIL = 10000000L

        private val SUBRIP_PREFIX = byteArrayOf(
            49,
            10,
            48,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            44,
            48,
            48,
            48,
            32,
            45,
            45,
            62,
            32,
            48,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            44,
            48,
            48,
            48,
            10
        )

        private const val SUBRIP_PREFIX_END_TIMECODE_OFFSET = 19

        private const val SUBRIP_TIMECODE_LAST_VALUE_SCALING_FACTOR: kotlin.Long = 1000

        private const val SUBRIP_TIMECODE_FORMAT = "%02d:%02d:%02d,%03d"

        private val SSA_DIALOGUE_FORMAT = Util.getUtf8Bytes(
            "Format: Start, End, "
                    + "ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        )

        private val SSA_PREFIX = byteArrayOf(
            68,
            105,
            97,
            108,
            111,
            103,
            117,
            101,
            58,
            32,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            44,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            44
        )

        private const val SSA_PREFIX_END_TIMECODE_OFFSET = 21

        private const val SSA_TIMECODE_LAST_VALUE_SCALING_FACTOR: kotlin.Long = 10000

        private const val SSA_TIMECODE_FORMAT = "%01d:%02d:%02d:%02d"

        private val VTT_PREFIX = byteArrayOf(
            87,
            69,
            66,
            86,
            84,
            84,
            10,
            10,
            48,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            46,
            48,
            48,
            48,
            32,
            45,
            45,
            62,
            32,
            48,
            48,
            58,
            48,
            48,
            58,
            48,
            48,
            46,
            48,
            48,
            48,
            10
        )

        private const val VTT_PREFIX_END_TIMECODE_OFFSET = 25

        private const val VTT_TIMECODE_LAST_VALUE_SCALING_FACTOR: kotlin.Long = 1000

        private const val VTT_TIMECODE_FORMAT = "%02d:%02d:%02d.%03d"

        private const val WAVE_FORMAT_SIZE = 18

        private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

        private const val WAVE_FORMAT_PCM = 1

        private val WAVE_SUBFORMAT_PCM = UUID(0x0100000000001000L, -0x7fffff55ffc7648fL)

        private val TRACK_NAME_TO_ROTATION_DEGREES: MutableMap<String?, Int?>

        init {
            val trackNameToRotationDegrees: MutableMap<String?, Int?> = HashMap<String?, Int?>()
            trackNameToRotationDegrees.put("htc_video_rotA-000", 0)
            trackNameToRotationDegrees.put("htc_video_rotA-090", 90)
            trackNameToRotationDegrees.put("htc_video_rotA-180", 180)
            trackNameToRotationDegrees.put("htc_video_rotA-270", 270)
            TRACK_NAME_TO_ROTATION_DEGREES =
                Collections.unmodifiableMap<String?, Int?>(trackNameToRotationDegrees)
        }

        private fun setSubtitleEndTime(
            codecId: String,
            durationUs: kotlin.Long,
            subtitleData: ByteArray
        ) {
            val endTimecode: ByteArray
            val endTimecodeOffset: Int
            when (codecId) {
                CODEC_ID_SUBRIP -> {
                    endTimecode =
                        formatSubtitleTimecode(
                            durationUs,
                            SUBRIP_TIMECODE_FORMAT,
                            SUBRIP_TIMECODE_LAST_VALUE_SCALING_FACTOR
                        )
                    endTimecodeOffset = SUBRIP_PREFIX_END_TIMECODE_OFFSET
                }

                CODEC_ID_ASS, CODEC_ID_SSA -> {
                    endTimecode =
                        formatSubtitleTimecode(
                            durationUs, SSA_TIMECODE_FORMAT, SSA_TIMECODE_LAST_VALUE_SCALING_FACTOR
                        )
                    endTimecodeOffset = SSA_PREFIX_END_TIMECODE_OFFSET
                }

                CODEC_ID_VTT -> {
                    endTimecode =
                        formatSubtitleTimecode(
                            durationUs, VTT_TIMECODE_FORMAT, VTT_TIMECODE_LAST_VALUE_SCALING_FACTOR
                        )
                    endTimecodeOffset = VTT_PREFIX_END_TIMECODE_OFFSET
                }

                else -> throw IllegalArgumentException()
            }
            System.arraycopy(endTimecode, 0, subtitleData, endTimecodeOffset, endTimecode.size)
        }

        private fun formatSubtitleTimecode(
            timeUs: kotlin.Long, timecodeFormat: String, lastTimecodeValueScalingFactor: kotlin.Long
        ): ByteArray {
            var timeUs = timeUs
            Preconditions.checkArgument(timeUs != C.TIME_UNSET)
            val timeCodeData: ByteArray
            val hours = (timeUs / (3600 * C.MICROS_PER_SECOND)).toInt()
            timeUs -= (hours * 3600L * C.MICROS_PER_SECOND)
            val minutes = (timeUs / (60 * C.MICROS_PER_SECOND)).toInt()
            timeUs -= (minutes * 60L * C.MICROS_PER_SECOND)
            val seconds = (timeUs / C.MICROS_PER_SECOND).toInt()
            timeUs -= (seconds * C.MICROS_PER_SECOND)
            val lastValue = (timeUs / lastTimecodeValueScalingFactor).toInt()
            timeCodeData =
                Util.getUtf8Bytes(
                    kotlin.String.format(
                        java.util.Locale.US,
                        timecodeFormat,
                        hours,
                        minutes,
                        seconds,
                        lastValue
                    )
                )!!
            return timeCodeData
        }

        private fun isCodecSupported(codecId: String): Boolean {
            when (codecId) {
                CODEC_ID_VP8, CODEC_ID_VP9, CODEC_ID_AV1, CODEC_ID_MPEG2, CODEC_ID_MPEG4_SP, CODEC_ID_MPEG4_ASP, CODEC_ID_MPEG4_AP, CODEC_ID_H264, CODEC_ID_H265, CODEC_ID_FOURCC, CODEC_ID_THEORA, CODEC_ID_OPUS, CODEC_ID_VORBIS, CODEC_ID_AAC, CODEC_ID_MP2, CODEC_ID_MP3, CODEC_ID_AC3, CODEC_ID_E_AC3, CODEC_ID_TRUEHD, CODEC_ID_DTS, CODEC_ID_DTS_EXPRESS, CODEC_ID_DTS_LOSSLESS, CODEC_ID_FLAC, CODEC_ID_ALAC, CODEC_ID_ACM, CODEC_ID_PCM_INT_LIT, CODEC_ID_PCM_INT_BIG, CODEC_ID_PCM_FLOAT, CODEC_ID_SUBRIP, CODEC_ID_ASS, CODEC_ID_SSA, CODEC_ID_VTT, CODEC_ID_VOBSUB, CODEC_ID_PGS, CODEC_ID_DVBSUB -> return true
                else -> return false
            }
        }

        private fun ensureArrayCapacity(array: IntArray?, length: Int): IntArray {
            if (array == null) {
                return IntArray(length)
            } else if (array.size >= length) {
                return array
            } else {
                // Double the size to avoid allocating constantly if the required length increases gradually.
                return IntArray(max(array.size * 2, length))
            }
        }
    }
}
