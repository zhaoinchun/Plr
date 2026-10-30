
package androidx.media3.extractor.mp4

import android.util.Pair
import android.util.SparseArray
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.nullSafeArrayCopy
import androidx.media3.container.Mp4Box
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ReorderingBufferQueue
import androidx.media3.extractor.Ac4Util
import androidx.media3.extractor.CeaUtil
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.ChunkIndexMerger
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.emsg.EventMessage
import androidx.media3.extractor.metadata.emsg.EventMessageEncoder
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.google.common.base.Function
import com.google.common.base.Preconditions

import java.io.IOException
import java.util.ArrayDeque
import java.util.Arrays
import java.util.Collections
import kotlin.math.max

class FragmentedMp4Extractor  constructor(
    subtitleParserFactory: SubtitleParser.Factory,
    flags: @Flags Int = 0,
    timestampAdjuster: TimestampAdjuster? = null,
    sideloadedTrack: Track? = null,
    closedCaptionFormats: List<Format> = listOf(),
    additionalEmsgTrackOutput: TrackOutput? = null
) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME, FLAG_WORKAROUND_IGNORE_TFDT_BOX, FLAG_ENABLE_EMSG_TRACK, FLAG_WORKAROUND_IGNORE_EDIT_LISTS, FLAG_EMIT_RAW_SUBTITLE_DATA, FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES, FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265, FLAG_MERGE_FRAGMENTED_SIDX, FLAG_READ_MFRA_FOR_SEEK_MAP, FLAG_DISABLE_ARTWORK_METADATA
        ]
    )
    annotation class Flags

    private val subtitleParserFactory: SubtitleParser.Factory
    private val flags: @Flags Int
    private val sideloadedTrack: Track?

    // Sideloaded data.
    private val closedCaptionFormats: MutableList<Format?>

    // Track-linked data bundle, accessible as a whole through trackID.
    private val trackBundles: SparseArray<TrackBundle>

    // Temporary arrays.
    private val nalStartCode: ParsableByteArray
    private val nalPrefix: ParsableByteArray
    private val nalUnitWithoutHeaderBuffer: ParsableByteArray
    private val scratchBytes: ByteArray
    private val scratch: ParsableByteArray

    // Adjusts sample timestamps.
    private val timestampAdjuster: TimestampAdjuster?

    private val eventMessageEncoder: EventMessageEncoder

    // Parser state.
    private val atomHeader: ParsableByteArray
    private val containerAtoms: ArrayDeque<Mp4Box.ContainerBox?>
    private val pendingMetadataSampleInfos: ArrayDeque<MetadataSampleInfo>
    private val reorderingBufferQueue: ReorderingBufferQueue
    private val additionalEmsgTrackOutput: TrackOutput?

    private val chunkIndexMerger: ChunkIndexMerger

    private var lastSniffFailures: List<SniffFailure>
    private var parserState = 0
    private var atomType = 0
    private var atomSize: Long = 0
    private var atomHeaderBytesRead = 0
    private var atomData: ParsableByteArray? = null
    private var endOfMdatPosition: Long = 0
    private var pendingMetadataSampleBytes = 0
    private var pendingSeekTimeUs: Long

    private var durationUs: Long
    private var segmentIndexEarliestPresentationTimeUs: Long
    private var currentTrackBundle: TrackBundle? = null
    private var sampleSize = 0
    private var sampleBytesWritten = 0
    private var sampleCurrentNalBytesRemaining = 0
    private var isSampleDependedOn = false
    private var processSeiNalUnitPayload = false

    // Outputs.
    private var extractorOutput: ExtractorOutput
    private var emsgTrackOutputs: Array<TrackOutput>
    private var ceaTrackOutputs: Array<TrackOutput?>

    // Whether extractorOutput.seekMap has been called.
    private var haveOutputSeekMap = false

    // Whether the upfront forward scan for sidx boxes (triggered by FLAG_MERGE_FRAGMENTED_SIDX) has
    // successfully completed and output the merged seek map.
    private var upfrontSidxScanComplete = false

    private var seekPositionBeforeSidxProcessing: Long
    private var seekPositionBeforeMfraProcessing: Long

    @Deprecated("Use {@link #FragmentedMp4Extractor(SubtitleParser.Factory)} instead")
    constructor() : this(
        SubtitleParser.Factory.UNSUPPORTED,  
        FLAG_EMIT_RAW_SUBTITLE_DATA,  
        null,  
        null,  
        listOf<Format>(),  
        null
    )

    @Deprecated("Use {@link #FragmentedMp4Extractor(SubtitleParser.Factory, int)} instead")
    constructor(flags: @Flags Int) : this(
        SubtitleParser.Factory.UNSUPPORTED,
        flags or FLAG_EMIT_RAW_SUBTITLE_DATA,  
        null,  
        null,  
        listOf(),  
        null
    )

    @Deprecated(
        """Use {@link #FragmentedMp4Extractor(SubtitleParser.Factory, int, TimestampAdjuster,
   *     Track, List, TrackOutput)} instead"""
    )
    constructor(flags: @Flags Int, timestampAdjuster: TimestampAdjuster?) : this(
        SubtitleParser.Factory.UNSUPPORTED,
        flags or FLAG_EMIT_RAW_SUBTITLE_DATA,
        timestampAdjuster,  
        null,  
        listOf(),  
        null
    )

    @Deprecated(
        """Use {@link #FragmentedMp4Extractor(SubtitleParser.Factory, int, TimestampAdjuster,
   *     Track, List, TrackOutput)} instead"""
    )
    constructor(
        flags: @Flags Int,
        timestampAdjuster: TimestampAdjuster?,
        sideloadedTrack: Track?
    ) : this(
        SubtitleParser.Factory.UNSUPPORTED,
        flags or FLAG_EMIT_RAW_SUBTITLE_DATA,
        timestampAdjuster,
        sideloadedTrack,  
        listOf(),  
        null
    )

    @Deprecated(
        """Use {@link #FragmentedMp4Extractor(SubtitleParser.Factory, int, TimestampAdjuster,
   *     Track, List, TrackOutput)} instead"""
    )
    constructor(
        flags: @Flags Int,
        timestampAdjuster: TimestampAdjuster?,
        sideloadedTrack: Track?,
        closedCaptionFormats: MutableList<Format>
    ) : this(
        SubtitleParser.Factory.UNSUPPORTED,
        flags or FLAG_EMIT_RAW_SUBTITLE_DATA,
        timestampAdjuster,
        sideloadedTrack,
        closedCaptionFormats,  
        null
    )

    @Deprecated(
        """Use {@link #FragmentedMp4Extractor(SubtitleParser.Factory, int, TimestampAdjuster,
   *     Track, List, TrackOutput)} instead"""
    )
    constructor(
        flags: @Flags Int,
        timestampAdjuster: TimestampAdjuster?,
        sideloadedTrack: Track?,
        closedCaptionFormats: MutableList<Format>,
        additionalEmsgTrackOutput: TrackOutput?
    ) : this(
        SubtitleParser.Factory.UNSUPPORTED,
        flags or FLAG_EMIT_RAW_SUBTITLE_DATA,
        timestampAdjuster,
        sideloadedTrack,
        closedCaptionFormats,
        additionalEmsgTrackOutput
    )

    init {
        this.subtitleParserFactory = subtitleParserFactory
        this.flags = flags
        this.timestampAdjuster = timestampAdjuster
        this.sideloadedTrack = sideloadedTrack
        this.closedCaptionFormats = Collections.unmodifiableList<Format?>(closedCaptionFormats)
        this.additionalEmsgTrackOutput = additionalEmsgTrackOutput
        eventMessageEncoder = EventMessageEncoder()
        atomHeader = ParsableByteArray(Mp4Box.LONG_HEADER_SIZE)
        nalStartCode = ParsableByteArray(NalUnitUtil.NAL_START_CODE)
        nalPrefix = ParsableByteArray(6)
        nalUnitWithoutHeaderBuffer = ParsableByteArray()
        scratchBytes = ByteArray(16)
        scratch = ParsableByteArray(scratchBytes)
        containerAtoms = ArrayDeque<Mp4Box.ContainerBox?>()
        pendingMetadataSampleInfos = ArrayDeque<MetadataSampleInfo>()
        trackBundles = SparseArray<TrackBundle>()
        lastSniffFailures = listOf()
        durationUs = C.TIME_UNSET
        pendingSeekTimeUs = C.TIME_UNSET
        segmentIndexEarliestPresentationTimeUs = C.TIME_UNSET
        extractorOutput = ExtractorOutput.PLACEHOLDER
        emsgTrackOutputs = arrayOf()
        ceaTrackOutputs = arrayOf()
        reorderingBufferQueue =
            ReorderingBufferQueue(
                object :ReorderingBufferQueue.OutputConsumer {
                    override fun consume(presentationTimeUs: Long, buffer: ParsableByteArray?) {
                        CeaUtil.consume(
                            presentationTimeUs,
                            buffer!!,
                            ceaTrackOutputs
                        )
                    }
                })
        chunkIndexMerger = ChunkIndexMerger()
        seekPositionBeforeSidxProcessing = C.INDEX_UNSET.toLong()
        seekPositionBeforeMfraProcessing = C.INDEX_UNSET.toLong()
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        val sniffFailure: SniffFailure? = Sniffer.sniffFragmented(input)
        lastSniffFailures =
            if (sniffFailure != null) listOf(sniffFailure) else listOf()
        return sniffFailure == null
    }

    override val sniffFailureDetails: List<SniffFailure>
        get() = lastSniffFailures

    override fun init(output: ExtractorOutput) {
        extractorOutput =
            if ((flags and FLAG_EMIT_RAW_SUBTITLE_DATA) == 0)
                SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            else
                output
        enterReadingAtomHeaderState()
        initExtraTracks()
        if (sideloadedTrack != null) {
            val formatBuilder = sideloadedTrack.format!!.buildUpon()
            formatBuilder.setContainerMimeType(MimeTypeResolver.getContainerMimeType(sideloadedTrack.format))
            val bundle =
                TrackBundle(
                    extractorOutput.track(0, sideloadedTrack.type),
                    TrackSampleTable(
                        sideloadedTrack,  
                        LongArray(0),  
                        IntArray(0),  
                        0,  
                        LongArray(0),  
                        IntArray(0),  
                        IntArray(0),  
                        false,  
                        0,  
                        0
                    ),
                    DefaultSampleValues( 
                        0,  
                        0,  
                        0,  
                        0
                    ),
                    formatBuilder.build()
                )
            trackBundles.put(0, bundle)
            extractorOutput.endTracks()
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        val trackCount = trackBundles.size()
        for (i in 0..<trackCount) {
            trackBundles.valueAt(i).resetFragmentInfo()
        }
        pendingMetadataSampleInfos.clear()
        pendingMetadataSampleBytes = 0
        reorderingBufferQueue.clear()
        pendingSeekTimeUs = timeUs
        containerAtoms.clear()
        seekPositionBeforeMfraProcessing = C.INDEX_UNSET.toLong()
        enterReadingAtomHeaderState()
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        while (true) {
            when (parserState) {
                STATE_READING_ATOM_HEADER -> {
                    if (!readAtomHeader(input, seekPosition)) {
                        if (seekPositionBeforeSidxProcessing != C.INDEX_UNSET.toLong()) {
                            seekPosition.position = seekPositionBeforeSidxProcessing
                            seekPositionBeforeSidxProcessing = C.INDEX_UNSET.toLong()
                            extractorOutput.seekMap(chunkIndexMerger.merge())
                            upfrontSidxScanComplete = true
                            return Extractor.RESULT_SEEK
                        } else {
                            reorderingBufferQueue.flush()
                            return Extractor.RESULT_END_OF_INPUT
                        }
                    }
                    if (parserState == STATE_READING_MFRO) {
                        return Extractor.RESULT_SEEK
                    }
                }

                STATE_READING_MFRO -> {
                    readMfro(input, seekPosition)
                    if (parserState == STATE_READING_MFRA || parserState == STATE_READING_ATOM_HEADER) {
                        return Extractor.RESULT_SEEK
                    }
                }

                STATE_READING_MFRA -> {
                    readMfra(input, seekPosition)
                    if (parserState == STATE_READING_ATOM_HEADER) {
                        return Extractor.RESULT_SEEK
                    }
                }

                STATE_READING_ATOM_PAYLOAD -> readAtomPayload(input)
                STATE_READING_ENCRYPTION_DATA -> readEncryptionData(input)
                else -> if (readSample(input)) {
                    return Extractor.RESULT_CONTINUE
                }
            }
        }
    }

    private fun enterReadingAtomHeaderState() {
        parserState = STATE_READING_ATOM_HEADER
        atomHeaderBytesRead = 0
    }

    @Throws(IOException::class)
    private fun readAtomHeader(input: ExtractorInput, seekPosition: PositionHolder): Boolean {
        if (atomHeaderBytesRead == 0) {
            // Read the standard length atom header.
            if (!input.readFully(atomHeader.data, 0, Mp4Box.HEADER_SIZE, true)) {
                return false
            }
            atomHeaderBytesRead = Mp4Box.HEADER_SIZE
            atomHeader.setPosition(0)
            atomSize = atomHeader.readUnsignedInt()
            atomType = atomHeader.readInt()
        }

        if (atomSize == Mp4Box.DEFINES_LARGE_SIZE.toLong()) {
            // Read the large size.
            val headerBytesRemaining: Int = Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE
            input.readFully(atomHeader.data, Mp4Box.HEADER_SIZE, headerBytesRemaining)
            atomHeaderBytesRead += headerBytesRemaining
            atomSize = atomHeader.readUnsignedLongToLong()
        } else if (atomSize == Mp4Box.EXTENDS_TO_END_SIZE.toLong()) {
            // The atom extends to the end of the file. Note that if the atom is within a container we can
            // work out its size even if the input length is unknown.
            var endPosition: Long = input.length
            if (endPosition == C.LENGTH_UNSET.toLong() && !containerAtoms.isEmpty()) {
                endPosition = containerAtoms.peek()!!.endPosition
            }
            if (endPosition != C.LENGTH_UNSET.toLong()) {
                atomSize = endPosition - input.position + atomHeaderBytesRead
            }
        }

        if (atomSize < atomHeaderBytesRead) {
            if (atomType == Mp4Box.TYPE_free && atomHeaderBytesRead == Mp4Box.HEADER_SIZE) {
                // Workaround for writers that could create a malformed 'free' box with a size less than
                // its header, causing file corruption. [See internal: b/438187097].
                atomSize = atomHeaderBytesRead.toLong()
            } else {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Atom size less than header length (unsupported)."
                )
            }
        }

        if (seekPositionBeforeSidxProcessing != C.INDEX_UNSET.toLong()) {
            if (atomType == Mp4Box.TYPE_sidx) {
                scratch.reset(atomSize.toInt())
                System.arraycopy(atomHeader.data, 0, scratch.data, 0, Mp4Box.HEADER_SIZE)
                input.readFully(
                    scratch.data, Mp4Box.HEADER_SIZE, (atomSize - atomHeaderBytesRead).toInt()
                )

                val sidxBox: Mp4Box.LeafBox = Mp4Box.LeafBox(Mp4Box.TYPE_sidx, scratch)
                val result = parseSidx(sidxBox.data!!, input.peekPosition)
                chunkIndexMerger.add(result.second!!)
            } else {
                input.skipFully(
                    (atomSize - atomHeaderBytesRead).toInt(),  
                    true
                )
            }
            enterReadingAtomHeaderState()
            return true
        }

        val atomPosition: Long = input.position - atomHeaderBytesRead
        if (atomType == Mp4Box.TYPE_moof || atomType == Mp4Box.TYPE_mdat) {
            if (!haveOutputSeekMap) {
                // This must be the first moof or mdat in the stream.
                if (input.length != C.LENGTH_UNSET.toLong() && seekPositionBeforeMfraProcessing == C.INDEX_UNSET.toLong() && (flags and FLAG_READ_MFRA_FOR_SEEK_MAP) != 0) {
                    seekPositionBeforeMfraProcessing = atomPosition
                    seekPosition.position = input.length - MFRO_FIXED_BOX_SIZE
                    parserState = STATE_READING_MFRO
                    return true
                } else {
                    extractorOutput.seekMap(SeekMap.Unseekable(durationUs, atomPosition))
                    haveOutputSeekMap = true
                }
            }
        }

        if (atomType == Mp4Box.TYPE_moof) {
            // The data positions may be updated when parsing the tfhd/trun.
            val trackCount = trackBundles.size()
            for (i in 0..<trackCount) {
                val fragment = trackBundles.valueAt(i).fragment
                fragment.atomPosition = atomPosition
                fragment.auxiliaryDataPosition = atomPosition
                fragment.dataPosition = atomPosition
            }
        }

        if (atomType == Mp4Box.TYPE_mdat) {
            currentTrackBundle = null
            endOfMdatPosition = atomPosition + atomSize
            parserState = STATE_READING_ENCRYPTION_DATA
            return true
        }

        if (shouldParseContainerAtom(atomType)) {
            val endPosition: Long = input.position + atomSize - Mp4Box.HEADER_SIZE
            if (atomSize != atomHeaderBytesRead.toLong() && atomType == Mp4Box.TYPE_meta) {
                maybeSkipRemainingMetaAtomHeaderBytes(input)
            }
            containerAtoms.push(Mp4Box.ContainerBox(atomType, endPosition))
            if (atomSize == atomHeaderBytesRead.toLong()) {
                processAtomEnded(endPosition)
            } else {
                // Start reading the first child atom.
                enterReadingAtomHeaderState()
            }
        } else if (shouldParseLeafAtom(atomType)) {
            if (atomHeaderBytesRead != Mp4Box.HEADER_SIZE) {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Leaf atom defines extended atom size (unsupported)."
                )
            }
            if (atomSize > Int.MAX_VALUE) {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Leaf atom with length > 2147483647 (unsupported)."
                )
            }
            val atomData: ParsableByteArray = ParsableByteArray(atomSize.toInt())
            System.arraycopy(atomHeader.data, 0, atomData.data, 0, Mp4Box.HEADER_SIZE)
            this.atomData = atomData
            parserState = STATE_READING_ATOM_PAYLOAD
        } else {
            if (atomSize > Int.MAX_VALUE) {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Skipping atom with length > 2147483647 (unsupported)."
                )
            }
            atomData = null
            parserState = STATE_READING_ATOM_PAYLOAD
        }

        return true
    }

    @Throws(IOException::class)
    private fun maybeSkipRemainingMetaAtomHeaderBytes(input: ExtractorInput) {
        scratch.reset(Mp4Box.HEADER_SIZE)
        input.peekFully(scratch.data, 0, Mp4Box.HEADER_SIZE)
        BoxParser.maybeSkipRemainingMetaBoxHeaderBytes(scratch)
        input.skipFully(scratch.getPosition())
        input.resetPeekPosition()
    }

    @Throws(IOException::class)
    private fun readAtomPayload(input: ExtractorInput) {
        val atomPayloadSize = (atomSize - atomHeaderBytesRead).toInt()
        val atomData: ParsableByteArray? = this.atomData
        if (atomData != null) {
            input.readFully(atomData.data, Mp4Box.HEADER_SIZE, atomPayloadSize)
            onLeafAtomRead(Mp4Box.LeafBox(atomType, atomData), input)
        } else {
            input.skipFully(atomPayloadSize)
        }
        processAtomEnded(input.position)
    }

    @Throws(ParserException::class)
    private fun processAtomEnded(atomEndPosition: Long) {
        while (!containerAtoms.isEmpty() && containerAtoms.peek()!!.endPosition == atomEndPosition) {
            onContainerAtomRead(containerAtoms.pop())
        }
        enterReadingAtomHeaderState()
    }

    @Throws(IOException::class)
    private fun onLeafAtomRead(leaf: Mp4Box.LeafBox, input: ExtractorInput) {
        if (!containerAtoms.isEmpty()) {
            containerAtoms.peek()!!.add(leaf)
        } else if (leaf.type == Mp4Box.TYPE_sidx) {
            val result: Pair<Long?, ChunkIndex?> = parseSidx(leaf.data!!, input.position)
            chunkIndexMerger.add(result.second!!)
            segmentIndexEarliestPresentationTimeUs = result.first!!
            if (!upfrontSidxScanComplete) {
                extractorOutput.seekMap(
                    if (chunkIndexMerger.size() == 1) result.second!! else chunkIndexMerger.merge()
                )
                haveOutputSeekMap = true
            }
            if ((flags and FLAG_MERGE_FRAGMENTED_SIDX) != 0 && !upfrontSidxScanComplete && chunkIndexMerger.size() > 1) {
                seekPositionBeforeSidxProcessing = input.position
            }
        } else if (leaf.type == Mp4Box.TYPE_emsg) {
            onEmsgLeafAtomRead(leaf.data!!)
        }
    }

    @Throws(ParserException::class)
    private fun onContainerAtomRead(container: Mp4Box.ContainerBox?) {
        if (container!!.type == Mp4Box.TYPE_moov) {
            onMoovContainerAtomRead(container)
        } else if (container.type == Mp4Box.TYPE_moof) {
            onMoofContainerAtomRead(container)
        } else if (!containerAtoms.isEmpty()) {
            containerAtoms.peek()!!.add(container)
        }
    }

    @Throws(ParserException::class)
    private fun onMoovContainerAtomRead(moov: Mp4Box.ContainerBox) {
        Preconditions.checkState(sideloadedTrack == null, "Unexpected moov box.")

        val drmInitData: DrmInitData? = getDrmInitDataFromAtoms(moov.leafChildren)

        // Read declaration of track fragments in the moov box.
        val mvex: Mp4Box.ContainerBox =moov.getContainerBoxOfType(Mp4Box.TYPE_mvex)!!
        val defaultSampleValuesArray = SparseArray<DefaultSampleValues?>()
        var duration = C.TIME_UNSET
        val mvexChildrenSize: Int = mvex.leafChildren.size
        for (i in 0..<mvexChildrenSize) {
            val atom: Mp4Box.LeafBox = mvex.leafChildren[i]
            if (atom.type == Mp4Box.TYPE_trex) {
                val trexData: Pair<Int?, DefaultSampleValues?> = parseTrex(atom.data!!)
                defaultSampleValuesArray.put(trexData.first!!, trexData.second)
            } else if (atom.type == Mp4Box.TYPE_mehd) {
                duration = parseMehd(atom.data!!)
            }
        }

        var mdtaMetadata: Metadata? = null
        val meta: Mp4Box.ContainerBox? = moov.getContainerBoxOfType(Mp4Box.TYPE_meta)
        if (meta != null) {
            mdtaMetadata = BoxParser.parseMdtaFromMeta(meta)
        }
        val gaplessInfoHolder: GaplessInfoHolder = GaplessInfoHolder()
        var udtaMetadata: Metadata? = null
        val udta: Mp4Box.LeafBox? = moov.getLeafBoxOfType(Mp4Box.TYPE_udta)
        if (udta != null) {
            udtaMetadata =
                BoxParser.parseUdta(
                    udta,  (flags and FLAG_DISABLE_ARTWORK_METADATA) != 0
                )
            gaplessInfoHolder.setFromMetadata(udtaMetadata)
        }
        val mvhdMetadata =
            Metadata(
                listOf(BoxParser.parseMvhd(
                    moov.getLeafBoxOfType(
                        Mp4Box.TYPE_mvhd
                    )!!.data!!
                ))
            )

        // Construction of tracks and sample tables.
        val sampleTables =
            BoxParser.parseTraks(
                moov,
                gaplessInfoHolder,
                duration,
                drmInitData,  
                (flags and FLAG_WORKAROUND_IGNORE_EDIT_LISTS) != 0,  
                false,
                Function { track: Track? -> this.modifyTrack(track) },  
                false
            )

        val trackCount = sampleTables.size
        if (trackBundles.size() == 0) {
            // We need to create the track bundles.
            val containerMimeType = MimeTypeResolver.getContainerMimeType(sampleTables)
            for (i in 0..<trackCount) {
                val sampleTable = sampleTables[i]
                val track = sampleTable.track
                if (!track!!.shouldBeExposed) {
                    continue
                }
                val output: TrackOutput? = extractorOutput.track(i, track.type)
                output!!.durationUs(track.durationUs)
                val formatBuilder = track.format!!.buildUpon()
                formatBuilder.setContainerMimeType(containerMimeType)
                MetadataUtil.setFormatGaplessInfo(track.type, gaplessInfoHolder, formatBuilder)
                MetadataUtil.setFormatMetadata(
                    track.type,
                    mdtaMetadata,
                    formatBuilder,
                    track.format.metadata,
                    mvhdMetadata,
                )
                val trackBundle =
                    TrackBundle(
                        output,
                        sampleTable,
                        getDefaultSampleValues(defaultSampleValuesArray, track.id)!!,
                        formatBuilder.build()
                    )
                trackBundles.put(track.id, trackBundle)
                durationUs = max(durationUs, track.durationUs)
            }
            extractorOutput.endTracks()
        } else {
            var exposedTrackCount = 0
            for (i in 0..<trackCount) {
                if (sampleTables[i].track!!.shouldBeExposed) {
                    exposedTrackCount++
                }
            }
            Preconditions.checkState(trackBundles.size() == exposedTrackCount)
            for (i in 0..<trackCount) {
                val sampleTable = sampleTables[i]
                val track = sampleTable.track
                if (!track!!.shouldBeExposed) {
                    continue
                }
                trackBundles
                    .get(track.id)
                    .reset(
                        sampleTable,
                        getDefaultSampleValues(defaultSampleValuesArray, track.id)!!
                    )
            }
        }
    }

    protected fun modifyTrack(track: Track?): Track? {
        return track
    }

    private fun getDefaultSampleValues(
        defaultSampleValuesArray: SparseArray<DefaultSampleValues?>, trackId: Int
    ): DefaultSampleValues? {
        if (defaultSampleValuesArray.size() == 1) {
            // Ignore track id if there is only one track to cope with non-matching track indices.
            // See https://github.com/google/ExoPlayer/issues/4477.
            return defaultSampleValuesArray.valueAt( 0)
        }
        return defaultSampleValuesArray.get(trackId)!!
    }

    @Throws(ParserException::class)
    private fun onMoofContainerAtomRead(moof: Mp4Box.ContainerBox) {
        parseMoof(moof, trackBundles, sideloadedTrack != null, flags, scratchBytes)

        val drmInitData: DrmInitData? = getDrmInitDataFromAtoms(moof.leafChildren)
        if (drmInitData != null) {
            val trackCount = trackBundles.size()
            for (i in 0..<trackCount) {
                trackBundles.valueAt(i).updateDrmInitData(drmInitData)
            }
        }
        // If we have a pending seek, advance tracks to their preceding sync frames.
        if (pendingSeekTimeUs != C.TIME_UNSET) {
            val trackCount = trackBundles.size()
            for (i in 0..<trackCount) {
                trackBundles.valueAt(i).seek(pendingSeekTimeUs)
            }
            pendingSeekTimeUs = C.TIME_UNSET
        }
    }

    private fun initExtraTracks() {
        var nextExtraTrackId: Int = EXTRA_TRACKS_BASE_ID

        val list = mutableListOf<TrackOutput>()
        var emsgTrackOutputCount = 0
        if (additionalEmsgTrackOutput != null) {
            list.add(additionalEmsgTrackOutput)
        }
        if ((flags and FLAG_ENABLE_EMSG_TRACK) != 0) {
            list.add( extractorOutput.track(nextExtraTrackId++, C.TRACK_TYPE_METADATA))
        }
        emsgTrackOutputs = list.toTypedArray()
        for (eventMessageTrackOutput in emsgTrackOutputs) {
            eventMessageTrackOutput.format(EMSG_FORMAT)
        }

        ceaTrackOutputs = kotlin.arrayOfNulls<TrackOutput>(closedCaptionFormats.size)
        for (i in ceaTrackOutputs.indices) {
            val output: TrackOutput? = extractorOutput.track(nextExtraTrackId++, C.TRACK_TYPE_TEXT)
            output!!.format(closedCaptionFormats[i]!!)
            ceaTrackOutputs[i] = output
        }
    }

    private fun onEmsgLeafAtomRead(atom: ParsableByteArray) {
        if (emsgTrackOutputs.size == 0) {
            return
        }
        atom.setPosition(Mp4Box.HEADER_SIZE)
        val fullAtom: Int = atom.readInt()
        val version = BoxParser.parseFullBoxVersion(fullAtom)
        val schemeIdUri: String?
        val value: String?
        val timescale: Long
        var presentationTimeDeltaUs = C.TIME_UNSET // Only set if version == 0
        var sampleTimeUs = C.TIME_UNSET
        val durationMs: Long
        val id: Long
        when (version) {
            0 -> {
                schemeIdUri = atom.readNullTerminatedString()!!
                value = atom.readNullTerminatedString()!!
                timescale = atom.readUnsignedInt()
                presentationTimeDeltaUs =
                    Util.scaleLargeTimestamp(atom.readUnsignedInt(), C.MICROS_PER_SECOND, timescale)
                if (segmentIndexEarliestPresentationTimeUs != C.TIME_UNSET) {
                    sampleTimeUs = segmentIndexEarliestPresentationTimeUs + presentationTimeDeltaUs
                }
                durationMs =
                    Util.scaleLargeTimestamp(atom.readUnsignedInt(), C.MILLIS_PER_SECOND, timescale)
                id = atom.readUnsignedInt()
            }

            1 -> {
                timescale = atom.readUnsignedInt()
                sampleTimeUs =
                    Util.scaleLargeTimestamp(
                        atom.readUnsignedLongToLong(),
                        C.MICROS_PER_SECOND,
                        timescale
                    )
                durationMs =
                    Util.scaleLargeTimestamp(atom.readUnsignedInt(), C.MILLIS_PER_SECOND, timescale)
                id = atom.readUnsignedInt()
                schemeIdUri = atom.readNullTerminatedString()!!
                value =atom.readNullTerminatedString()!!
            }

            else -> {
                Log.w(TAG, "Skipping unsupported emsg version: " + version)
                return
            }
        }

        val messageData = ByteArray(atom.bytesLeft())
        atom.readBytes(messageData,  0, atom.bytesLeft())
        val eventMessage: EventMessage =
            EventMessage(schemeIdUri, value, durationMs, id, messageData)
        val encodedEventMessage: ParsableByteArray =
            ParsableByteArray(eventMessageEncoder.encode(eventMessage))
        val sampleSize: Int = encodedEventMessage.bytesLeft()

        // Output the sample data.
        for (emsgTrackOutput in emsgTrackOutputs) {
            encodedEventMessage.setPosition(0)
            emsgTrackOutput.sampleData(encodedEventMessage, sampleSize)
        }

        // Output the sample metadata.
        if (sampleTimeUs == C.TIME_UNSET) {
            // We're processing a v0 emsg atom, which contains a presentation time delta, and cannot yet
            // calculate its absolute sample timestamp. Defer outputting the metadata until we can.
            pendingMetadataSampleInfos.addLast(
                MetadataSampleInfo(
                    presentationTimeDeltaUs,  true, sampleSize
                )
            )
            pendingMetadataSampleBytes += sampleSize
        } else if (!pendingMetadataSampleInfos.isEmpty()) {
            // We also need to defer outputting metadata if pendingMetadataSampleInfos is non-empty, else
            // we will output metadata for samples in the wrong order. See:
            // https://github.com/google/ExoPlayer/issues/9996.
            pendingMetadataSampleInfos.addLast(
                MetadataSampleInfo(sampleTimeUs,  false, sampleSize)
            )
            pendingMetadataSampleBytes += sampleSize
        } else if (timestampAdjuster != null && !timestampAdjuster.isInitialized) {
            // We also need to defer outputting metadata if the timestampAdjuster is not initialized,
            // else we will set a wrong timestampOffsetUs in timestampAdjuster. See:
            // https://github.com/androidx/media/issues/356.
            pendingMetadataSampleInfos.addLast(
                MetadataSampleInfo(sampleTimeUs,  false, sampleSize)
            )
            pendingMetadataSampleBytes += sampleSize
        } else {
            // We can output the sample metadata immediately.
            if (timestampAdjuster != null) {
                sampleTimeUs = timestampAdjuster.adjustSampleTimestamp(sampleTimeUs)
            }
            for (emsgTrackOutput in emsgTrackOutputs) {
                emsgTrackOutput.sampleMetadata(
                    sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize,  0, null
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun readEncryptionData(input: ExtractorInput) {
        var nextTrackBundle: TrackBundle? = null
        var nextDataOffset = Long.MAX_VALUE
        val trackBundlesSize = trackBundles.size()
        for (i in 0..<trackBundlesSize) {
            val trackFragment = trackBundles.valueAt(i).fragment
            if (trackFragment.sampleEncryptionDataNeedsFill
                && trackFragment.auxiliaryDataPosition < nextDataOffset
            ) {
                nextDataOffset = trackFragment.auxiliaryDataPosition
                nextTrackBundle = trackBundles.valueAt(i)
            }
        }
        if (nextTrackBundle == null) {
            parserState = STATE_READING_SAMPLE_START
            return
        }
        val bytesToSkip = (nextDataOffset - input.position) as Int
        if (bytesToSkip < 0) {
            throw ParserException.createForMalformedContainer(
                "Offset to encryption data was negative.",  null
            )
        }
        input.skipFully(bytesToSkip)
        nextTrackBundle.fragment.fillEncryptionData(input)
    }

    @Throws(IOException::class)
    private fun readSample(input: ExtractorInput): Boolean {
        var trackBundle = currentTrackBundle
        if (trackBundle == null) {
            trackBundle = getNextTrackBundle(trackBundles)
            if (trackBundle == null) {
                // We've run out of samples in the current mdat. Discard any trailing data and prepare to
                // read the header of the next atom.
                val bytesToSkip = (endOfMdatPosition - input.position) as Int
                if (bytesToSkip < 0) {
                    throw ParserException.createForMalformedContainer(
                        "Offset to end of mdat was negative.",  null
                    )
                }
                input.skipFully(bytesToSkip)
                enterReadingAtomHeaderState()
                return false
            }

            val nextDataPosition = trackBundle.currentSampleOffset
            // We skip bytes preceding the next sample to read.
            var bytesToSkip = (nextDataPosition - input.position) as Int
            if (bytesToSkip < 0) {
                // Assume the sample data must be contiguous in the mdat with no preceding data.
                Log.w(TAG, "Ignoring negative offset to sample data.")
                bytesToSkip = 0
            }
            input.skipFully(bytesToSkip)
            currentTrackBundle = trackBundle
        }
        if (parserState == STATE_READING_SAMPLE_START) {
            sampleSize = trackBundle.currentSampleSize
            // We must check all NAL units in the Fragmented MP4 sample for dependencies.
            // When reading sample dependencies is disabled, or codec is not supported,
            // set isSampleDependedOn = true and skip parsing the payload bytes.
            isSampleDependedOn =
                !canReadWithinGopSampleDependencies(trackBundle.moovSampleTable.track!!.format!!)

            if (trackBundle.currentSampleIndex < trackBundle.firstSampleToOutputIndex) {
                input.skipFully(sampleSize)
                trackBundle.skipSampleEncryptionData()
                if (!trackBundle.next()) {
                    currentTrackBundle = null
                }
                parserState = STATE_READING_SAMPLE_START
                return true
            }

            if (trackBundle.moovSampleTable.track!!.sampleTransformation
                == Track.TRANSFORMATION_CEA608_CDAT
            ) {
                sampleSize -= Mp4Box.HEADER_SIZE
                input.skipFully(Mp4Box.HEADER_SIZE)
            }

            if (MimeTypes.AUDIO_AC4 == trackBundle.moovSampleTable.track!!.format!!.sampleMimeType) {
                // AC4 samples need to be prefixed with a clear sample header.
                sampleBytesWritten =
                    trackBundle.outputSampleEncryptionData(sampleSize, Ac4Util.SAMPLE_HEADER_SIZE)
                Ac4Util.getAc4SampleHeader(sampleSize, scratch)
                trackBundle.output.sampleData(scratch, Ac4Util.SAMPLE_HEADER_SIZE)
                sampleBytesWritten += Ac4Util.SAMPLE_HEADER_SIZE
            } else {
                sampleBytesWritten =
                    trackBundle.outputSampleEncryptionData(sampleSize,  0)
            }
            sampleSize += sampleBytesWritten
            parserState = STATE_READING_SAMPLE_CONTINUE
            sampleCurrentNalBytesRemaining = 0
        }

        val track = trackBundle.moovSampleTable.track
        val output: TrackOutput = trackBundle.output
        var sampleTimeUs = trackBundle.currentSamplePresentationTimeUs
        if (timestampAdjuster != null) {
            sampleTimeUs = timestampAdjuster.adjustSampleTimestamp(sampleTimeUs)
        }
        if (track!!.nalUnitLengthFieldLength != 0) {
            // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
            // they're only 1 or 2 bytes long.
            val nalPrefixData = nalPrefix.data
            nalPrefixData[0] = 0
            nalPrefixData[1] = 0
            nalPrefixData[2] = 0
            val nalUnitLengthFieldLengthDiff = 4 - track.nalUnitLengthFieldLength
            // NAL units are length delimited, but the decoder requires start code delimited units.
            // Loop until we've written the sample to the track output, replacing length delimiters with
            // start codes as we encounter them.
            while (sampleBytesWritten < sampleSize) {
                if (sampleCurrentNalBytesRemaining == 0) {
                    var numberOfNalUnitHeaderBytesToRead = 0
                    if (ceaTrackOutputs.size > 0 || !isSampleDependedOn) {
                        // Try to read the NAL unit header if we're parsing captions or sample dependencies.
                        val nalUnitHeaderSize: Int =
                            NalUnitUtil.numberOfBytesInNalUnitHeader(track.format!!)
                        if (track!!.nalUnitLengthFieldLength + nalUnitHeaderSize
                            <= sampleSize - sampleBytesWritten
                        ) {
                            // In some malformed videos with padding, the NAL unit may be empty.
                            // See b/383201567#comment20
                            // Only try to read the header if there are enough bytes in this sample.
                            numberOfNalUnitHeaderBytesToRead = nalUnitHeaderSize
                        }
                    }
                    // Read numberOfNalUnitHeaderBytesToRead in the same readFully call that reads the NAL
                    // length. This ensures sampleBytesRead, sampleBytesWritten and isSampleDependedOn remain
                    // in a consistent state if we have read failures.
                    val nalUnitPrefixLength =
                        track.nalUnitLengthFieldLength + numberOfNalUnitHeaderBytesToRead
                    // Read the NAL length so that we know where we find the next one, and its type.
                    input.readFully(
                        nalPrefixData,
                        nalUnitLengthFieldLengthDiff,
                        nalUnitPrefixLength
                    )
                    nalPrefix.setPosition(0)
                    val nalLengthInt = nalPrefix.readInt()
                    if (nalLengthInt < 0) {
                        throw ParserException.createForMalformedContainer(
                            "Invalid NAL length",  null
                        )
                    }
                    sampleCurrentNalBytesRemaining = nalLengthInt - numberOfNalUnitHeaderBytesToRead
                    // Write a start code for the current NAL unit.
                    nalStartCode.setPosition(0)
                    output.sampleData(nalStartCode, 4)
                    sampleBytesWritten += 4
                    sampleSize += nalUnitLengthFieldLengthDiff
                    processSeiNalUnitPayload =
                        ceaTrackOutputs.size > 0 && numberOfNalUnitHeaderBytesToRead > 0 && NalUnitUtil.isNalUnitSei(
                            track.format!!,
                            nalPrefixData,  
                            4
                        )
                    // Write the extra NAL unit bytes to the output.
                    output.sampleData(nalPrefix, numberOfNalUnitHeaderBytesToRead)
                    sampleBytesWritten += numberOfNalUnitHeaderBytesToRead
                    if (numberOfNalUnitHeaderBytesToRead > 0 && !isSampleDependedOn && NalUnitUtil.isDependedOn(
                            nalPrefixData,  
                            4,  
                            numberOfNalUnitHeaderBytesToRead,
                            track.format!!
                        )
                    ) {
                        isSampleDependedOn = true
                    }
                } else {
                    val writtenBytes: Int
                    if (processSeiNalUnitPayload) {
                        // Read and write the remaining payload of the SEI NAL unit.
                        nalUnitWithoutHeaderBuffer.reset(sampleCurrentNalBytesRemaining)
                        input.readFully(
                            nalUnitWithoutHeaderBuffer.data, 0, sampleCurrentNalBytesRemaining
                        )
                        output.sampleData(
                            nalUnitWithoutHeaderBuffer,
                            sampleCurrentNalBytesRemaining
                        )
                        writtenBytes = sampleCurrentNalBytesRemaining
                        // Unescape and process the SEI NAL unit.
                        val unescapedLength: Int =
                            NalUnitUtil.unescapeStream(
                                nalUnitWithoutHeaderBuffer.data,
                                nalUnitWithoutHeaderBuffer.limit()
                            )
                        nalUnitWithoutHeaderBuffer.setPosition(0)
                        nalUnitWithoutHeaderBuffer.setLimit(unescapedLength)

                        if (track.format!!.maxNumReorderSamples == Format.NO_VALUE) {
                            if (reorderingBufferQueue.maxSize != 0) {
                                reorderingBufferQueue.maxSize = 0
                            }
                        } else if (reorderingBufferQueue.maxSize != track.format.maxNumReorderSamples) {
                            reorderingBufferQueue.maxSize = track.format.maxNumReorderSamples
                        }
                        reorderingBufferQueue.add(sampleTimeUs, nalUnitWithoutHeaderBuffer)

                        if ((trackBundle.currentSampleFlags and C.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            reorderingBufferQueue.flush()
                        }
                    } else {
                        // Write the payload of the NAL unit.
                        writtenBytes =
                            output.sampleData(input, sampleCurrentNalBytesRemaining, false)
                    }
                    sampleBytesWritten += writtenBytes
                    sampleCurrentNalBytesRemaining -= writtenBytes
                }
            }
        } else {
            val pendingFormat = trackBundle.pendingFormat!!
            if (pendingFormat != null && DtsUtil.isDtsBaseAudioMimeType(track!!.format!!.sampleMimeType)) {
                trackBundle.baseFormat =
                    DtsUtil.updateFormatWithDtsHdInfo(input, sampleSize, trackBundle.baseFormat)
                val outputFormat =
                    trackBundle.baseFormat.buildUpon().setDrmInitData(pendingFormat.drmInitData)
                        .build()
                trackBundle.output.format(outputFormat)
                trackBundle.pendingFormat = null
            }
            while (sampleBytesWritten < sampleSize) {
                val writtenBytes: Int =
                    output.sampleData(input, sampleSize - sampleBytesWritten, false)
                sampleBytesWritten += writtenBytes
            }
        }

        var sampleFlags: @C.BufferFlags Int = trackBundle.currentSampleFlags
        if (!isSampleDependedOn) {
            sampleFlags = sampleFlags or C.BUFFER_FLAG_NOT_DEPENDED_ON
        }

        // Encryption data.
        var cryptoData: TrackOutput.CryptoData? = null
        val encryptionBox = trackBundle.encryptionBoxIfEncrypted
        if (encryptionBox != null) {
            cryptoData = encryptionBox.cryptoData
        }

        output.sampleMetadata(sampleTimeUs, sampleFlags, sampleSize, 0, cryptoData)

        // After we have the sampleTimeUs, we can commit all the pending metadata samples
        outputPendingMetadataSamples(sampleTimeUs)
        if (!trackBundle.next()) {
            currentTrackBundle = null
        }
        parserState = STATE_READING_SAMPLE_START
        return true
    }

    private fun canReadWithinGopSampleDependencies(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_H264) {
            return (flags and FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES) != 0
        }
        if (format.sampleMimeType == MimeTypes.VIDEO_H265) {
            return (flags and FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265) != 0
        }
        return false
    }

    private fun outputPendingMetadataSamples(sampleTimeUs: Long) {
        while (!pendingMetadataSampleInfos.isEmpty()) {
            val metadataSampleInfo = pendingMetadataSampleInfos.removeFirst()
            pendingMetadataSampleBytes -= metadataSampleInfo.size
            var metadataSampleTimeUs = metadataSampleInfo.sampleTimeUs
            if (metadataSampleInfo.sampleTimeIsRelative) {
                // The metadata sample timestamp is relative to the timestamp of the non-metadata sample
                // that was just output. Make it absolute.
                metadataSampleTimeUs += sampleTimeUs
            }
            if (timestampAdjuster != null) {
                metadataSampleTimeUs = timestampAdjuster.adjustSampleTimestamp(metadataSampleTimeUs)
            }
            for (emsgTrackOutput in emsgTrackOutputs) {
                emsgTrackOutput.sampleMetadata(
                    metadataSampleTimeUs,
                    C.BUFFER_FLAG_KEY_FRAME,
                    metadataSampleInfo.size,
                    pendingMetadataSampleBytes,
                    null
                )
            }
        }
    }

    private class MetadataSampleInfo(
        val sampleTimeUs: Long,
        val sampleTimeIsRelative: Boolean,
        val size: Int
    )

    @Throws(IOException::class)
    private fun readMfro(input: ExtractorInput, seekPosition: PositionHolder) {
        // An mfro box is always exactly 16 bytes long.
        scratch.reset(MFRO_FIXED_BOX_SIZE)
        if (!input.readFully(
                scratch.data,
                0,
                MFRO_FIXED_BOX_SIZE,  
                true
            )
        ) {
            onMfraProcessingFinished(
                SeekMap.Unseekable(durationUs, seekPositionBeforeMfraProcessing), seekPosition
            )
            return
        }
        scratch.setPosition(0)
        val size = scratch.readInt()
        val type = scratch.readInt()
        if (size != MFRO_FIXED_BOX_SIZE || type != Mp4Box.TYPE_mfro) {
            onMfraProcessingFinished(
                SeekMap.Unseekable(durationUs, seekPositionBeforeMfraProcessing), seekPosition
            )
            return
        }
        scratch.skipBytes(4) // version and flags
        val mfraSize = scratch.readUnsignedInt()
        val mfraOffset: Long = input.length - mfraSize
        if (mfraSize <= 0 || mfraSize > Int.MAX_VALUE || mfraOffset < 0 || mfraOffset < seekPositionBeforeMfraProcessing) {
            onMfraProcessingFinished(
                SeekMap.Unseekable(durationUs, seekPositionBeforeMfraProcessing), seekPosition
            )
            return
        }
        seekPosition.position = mfraOffset
        parserState = STATE_READING_MFRA
    }

    @Throws(IOException::class)
    private fun readMfra(input: ExtractorInput, seekPosition: PositionHolder) {
        val mfraSize: Long = input.length - input.position
        scratch.reset(Mp4Box.HEADER_SIZE)
        if (!input.peekFully(
                scratch.data,
                0,
                Mp4Box.HEADER_SIZE,  
                true
            )
        ) {
            onMfraProcessingFinished(
                SeekMap.Unseekable(durationUs, seekPositionBeforeMfraProcessing), seekPosition
            )
            return
        }
        scratch.setPosition(0)
        val mfraBoxSize = scratch.readInt()
        val mfraBoxType = scratch.readInt()
        if (mfraBoxType != Mp4Box.TYPE_mfra) {
            onMfraProcessingFinished(
                SeekMap.Unseekable(durationUs, seekPositionBeforeMfraProcessing), seekPosition
            )
            return
        }

        val mfra: ParsableByteArray = ParsableByteArray(mfraSize.toInt())
        input.readFully(mfra.data, 0, mfraSize.toInt())

        mfra.setPosition(if (mfraBoxSize == 1) Mp4Box.LONG_HEADER_SIZE else Mp4Box.HEADER_SIZE)

        val trackTimesUs = SparseArray<LongArray?>()
        val trackOffsets = SparseArray<LongArray?>()

        while (mfra.bytesLeft() >= Mp4Box.HEADER_SIZE) {
            val boxStartPosition: Int = mfra.getPosition()
            val boxSize: Long = mfra.readUnsignedInt()
            val boxType: Int = mfra.readInt()
            var actualBoxSize = boxSize

            if (boxSize == 1L) {
                if (mfra.bytesLeft() < Mp4Box.HEADER_SIZE) {
                    break
                }
                actualBoxSize = mfra.readLong()
            } else if (boxSize == 0L) {
                actualBoxSize = mfra.limit().toLong() - boxStartPosition
            }

            val headerSize: Int = if (boxSize == 1L) Mp4Box.LONG_HEADER_SIZE else Mp4Box.HEADER_SIZE
            if (actualBoxSize < headerSize || actualBoxSize > mfra.limit()
                    .toLong() - boxStartPosition
            ) {
                break
            }

            if (boxType == Mp4Box.TYPE_tfra) {
                // Protect against malformed tfra boxes. We need at least 16 bytes for the
                // basic fields (versionAndFlags, trackId, lengthFields, numberOfEntry).
                if (actualBoxSize < headerSize + 16) {
                    mfra.setPosition((boxStartPosition + actualBoxSize).toInt())
                    continue
                }

                val versionAndFlags: Int = mfra.readInt()
                val version = BoxParser.parseFullBoxVersion(versionAndFlags)
                val trackId: Int = mfra.readInt()

                val trackBundle = trackBundles.get(trackId)
                if (trackBundle == null) {
                    mfra.setPosition((boxStartPosition + actualBoxSize).toInt())
                    continue
                }
                val timescale = trackBundle.moovSampleTable.track!!.timescale

                val lengthFields: Int = mfra.readInt()
                val lengthSizeOfTrafNum = (lengthFields shr 4) and 3
                val lengthSizeOfTrunNum = (lengthFields shr 2) and 3
                val lengthSizeOfSampleNum = lengthFields and 3
                val numberOfEntry: Long = mfra.readUnsignedInt()

                // The time and moof_offset fields are 8 bytes each for version 1, and 4 bytes each for
                // version 0.
                val entrySize =
                    ((if (version == 1) 16L else 8L)
                            + (lengthSizeOfTrafNum + 1)
                            + (lengthSizeOfTrunNum + 1)
                            + (lengthSizeOfSampleNum + 1))

                if (numberOfEntry * entrySize > mfra.bytesLeft()) {
                    mfra.setPosition((boxStartPosition + actualBoxSize).toInt())
                    continue
                }

                val timesUs = LongArray(numberOfEntry.toInt())
                val offsets = LongArray(numberOfEntry.toInt())

                for (i in 0..<numberOfEntry.toInt()) {
                    val time: Long =
                        if (version == 1) mfra.readUnsignedLongToLong() else mfra.readUnsignedInt()
                    val moofOffset: Long =
                        if (version == 1) mfra.readUnsignedLongToLong() else mfra.readUnsignedInt()

                    mfra.skipBytes(
                        (lengthSizeOfTrafNum + 1) + (lengthSizeOfTrunNum + 1) + (lengthSizeOfSampleNum + 1)
                    )

                    timesUs[i] =
                        if (timescale != C.TIME_UNSET)
                            Util.scaleLargeTimestamp(time, C.MICROS_PER_SECOND, timescale)
                        else
                            time
                    offsets[i] = moofOffset
                }
                trackTimesUs.put(trackId, timesUs)
                trackOffsets.put(trackId, offsets)
            }
            mfra.setPosition((boxStartPosition + actualBoxSize).toInt())
        }

        if (trackTimesUs.size() == 0) {
            onMfraProcessingFinished(
                SeekMap.Unseekable(durationUs, seekPositionBeforeMfraProcessing), seekPosition
            )
            return
        }

        var firstVideoTrackId = C.INDEX_UNSET
        var firstAudioTrackId = C.INDEX_UNSET
        for (i in 0..<trackTimesUs.size()) {
            val trackId = trackTimesUs.keyAt(i)
            val trackBundle = trackBundles.get(trackId)
            if (trackBundle == null) {
                continue
            }
            val trackType = trackBundle.moovSampleTable.track!!.type
            if (firstVideoTrackId == C.INDEX_UNSET && trackType == C.TRACK_TYPE_VIDEO) {
                firstVideoTrackId = trackId
            } else if (firstAudioTrackId == C.INDEX_UNSET && trackType == C.TRACK_TYPE_AUDIO) {
                firstAudioTrackId = trackId
            }
        }
        val primaryTrackId =
            if (firstVideoTrackId != C.INDEX_UNSET)
                firstVideoTrackId
            else
                if (firstAudioTrackId != C.INDEX_UNSET) firstAudioTrackId else trackTimesUs.keyAt(0)

        onMfraProcessingFinished(
            MfraSeekMap(
                trackTimesUs,
                trackOffsets,
                durationUs,
                seekPositionBeforeMfraProcessing,
                primaryTrackId
            ),
            seekPosition
        )
    }

    private fun onMfraProcessingFinished(seekMap: SeekMap, seekPosition: PositionHolder) {
        extractorOutput.seekMap(seekMap)
        haveOutputSeekMap = true
        seekPosition.position = seekPositionBeforeMfraProcessing
        enterReadingAtomHeaderState()
    }

    private class MfraSeekMap(
        private val trackTimesUs: SparseArray<LongArray?>,
        private val trackOffsets: SparseArray<LongArray?>,
        override val durationUs: Long,
        private val firstMediaDataPosition: Long,
        private val primaryTrackId: Int
    ) : TrackAwareSeekMap {
        override val isSeekable: Boolean
            get() = true

        override fun isSeekable(trackId: Int): Boolean {
            return trackTimesUs.indexOfKey(trackId) >= 0
        }

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            return getSeekPoints(timeUs, primaryTrackId)
        }

        override fun getSeekPoints(timeUs: Long, trackId: Int): SeekMap.SeekPoints {
            var timesUs = trackTimesUs.get(trackId)
            var offsets = trackOffsets.get(trackId)

            // Fallback safely if the requested track is missing
            if (timesUs == null || offsets == null) {
                timesUs = trackTimesUs.get(primaryTrackId)
                offsets = trackOffsets.get(primaryTrackId)
                if (timesUs == null || offsets == null) {
                    timesUs = trackTimesUs.valueAt(0)
                    offsets = trackOffsets.valueAt(0)
                }
            }

            // If the requested time is before the first indexed fragment, jump to the absolute start of
            // the media data.
            if (timesUs!!.size == 0 || timeUs < timesUs[0]) {
                return SeekMap.SeekPoints(SeekPoint(0, firstMediaDataPosition))
            }

            val index =
                Util.binarySearchFloor(
                    timesUs,
                    timeUs,  
                    true,  
                    true
                )
            return SeekMap.SeekPoints(SeekPoint(timesUs[index], offsets!![index]))
        }
    }

    private class TrackBundle(
        output: TrackOutput,
        moovSampleTable: TrackSampleTable,
        defaultSampleValues: DefaultSampleValues,
        baseFormat: Format
    ) {
        val output: TrackOutput
        val fragment: TrackFragment
        val scratch: ParsableByteArray

        var moovSampleTable: TrackSampleTable
        var defaultSampleValues: DefaultSampleValues
        var currentSampleIndex: Int = 0
        var currentSampleInTrackRun: Int = 0
        var currentTrackRunIndex: Int = 0
        var firstSampleToOutputIndex: Int = 0

        private val encryptionSignalByte: ParsableByteArray
        private val defaultInitializationVector: ParsableByteArray

         var pendingFormat: Format? = null

         var baseFormat: Format

         var currentlyInFragment = false

        init {
            this.output = output
            this.moovSampleTable = moovSampleTable
            this.defaultSampleValues = defaultSampleValues
            this.baseFormat = baseFormat
            fragment = TrackFragment()
            scratch = ParsableByteArray()
            encryptionSignalByte = ParsableByteArray(1)
            defaultInitializationVector = ParsableByteArray()
            if (DtsUtil.isDtsBaseAudioMimeType(baseFormat.sampleMimeType)) {
                pendingFormat = baseFormat
            }
            reset(moovSampleTable, defaultSampleValues)
        }

        fun reset(moovSampleTable: TrackSampleTable, defaultSampleValues: DefaultSampleValues) {
            this.moovSampleTable = moovSampleTable
            this.defaultSampleValues = defaultSampleValues
            if (pendingFormat == null) {
                output.format(baseFormat)
            }
            resetFragmentInfo()
        }

        fun updateDrmInitData(drmInitData: DrmInitData) {
            val encryptionBox =
                moovSampleTable.track!!.getSampleDescriptionEncryptionBox(
                    fragment.header!!.sampleDescriptionIndex
                )
            val schemeType = if (encryptionBox != null) encryptionBox.schemeType else null
            val updatedDrmInitData = drmInitData.copyWithSchemeType(schemeType)
            val format = baseFormat.buildUpon().setDrmInitData(updatedDrmInitData).build()
            if (pendingFormat != null) {
                pendingFormat = format
            } else {
                output.format(format)
            }
        }

        fun resetFragmentInfo() {
            fragment.reset()
            currentSampleIndex = 0
            currentTrackRunIndex = 0
            currentSampleInTrackRun = 0
            firstSampleToOutputIndex = 0
            currentlyInFragment = false
        }

        fun seek(timeUs: Long) {
            var searchIndex = currentSampleIndex
            while (searchIndex < fragment.sampleCount
                && fragment.getSamplePresentationTimeUs(searchIndex) <= timeUs
            ) {
                if (fragment.sampleIsSyncFrameTable?.get(searchIndex)==true) {
                    firstSampleToOutputIndex = searchIndex
                }
                searchIndex++
            }
        }

        val currentSamplePresentationTimeUs: Long
            
            get() = if (!currentlyInFragment)
                moovSampleTable.timestampsUs[currentSampleIndex]
            else
                fragment.getSamplePresentationTimeUs(currentSampleIndex)

        val currentSampleOffset: Long
            
            get() = if (!currentlyInFragment)
                moovSampleTable.offsets[currentSampleIndex]
            else
                fragment.trunDataPosition?.get(currentTrackRunIndex)!!

        val currentSampleSize: Int
            
            get() = if (!currentlyInFragment)
                moovSampleTable.sizes[currentSampleIndex]
            else
                fragment.sampleSizeTable[currentSampleIndex]

        val currentSampleFlags: Int
            
            get() {
                var flags =
                    if (!currentlyInFragment)
                        moovSampleTable.flags[currentSampleIndex]
                    else
                        (if (fragment.sampleIsSyncFrameTable?.get(currentSampleIndex)!!) C.BUFFER_FLAG_KEY_FRAME else 0)
                if (this.encryptionBoxIfEncrypted != null) {
                    flags = flags or C.BUFFER_FLAG_ENCRYPTED
                }
                return flags
            }

        fun next(): Boolean {
            currentSampleIndex++
            if (!currentlyInFragment) {
                return false
            }
            currentSampleInTrackRun++
            if (currentSampleInTrackRun == fragment.trunLength[currentTrackRunIndex]) {
                currentTrackRunIndex++
                currentSampleInTrackRun = 0
                return false
            }
            return true
        }

        fun outputSampleEncryptionData(sampleSize: Int, clearHeaderSize: Int): Int {
            val encryptionBox = this.encryptionBoxIfEncrypted
            if (encryptionBox == null) {
                return 0
            }

            val initializationVectorData: ParsableByteArray?
            val vectorSize: Int
            if (encryptionBox.perSampleIvSize != 0) {
                initializationVectorData = fragment.sampleEncryptionData
                vectorSize = encryptionBox.perSampleIvSize
            } else {
                // The default initialization vector should be used.
                val initVectorData: ByteArray =encryptionBox.defaultInitializationVector!!
                defaultInitializationVector.reset(initVectorData, initVectorData.size)
                initializationVectorData = defaultInitializationVector
                vectorSize = initVectorData.size
            }

            val haveSubsampleEncryptionTable =
                fragment.sampleHasSubsampleEncryptionTable(currentSampleIndex)
            val writeSubsampleEncryptionData = haveSubsampleEncryptionTable || clearHeaderSize != 0

            // Write the signal byte, containing the vector size and the subsample encryption flag.
            encryptionSignalByte.data[0] =
                (vectorSize or (if (writeSubsampleEncryptionData) 0x80 else 0)).toByte()
            encryptionSignalByte.setPosition(0)
            output.sampleData(encryptionSignalByte, 1, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION)
            // Write the vector.
            output.sampleData(
                initializationVectorData, vectorSize, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
            )

            if (!writeSubsampleEncryptionData) {
                return 1 + vectorSize
            }

            if (!haveSubsampleEncryptionTable) {
                // The sample is fully encrypted, except for the additional clear header that the extractor
                // is going to prefix. We need to synthesize subsample encryption data that takes the header
                // into account.
                scratch.reset(SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH)
                // subsampleCount = 1 (unsigned short)
                val data = scratch.data
                data[0] = 0.toByte()
                data[1] = 1.toByte()
                // clearDataSize = clearHeaderSize (unsigned short)
                data[2] = ((clearHeaderSize shr 8) and 0xFF).toByte()
                data[3] = (clearHeaderSize and 0xFF).toByte()
                // encryptedDataSize = sampleSize (unsigned int)
                data[4] = ((sampleSize shr 24) and 0xFF).toByte()
                data[5] = ((sampleSize shr 16) and 0xFF).toByte()
                data[6] = ((sampleSize shr 8) and 0xFF).toByte()
                data[7] = (sampleSize and 0xFF).toByte()
                output.sampleData(
                    scratch,
                    SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH,
                    TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
                )
                return 1 + vectorSize + SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH
            }

            var subsampleEncryptionData: ParsableByteArray = fragment.sampleEncryptionData
            val subsampleCount: Int = subsampleEncryptionData.readUnsignedShort()
            subsampleEncryptionData.skipBytes(-2)
            val subsampleDataLength = 2 + 6 * subsampleCount

            if (clearHeaderSize != 0) {
                // We need to account for the additional clear header by adding clearHeaderSize to
                // clearDataSize for the first subsample specified in the subsample encryption data.
                scratch.reset(subsampleDataLength)
                val scratchData = scratch.data
                subsampleEncryptionData.readBytes(scratchData,  0, subsampleDataLength)

                val clearDataSize =
                    (scratchData[2].toInt() and 0xFF) shl 8 or (scratchData[3].toInt() and 0xFF)
                val adjustedClearDataSize = clearDataSize + clearHeaderSize
                scratchData[2] = ((adjustedClearDataSize shr 8) and 0xFF).toByte()
                scratchData[3] = (adjustedClearDataSize and 0xFF).toByte()
                subsampleEncryptionData = scratch
            }

            output.sampleData(
                subsampleEncryptionData,
                subsampleDataLength,
                TrackOutput.SAMPLE_DATA_PART_ENCRYPTION
            )
            return 1 + vectorSize + subsampleDataLength
        }

        fun skipSampleEncryptionData() {
            val encryptionBox = this.encryptionBoxIfEncrypted
            if (encryptionBox == null) {
                return
            }

            val sampleEncryptionData: ParsableByteArray = fragment.sampleEncryptionData
            if (encryptionBox.perSampleIvSize != 0) {
                sampleEncryptionData.skipBytes(encryptionBox.perSampleIvSize)
            }
            if (fragment.sampleHasSubsampleEncryptionTable(currentSampleIndex)) {
                sampleEncryptionData.skipBytes(6 * sampleEncryptionData.readUnsignedShort())
            }
        }

        val encryptionBoxIfEncrypted: TrackEncryptionBox?
            get() {
                if (!currentlyInFragment) {
                    // Encryption is not supported yet for samples specified in the sample table.
                    return null
                }
                val sampleDescriptionIndex: Int =
                    fragment.header!!.sampleDescriptionIndex
                val encryptionBox =
                    if (fragment.trackEncryptionBox != null)
                        fragment.trackEncryptionBox
                    else
                        moovSampleTable.track!!.getSampleDescriptionEncryptionBox(
                            sampleDescriptionIndex
                        )
                return if (encryptionBox != null && encryptionBox.isEncrypted) encryptionBox else null
            }

        companion object {
            private const val SINGLE_SUBSAMPLE_ENCRYPTION_DATA_LENGTH = 8
        }
    }

    companion object {
        
        fun newFactory(subtitleParserFactory: SubtitleParser.Factory?): ExtractorsFactory {
            return object :ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> {
                    return arrayOf<Extractor>(
                        FragmentedMp4Extractor(
                            subtitleParserFactory!!
                        )
                    )
                }
            }
        }

        const val FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME: Int = 1
        const val FLAG_WORKAROUND_IGNORE_TFDT_BOX: Int = 1 shl 1 // 2
        const  val FLAG_ENABLE_EMSG_TRACK: Int = 1 shl 2 // 4
        const    val FLAG_WORKAROUND_IGNORE_EDIT_LISTS: Int = 1 shl 4 // 16
        const  val FLAG_EMIT_RAW_SUBTITLE_DATA: Int = 1 shl 5 // 32
        const   val FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES: Int = 1 shl 6 // 64
        const   val FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265: Int = 1 shl 7
        const   val FLAG_MERGE_FRAGMENTED_SIDX: Int = 1 shl 8
        const   val FLAG_READ_MFRA_FOR_SEEK_MAP: Int = 1 shl 9
        const   val FLAG_DISABLE_ARTWORK_METADATA: Int = 1 shl 10

        @Deprecated("Use {@link #newFactory(SubtitleParser.Factory)} instead.")
        val FACTORY: ExtractorsFactory =object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return   arrayOf<Extractor>(
                    FragmentedMp4Extractor(
                        SubtitleParser.Factory.UNSUPPORTED,  FLAG_EMIT_RAW_SUBTITLE_DATA
                    )
                )
            }

        }

        private const val TAG = "FragmentedMp4Extractor"

        private const val SAMPLE_GROUP_TYPE_seig = 0x73656967

        private val PIFF_SAMPLE_ENCRYPTION_BOX_EXTENDED_TYPE =
            byteArrayOf(-94, 57, 79, 82, 90, -101, 79, 20, -94, 68, 108, 66, 124, 100, -115, -12)

        // Extra tracks constants.
        private val EMSG_FORMAT =
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build()
        private const val EXTRA_TRACKS_BASE_ID = 100

        // Parser states.
        private const val STATE_READING_ATOM_HEADER = 0
        private const val STATE_READING_ATOM_PAYLOAD = 1
        private const val STATE_READING_ENCRYPTION_DATA = 2
        private const val STATE_READING_SAMPLE_START = 3
        private const val STATE_READING_SAMPLE_CONTINUE = 4
        private const val STATE_READING_MFRO = 5
        private const val STATE_READING_MFRA = 6

        private const val MFRO_FIXED_BOX_SIZE = 16

        fun codecsToParseWithinGopSampleDependenciesAsFlags(
            videoCodecFlags: @C.VideoCodecFlags Int
        ): @Flags Int {
            var flags: @Flags Int = 0
            if ((videoCodecFlags and C.VIDEO_CODEC_FLAG_H264) != 0) {
                flags = flags or FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES
            }
            if ((videoCodecFlags and C.VIDEO_CODEC_FLAG_H265) != 0) {
                flags = flags or FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265
            }
            return flags
        }

        private fun parseTrex(trex: ParsableByteArray): Pair<Int?, DefaultSampleValues?> {
            trex.setPosition(Mp4Box.FULL_HEADER_SIZE)
            val trackId: Int = trex.readInt()
            val defaultSampleDescriptionIndex: Int = trex.readInt() - 1
            val defaultSampleDuration: Int = trex.readInt()
            val defaultSampleSize: Int = trex.readInt()
            val defaultSampleFlags: Int = trex.readInt()

            return Pair.create<Int?, DefaultSampleValues?>(
                trackId,
                DefaultSampleValues(
                    defaultSampleDescriptionIndex,
                    defaultSampleDuration,
                    defaultSampleSize,
                    defaultSampleFlags
                )
            )
        }

        private fun parseMehd(mehd: ParsableByteArray): Long {
            mehd.setPosition(Mp4Box.HEADER_SIZE)
            val fullAtom: Int = mehd.readInt()
            val version = BoxParser.parseFullBoxVersion(fullAtom)
            return if (version == 0) mehd.readUnsignedInt() else mehd.readUnsignedLongToLong()
        }

        @Throws(ParserException::class)
        private fun parseMoof(
            moof: Mp4Box.ContainerBox,
            trackBundles: SparseArray<TrackBundle>,
            haveSideloadedTrack: Boolean,
            flags: @Flags Int,
            extendedTypeScratch: ByteArray?
        ) {
            val moofContainerChildrenSize: Int = moof.containerChildren.size
            for (i in 0..<moofContainerChildrenSize) {
                val child: Mp4Box.ContainerBox = moof.containerChildren[i]
                // TODO: Support multiple traf boxes per track in a single moof.
                if (child.type == Mp4Box.TYPE_traf) {
                    parseTraf(child, trackBundles, haveSideloadedTrack, flags, extendedTypeScratch)
                }
            }
        }

        @Throws(ParserException::class)
        private fun parseTraf(
            traf: Mp4Box.ContainerBox,
            trackBundles: SparseArray<TrackBundle>,
            haveSideloadedTrack: Boolean,
            flags: @Flags Int,
            extendedTypeScratch: ByteArray?
        ) {
            val tfhd: Mp4Box.LeafBox =traf.getLeafBoxOfType(Mp4Box.TYPE_tfhd)!!
            val trackBundle: TrackBundle? = parseTfhd(tfhd!!.data!!, trackBundles, haveSideloadedTrack)
            if (trackBundle == null) {
                return
            }

            val fragment = trackBundle.fragment
            val fragmentDecodeTime = fragment.nextFragmentDecodeTime
            val fragmentDecodeTimeIncludesMoov = fragment.nextFragmentDecodeTimeIncludesMoov
            trackBundle.resetFragmentInfo()
            trackBundle.currentlyInFragment = true
            val tfdtAtom: Mp4Box.LeafBox? = traf.getLeafBoxOfType(Mp4Box.TYPE_tfdt)
            if (tfdtAtom != null && (flags and FLAG_WORKAROUND_IGNORE_TFDT_BOX) == 0) {
                fragment.nextFragmentDecodeTime = parseTfdt(tfdtAtom!!.data!!)
                fragment.nextFragmentDecodeTimeIncludesMoov = true
            } else {
                fragment.nextFragmentDecodeTime = fragmentDecodeTime
                fragment.nextFragmentDecodeTimeIncludesMoov = fragmentDecodeTimeIncludesMoov
            }

            parseTruns(traf, trackBundle, flags)

            val encryptionBox =
                trackBundle.moovSampleTable.track!!.getSampleDescriptionEncryptionBox(
                    fragment.header!!.sampleDescriptionIndex
                )

            val saiz: Mp4Box.LeafBox? = traf.getLeafBoxOfType(Mp4Box.TYPE_saiz)
            if (saiz != null) {
                parseSaiz(
                    encryptionBox!!,
                    saiz.data!!,
                    fragment
                )
            }

            val saio: Mp4Box.LeafBox? = traf.getLeafBoxOfType(Mp4Box.TYPE_saio)
            if (saio != null) {
                parseSaio(saio.data!!, fragment)
            }

            val senc: Mp4Box.LeafBox? = traf.getLeafBoxOfType(Mp4Box.TYPE_senc)
            if (senc != null) {
                parseSenc(senc.data!!, fragment)
            }

            parseSampleGroups(
                traf,
                if (encryptionBox != null) encryptionBox.schemeType else null,
                fragment
            )

            val leafChildrenSize: Int = traf.leafChildren.size
            for (i in 0..<leafChildrenSize) {
                val atom: Mp4Box.LeafBox = traf.leafChildren[i]
                if (atom.type == Mp4Box.TYPE_uuid) {
                    parseUuid(atom.data!!, fragment, extendedTypeScratch)
                }
            }
        }

        @Throws(ParserException::class)
        private fun parseTruns(traf: Mp4Box.ContainerBox, trackBundle: TrackBundle, flags: @Flags Int) {
            var trunCount = 0
            var totalSampleCount = 0
            val leafChildren: MutableList<Mp4Box.LeafBox> = traf.leafChildren
            val leafChildrenSize = leafChildren.size
            for (i in 0..<leafChildrenSize) {
                val atom: Mp4Box.LeafBox = leafChildren[i]
                if (atom.type == Mp4Box.TYPE_trun) {
                    val trunData: ParsableByteArray? = atom.data
                    trunData!!.setPosition(Mp4Box.FULL_HEADER_SIZE)
                    val trunSampleCount: Int = trunData.readUnsignedIntToInt()
                    if (trunSampleCount > 0) {
                        totalSampleCount += trunSampleCount
                        trunCount++
                    }
                }
            }
            trackBundle.currentTrackRunIndex = 0
            trackBundle.currentSampleInTrackRun = 0
            trackBundle.currentSampleIndex = 0
            trackBundle.fragment.initTables(trunCount, totalSampleCount)

            var trunIndex = 0
            var trunStartPosition = 0
            for (i in 0..<leafChildrenSize) {
                val trun: Mp4Box.LeafBox = leafChildren[i]
                if (trun.type == Mp4Box.TYPE_trun) {
                    trunStartPosition =
                        parseTrun(trackBundle, trunIndex++, flags, trun.data!!, trunStartPosition)
                }
            }
        }

        @Throws(ParserException::class)
        private fun parseSaiz(
            encryptionBox: TrackEncryptionBox, saiz: ParsableByteArray, out: TrackFragment
        ) {
            val vectorSize = encryptionBox.perSampleIvSize
            saiz.setPosition(Mp4Box.HEADER_SIZE)
            val fullAtom: Int = saiz.readInt()
            val flags = BoxParser.parseFullBoxFlags(fullAtom)
            if ((flags and 0x01) == 1) {
                saiz.skipBytes(8)
            }
            val defaultSampleInfoSize: Int = saiz.readUnsignedByte()

            val sampleCount: Int = saiz.readUnsignedIntToInt()
            if (sampleCount > out.sampleCount) {
                throw ParserException.createForMalformedContainer(
                    ("Saiz sample count "
                            + sampleCount
                            + " is greater than fragment sample count"
                            + out.sampleCount),  
                    null
                )
            }

            var totalSize = 0
            if (defaultSampleInfoSize == 0) {
                val sampleHasSubsampleEncryptionTable = out.sampleHasSubsampleEncryptionTable
                for (i in 0..<sampleCount) {
                    val sampleInfoSize: Int = saiz.readUnsignedByte()
                    totalSize += sampleInfoSize
                    sampleHasSubsampleEncryptionTable[i] = sampleInfoSize > vectorSize
                }
            } else {
                val subsampleEncryption = defaultSampleInfoSize > vectorSize
                totalSize += defaultSampleInfoSize * sampleCount
                Arrays.fill(
                    out.sampleHasSubsampleEncryptionTable,
                    0,
                    sampleCount,
                    subsampleEncryption
                )
            }
            Arrays.fill(out.sampleHasSubsampleEncryptionTable, sampleCount, out.sampleCount, false)
            if (totalSize > 0) {
                out.initEncryptionData(totalSize)
            }
        }

        @Throws(ParserException::class)
        private fun parseSaio(saio: ParsableByteArray, out: TrackFragment) {
            saio.setPosition(Mp4Box.HEADER_SIZE)
            val fullAtom: Int = saio.readInt()
            val flags = BoxParser.parseFullBoxFlags(fullAtom)
            if ((flags and 0x01) == 1) {
                saio.skipBytes(8)
            }

            val entryCount: Int = saio.readUnsignedIntToInt()
            if (entryCount != 1) {
                // We only support one trun element currently, so always expect one entry.
                throw ParserException.createForMalformedContainer(
                    "Unexpected saio entry count: " + entryCount,  null
                )
            }

            val version = BoxParser.parseFullBoxVersion(fullAtom)
            out.auxiliaryDataPosition +=
                if (version == 0) saio.readUnsignedInt() else saio.readUnsignedLongToLong()
        }

        private fun parseTfhd(
            tfhd: ParsableByteArray,
            trackBundles: SparseArray<TrackBundle>,
            haveSideloadedTrack: Boolean
        ): TrackBundle? {
            tfhd.setPosition(Mp4Box.HEADER_SIZE)
            val fullAtom: Int = tfhd.readInt()
            val atomFlags = BoxParser.parseFullBoxFlags(fullAtom)
            val trackId: Int = tfhd.readInt()
            val trackBundle =
                if (haveSideloadedTrack) trackBundles.valueAt(0) else trackBundles.get(trackId)
            if (trackBundle == null) {
                return null
            }
            if ((atomFlags and 0x01 ) != 0) {
                val baseDataPosition: Long = tfhd.readUnsignedLongToLong()
                trackBundle.fragment.dataPosition = baseDataPosition
                trackBundle.fragment.auxiliaryDataPosition = baseDataPosition
            }

            val defaultSampleValues = trackBundle.defaultSampleValues
            val defaultSampleDescriptionIndex =
                if ((atomFlags and 0x02 ) != 0)
                    tfhd.readInt() - 1
                else
                    defaultSampleValues.sampleDescriptionIndex
            val defaultSampleDuration =
                if ((atomFlags and 0x08 ) != 0)
                    tfhd.readInt()
                else
                    defaultSampleValues.duration
            val defaultSampleSize =
                if ((atomFlags and 0x10 ) != 0)
                    tfhd.readInt()
                else
                    defaultSampleValues.size
            val defaultSampleFlags =
                if ((atomFlags and 0x20 ) != 0)
                    tfhd.readInt()
                else
                    defaultSampleValues.flags
            trackBundle.fragment.header =
                DefaultSampleValues(
                    defaultSampleDescriptionIndex,
                    defaultSampleDuration,
                    defaultSampleSize,
                    defaultSampleFlags
                )
            return trackBundle
        }

        private fun parseTfdt(tfdt: ParsableByteArray): Long {
            tfdt.setPosition(Mp4Box.HEADER_SIZE)
            val fullAtom: Int = tfdt.readInt()
            val version = BoxParser.parseFullBoxVersion(fullAtom)
            return if (version == 1) tfdt.readUnsignedLongToLong() else tfdt.readUnsignedInt()
        }

        private fun isEdtsListDurationForEntireMediaTimeline(track: Track): Boolean {
            // Currently we only support a single edit that moves the entire media timeline (indicated by
            // duration == 0 or (editListDurationUs + editListMediaTimeUs) >= track duration.
            // Other uses of edit lists are uncommon and unsupported.
            if (track.editListDurations == null || track.editListDurations.length() != 1 || track.editListMediaTimes == null) {
                return false
            }
            if (track.editListDurations.get(0) == 0L) {
                return true
            }
            val editListDurationUs =
                Util.scaleLargeTimestamp(
                    track.editListDurations.get(0), C.MICROS_PER_SECOND, track.movieTimescale
                )
            val editListMediaTimeUs =
                Util.scaleLargeTimestamp(
                    track.editListMediaTimes.get(0), C.MICROS_PER_SECOND, track.timescale
                )
            return editListDurationUs + editListMediaTimeUs >= track.durationUs
        }

        @Throws(ParserException::class)
        private fun parseTrun(
            trackBundle: TrackBundle,
            index: Int,
            flags: @Flags Int,
            trun: ParsableByteArray,
            trackRunStart: Int
        ): Int {
            trun.setPosition(Mp4Box.HEADER_SIZE)
            val fullAtom: Int = trun.readInt()
            val atomFlags = BoxParser.parseFullBoxFlags(fullAtom)

            val track = trackBundle.moovSampleTable.track
            val fragment = trackBundle.fragment
            val defaultSampleValues: DefaultSampleValues = fragment.header!!

            fragment.trunLength[index] = trun.readUnsignedIntToInt()
            fragment.trunDataPosition!![index] = fragment.dataPosition
            if ((atomFlags and 0x01 ) != 0) {
                fragment.trunDataPosition!![index] += trun.readInt().toLong()
            }

            val firstSampleFlagsPresent = (atomFlags and 0x04 ) != 0
            var firstSampleFlags = defaultSampleValues.flags
            if (firstSampleFlagsPresent) {
                firstSampleFlags = trun.readInt()
            }

            val sampleDurationsPresent = (atomFlags and 0x100 ) != 0
            val sampleSizesPresent = (atomFlags and 0x200 ) != 0
            val sampleFlagsPresent = (atomFlags and 0x400 ) != 0
            val sampleCompositionTimeOffsetsPresent =
                (atomFlags and 0x800 ) != 0

            // Offset to the entire video timeline. In the presence of B-frames this is usually used to
            // ensure that the first frame's presentation timestamp is zero.
            var edtsOffset: Long = 0

            // Currently we only support a single edit that moves the entire media timeline.
            if (isEdtsListDurationForEntireMediaTimeline(track!!)) {
                edtsOffset = track.editListMediaTimes!!.get(0)
            }

            val sampleSizeTable = fragment.sampleSizeTable
            val samplePresentationTimesUs = fragment.samplePresentationTimesUs
            val sampleIsSyncFrameTable = fragment.sampleIsSyncFrameTable

            val workaroundEveryVideoFrameIsSyncFrame =
                track.type == C.TRACK_TYPE_VIDEO
                        && (flags and FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME) != 0

            val trackRunEnd = trackRunStart + fragment.trunLength[index]
            val timescale = track.timescale
            var cumulativeTime = fragment.nextFragmentDecodeTime
            for (i in trackRunStart..<trackRunEnd) {
                // Use trun values if present, otherwise tfhd, otherwise trex.
                val sampleDuration: Int =
                    checkNonNegative(if (sampleDurationsPresent) trun.readInt() else defaultSampleValues.duration)
                val sampleSize: Int =
                    checkNonNegative(if (sampleSizesPresent) trun.readInt() else defaultSampleValues.size)
                val sampleFlags =
                    if (sampleFlagsPresent)
                        trun.readInt()
                    else
                        if (i == 0 && firstSampleFlagsPresent) firstSampleFlags else defaultSampleValues.flags
                var sampleCompositionTimeOffset = 0
                if (sampleCompositionTimeOffsetsPresent) {
                    // The BMFF spec (ISO 14496-12) states that sample offsets should be unsigned integers in
                    // version 0 trun boxes, however a significant number of streams violate the spec and use
                    // signed integers instead. It's safe to always decode sample offsets as signed integers
                    // here, because unsigned integers will still be parsed correctly (unless their top bit is
                    // set, which is never true in practice because sample offsets are always small).
                    sampleCompositionTimeOffset = trun.readInt()
                }
                val samplePresentationTime =
                    cumulativeTime + sampleCompositionTimeOffset - edtsOffset
                samplePresentationTimesUs[i] =
                    Util.scaleLargeTimestamp(samplePresentationTime, C.MICROS_PER_SECOND, timescale)
                if (!fragment.nextFragmentDecodeTimeIncludesMoov) {
                    samplePresentationTimesUs[i] += trackBundle.moovSampleTable.durationUs
                }
                sampleSizeTable[i] = sampleSize
                sampleIsSyncFrameTable!![i] =
                    ((sampleFlags shr 16) and 0x1) == 0 && (!workaroundEveryVideoFrameIsSyncFrame || i == 0)
                cumulativeTime += sampleDuration.toLong()
            }
            fragment.nextFragmentDecodeTime = cumulativeTime
            return trackRunEnd
        }

        @Throws(ParserException::class)
        private fun checkNonNegative(value: Int): Int {
            if (value < 0) {
                throw ParserException.createForMalformedContainer(
                    "Unexpected negative value: " + value,  null
                )
            }
            return value
        }

        @Throws(ParserException::class)
        private fun parseUuid(
            uuid: ParsableByteArray, out: TrackFragment, extendedTypeScratch: ByteArray?
        ) {
            uuid.setPosition(Mp4Box.HEADER_SIZE)
            uuid.readBytes(extendedTypeScratch!!, 0, 16)

            // Currently this parser only supports Microsoft's PIFF SampleEncryptionBox.
            if (!extendedTypeScratch.contentEquals(PIFF_SAMPLE_ENCRYPTION_BOX_EXTENDED_TYPE)) {
                return
            }

            // Except for the extended type, this box is identical to a SENC box. See "Portable encoding of
            // audio-video objects: The Protected Interoperable File Format (PIFF), John A. Bocharov et al,
            // Section 5.3.2.1."
            parseSenc(uuid, 16, out)
        }

        @Throws(ParserException::class)
        private fun parseSenc(senc: ParsableByteArray, out: TrackFragment) {
            parseSenc(senc, 0, out)
        }

        @Throws(ParserException::class)
        private fun parseSenc(senc: ParsableByteArray, offset: Int, out: TrackFragment) {
            senc.setPosition(Mp4Box.HEADER_SIZE + offset)
            val fullAtom: Int = senc.readInt()
            val flags = BoxParser.parseFullBoxFlags(fullAtom)

            if ((flags and 0x01 ) != 0) {
                // TODO: Implement this.
                throw ParserException.createForUnsupportedContainerFeature(
                    "Overriding TrackEncryptionBox parameters is unsupported."
                )
            }

            val subsampleEncryption = (flags and 0x02 ) != 0
            val sampleCount: Int = senc.readUnsignedIntToInt()
            if (sampleCount == 0) {
                // Samples are unencrypted.
                Arrays.fill(out.sampleHasSubsampleEncryptionTable, 0, out.sampleCount, false)
                return
            } else if (sampleCount != out.sampleCount) {
                throw ParserException.createForMalformedContainer(
                    ("Senc sample count "
                            + sampleCount
                            + " is different from fragment sample count"
                            + out.sampleCount),  
                    null
                )
            }

            Arrays.fill(out.sampleHasSubsampleEncryptionTable, 0, sampleCount, subsampleEncryption)
            out.initEncryptionData(senc.bytesLeft())
            out.fillEncryptionData(senc)
        }

        @Throws(ParserException::class)
        private fun parseSampleGroups(
            traf: Mp4Box.ContainerBox, schemeType: String?, out: TrackFragment
        ) {
            // Find sbgp and sgpd boxes with grouping_type == seig.
            var sbgp: ParsableByteArray? = null
            var sgpd: ParsableByteArray? = null
            for (i in traf.leafChildren.indices) {
                val leafAtom: Mp4Box.LeafBox = traf.leafChildren[i]
                val leafAtomData: ParsableByteArray? = leafAtom.data
                if (leafAtom.type == Mp4Box.TYPE_sbgp) {
                    leafAtomData!!.setPosition(Mp4Box.FULL_HEADER_SIZE)
                    if (leafAtomData.readInt() == SAMPLE_GROUP_TYPE_seig) {
                        sbgp = leafAtomData
                    }
                } else if (leafAtom.type == Mp4Box.TYPE_sgpd) {
                    leafAtomData!!.setPosition(Mp4Box.FULL_HEADER_SIZE)
                    if (leafAtomData.readInt() == SAMPLE_GROUP_TYPE_seig) {
                        sgpd = leafAtomData
                    }
                }
            }
            if (sbgp == null || sgpd == null) {
                return
            }

            sbgp.setPosition(Mp4Box.HEADER_SIZE)
            val sbgpVersion = BoxParser.parseFullBoxVersion(sbgp.readInt())
            sbgp.skipBytes(4) // grouping_type == seig.
            if (sbgpVersion == 1) {
                sbgp.skipBytes(4) // grouping_type_parameter.
            }
            if (sbgp.readInt() != 1) { // entry_count.
                throw ParserException.createForUnsupportedContainerFeature(
                    "Entry count in sbgp != 1 (unsupported)."
                )
            }

            sgpd.setPosition(Mp4Box.HEADER_SIZE)
            val sgpdVersion = BoxParser.parseFullBoxVersion(sgpd.readInt())
            sgpd.skipBytes(4) // grouping_type == seig.
            val defaultLength: Long = if (sgpdVersion >= 1) sgpd.readUnsignedInt() else 0
            if (sgpdVersion >= 2) {
                sgpd.skipBytes(4) // default_sample_description_index.
            }
            if (sgpd.readUnsignedInt() != 1L) { // entry_count.
                throw ParserException.createForUnsupportedContainerFeature(
                    "Entry count in sgpd != 1 (unsupported)."
                )
            }
            if (sgpdVersion >= 1 && defaultLength == 0L) {
                sgpd.skipBytes(4) // description_length.
            }

            // CencSampleEncryptionInformationGroupEntry
            sgpd.skipBytes(1) // reserved = 0.
            val patternByte: Int = sgpd.readUnsignedByte()
            val cryptByteBlock = (patternByte and 0xF0) shr 4
            val skipByteBlock = patternByte and 0x0F
            val isProtected = sgpd.readUnsignedByte() == 1
            if (!isProtected) {
                return
            }
            val perSampleIvSize: Int = sgpd.readUnsignedByte()
            val keyId = ByteArray(16)
            sgpd.readBytes(keyId, 0, keyId.size)
            var constantIv: ByteArray? = null
            if (perSampleIvSize == 0) {
                val constantIvSize: Int = sgpd.readUnsignedByte()
                constantIv = ByteArray(constantIvSize)
                sgpd.readBytes(constantIv, 0, constantIvSize)
            }
            out.definesEncryptionData = true
            out.trackEncryptionBox =
                TrackEncryptionBox(
                    isProtected,
                    schemeType,
                    perSampleIvSize,
                    keyId,
                    cryptByteBlock,
                    skipByteBlock,
                    constantIv
                )
        }

        @Throws(ParserException::class)
        private fun parseSidx(
            atom: ParsableByteArray,
            inputPosition: Long
        ): Pair<Long?, ChunkIndex?> {
            atom.setPosition(Mp4Box.HEADER_SIZE)
            val fullAtom: Int = atom.readInt()
            val version = BoxParser.parseFullBoxVersion(fullAtom)

            atom.skipBytes(4)
            val timescale: Long = atom.readUnsignedInt()
            val earliestPresentationTime: Long
            var offset = inputPosition
            if (version == 0) {
                earliestPresentationTime = atom.readUnsignedInt()
                offset += atom.readUnsignedInt()
            } else {
                earliestPresentationTime = atom.readUnsignedLongToLong()
                offset += atom.readUnsignedLongToLong()
            }
            val earliestPresentationTimeUs =
                Util.scaleLargeTimestamp(earliestPresentationTime, C.MICROS_PER_SECOND, timescale)

            atom.skipBytes(2)

            val referenceCount: Int = atom.readUnsignedShort()
            val sizes = IntArray(referenceCount)
            val offsets = LongArray(referenceCount)
            val durationsUs = LongArray(referenceCount)
            val timesUs = LongArray(referenceCount)

            var time = earliestPresentationTime
            var timeUs = earliestPresentationTimeUs
            for (i in 0..<referenceCount) {
                val firstInt: Int = atom.readInt()

                val type = -0x80000000 and firstInt
                if (type != 0) {
                    throw ParserException.createForMalformedContainer(
                        "Unhandled indirect reference",  null
                    )
                }
                val referenceDuration: Long = atom.readUnsignedInt()

                sizes[i] = 0x7FFFFFFF and firstInt
                offsets[i] = offset

                // Calculate time and duration values such that any rounding errors are consistent. i.e. That
                // timesUs[i] + durationsUs[i] == timesUs[i + 1].
                timesUs[i] = timeUs
                time += referenceDuration
                timeUs = Util.scaleLargeTimestamp(time, C.MICROS_PER_SECOND, timescale)
                durationsUs[i] = timeUs - timesUs[i]

                atom.skipBytes(4)
                offset += sizes[i].toLong()
            }

            return Pair.create<Long?, ChunkIndex?>(
                earliestPresentationTimeUs, ChunkIndex(sizes, offsets, durationsUs, timesUs)
            )
        }

        private fun getNextTrackBundle(trackBundles: SparseArray<TrackBundle>): TrackBundle? {
            var nextTrackBundle: TrackBundle? = null
            var nextSampleOffset = Long.MAX_VALUE

            val trackBundlesSize = trackBundles.size()
            for (i in 0..<trackBundlesSize) {
                val trackBundle = trackBundles.valueAt(i)
                if ((!trackBundle.currentlyInFragment
                            && trackBundle.currentSampleIndex == trackBundle.moovSampleTable.sampleCount)
                    || (trackBundle.currentlyInFragment
                            && trackBundle.currentTrackRunIndex == trackBundle.fragment.trunCount)
                ) {
                    // This track sample table or fragment contains no more runs in the next mdat box.
                } else {
                    val sampleOffset = trackBundle.currentSampleOffset
                    if (sampleOffset < nextSampleOffset) {
                        nextTrackBundle = trackBundle
                        nextSampleOffset = sampleOffset
                    }
                }
            }
            return nextTrackBundle
        }

        private fun getDrmInitDataFromAtoms(leafChildren: MutableList<Mp4Box.LeafBox>): DrmInitData? {
            var schemeDatas: ArrayList<DrmInitData.SchemeData>? = null
            val leafChildrenSize = leafChildren.size
            for (i in 0..<leafChildrenSize) {
                val child: Mp4Box.LeafBox = leafChildren[i]
                if (child.type == Mp4Box.TYPE_pssh) {
                    if (schemeDatas == null) {
                        schemeDatas = ArrayList<DrmInitData.SchemeData>()
                    }
                    val psshData: ByteArray = child.data!!.data
                    val uuid = PsshAtomUtil.parseUuid(psshData)
                    if (uuid == null) {
                        Log.w(TAG, "Skipped pssh atom (failed to extract uuid)")
                    } else {
                        schemeDatas.add(DrmInitData.SchemeData(uuid, MimeTypes.VIDEO_MP4, psshData))
                    }
                }
            }
            return if (schemeDatas == null) null else DrmInitData(schemeDatas)
        }

        private fun shouldParseLeafAtom(atom: Int): Boolean {
            return atom == Mp4Box.TYPE_hdlr || atom == Mp4Box.TYPE_mdhd || atom == Mp4Box.TYPE_mvhd || atom == Mp4Box.TYPE_sidx || atom == Mp4Box.TYPE_stsd || atom == Mp4Box.TYPE_stts || atom == Mp4Box.TYPE_ctts || atom == Mp4Box.TYPE_stsc || atom == Mp4Box.TYPE_stsz || atom == Mp4Box.TYPE_stz2 || atom == Mp4Box.TYPE_stco || atom == Mp4Box.TYPE_co64 || atom == Mp4Box.TYPE_stss || atom == Mp4Box.TYPE_tfdt || atom == Mp4Box.TYPE_tfhd || atom == Mp4Box.TYPE_tkhd || atom == Mp4Box.TYPE_trex || atom == Mp4Box.TYPE_trun || atom == Mp4Box.TYPE_pssh || atom == Mp4Box.TYPE_saiz || atom == Mp4Box.TYPE_saio || atom == Mp4Box.TYPE_senc || atom == Mp4Box.TYPE_uuid || atom == Mp4Box.TYPE_sbgp || atom == Mp4Box.TYPE_sgpd || atom == Mp4Box.TYPE_elst || atom == Mp4Box.TYPE_mehd || atom == Mp4Box.TYPE_emsg || atom == Mp4Box.TYPE_udta || atom == Mp4Box.TYPE_keys || atom == Mp4Box.TYPE_ilst
        }

        private fun shouldParseContainerAtom(atom: Int): Boolean {
            return atom == Mp4Box.TYPE_moov || atom == Mp4Box.TYPE_trak || atom == Mp4Box.TYPE_mdia || atom == Mp4Box.TYPE_minf || atom == Mp4Box.TYPE_stbl || atom == Mp4Box.TYPE_moof || atom == Mp4Box.TYPE_traf || atom == Mp4Box.TYPE_mvex || atom == Mp4Box.TYPE_edts || atom == Mp4Box.TYPE_meta
        }
    }
}
