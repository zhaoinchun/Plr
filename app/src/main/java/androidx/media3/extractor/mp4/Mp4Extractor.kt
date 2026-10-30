
package androidx.media3.extractor.mp4

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.AuxiliaryTrackType
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.C.VideoCodecFlags
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isVideo
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.usToMs
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4Box
import androidx.media3.container.Mp4Box.ContainerBox
import androidx.media3.container.Mp4Box.LeafBox
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.NalUnitUtil.isDependedOn
import androidx.media3.container.NalUnitUtil.numberOfBytesInNalUnitHeader
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.Ac4Util
import androidx.media3.extractor.Ac4Util.getAc4SampleHeader
import androidx.media3.extractor.DtsUtil.isDtsBaseAudioMimeType
import androidx.media3.extractor.DtsUtil.updateFormatWithDtsHdInfo
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.TrueHdSampleRechunker
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.metadata.ThumbnailMetadata
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import kotlin.collections.toTypedArray
import kotlin.math.max
import kotlin.math.min

class Mp4Extractor  constructor(
    private val subtitleParserFactory: SubtitleParser.Factory,
    private val flags: @Flags Int = 0
) : Extractor {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_WORKAROUND_IGNORE_EDIT_LISTS, FLAG_READ_SEF_DATA, FLAG_MARK_FIRST_VIDEO_TRACK_WITH_MAIN_ROLE, FLAG_EMIT_RAW_SUBTITLE_DATA, FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES, FLAG_READ_AUXILIARY_TRACKS, FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265, FLAG_OMIT_TRACK_SAMPLE_TABLE, FLAG_DISABLE_ARTWORK_METADATA
        ]
    )
    annotation class Flags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_READING_ATOM_HEADER, STATE_READING_ATOM_PAYLOAD, STATE_READING_SAMPLE, STATE_READING_SEF, STATE_READING_QUICKTIME_CHAPTERS
        ]
    )
    private annotation class State

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [FILE_TYPE_MP4, FILE_TYPE_QUICKTIME])
    private annotation class FileType

    private val omitTrackSampleTable: Boolean

    // Temporary arrays.
    private val nalStartCode: ParsableByteArray
    private val nalPrefix: ParsableByteArray
    private val scratch: ParsableByteArray

    private val atomHeader: ParsableByteArray
    private val containerAtoms: ArrayDeque<ContainerBox>
    private val sefReader: SefReader
    private val slowMotionMetadataEntries: MutableList<Metadata.Entry>
    private val chapterSampleTables: MutableList<TrackSampleTable>
    private val quickTimeChapters: MutableList<Chapter>

    override var sniffFailureDetails: List<SniffFailure>
        private set
    private var parserState: @State Int
    private var atomType = 0
    private var atomSize: Long = 0
    private var atomHeaderBytesRead = 0
    private var atomData: ParsableByteArray? = null

    private var sampleTrackIndex: Int
    private var sampleBytesRead = 0
    private var sampleBytesWritten = 0
    private var sampleCurrentNalBytesRemaining = 0
    private var isSampleDependedOn = false
    private var seenFtypAtom = false
    private var seekToAxteAtom = false
    private var axteAtomOffset: Long = 0
    private var readingAuxiliaryTracks = false
    private var moovAtomProcessed = false
    private var chapterTrackIndex = 0
    private var chapterSampleIndex = 0

    // Used when auxiliary tracks samples are in the auxiliary tracks MP4 (inside axte atom).
    private var sampleOffsetForAuxiliaryTracks: Long = 0

    // Extractor outputs.
    private var extractorOutput: ExtractorOutput
    private var tracks: Array<Mp4Track>

    private var accumulatedSampleSizes: Array<LongArray>? = null
    private var fileType: @FileType Int = 0

    @Deprecated("Use {@link #Mp4Extractor(SubtitleParser.Factory)} instead")
    constructor() : this(
        SubtitleParser.Factory.UNSUPPORTED,  
        FLAG_EMIT_RAW_SUBTITLE_DATA
    )

    @Deprecated("Use {@link #Mp4Extractor(SubtitleParser.Factory, int)} instead")
    constructor(flags: @Flags Int) : this(SubtitleParser.Factory.UNSUPPORTED, flags)

    init {
        omitTrackSampleTable = (flags and FLAG_OMIT_TRACK_SAMPLE_TABLE) != 0
        this.sniffFailureDetails = mutableListOf<SniffFailure>()
        parserState =
            if ((flags and FLAG_READ_SEF_DATA) != 0) STATE_READING_SEF else STATE_READING_ATOM_HEADER
        sefReader = SefReader()
        slowMotionMetadataEntries = ArrayList<Metadata.Entry>()
        atomHeader = ParsableByteArray(Mp4Box.LONG_HEADER_SIZE)
        containerAtoms = ArrayDeque<ContainerBox>()
        nalStartCode = ParsableByteArray(NalUnitUtil.NAL_START_CODE)
        nalPrefix = ParsableByteArray(6)
        scratch = ParsableByteArray()
        sampleTrackIndex = C.INDEX_UNSET
        extractorOutput = ExtractorOutput.PLACEHOLDER
        tracks = arrayOf()
        chapterSampleTables = ArrayList<TrackSampleTable>()
        quickTimeChapters = ArrayList<Chapter>()
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        val sniffFailure = Sniffer.sniffUnfragmented(input)
        this.sniffFailureDetails =
            if (sniffFailure != null) listOf(sniffFailure) else listOf<SniffFailure>()
        return sniffFailure == null
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput =
            (if ((flags and Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA) == 0)
                SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            else
                output)!!
    }

    override fun seek(position: Long, timeUs: Long) {
        containerAtoms.clear()
        atomHeaderBytesRead = 0
        sampleTrackIndex = C.INDEX_UNSET
        sampleBytesRead = 0
        sampleBytesWritten = 0
        sampleCurrentNalBytesRemaining = 0
        isSampleDependedOn = false
        moovAtomProcessed = false
        chapterTrackIndex = 0
        chapterSampleIndex = 0
        chapterSampleTables.clear()
        quickTimeChapters.clear()
        if (position == 0L) {
            // Reading the SEF data occurs before normal MP4 parsing. Therefore we can not transition to
            // reading the atom header until that has completed.
            if (parserState != STATE_READING_SEF) {
                enterReadingAtomHeaderState()
            } else {
                sefReader.reset()
                slowMotionMetadataEntries.clear()
            }
        } else {
            for (track in tracks) {
                updateSampleIndex(track, timeUs)
                if (track.trueHdSampleRechunker != null) {
                    track.trueHdSampleRechunker.reset()
                }
            }
        }
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (omitTrackSampleTable && moovAtomProcessed) {
            return Extractor.RESULT_END_OF_INPUT
        }
        while (true) {
            when (parserState) {
                STATE_READING_ATOM_HEADER -> if (!readAtomHeader(input)) {
                    return Extractor.RESULT_END_OF_INPUT
                }

                STATE_READING_ATOM_PAYLOAD -> if (readAtomPayload(input, seekPosition)) {
                    return Extractor.RESULT_SEEK
                }

                STATE_READING_SAMPLE -> return readSample(input, seekPosition)
                STATE_READING_SEF -> return readSefData(input, seekPosition)
                STATE_READING_QUICKTIME_CHAPTERS -> return readQuickTimeChapters(
                    input,
                    seekPosition
                )

                else -> throw IllegalStateException()
            }
        }
    }

    fun getSampleTimestampsUs(trackId: Int): LongArray? {
        if (tracks.size <= trackId) {
            return LongArray(0)
        }
        return tracks[trackId].sampleTable.timestampsUs
    }

    // Private methods.
    private fun enterReadingAtomHeaderState() {
        parserState = STATE_READING_ATOM_HEADER
        atomHeaderBytesRead = 0
    }

    @Throws(IOException::class)
    private fun readAtomHeader(input: ExtractorInput): Boolean {
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
            val headerBytesRemaining = Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE
            input.readFully(atomHeader.data, Mp4Box.HEADER_SIZE, headerBytesRemaining)
            atomHeaderBytesRead += headerBytesRemaining
            atomSize = atomHeader.readUnsignedLongToLong()
        } else if (atomSize == Mp4Box.EXTENDS_TO_END_SIZE.toLong()) {
            // The atom extends to the end of the file. Note that if the atom is within a container we can
            // work out its size even if the input length is unknown.
            var endPosition = input.length
            if (endPosition == C.LENGTH_UNSET.toLong()) {
                val containerAtom = containerAtoms.peek()
                if (containerAtom != null) {
                    endPosition = containerAtom.endPosition
                }
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

        if (shouldParseContainerAtom(atomType)) {
            val endPosition = input.position + atomSize - atomHeaderBytesRead
            if (atomSize != atomHeaderBytesRead.toLong() && atomType == Mp4Box.TYPE_meta) {
                maybeSkipRemainingMetaAtomHeaderBytes(input)
            }
            containerAtoms.push(ContainerBox(atomType, endPosition))
            if (atomSize == atomHeaderBytesRead.toLong()) {
                processAtomEnded(endPosition)
            } else {
                // Start reading the first child atom.
                enterReadingAtomHeaderState()
            }
        } else if (shouldParseLeafAtom(atomType)) {
            // We don't support parsing of leaf atoms that define extended atom sizes, or that have
            // lengths greater than Integer.MAX_VALUE.
            Preconditions.checkState(atomHeaderBytesRead == Mp4Box.HEADER_SIZE)
            Preconditions.checkState(atomSize <= Int.MAX_VALUE)
            val atomData = ParsableByteArray(atomSize.toInt())
            System.arraycopy(atomHeader.data, 0, atomData.data, 0, Mp4Box.HEADER_SIZE)
            this.atomData = atomData
            parserState = STATE_READING_ATOM_PAYLOAD
        } else {
            atomData = null
            parserState = STATE_READING_ATOM_PAYLOAD
        }

        return true
    }

    @Throws(IOException::class)
    private fun readAtomPayload(input: ExtractorInput, positionHolder: PositionHolder): Boolean {
        val atomPayloadSize = atomSize - atomHeaderBytesRead
        val atomEndPosition = input.position + atomPayloadSize
        var seekRequired = false
        val atomData = this.atomData
        if (atomData != null) {
            input.readFully(atomData.data, atomHeaderBytesRead, atomPayloadSize.toInt())
            if (atomType == Mp4Box.TYPE_ftyp) {
                seenFtypAtom = true
                fileType = processFtypAtom(atomData)
            } else if (!containerAtoms.isEmpty()) {
                containerAtoms.peek().add(LeafBox(atomType, atomData))
            }
        } else {
            if (!seenFtypAtom && atomType == Mp4Box.TYPE_mdat) {
                // The original QuickTime specification did not require files to begin with the ftyp atom.
                // See https://developer.apple.com/standards/qtff-2001.pdf.
                fileType = FILE_TYPE_QUICKTIME
            }
            // We don't need the data. Skip or seek, depending on how large the atom is.
            if (atomPayloadSize < RELOAD_MINIMUM_SEEK_DISTANCE) {
                input.skipFully(atomPayloadSize.toInt())
            } else {
                positionHolder.position = input.position + atomPayloadSize
                seekRequired = true
            }
        }
        processAtomEnded(atomEndPosition)
        if (seekToAxteAtom) {
            readingAuxiliaryTracks = true
            positionHolder.position = axteAtomOffset
            seekRequired = true
            seekToAxteAtom = false
        }
        return seekRequired && parserState != STATE_READING_SAMPLE
    }

    @Throws(IOException::class)
    private fun readSefData(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        val result: @Extractor.ReadResult Int =
            sefReader.read(input, seekPosition, slowMotionMetadataEntries)
        if (result == Extractor.RESULT_SEEK && seekPosition.position == 0L) {
            enterReadingAtomHeaderState()
        }
        return result
    }

    @Throws(ParserException::class)
    private fun processAtomEnded(atomEndPosition: Long) {
        while (!containerAtoms.isEmpty() && containerAtoms.peek().endPosition == atomEndPosition) {
            val containerAtom = containerAtoms.pop()
            if (containerAtom.type == Mp4Box.TYPE_moov) {
                // We've reached the end of the moov atom. Process it and prepare to read samples.
                processMoovAtom(containerAtom)
                containerAtoms.clear()
                moovAtomProcessed = true
                if (!seekToAxteAtom && !omitTrackSampleTable) {
                    parserState =
                        if (!chapterSampleTables.isEmpty())
                            STATE_READING_QUICKTIME_CHAPTERS
                        else
                            STATE_READING_SAMPLE
                }
            } else if (!containerAtoms.isEmpty()) {
                containerAtoms.peek().add(containerAtom)
            }
        }
        if (parserState != STATE_READING_QUICKTIME_CHAPTERS && parserState != STATE_READING_SAMPLE) {
            enterReadingAtomHeaderState()
        }
    }

    @Throws(ParserException::class)
    private fun processMoovAtom(moov: ContainerBox) {
        // Process metadata first to determine whether to abort processing and seek to the axte atom.
        var mdtaMetadata: Metadata? = null
        val meta = moov.getContainerBoxOfType(Mp4Box.TYPE_meta)
        var auxiliaryTrackTypesForAuxiliaryTracks: MutableList<Int?> = ArrayList<Int?>()
        if (meta != null) {
            mdtaMetadata = BoxParser.parseMdtaFromMeta(meta)
            if (readingAuxiliaryTracks) {
                maybeSetDefaultSampleOffsetForAuxiliaryTracks(mdtaMetadata!!)
                auxiliaryTrackTypesForAuxiliaryTracks =
                    getAuxiliaryTrackTypesForAuxiliaryTracks(mdtaMetadata)
            } else if (shouldSeekToAxteAtom(mdtaMetadata)) {
                seekToAxteAtom = true
                return
            }
        }
        var firstVideoTrackIndex = C.INDEX_UNSET
        var durationUs = C.TIME_UNSET
        val tracks: MutableList<Mp4Track> = ArrayList<Mp4Track>()

        // Process remaining metadata.
        val isQuickTime = fileType == FILE_TYPE_QUICKTIME
        val gaplessInfoHolder = GaplessInfoHolder()
        var udtaMetadata: Metadata? = null
        val udta = moov.getLeafBoxOfType(Mp4Box.TYPE_udta)
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

        val ignoreEditLists = (flags and FLAG_WORKAROUND_IGNORE_EDIT_LISTS) != 0
        val trackSampleTables =
            BoxParser.parseTraks(
                moov,
                gaplessInfoHolder,  
                C.TIME_UNSET,  
                null,
                ignoreEditLists,
                isQuickTime,  
                Function { track: Track? -> track },
                omitTrackSampleTable
            )

        if (readingAuxiliaryTracks) {
            Preconditions.checkState(
                auxiliaryTrackTypesForAuxiliaryTracks.size == trackSampleTables.size,
                String.format(
                    Locale.US,
                    "The number of auxiliary track types from metadata (%d) is not same as the number of"
                            + " auxiliary tracks (%d)",
                    auxiliaryTrackTypesForAuxiliaryTracks.size,
                    trackSampleTables.size
                )
            )
        }

        val chapterTrackIds: MutableList<Int?> = ArrayList<Int?>()
        for (table in trackSampleTables) {
            if (table!!.track!!.chapterTrackId != C.INDEX_UNSET
                && !chapterTrackIds.contains(table.track.chapterTrackId)
            ) {
                chapterTrackIds.add(table.track.chapterTrackId)
            }
        }

        chapterSampleTables.clear()
        for (table in trackSampleTables) {
            if (chapterTrackIds.contains(table!!.track!!.id)) {
                chapterSampleTables.add(table)
            }
        }

        var trackIndex = 0
        val containerMimeType = MimeTypeResolver.getContainerMimeType(trackSampleTables)
        for (i in trackSampleTables.indices) {
            val trackSampleTable = trackSampleTables[i]
            if (trackSampleTable!!.sampleCount == 0) {
                continue
            }
            val track = trackSampleTable.track
            if (!track!!.shouldBeExposed) {
                continue
            }
            val mp4Track =
                Mp4Track(track, trackSampleTable, extractorOutput.track(trackIndex++, track.type)!!)
            val trackDurationUs =
                if (track.durationUs != C.TIME_UNSET) track.durationUs else trackSampleTable.durationUs
            mp4Track.trackOutput.durationUs(trackDurationUs)
            durationUs = max(durationUs, trackDurationUs)

            val maxInputSize: Int
            if (MimeTypes.AUDIO_TRUEHD == track.format!!.sampleMimeType) {
                // TrueHD groups samples per chunks of TRUEHD_RECHUNK_SAMPLE_COUNT samples.
                maxInputSize = trackSampleTable.maximumSize * Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT
            } else {
                // Each sample has up to three bytes of overhead for the start code that replaces its
                // length. Allow ten source samples per output sample, like the platform extractor.
                maxInputSize = trackSampleTable.maximumSize + 3 * 10
            }

            val formatBuilder = track.format!!.buildUpon()
            formatBuilder.setMaxInputSize(maxInputSize)
            if (track.type == C.TRACK_TYPE_VIDEO) {
                @RoleFlags var roleFlags: @RoleFlags Int = track.format.roleFlags
                if ((flags and FLAG_MARK_FIRST_VIDEO_TRACK_WITH_MAIN_ROLE) != 0) {
                    roleFlags =
                        roleFlags or if (firstVideoTrackIndex == C.INDEX_UNSET) C.ROLE_FLAG_MAIN else C.ROLE_FLAG_ALTERNATE
                }
                if (readingAuxiliaryTracks) {
                    roleFlags = roleFlags or C.ROLE_FLAG_AUXILIARY
                    formatBuilder.setAuxiliaryTrackType(auxiliaryTrackTypesForAuxiliaryTracks[i]!!)
                }
                formatBuilder.setRoleFlags(roleFlags)
            }

            var thumbnailMetadata: Metadata? = null
            val thumbnailPresentationTimeUs: Long =
                findBestThumbnailPresentationTimeUs(trackSampleTable, trackDurationUs)
            if (thumbnailPresentationTimeUs != C.TIME_UNSET) {
                thumbnailMetadata = Metadata(listOf(ThumbnailMetadata(thumbnailPresentationTimeUs)))
            }

            MetadataUtil.setFormatGaplessInfo(track.type, gaplessInfoHolder, formatBuilder)
            MetadataUtil.setFormatMetadata(
                track.type,
                mdtaMetadata,
                formatBuilder,
                track.format.metadata,
                if (slowMotionMetadataEntries.isEmpty()) null else Metadata(
                    slowMotionMetadataEntries
                ),
                udtaMetadata,
                mvhdMetadata,
                thumbnailMetadata
            )
            formatBuilder.setContainerMimeType(containerMimeType)
            val format = formatBuilder.build()
            // The moov and esds boxes don't contain enough information to distinguish between MPEG
            // audio layers 1, 2 and 3, but the distinction is important to select the right MIME type
            // for MediaCodec decoders (and other decoders that handle the same audio/mpeg-L1 and
            // audio/mpeg-L2 MIME types). DTS has a similar problem where we can't distinguish DTS,
            // DTS-HD and DTS Express. So we store the format with a placeholder MIME for now, and then
            // update the MIME type and pass it to TrackOutput.format(...) based on the info in the first
            // sample.
            val needsSamplesForMimeType =
                track.format.sampleMimeType == MimeTypes.AUDIO_MPEG
                        || isDtsBaseAudioMimeType(track.format.sampleMimeType)
            var needsChapterMetadata = false
            if (!omitTrackSampleTable && track.chapterTrackId != C.INDEX_UNSET) {
                for (chapterSampleTable in chapterSampleTables) {
                    if (chapterSampleTable.track!!.id == track.chapterTrackId) {
                        needsChapterMetadata = true
                        break
                    }
                }
            }
            if (needsSamplesForMimeType || needsChapterMetadata) {
                mp4Track.pendingFormat = format
            } else {
                mp4Track.trackOutput.format(format)
            }

            if (track.type == C.TRACK_TYPE_VIDEO && firstVideoTrackIndex == C.INDEX_UNSET) {
                firstVideoTrackIndex = tracks.size
            }
            tracks.add(mp4Track)
        }
        this.tracks = tracks.toTypedArray<Mp4Track>()
        accumulatedSampleSizes =
            if (!omitTrackSampleTable) calculateAccumulatedSampleSizes(this.tracks) else null

        extractorOutput.endTracks()
        extractorOutput.seekMap(Mp4SeekMap(durationUs, this.tracks, firstVideoTrackIndex))
    }

    private fun shouldSeekToAxteAtom(mdtaMetadata: Metadata?): Boolean {
        if (mdtaMetadata == null || (flags and FLAG_READ_AUXILIARY_TRACKS) == 0) {
            return false
        }
        val axteAtomOffsetMetadata =
            mdtaMetadata.getFirstMatchingEntry<MdtaMetadataEntry>(
                MdtaMetadataEntry::class.java,
                Predicate { mdtaEntry: MdtaMetadataEntry? -> mdtaEntry!!.key == MdtaMetadataEntry.KEY_AUXILIARY_TRACKS_OFFSET })
        if (axteAtomOffsetMetadata == null) {
            return false
        }
        val offset = ParsableByteArray(axteAtomOffsetMetadata.value).readUnsignedLongToLong()
        if (offset <= 0) {
            return false
        }
        axteAtomOffset = offset
        return true
    }

    private fun maybeSetDefaultSampleOffsetForAuxiliaryTracks(metadata: Metadata) {
        val samplesInterleavedMetadata =
            metadata.getFirstMatchingEntry<MdtaMetadataEntry>(
                MdtaMetadataEntry::class.java,
                Predicate { mdtaEntry: MdtaMetadataEntry? -> mdtaEntry!!.key == MdtaMetadataEntry.KEY_AUXILIARY_TRACKS_INTERLEAVED })
        if (samplesInterleavedMetadata != null
            && samplesInterleavedMetadata.value[0] == MdtaMetadataEntry.AUXILIARY_TRACKS_SAMPLES_NOT_INTERLEAVED
        ) {
            sampleOffsetForAuxiliaryTracks = axteAtomOffset + 16 // 16 bits for axte atom header
        }
    }

    private fun getAuxiliaryTrackTypesForAuxiliaryTracks(
        metadata: Metadata
    ): MutableList<Int?> {
        val trackTypesMetadata =metadata.getFirstMatchingEntry<MdtaMetadataEntry>(
            MdtaMetadataEntry::class.java,
            Predicate { mdtaEntry: MdtaMetadataEntry? -> mdtaEntry!!.key == MdtaMetadataEntry.KEY_AUXILIARY_TRACKS_MAP })!!
        val auxiliaryTrackTypesFromMap = trackTypesMetadata.auxiliaryTrackTypesFromMap
        val auxiliaryTrackTypes: MutableList<Int?> =
            ArrayList<Int?>(auxiliaryTrackTypesFromMap.size)
        for (i in auxiliaryTrackTypesFromMap.indices) {
            @AuxiliaryTrackType val auxiliaryTrackType: @AuxiliaryTrackType Int
            when (auxiliaryTrackTypesFromMap[i]) {
                0 -> auxiliaryTrackType = C.AUXILIARY_TRACK_TYPE_ORIGINAL
                1 -> auxiliaryTrackType = C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR
                2 -> auxiliaryTrackType = C.AUXILIARY_TRACK_TYPE_DEPTH_INVERSE
                3 -> auxiliaryTrackType = C.AUXILIARY_TRACK_TYPE_DEPTH_METADATA
                else -> auxiliaryTrackType = C.AUXILIARY_TRACK_TYPE_UNDEFINED
            }
            auxiliaryTrackTypes.add(auxiliaryTrackType)
        }
        return auxiliaryTrackTypes
    }

    @Throws(IOException::class)
    private fun readSample(input: ExtractorInput, positionHolder: PositionHolder): Int {
        val inputPosition = input.position
        if (sampleTrackIndex == C.INDEX_UNSET) {
            sampleTrackIndex = getTrackIndexOfNextReadSample(inputPosition)
            if (sampleTrackIndex == C.INDEX_UNSET) {
                return Extractor.RESULT_END_OF_INPUT
            }
        }
        val track = tracks[sampleTrackIndex]
        val trackOutput = track.trackOutput
        val sampleIndex = track.sampleIndex
        val position = track.sampleTable.offsets[sampleIndex] + sampleOffsetForAuxiliaryTracks
        var sampleSize = track.sampleTable.sizes[sampleIndex]
        val trueHdSampleRechunker = track.trueHdSampleRechunker
        var skipAmount = position - inputPosition + sampleBytesRead
        if (skipAmount < 0 || skipAmount >= RELOAD_MINIMUM_SEEK_DISTANCE) {
            positionHolder.position = position
            return Extractor.RESULT_SEEK
        }
        if (track.track.sampleTransformation == Track.TRANSFORMATION_CEA608_CDAT) {
            // The sample information is contained in a cdat atom. The header must be discarded for
            // committing.
            skipAmount += Mp4Box.HEADER_SIZE.toLong()
            sampleSize -= Mp4Box.HEADER_SIZE
        }
        input.skipFully(skipAmount.toInt())
        if (!canReadWithinGopSampleDependencies(track!!.track!!.format!!)) {
            isSampleDependedOn = true
        }
        if (track.track.nalUnitLengthFieldLength != 0) {
            // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
            // they're only 1 or 2 bytes long.
            val nalPrefixData = nalPrefix.data
            nalPrefixData[0] = 0
            nalPrefixData[1] = 0
            nalPrefixData[2] = 0
            val nalUnitLengthFieldLengthDiff = 4 - track.track.nalUnitLengthFieldLength
            sampleSize += nalUnitLengthFieldLengthDiff
            // NAL units are length delimited, but the decoder requires start code delimited units.
            // Loop until we've written the sample to the track output, replacing length delimiters with
            // start codes as we encounter them.
            while (sampleBytesWritten < sampleSize) {
                if (sampleCurrentNalBytesRemaining == 0) {
                    var nalUnitPrefixLength = track.track.nalUnitLengthFieldLength
                    var numberOfBytesToDetermineSampleDependencies = 0
                    if (!isSampleDependedOn
                        && (nalUnitPrefixLength + numberOfBytesInNalUnitHeader(track.track.format)
                                <= track.sampleTable.sizes[sampleIndex] - sampleBytesRead)
                    ) {
                        // Parsing sample dependencies needs the first few NAL unit bytes. Read them in the same
                        // readFully call that reads the NAL length. This ensures sampleBytesRead,
                        // sampleBytesWritten and isSampleDependedOn remain in a consistent state if we have
                        // read failures.
                        numberOfBytesToDetermineSampleDependencies =
                            numberOfBytesInNalUnitHeader(track.track.format)
                        nalUnitPrefixLength =
                            track.track.nalUnitLengthFieldLength + numberOfBytesToDetermineSampleDependencies
                    }
                    // Read the NAL length so that we know where we find the next one.
                    input.readFully(
                        nalPrefixData,
                        nalUnitLengthFieldLengthDiff,
                        nalUnitPrefixLength
                    )
                    sampleBytesRead += nalUnitPrefixLength
                    nalPrefix.setPosition(0)
                    val nalLengthInt = nalPrefix.readInt()
                    if (nalLengthInt < 0) {
                        throw ParserException.createForMalformedContainer(
                            "Invalid NAL length",  null
                        )
                    }
                    sampleCurrentNalBytesRemaining =
                        nalLengthInt - numberOfBytesToDetermineSampleDependencies
                    // Write a start code for the current NAL unit.
                    nalStartCode.setPosition(0)
                    trackOutput.sampleData(nalStartCode, 4)
                    sampleBytesWritten += 4
                    if (numberOfBytesToDetermineSampleDependencies > 0) {
                        // Write the first NAL unit bytes that were read.
                        trackOutput.sampleData(
                            nalPrefix,
                            numberOfBytesToDetermineSampleDependencies
                        )
                        sampleBytesWritten += numberOfBytesToDetermineSampleDependencies
                        // If any NAL unit that's part of this sample can be depended on, treat the entire
                        // sample as depended on.
                        if (isDependedOn(
                                nalPrefixData,  
                                4,  
                                numberOfBytesToDetermineSampleDependencies,
                                track.track.format
                            )
                        ) {
                            isSampleDependedOn = true
                        }
                    }
                } else {
                    // Write the payload of the NAL unit.
                    val writtenBytes =
                        trackOutput.sampleData(input, sampleCurrentNalBytesRemaining, false)
                    sampleBytesRead += writtenBytes
                    sampleBytesWritten += writtenBytes
                    sampleCurrentNalBytesRemaining -= writtenBytes
                }
            }
        } else {
            val pendingFormat = track.pendingFormat
            if (MimeTypes.AUDIO_AC4 == track.track.format.sampleMimeType) {
                if (sampleBytesWritten == 0) {
                    getAc4SampleHeader(sampleSize, scratch)
                    trackOutput.sampleData(scratch, Ac4Util.SAMPLE_HEADER_SIZE)
                    sampleBytesWritten += Ac4Util.SAMPLE_HEADER_SIZE
                }
                sampleSize += Ac4Util.SAMPLE_HEADER_SIZE
            } else if (pendingFormat != null
                && track.track.format.sampleMimeType == MimeTypes.AUDIO_MPEG
            ) {
                scratch.reset( 4)
                input.peekFully(scratch.data,  0,  4)
                input.resetPeekPosition()
                val mpegHeader = MpegAudioUtil.Header()
                track.trackOutput.format(
                    if (mpegHeader.setForHeaderData(scratch.readInt())
                        && pendingFormat.sampleMimeType != mpegHeader.mimeType
                    )
                        pendingFormat
                            .buildUpon()
                            .setSampleMimeType(mpegHeader.mimeType!!)
                            .build()
                    else
                        pendingFormat
                )
                track.pendingFormat = null
            } else if (pendingFormat != null
                && isDtsBaseAudioMimeType(track.track.format.sampleMimeType)
            ) {
                track.trackOutput.format(
                    updateFormatWithDtsHdInfo(input, sampleSize, pendingFormat)
                )
                track.pendingFormat = null
            } else if (trueHdSampleRechunker != null) {
                trueHdSampleRechunker.startSample(input)
            }

            while (sampleBytesWritten < sampleSize) {
                val writtenBytes =
                    trackOutput.sampleData(input, sampleSize - sampleBytesWritten, false)
                sampleBytesRead += writtenBytes
                sampleBytesWritten += writtenBytes
                sampleCurrentNalBytesRemaining -= writtenBytes
            }
        }

        val timeUs = track.sampleTable.timestampsUs[sampleIndex]
        var sampleFlags: @BufferFlags Int = track.sampleTable.flags[sampleIndex]
        if (!isSampleDependedOn) {
            sampleFlags = sampleFlags or C.BUFFER_FLAG_NOT_DEPENDED_ON
        }
        if (trueHdSampleRechunker != null) {
            trueHdSampleRechunker.sampleMetadata(
                trackOutput,
                timeUs,
                sampleFlags,
                sampleSize,  
                0,  
                null
            )
            if (sampleIndex + 1 == track.sampleTable.sampleCount) {
                trueHdSampleRechunker.outputPendingSampleMetadata(
                    trackOutput,  
                    null
                )
            }
        } else {
            trackOutput.sampleMetadata(
                timeUs, sampleFlags, sampleSize,  0,  null
            )
        }

        track.sampleIndex++
        sampleTrackIndex = C.INDEX_UNSET
        sampleBytesRead = 0
        sampleBytesWritten = 0
        sampleCurrentNalBytesRemaining = 0
        isSampleDependedOn = false
        return Extractor.RESULT_CONTINUE
    }

    @Throws(IOException::class)
    private fun readQuickTimeChapters(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val chapterSampleTable = chapterSampleTables.get(chapterTrackIndex)
        if (chapterSampleIndex < chapterSampleTable.sampleCount) {
            val offset = chapterSampleTable.offsets[chapterSampleIndex]
            if (input.position !== offset) {
                seekPosition.position = offset
                return Extractor.RESULT_SEEK
            }
            val size = chapterSampleTable.sizes[chapterSampleIndex]
            scratch.reset(size)
            input.readFully(scratch.data, 0, size)
            val length = scratch.readUnsignedShort()
            val stringLength = min(length, scratch.bytesLeft())
            val text = scratch.readString(stringLength)

            val startTimeMs = usToMs(chapterSampleTable.timestampsUs[chapterSampleIndex])
            val endTimeMs =
                if (chapterSampleIndex + 1 < chapterSampleTable.sampleCount)
                    usToMs(chapterSampleTable.timestampsUs[chapterSampleIndex + 1])
                else
                    usToMs(chapterSampleTable.durationUs)
            quickTimeChapters.add(
                Chapter.Builder()
                    .setStartTimeMs(startTimeMs)
                    .setEndTimeMs(endTimeMs)
                    .setTitle(Label( null, text))
                    .build()
            )
            chapterSampleIndex++
            return Extractor.RESULT_CONTINUE
        }

        for (track in tracks) {
            if (track.track.chapterTrackId == chapterSampleTable.track!!.id) {
                val currentFormat = track.pendingFormat!!
                val currentMetadata = currentFormat.metadata
                val filteredEntries: MutableList<Metadata.Entry> = ArrayList<Metadata.Entry>()
                if (currentMetadata != null) {
                    filteredEntries.addAll(
                        currentMetadata.getMatchingEntries<Metadata.Entry>(
                            Metadata.Entry::class.java,
                            Predicate { entry: Metadata.Entry? -> entry !is Chapter })
                    )
                }
                filteredEntries.addAll(quickTimeChapters)
                val updatedFormat =
                    currentFormat.buildUpon().setMetadata(Metadata(filteredEntries)).build()

                // The format was kept pending in processMoovAtom either because it was waiting for chapter
                // metadata, or because it is MPEG or DTS audio (which needs to wait for the first sample to
                // determine the exact MIME type). We have now applied the chapter metadata, so we can
                // output the format, unless it is also MPEG or DTS audio.
                if (updatedFormat.sampleMimeType == MimeTypes.AUDIO_MPEG
                    || isDtsBaseAudioMimeType(updatedFormat.sampleMimeType)
                ) {
                    track.pendingFormat = updatedFormat
                } else {
                    track.trackOutput.format(updatedFormat)
                    track.pendingFormat = null
                }
            }
        }

        chapterTrackIndex++
        chapterSampleIndex = 0
        quickTimeChapters.clear()

        if (chapterTrackIndex == chapterSampleTables.size) {
            parserState = STATE_READING_SAMPLE
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun getTrackIndexOfNextReadSample(inputPosition: Long): Int {
        var preferredSkipAmount = Long.MAX_VALUE
        var preferredRequiresReload = true
        var preferredTrackIndex = C.INDEX_UNSET
        var preferredAccumulatedBytes = Long.MAX_VALUE
        var minAccumulatedBytes = Long.MAX_VALUE
        var minAccumulatedBytesRequiresReload = true
        var minAccumulatedBytesTrackIndex = C.INDEX_UNSET

        var minVideoTimestampUs = Long.MAX_VALUE
        var minIt35TimestampUs = Long.MAX_VALUE
        var minIt35TrackIndex = C.INDEX_UNSET

        for (trackIndex in tracks.indices) {
            val track = tracks[trackIndex]
            val sampleIndex = track.sampleIndex
            if (sampleIndex == track.sampleTable.sampleCount) {
                continue
            }

            val sampleTimestampUs = track.sampleTable.timestampsUs[sampleIndex]
            if (track.isVideo) {
                minVideoTimestampUs = min(minVideoTimestampUs, sampleTimestampUs)
            } else if (track.isItutT35) {
                if (sampleTimestampUs < minIt35TimestampUs) {
                    minIt35TimestampUs = sampleTimestampUs
                    minIt35TrackIndex = trackIndex
                }
            }
            val sampleOffset = track.sampleTable.offsets[sampleIndex]
            val sampleAccumulatedBytes = accumulatedSampleSizes!![trackIndex]!![sampleIndex]
            val skipAmount = sampleOffset - inputPosition
            val requiresReload = skipAmount < 0 || skipAmount >= RELOAD_MINIMUM_SEEK_DISTANCE
            if ((!requiresReload && preferredRequiresReload)
                || (requiresReload == preferredRequiresReload && skipAmount < preferredSkipAmount)
            ) {
                preferredRequiresReload = requiresReload
                preferredSkipAmount = skipAmount
                preferredTrackIndex = trackIndex
                preferredAccumulatedBytes = sampleAccumulatedBytes
            }
            if (sampleAccumulatedBytes < minAccumulatedBytes) {
                minAccumulatedBytes = sampleAccumulatedBytes
                minAccumulatedBytesRequiresReload = requiresReload
                minAccumulatedBytesTrackIndex = trackIndex
            }
        }
        if (minVideoTimestampUs != Long.MAX_VALUE && minIt35TrackIndex != C.INDEX_UNSET && minIt35TimestampUs <= minVideoTimestampUs) {
            return minIt35TrackIndex
        }

        return if (minAccumulatedBytes == Long.MAX_VALUE || !minAccumulatedBytesRequiresReload || preferredAccumulatedBytes < minAccumulatedBytes + MAXIMUM_READ_AHEAD_BYTES_STREAM)
            preferredTrackIndex
        else
            minAccumulatedBytesTrackIndex
    }

    private fun updateSampleIndex(track: Mp4Track, timeUs: Long) {
        val sampleTable = track.sampleTable
        var sampleIndex = sampleTable.getIndexOfEarlierOrEqualSynchronizationSample(timeUs)
        if (sampleIndex == C.INDEX_UNSET) {
            // Handle the case where the requested time is before the first synchronization sample.
            sampleIndex = sampleTable.getIndexOfLaterOrEqualSynchronizationSample(timeUs)
        }
        track.sampleIndex = sampleIndex
    }

    @Throws(IOException::class)
    private fun maybeSkipRemainingMetaAtomHeaderBytes(input: ExtractorInput) {
        scratch.reset(Mp4Box.HEADER_SIZE)
        input.peekFully(scratch.data, 0, Mp4Box.HEADER_SIZE)
        BoxParser.maybeSkipRemainingMetaBoxHeaderBytes(scratch)
        input.skipFully(scratch.getPosition())
        input.resetPeekPosition()
    }

    private fun canReadWithinGopSampleDependencies(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_H264) {
            return (flags and FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES) != 0
        }
        if (format.sampleMimeType == MimeTypes.VIDEO_H265) {
            return (flags and FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265) != 0
        }
        // Do not flag gate APV sample dependency parsing - prior experiments with H.264 and H.265
        // were positive, and the defaults have been updated.
        return format.sampleMimeType == MimeTypes.VIDEO_APV
    }

    private class Mp4Track(
        val track: Track,
        val sampleTable: TrackSampleTable,
        val trackOutput: TrackOutput
    ) {
        val trueHdSampleRechunker: TrueHdSampleRechunker?
         val isVideo: Boolean
         val isItutT35: Boolean

        var sampleIndex: Int = 0

         var pendingFormat: Format? = null

        init {
            this.isVideo = track.type == C.TRACK_TYPE_VIDEO
            this.isItutT35 = track.format!!.sampleMimeType == MimeTypes.APPLICATION_ITUT_T35
            trueHdSampleRechunker =
                if (MimeTypes.AUDIO_TRUEHD == track.format.sampleMimeType)
                    TrueHdSampleRechunker()
                else
                    null
        }
    }

    private class Mp4SeekMap(
        override val durationUs: Long,
        private val tracks: Array<Mp4Track>,
        private val firstVideoTrackIndex: Int
    ) : TrackAwareSeekMap {
        override val isSeekable: Boolean
            get() = true

        override fun isSeekable(trackId: Int): Boolean {
            return true
        }

        override fun getSeekPoints(timeUs: Long): SeekPoints {
            return getSeekPoints(timeUs,  C.INDEX_UNSET)
        }

        override fun getSeekPoints(timeUs: Long, trackId: Int): SeekPoints {
            if (tracks.size == 0) {
                return SeekPoints(SeekPoint.START)
            }

            val firstTimeUs: Long
            var firstOffset: Long
            var secondTimeUs = C.TIME_UNSET
            var secondOffset = C.INDEX_UNSET.toLong()

            // Note that the id matches the index in tracks.
            val mainTrackIndex = if (trackId != C.INDEX_UNSET) trackId else firstVideoTrackIndex
            // If we have a video track, use it to establish one or two seek points.
            if (mainTrackIndex != C.INDEX_UNSET) {
                val sampleTable = tracks[mainTrackIndex].sampleTable
                val sampleIndex: Int = getSynchronizationSampleIndex(sampleTable, timeUs)
                if (sampleIndex == C.INDEX_UNSET) {
                    return SeekPoints(SeekPoint.START)
                }
                val sampleTimeUs = sampleTable.timestampsUs[sampleIndex]
                firstTimeUs = sampleTimeUs
                firstOffset = sampleTable.offsets[sampleIndex]
                if (sampleTimeUs < timeUs && sampleIndex < sampleTable.sampleCount - 1) {
                    val secondSampleIndex =
                        sampleTable.getIndexOfLaterOrEqualSynchronizationSample(timeUs)
                    if (secondSampleIndex != C.INDEX_UNSET && secondSampleIndex != sampleIndex) {
                        secondTimeUs = sampleTable.timestampsUs[secondSampleIndex]
                        secondOffset = sampleTable.offsets[secondSampleIndex]
                    }
                }
            } else {
                firstTimeUs = timeUs
                firstOffset = Long.MAX_VALUE
            }

            if (trackId == C.INDEX_UNSET) {
                // Take into account other tracks, but only if the caller has not specified a trackId.
                for (i in tracks.indices) {
                    if (i != firstVideoTrackIndex) {
                        val sampleTable = tracks[i].sampleTable
                        firstOffset = maybeAdjustSeekOffset(sampleTable, firstTimeUs, firstOffset)
                        if (secondTimeUs != C.TIME_UNSET) {
                            secondOffset =
                                maybeAdjustSeekOffset(sampleTable, secondTimeUs, secondOffset)
                        }
                    }
                }
            }

            val firstSeekPoint = SeekPoint(firstTimeUs, firstOffset)
            if (secondTimeUs == C.TIME_UNSET) {
                return SeekPoints(firstSeekPoint)
            } else {
                val secondSeekPoint = SeekPoint(secondTimeUs, secondOffset)
                return SeekPoints(firstSeekPoint, secondSeekPoint)
            }
        }
    }

    companion object {
        
        fun newFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
            return object :ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> {
                    return arrayOf<Extractor>(Mp4Extractor(subtitleParserFactory))
                }
            }
        }

        const val FLAG_WORKAROUND_IGNORE_EDIT_LISTS: Int = 1
        const  val FLAG_READ_SEF_DATA: Int = 1 shl 2
        const  val FLAG_MARK_FIRST_VIDEO_TRACK_WITH_MAIN_ROLE: Int = 1 shl 3
        const  val FLAG_EMIT_RAW_SUBTITLE_DATA: Int = 1 shl 4
        const  val FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES: Int = 1 shl 5
        const  val FLAG_READ_AUXILIARY_TRACKS: Int = 1 shl 6
        const  val FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265: Int = 1 shl 7
        const  val FLAG_OMIT_TRACK_SAMPLE_TABLE: Int = 1 shl 8
        const  val FLAG_DISABLE_ARTWORK_METADATA: Int = 1 shl 9
        private const val MAX_SYNC_SAMPLES_TO_SCAN_FOR_THUMBNAIL = 20
        private const val MAX_DURATION_US_TO_SCAN_FOR_THUMBNAIL = 10000000L

        @Deprecated("Use {@link #newFactory(SubtitleParser.Factory)} instead.")
        val FACTORY: ExtractorsFactory = object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(
                    Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, FLAG_EMIT_RAW_SUBTITLE_DATA)
                )
            }
        }

        private const val STATE_READING_ATOM_HEADER = 0
        private const val STATE_READING_ATOM_PAYLOAD = 1
        private const val STATE_READING_SAMPLE = 2
        private const val STATE_READING_SEF = 3
        private const val STATE_READING_QUICKTIME_CHAPTERS = 4

        private const val FILE_TYPE_MP4 = 0
        private const val FILE_TYPE_QUICKTIME = 1

        private val RELOAD_MINIMUM_SEEK_DISTANCE = (256 * 1024).toLong()

        private val MAXIMUM_READ_AHEAD_BYTES_STREAM = (10 * 1024 * 1024).toLong()

        fun codecsToParseWithinGopSampleDependenciesAsFlags(
            videoCodecFlags: @VideoCodecFlags Int
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

        private fun findBestThumbnailPresentationTimeUs(
            sampleTable: TrackSampleTable, durationUs: Long
        ): Long {
            if (!isVideo(sampleTable.track!!.format!!.sampleMimeType)
                || !sampleTable.hasSampleTableData()
            ) {
                return C.TIME_UNSET
            }

            var bestSampleIndex = -1
            var maxSampleSize = 0

            val syncSampleCount =
                if (sampleTable.hasOnlySyncSamples)
                    sampleTable.sampleCount
                else
                    sampleTable.syncSampleIndices.size
            val scanLimit = min(syncSampleCount, MAX_SYNC_SAMPLES_TO_SCAN_FOR_THUMBNAIL)
            Preconditions.checkState(durationUs != C.TIME_UNSET)
            val maxDurationUsToScan = min(durationUs, MAX_DURATION_US_TO_SCAN_FOR_THUMBNAIL)

            for (i in 0..<scanLimit) {
                val sampleIndex =
                    if (sampleTable.hasOnlySyncSamples) i else sampleTable.syncSampleIndices[i]
                val timestampUs = sampleTable.timestampsUs[sampleIndex]

                if (timestampUs > maxDurationUsToScan) {
                    break
                }

                if (timestampUs >= 0 && sampleTable.sizes[sampleIndex] > maxSampleSize) {
                    maxSampleSize = sampleTable.sizes[sampleIndex]
                    bestSampleIndex = sampleIndex
                }
            }

            return if (bestSampleIndex == -1) C.TIME_UNSET else sampleTable.timestampsUs[bestSampleIndex]
        }

        private fun calculateAccumulatedSampleSizes(tracks: Array<Mp4Track>): Array<LongArray> {
            val accumulatedSampleSizes = mutableListOf<LongArray>()
            val nextSampleIndex = IntArray(tracks.size)
            val nextSampleTimesUs = LongArray(tracks.size)
            val tracksFinished = BooleanArray(tracks.size)
            for (i in tracks.indices) {
                accumulatedSampleSizes.add(LongArray(tracks[i].sampleTable.sampleCount))
                nextSampleTimesUs[i] = tracks[i].sampleTable.timestampsUs[0]
            }
            var accumulatedSampleSize: Long = 0
            var finishedTracks = 0
            while (finishedTracks < tracks.size) {
                var minTimeUs = Long.MAX_VALUE
                var minTimeTrackIndex = -1
                for (i in tracks.indices) {
                    if (!tracksFinished[i] && nextSampleTimesUs[i] <= minTimeUs) {
                        minTimeTrackIndex = i
                        minTimeUs = nextSampleTimesUs[i]
                    }
                }
                var trackSampleIndex = nextSampleIndex[minTimeTrackIndex]
                accumulatedSampleSizes[minTimeTrackIndex]!![trackSampleIndex] =
                    accumulatedSampleSize
                accumulatedSampleSize += tracks[minTimeTrackIndex].sampleTable.sizes[trackSampleIndex].toLong()
                nextSampleIndex[minTimeTrackIndex] = ++trackSampleIndex
                if (trackSampleIndex < accumulatedSampleSizes[minTimeTrackIndex]!!.size) {
                    nextSampleTimesUs[minTimeTrackIndex] =
                        tracks[minTimeTrackIndex].sampleTable.timestampsUs[trackSampleIndex]
                } else {
                    tracksFinished[minTimeTrackIndex] = true
                    finishedTracks++
                }
            }
            return accumulatedSampleSizes.toTypedArray()
        }

        private fun maybeAdjustSeekOffset(
            sampleTable: TrackSampleTable, seekTimeUs: Long, offset: Long
        ): Long {
            val sampleIndex: Int = getSynchronizationSampleIndex(sampleTable, seekTimeUs)
            if (sampleIndex == C.INDEX_UNSET) {
                return offset
            }
            val sampleOffset = sampleTable.offsets[sampleIndex]
            return min(sampleOffset, offset)
        }

        private fun getSynchronizationSampleIndex(
            sampleTable: TrackSampleTable,
            timeUs: Long
        ): Int {
            var sampleIndex = sampleTable.getIndexOfEarlierOrEqualSynchronizationSample(timeUs)
            if (sampleIndex == C.INDEX_UNSET) {
                // Handle the case where the requested time is before the first synchronization sample.
                sampleIndex = sampleTable.getIndexOfLaterOrEqualSynchronizationSample(timeUs)
            }
            return sampleIndex
        }

        private fun processFtypAtom(atomData: ParsableByteArray): @FileType Int {
            atomData.setPosition(Mp4Box.HEADER_SIZE)
            val majorBrand = atomData.readInt()
            var fileType: @FileType Int = brandToFileType(majorBrand)
            if (fileType != FILE_TYPE_MP4) {
                return fileType
            }
            atomData.skipBytes(4) // minor_version
            while (atomData.bytesLeft() > 0) {
                fileType = brandToFileType(atomData.readInt())
                if (fileType != FILE_TYPE_MP4) {
                    return fileType
                }
            }
            return FILE_TYPE_MP4
        }

        private fun brandToFileType(brand: Int): @FileType Int {
            when (brand) {
                Sniffer.BRAND_QUICKTIME -> return FILE_TYPE_QUICKTIME
                else -> return FILE_TYPE_MP4
            }
        }

        private fun shouldParseLeafAtom(atom: Int): Boolean {
            return atom == Mp4Box.TYPE_mdhd || atom == Mp4Box.TYPE_mvhd || atom == Mp4Box.TYPE_hdlr || atom == Mp4Box.TYPE_stsd || atom == Mp4Box.TYPE_stts || atom == Mp4Box.TYPE_stss || atom == Mp4Box.TYPE_ctts || atom == Mp4Box.TYPE_elst || atom == Mp4Box.TYPE_stsc || atom == Mp4Box.TYPE_stsz || atom == Mp4Box.TYPE_stz2 || atom == Mp4Box.TYPE_stco || atom == Mp4Box.TYPE_co64 || atom == Mp4Box.TYPE_tkhd || atom == Mp4Box.TYPE_ftyp || atom == Mp4Box.TYPE_udta || atom == Mp4Box.TYPE_keys || atom == Mp4Box.TYPE_ilst || atom == Mp4Box.TYPE_chap
        }

        private fun shouldParseContainerAtom(atom: Int): Boolean {
            return atom == Mp4Box.TYPE_moov || atom == Mp4Box.TYPE_trak || atom == Mp4Box.TYPE_mdia || atom == Mp4Box.TYPE_minf || atom == Mp4Box.TYPE_stbl || atom == Mp4Box.TYPE_edts || atom == Mp4Box.TYPE_meta || atom == Mp4Box.TYPE_axte || atom == Mp4Box.TYPE_tref
        }
    }
}
