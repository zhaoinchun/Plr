
package androidx.media3.extractor.mp4

import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.C.ColorRange
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.C.PcmEncoding
import androidx.media3.common.C.StereoMode
import androidx.media3.common.ColorInfo
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.getMimeTypeFromMp4ObjectType
import androidx.media3.common.ParserException
import androidx.media3.common.util.CodecSpecificDataUtil.buildApvCodecString
import androidx.media3.common.util.CodecSpecificDataUtil.buildIamfCodecString
import androidx.media3.common.util.CodecSpecificDataUtil.buildVp9CodecPrivateInitializationData
import androidx.media3.common.util.CodecSpecificDataUtil.parseAlacAudioSpecificConfig
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.binarySearchCeil
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.getFloatPcmEncoding
import androidx.media3.common.util.Util.getPcmEncoding
import androidx.media3.common.util.Util.getPcmFrameSize
import androidx.media3.common.util.Util.getUtf8Bytes
import androidx.media3.common.util.Util.scaleLargeTimestamp
import androidx.media3.common.util.Util.scaleLargeTimestampsInPlace
import androidx.media3.common.util.Util.scaleLargeValue
import androidx.media3.container.DolbyVisionConfig
import androidx.media3.container.Mp4AlternateGroupData
import androidx.media3.container.Mp4Box
import androidx.media3.container.Mp4Box.ContainerBox
import androidx.media3.container.Mp4Box.LeafBox
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.container.NalUnitUtil.H265VpsData
import androidx.media3.container.OpusUtil.buildInitializationData
import androidx.media3.extractor.AacUtil.parseAudioSpecificConfig
import androidx.media3.extractor.Ac3Util.parseAc3AnnexFFormat
import androidx.media3.extractor.Ac3Util.parseEAc3AnnexFFormat
import androidx.media3.extractor.Ac4Util.parseAc4AnnexEFormat
import androidx.media3.extractor.Av1Config
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.ExtractorUtil.checkContainerInput
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.HevcConfig
import androidx.media3.extractor.VorbisUtil.parseVorbisCsdFromEsdsInitializationData
import androidx.media3.extractor.VvcConfig
import androidx.media3.extractor.metadata.Chapter
import com.google.common.base.Function
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.primitives.ImmutableLongArray
import com.google.common.primitives.Ints
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Collections.addAll
import kotlin.math.max

object BoxParser {
    private const val TAG = "BoxParsers"

    private const val TYPE_clcp = 0x636c6370

    private const val TYPE_mdta = 0x6d647461

    private const val TYPE_meta = 0x6d657461

    private const val TYPE_nclc = 0x6e636c63

    private const val TYPE_nclx = 0x6e636c78

    private const val TYPE_sbtl = 0x7362746c

    private const val TYPE_soun = 0x736f756e

    private const val TYPE_subt = 0x73756274

    private const val TYPE_subp = 0x73756270

    private const val TYPE_text = 0x74657874

    private const val TYPE_vide = 0x76696465

    private const val SAMPLE_RATE_AMR_NB = 8000
    private const val SAMPLE_RATE_AMR_WB = 16000

    private const val MAX_GAPLESS_TRIM_SIZE_SAMPLES = 4

    private const val EDIT_LIST_DURATION_TOLERANCE_TIMESCALE_UNITS = 2

    private val opusMagic = getUtf8Bytes("OpusHead")

    fun parseFullBoxVersion(fullBoxInt: Int): Int {
        return 0x000000FF and (fullBoxInt shr 24)
    }

    fun parseFullBoxFlags(fullBoxInt: Int): Int {
        return 0x00FFFFFF and fullBoxInt
    }

    @Throws(ParserException::class)
    fun parseTraks(
        moov: ContainerBox,
        gaplessInfoHolder: GaplessInfoHolder,
        duration: Long,
        drmInitData: DrmInitData?,
        ignoreEditLists: Boolean,
        isQuickTime: Boolean,
        modifyTrackFunction: Function<Track?, Track?>,
        omitTrackSampleTable: Boolean
    ): MutableList<TrackSampleTable> {
        val trackSampleTables: MutableList<TrackSampleTable> = ArrayList<TrackSampleTable>()
        for (i in moov.containerChildren.indices) {
            val atom = moov.containerChildren[i]
            if (atom.type != Mp4Box.TYPE_trak) {
                continue
            }
            val track =
                modifyTrackFunction.apply(
                    parseTrak(
                        atom,moov.getLeafBoxOfType(Mp4Box.TYPE_mvhd)!!,
                        duration,
                        drmInitData,
                        ignoreEditLists,
                        isQuickTime
                    )
                )
            if (track == null) {
                continue
            }
            val stblAtom =atom.getContainerBoxOfType(Mp4Box.TYPE_mdia)!!
                .getContainerBoxOfType(Mp4Box.TYPE_minf)!!
                .getContainerBoxOfType(Mp4Box.TYPE_stbl)!!
            val trackSampleTable =
                BoxParser.parseStbl(track, stblAtom, gaplessInfoHolder, omitTrackSampleTable)
            trackSampleTables.add(trackSampleTable)
        }
        return trackSampleTables
    }

    fun parseUdta(udtaBox: LeafBox, ignoreArtwork: Boolean): Metadata {
        val udtaData = udtaBox.data
        udtaData!!.setPosition(Mp4Box.HEADER_SIZE)
        var metadata: Metadata = Metadata(listOf())
        while (udtaData.bytesLeft() >= Mp4Box.HEADER_SIZE) {
            val atomPosition = udtaData.getPosition()
            val atomSize = udtaData.readInt()
            val atomType = udtaData.readInt()
            if (atomType == Mp4Box.TYPE_meta) {
                udtaData.setPosition(atomPosition)
                metadata =
                    metadata.copyWithAppendedEntriesFrom(
                        BoxParser.parseUdtaMeta(udtaData, atomPosition + atomSize, ignoreArtwork)
                    )
            } else if (atomType == Mp4Box.TYPE_smta) {
                udtaData.setPosition(atomPosition)
                metadata =
                    metadata.copyWithAppendedEntriesFrom(
                        SmtaAtomUtil.parseSmta(udtaData, atomPosition + atomSize)
                    )
            } else if (atomType == Mp4Box.TYPE_xyz) {
                metadata = metadata.copyWithAppendedEntriesFrom(BoxParser.parseXyz(udtaData))
            } else if (atomType == Mp4Box.TYPE_chpl) {
                metadata = metadata.copyWithAppendedEntriesFrom(BoxParser.parseChpl(udtaData))
            }
            udtaData.setPosition(atomPosition + atomSize)
        }
        return metadata
    }

    fun parseMvhd(mvhd: ParsableByteArray): Mp4TimestampData {
        mvhd.setPosition(Mp4Box.HEADER_SIZE)
        val fullAtom = mvhd.readInt()
        val version = parseFullBoxVersion(fullAtom)
        val creationTimestampSeconds: Long
        val modificationTimestampSeconds: Long
        if (version == 0) {
            creationTimestampSeconds = mvhd.readUnsignedInt()
            modificationTimestampSeconds = mvhd.readUnsignedInt()
        } else {
            creationTimestampSeconds = mvhd.readLong()
            modificationTimestampSeconds = mvhd.readLong()
        }

        val timescale = mvhd.readUnsignedInt()
        return Mp4TimestampData(creationTimestampSeconds, modificationTimestampSeconds, timescale)
    }

    fun parseMdtaFromMeta(meta: ContainerBox): Metadata? {
        val hdlrAtom = meta.getLeafBoxOfType(Mp4Box.TYPE_hdlr)
        val keysAtom = meta.getLeafBoxOfType(Mp4Box.TYPE_keys)
        val ilstAtom = meta.getLeafBoxOfType(Mp4Box.TYPE_ilst)
        if (hdlrAtom == null || keysAtom == null || ilstAtom == null || BoxParser.parseHdlr(hdlrAtom.data!!) != TYPE_mdta) {
            // There isn't enough information to parse the metadata, or the handler type is unexpected.
            return null
        }

        // Parse metadata keys.
        val keys = keysAtom.data
        keys!!.setPosition(Mp4Box.FULL_HEADER_SIZE)
        val entryCount = keys.readInt()
        val keyNames = arrayOfNulls<String>(entryCount)
        for (i in 0..<entryCount) {
            val entrySize = keys.readInt()
            keys.skipBytes(4) // keyNamespace
            val keySize = entrySize - 8
            keyNames[i] = keys.readString(keySize)
        }

        // Parse metadata items.
        val ilst = ilstAtom.data
        ilst!!.setPosition(Mp4Box.HEADER_SIZE)
        val entries = ArrayList<Metadata.Entry>()
        while (ilst.bytesLeft() > Mp4Box.HEADER_SIZE) {
            val atomPosition = ilst.getPosition()
            val atomSize = ilst.readInt()
            val keyIndex = ilst.readInt() - 1
            if (keyIndex >= 0 && keyIndex < keyNames.size) {
                val key = keyNames[keyIndex]
                val entry: Metadata.Entry? =
                    MetadataUtil.parseMdtaMetadataEntryFromIlst(ilst, atomPosition + atomSize, key!!)
                if (entry != null) {
                    entries.add(entry)
                }
            } else {
                w(TAG, "Skipped metadata with unknown key index: " + keyIndex)
            }
            ilst.setPosition(atomPosition + atomSize)
        }
        return if (entries.isEmpty()) null else Metadata(entries)
    }

    fun maybeSkipRemainingMetaBoxHeaderBytes(meta: ParsableByteArray) {
        var endPosition = meta.getPosition()
        // The next 8 bytes can be either:
        // (iso) [1 byte version + 3 bytes flags][4 byte size of next atom]
        // (qt)  [4 byte size of next atom      ][4 byte hdlr atom type   ]
        // In case of (iso) we need to skip the next 4 bytes.
        meta.skipBytes(4)
        if (meta.readInt() != Mp4Box.TYPE_hdlr) {
            endPosition += 4
        }
        meta.setPosition(endPosition)
    }

    @Throws(ParserException::class)
    fun parseTrak(
        trak: ContainerBox,
        mvhd: LeafBox,
        duration: Long,
        drmInitData: DrmInitData?,
        ignoreEditLists: Boolean,
        isQuickTime: Boolean
    ): Track? {
        var duration = duration
        val mdia =trak.getContainerBoxOfType(Mp4Box.TYPE_mdia)!!
        val trackType: @C.TrackType Int =
            getTrackTypeForHdlr(
                BoxParser.parseHdlr(mdia.getLeafBoxOfType(
                    Mp4Box.TYPE_hdlr
                )!!.data!!
                )
            )
        if (trackType == C.TRACK_TYPE_UNKNOWN) {
            return null
        }

        val tkhdData =
            BoxParser.parseTkhd(trak.getLeafBoxOfType(Mp4Box.TYPE_tkhd)!!.data!!)
        if (duration == C.TIME_UNSET) {
            duration = tkhdData.duration
        }
        val movieTimescale = BoxParser.parseMvhd(mvhd.data!!).timescale
        val durationUs: Long
        if (duration == C.TIME_UNSET) {
            durationUs = C.TIME_UNSET
        } else {
            durationUs = scaleLargeTimestamp(duration, C.MICROS_PER_SECOND, movieTimescale)
        }
        val stbl =
            mdia.getContainerBoxOfType(Mp4Box.TYPE_minf)!!
                .getContainerBoxOfType(Mp4Box.TYPE_stbl)!!
        val mdhdData =
            BoxParser.parseMdhd(mdia.getLeafBoxOfType(Mp4Box.TYPE_mdhd)!!.data!!)
        val stsd = stbl.getLeafBoxOfType(Mp4Box.TYPE_stsd)
        if (stsd == null) {
            w(
                TAG,
                "Ignoring track where sample table (stbl) box is missing a sample description (stsd)."
            )
            return null
        }
        val stsdData =
            BoxParser.parseStsd(stsd.data!!, tkhdData, mdhdData.language, drmInitData, isQuickTime)

        var chapterTrackId = C.INDEX_UNSET
        val tref = trak.getContainerBoxOfType(Mp4Box.TYPE_tref)
        if (tref != null) {
            val chap = tref.getLeafBoxOfType(Mp4Box.TYPE_chap)
            if (chap != null) {
                val chapData = chap.data
                chapData!!.setPosition(Mp4Box.HEADER_SIZE)
                if (chapData.bytesLeft() >= 4) {
                    chapterTrackId = chapData.readInt()
                }
            }
        }

        var editListDurations: ImmutableLongArray? = null
        var editListMediaTimes: ImmutableLongArray? = null
        if (!ignoreEditLists) {
            val edtsAtom = trak.getContainerBoxOfType(Mp4Box.TYPE_edts)
            if (edtsAtom != null) {
                val edtsData = parseEdts(edtsAtom)
                if (edtsData != null) {
                    editListDurations = edtsData.first
                    editListMediaTimes = edtsData.second
                }
            }
        }
        if (stsdData.format == null) {
            return null
        }
        val format: Format?
        if (tkhdData.alternateGroup != 0) {
            val alternateGroupEntry =
                Mp4AlternateGroupData(tkhdData.alternateGroup)
            format =
                stsdData
                    .format!!
                    .buildUpon()
                    .setMetadata(
                        if (stsdData.format!!.metadata != null)
                            stsdData.format!!.metadata!!.copyWithAppendedEntries(listOf(alternateGroupEntry))
                        else
                            Metadata(listOf(alternateGroupEntry))
                    )
                    .build()
        } else {
            format = stsdData.format
        }
        val shouldBeExposed = format!!.sampleMimeType != MimeTypes.TEXT_UNKNOWN
        return Track.Builder()
            .setId(tkhdData.id)
            .setType(trackType)
            .setTimescale(mdhdData.timescale)
            .setMovieTimescale(movieTimescale)
            .setDurationUs(durationUs)
            .setMediaDurationUs(mdhdData.mediaDurationUs)
            .setFormat(format)
            .setSampleTransformation(stsdData.requiredSampleTransformation)
            .setSampleDescriptionEncryptionBoxes(stsdData.trackEncryptionBoxes)
            .setNalUnitLengthFieldLength(stsdData.nalUnitLengthFieldLength)
            .setEditListDurations(editListDurations)
            .setEditListMediaTimes(editListMediaTimes)
            .setShouldBeExposed(shouldBeExposed)
            .setChapterTrackId(chapterTrackId)
            .build()
    }

    @Throws(ParserException::class)
    fun parseStbl(
        track: Track,
        stblBox: ContainerBox,
        gaplessInfoHolder: GaplessInfoHolder,
        omitTrackSampleTable: Boolean
    ): TrackSampleTable {
        var track = track
        val sampleSizeBox: SampleSizeBox?
        val stszAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stsz)
        if (stszAtom != null) {
            sampleSizeBox = StszSampleSizeBox(stszAtom, track.format!!)
        } else {
            val stz2Atom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stz2)
            if (stz2Atom == null) {
                throw ParserException.createForMalformedContainer(
                    "Track has no sample table size information",  null
                )
            }
            sampleSizeBox = Stz2SampleSizeBox(stz2Atom)
        }

        var sampleCount = sampleSizeBox.sampleCount
        if (sampleCount == 0) {
            return TrackSampleTable(
                track,  
                LongArray(0),  
                IntArray(0),  
                0,  
                LongArray(0),  
                IntArray(0),  
                IntArray(0),  
                false,  
                0,  
                0
            )
        }

        if (track.type == C.TRACK_TYPE_VIDEO && track.mediaDurationUs > 0) {
            val frameRate = sampleCount / (track.mediaDurationUs / 1000000f)
            val format = track.format!!.buildUpon().setFrameRate(frameRate).build()
            track = track.buildUpon().setFormat(format).build()
        }

        // Entries are byte offsets of chunks.
        var chunkOffsetsAreLongs = false
        var chunkOffsetsAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stco)
        if (chunkOffsetsAtom == null) {
            chunkOffsetsAreLongs = true
            chunkOffsetsAtom =stblBox.getLeafBoxOfType(Mp4Box.TYPE_co64)!!
        }
        val chunkOffsets = chunkOffsetsAtom.data
        // Entries are (chunk number, number of samples per chunk, sample description index).
        val stsc =stblBox.getLeafBoxOfType(Mp4Box.TYPE_stsc)!!.data
        // Entries are (number of samples, timestamp delta between those samples).
        val stts =stblBox.getLeafBoxOfType(Mp4Box.TYPE_stts)!!.data
        // Entries are the indices of samples that are synchronization samples.
        val stssAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stss)
        var stss = if (stssAtom != null) stssAtom.data else null
        // Entries are (number of samples, timestamp offset).
        val cttsAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_ctts)
        val ctts = if (cttsAtom != null) cttsAtom.data else null

        // Prepare to read chunk information.
        val chunkIterator = ChunkIterator(stsc!!, chunkOffsets!!, chunkOffsetsAreLongs)

        // Prepare to read sample timestamps.
        stts!!.setPosition(Mp4Box.FULL_HEADER_SIZE)
        var remainingTimestampDeltaChanges = stts.readUnsignedIntToInt() - 1
        var remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt()
        var timestampDeltaInTimeUnits = stts.readUnsignedIntToInt()

        // Prepare to read sample timestamp offsets, if ctts is present.
        var remainingSamplesAtTimestampOffset = 0
        var remainingTimestampOffsetChanges = 0
        var timestampOffset = 0
        if (ctts != null) {
            ctts.setPosition(Mp4Box.FULL_HEADER_SIZE)
            remainingTimestampOffsetChanges = ctts.readUnsignedIntToInt()
        }

        var nextSynchronizationSampleIndex = C.INDEX_UNSET
        var remainingSynchronizationSamples = 0
        if (stss != null) {
            stss.setPosition(Mp4Box.FULL_HEADER_SIZE)
            remainingSynchronizationSamples = stss.readUnsignedIntToInt()
            if (remainingSynchronizationSamples > 0) {
                nextSynchronizationSampleIndex = stss.readUnsignedIntToInt() - 1
            } else {
                // Ignore empty stss boxes, which causes all samples to be treated as sync samples.
                stss = null
            }
        }

        // Fixed sample size raw audio may need to be rechunked.
        val fixedSampleSize = sampleSizeBox.fixedSampleSize
        val sampleMimeType = track.format!!.sampleMimeType
        val rechunkFixedSizeSamples =
            fixedSampleSize != C.LENGTH_UNSET && (MimeTypes.AUDIO_RAW == sampleMimeType
                    || MimeTypes.AUDIO_MLAW == sampleMimeType
                    || MimeTypes.AUDIO_ALAW == sampleMimeType)
                    && remainingTimestampDeltaChanges == 0 && remainingTimestampOffsetChanges == 0 && remainingSynchronizationSamples == 0

        var offsets: LongArray
        var sizes: IntArray
        var maximumSize = 0
        var timestamps: LongArray
        var flags: IntArray
        val syncSampleIndicesList: MutableList<Int?> = ArrayList<Int?>()
        val hasOnlySyncSamples = stss == null
        var timestampTimeUnits: Long = 0
        val duration: Long
        var totalSize: Long = 0

        if (rechunkFixedSizeSamples) {
            val chunkOffsetsBytes = LongArray(chunkIterator.length)
            val chunkSampleCounts = IntArray(chunkIterator.length)
            while (chunkIterator.moveNext()) {
                chunkOffsetsBytes[chunkIterator.index] = chunkIterator.offset
                chunkSampleCounts[chunkIterator.index] = chunkIterator.numSamples
            }
            val rechunkedResults =
                FixedSampleSizeRechunker.rechunk(
                    fixedSampleSize,
                    chunkOffsetsBytes,
                    chunkSampleCounts,
                    timestampDeltaInTimeUnits.toLong()
                )
            offsets = if (omitTrackSampleTable) LongArray(0) else rechunkedResults.offsets
            sizes = if (omitTrackSampleTable) IntArray(0) else rechunkedResults.sizes
            timestamps = if (omitTrackSampleTable) LongArray(0) else rechunkedResults.timestamps
            flags = if (omitTrackSampleTable) IntArray(0) else rechunkedResults.flags
            maximumSize = rechunkedResults.maximumSize
            duration = rechunkedResults.duration
            totalSize = rechunkedResults.totalSize
            sampleCount = rechunkedResults.offsets.size
        } else {
            offsets = if (omitTrackSampleTable) LongArray(0) else LongArray(sampleCount)
            sizes = if (omitTrackSampleTable) IntArray(0) else IntArray(sampleCount)
            timestamps = if (omitTrackSampleTable) LongArray(0) else LongArray(sampleCount)
            flags = if (omitTrackSampleTable) IntArray(0) else IntArray(sampleCount)
            var offset: Long = 0
            var remainingSamplesInChunk = 0

            var i = 0
            while (i < sampleCount) {
                // Advance to the next chunk if necessary.
                var chunkDataComplete = true
                while (remainingSamplesInChunk == 0 && (chunkIterator.moveNext()
                        .also { chunkDataComplete = it })
                ) {
                    offset = chunkIterator.offset
                    remainingSamplesInChunk = chunkIterator.numSamples
                }
                if (!chunkDataComplete) {
                    w(TAG, "Unexpected end of chunk data")
                    sampleCount = i
                    if (!omitTrackSampleTable) {
                        offsets = offsets!!.copyOf(sampleCount)
                        sizes = sizes.copyOf(sampleCount)
                        timestamps = timestamps.copyOf(sampleCount)
                        flags = flags.copyOf(sampleCount)
                    }
                    break
                }

                // Add on the timestamp offset if ctts is present.
                if (ctts != null) {
                    while (remainingSamplesAtTimestampOffset == 0 && remainingTimestampOffsetChanges > 0) {
                        remainingSamplesAtTimestampOffset = ctts.readUnsignedIntToInt()
                        // The BMFF spec (ISO/IEC 14496-12) states that sample offsets should be unsigned
                        // integers in version 0 ctts boxes, however some streams violate the spec and use
                        // signed integers instead. It's safe to always decode sample offsets as signed integers
                        // here, because unsigned integers will still be parsed correctly (unless their top bit
                        // is set, which is never true in practice because sample offsets are always small).
                        timestampOffset = ctts.readInt()
                        remainingTimestampOffsetChanges--
                    }
                    remainingSamplesAtTimestampOffset--
                }

                val currentSampleSize = sampleSizeBox.readNextSampleSize()
                totalSize += currentSampleSize.toLong()
                if (currentSampleSize > maximumSize) {
                    maximumSize = currentSampleSize
                }

                if (!omitTrackSampleTable) {
                    offsets!![i] = offset
                    sizes[i] = currentSampleSize
                    timestamps[i] = timestampTimeUnits + timestampOffset
                    // All samples are synchronization samples if the stss is not present.
                    flags[i] = if (stss == null) C.BUFFER_FLAG_KEY_FRAME else 0
                    if (i == nextSynchronizationSampleIndex) {
                        flags[i] = C.BUFFER_FLAG_KEY_FRAME
                        syncSampleIndicesList.add(i)
                    }
                }

                if (stss != null && i == nextSynchronizationSampleIndex) {
                    remainingSynchronizationSamples--
                    if (remainingSynchronizationSamples > 0) {
                        nextSynchronizationSampleIndex =stss!! .readUnsignedIntToInt() - 1
                    }
                }

                // Add on the duration of this sample.
                timestampTimeUnits += timestampDeltaInTimeUnits.toLong()
                remainingSamplesAtTimestampDelta--
                if (remainingSamplesAtTimestampDelta == 0 && remainingTimestampDeltaChanges > 0) {
                    remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt()
                    // The BMFF spec (ISO/IEC 14496-12) states that sample deltas should be unsigned integers
                    // in stts boxes, however some streams violate the spec and use signed integers instead.
                    // See https://github.com/google/ExoPlayer/issues/3384. It's safe to always decode sample
                    // deltas as signed integers here, because unsigned integers will still be parsed
                    // correctly (unless their top bit is set, which is never true in practice because sample
                    // deltas are always small).
                    timestampDeltaInTimeUnits = stts.readInt()
                    remainingTimestampDeltaChanges--
                }

                offset += currentSampleSize.toLong()
                remainingSamplesInChunk--
                i++
            }
            duration = timestampTimeUnits + timestampOffset

            // If the stbl's child boxes are not consistent the container is malformed, but the stream may
            // still be playable.
            var isCttsValid = true
            if (ctts != null) {
                while (remainingTimestampOffsetChanges > 0) {
                    if (ctts.readUnsignedIntToInt() != 0) {
                        isCttsValid = false
                        break
                    }
                    ctts.readInt() // Ignore offset.
                    remainingTimestampOffsetChanges--
                }
            }
            if (remainingSynchronizationSamples != 0 || remainingSamplesAtTimestampDelta != 0 || remainingSamplesInChunk != 0 || remainingTimestampDeltaChanges != 0 || remainingSamplesAtTimestampOffset != 0 || !isCttsValid) {
                w(
                    TAG,
                    ("Inconsistent stbl box for track "
                            + track.id
                            + ": remainingSynchronizationSamples "
                            + remainingSynchronizationSamples
                            + ", remainingSamplesAtTimestampDelta "
                            + remainingSamplesAtTimestampDelta
                            + ", remainingSamplesInChunk "
                            + remainingSamplesInChunk
                            + ", remainingTimestampDeltaChanges "
                            + remainingTimestampDeltaChanges
                            + ", remainingSamplesAtTimestampOffset "
                            + remainingSamplesAtTimestampOffset
                            + (if (!isCttsValid) ", ctts invalid" else ""))
                )
            }
        }

        if (track.mediaDurationUs > 0) {
            val averageBitrate =
                scaleLargeValue(
                    totalSize * C.BITS_PER_BYTE,
                    C.MICROS_PER_SECOND,
                    track.mediaDurationUs,
                    RoundingMode.HALF_DOWN
                )
            if (averageBitrate > 0 && averageBitrate < Int.MAX_VALUE) {
                val format =
                    track.format.buildUpon().setAverageBitrate(averageBitrate.toInt()).build()
                track = track.buildUpon().setFormat(format).build()
            }
        }

        var durationUs = scaleLargeTimestamp(duration, C.MICROS_PER_SECOND, track.timescale)
        val syncSampleIndices = Ints.toArray(syncSampleIndicesList)

        if (track.editListDurations == null) {
            if (!omitTrackSampleTable) {
                scaleLargeTimestampsInPlace(timestamps, C.MICROS_PER_SECOND, track.timescale)
            }
            return TrackSampleTable(
                track,
                offsets,
                sizes,
                maximumSize,
                timestamps,
                flags,
                syncSampleIndices,
                hasOnlySyncSamples,
                durationUs,
                sampleCount
            )
        }

        if (omitTrackSampleTable) {
            val editedDurationUs: Long
            val editListMediaTimes =track.editListMediaTimes!!
            if (track.editListDurations.length() == 1 && track.editListDurations.get(0) == 0L) {
                val editStartTime = editListMediaTimes.get(0)
                editedDurationUs =
                    scaleLargeTimestamp(
                        duration - editStartTime, C.MICROS_PER_SECOND, track.timescale
                    )
            } else {
                var pts: Long = 0
                for (i in 0..<track.editListDurations.length()) {
                    if (editListMediaTimes[i] != -1L) {
                        pts += track.editListDurations[i]
                    }
                }
                editedDurationUs =
                    scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale)
            }
            return TrackSampleTable(
                track,
                offsets,
                sizes,
                maximumSize,
                timestamps,
                flags,
                syncSampleIndices,
                hasOnlySyncSamples,
                editedDurationUs,
                sampleCount
            )
        }

        // See the BMFF spec (ISO/IEC 14496-12) subsection 8.6.6. Edit lists that require prerolling
        // from a sync sample after reordering are not supported. Partial audio sample truncation is
        // only supported in edit lists with one edit that removes less than
        // MAX_GAPLESS_TRIM_SIZE_SAMPLES samples from the start/end of the track. This implementation
        // handles simple discarding/delaying of samples. The extractor may place further restrictions
        // on what edited streams are playable.
        if (track.editListDurations.length() == 1 && track.type == C.TRACK_TYPE_AUDIO && timestamps.size >= 2) {
            val editStartTime =track.editListMediaTimes!!.get(0)
            val editEndTime =
                (editStartTime
                        + scaleLargeTimestamp(
                    track.editListDurations.get(0), track.timescale, track.movieTimescale
                ))
            if (canApplyEditWithGaplessInfo(timestamps, duration, editStartTime, editEndTime)) {
                // Clamp padding to 0 to account for rounding errors where editEndTime is slightly
                // greater than duration.
                val paddingTimeUnits = max(0, duration - editEndTime)
                val encoderDelay =
                    scaleLargeTimestamp(
                        editStartTime - timestamps[0],
                        track.format!!.sampleRate.toLong(),
                        track.timescale
                    )
                val encoderPadding =
                    scaleLargeTimestamp(
                        paddingTimeUnits,
                        track.format.sampleRate.toLong(),
                        track.timescale
                    )
                if ((encoderDelay != 0L || encoderPadding != 0L)
                    && encoderDelay <= Int.MAX_VALUE && encoderPadding <= Int.MAX_VALUE
                ) {
                    gaplessInfoHolder.encoderDelay = encoderDelay.toInt()
                    gaplessInfoHolder.encoderPadding = encoderPadding.toInt()
                    scaleLargeTimestampsInPlace(timestamps, C.MICROS_PER_SECOND, track.timescale)
                    val editedDurationUs =
                        scaleLargeTimestamp(
                            track.editListDurations.get(0),
                            C.MICROS_PER_SECOND,
                            track.movieTimescale
                        )
                    return TrackSampleTable(
                        track,
                        offsets,
                        sizes,
                        maximumSize,
                        timestamps,
                        flags,
                        syncSampleIndices,
                        hasOnlySyncSamples,
                        editedDurationUs,
                        sampleCount
                    )
                }
            }
        }

        if (track.editListDurations.length() == 1 && track.editListDurations.get(0) == 0L) {
            // The current version of the spec leaves handling of an edit with zero segment_duration in
            // unfragmented files open to interpretation. We handle this as a special case and include all
            // samples in the edit.
            val editStartTime =track.editListMediaTimes!!.get(0)
            for (i in timestamps.indices) {
                timestamps[i] =
                    scaleLargeTimestamp(
                        timestamps[i] - editStartTime, C.MICROS_PER_SECOND, track.timescale
                    )
            }
            durationUs =
                scaleLargeTimestamp(duration - editStartTime, C.MICROS_PER_SECOND, track.timescale)
            return TrackSampleTable(
                track,
                offsets,
                sizes,
                maximumSize,
                timestamps,
                flags,
                syncSampleIndices,
                hasOnlySyncSamples,
                durationUs,
                sampleCount
            )
        }

        // When applying edit lists, we need to include any partial clipped samples at the end to ensure
        // the final output is rendered correctly (see https://github.com/google/ExoPlayer/issues/2408).
        // For audio only, we can omit any sample that starts at exactly the end point of an edit as
        // there is no partial audio in this case.
        val omitZeroDurationClippedSample = track.type == C.TRACK_TYPE_AUDIO

        // Count the number of samples after applying edits.
        var editedSampleCount = 0
        var nextSampleIndex = 0
        var copyMetadata = false
        val startIndices = IntArray(track.editListDurations.length())
        val endIndices = IntArray(track.editListDurations.length())
        val editListMediaTimes =track.editListMediaTimes!!
        for (i in 0..<track.editListDurations.length()) {
            val editMediaTime = editListMediaTimes[i]
            if (editMediaTime != -1L) {
                val editDuration =
                    scaleLargeTimestamp(
                        track.editListDurations[i], track.timescale, track.movieTimescale
                    )
                val editEndTime = editMediaTime + editDuration

                // The timestamps array is in the order read from the media, which might not be strictly
                // sorted. However, all sync frames are guaranteed to be in order. The logic below
                // searches for the true start and end of the edit, accounting for out-of-order frames.

                // The startIndices calculation finds the sample at or just before the edit start time.
                // It then walks backward to ensure the index points to a sync frame, since
                // decoding must start from a keyframe.
                startIndices[i] =
                    binarySearchFloor(
                        timestamps, editMediaTime,  true,  true
                    )

                // The endIndices calculation finds the true end of the edit by searching past the
                // naive end point for any out-of-order frames that belong in the clip.
                val firstSampleAfterEdit =
                    binarySearchCeil(
                        timestamps,
                        editEndTime,  
                        omitZeroDurationClippedSample,  
                        false
                    )

                // To account for out-of-order frames, we use a search that continues until we have seen
                // more out-of-boundary frames than the reorder limit (maxNumReorderSamples), which
                // guarantees no more valid frames will be found.
                var samplesSeenAfterEnd = 0
                var maxValidIndexInWindow = firstSampleAfterEdit - 1
                for (j in firstSampleAfterEdit..<timestamps.size) {
                    if (timestamps[j] < editEndTime) {
                        // This is an out-of-order frame that belongs in the edit. Update our max index.
                        maxValidIndexInWindow = j
                    } else {
                        // This frame is outside the edit. Increment our counter of seen "post-roll" frames.
                        samplesSeenAfterEnd++
                        if (samplesSeenAfterEnd > track.format!!.maxNumReorderSamples) {
                            // We've exhausted our search budget. We can be sure no more valid frames will appear.
                            break
                        }
                    }
                }
                endIndices[i] = maxValidIndexInWindow + 1

                // Ensure we start decoding from a sync frame by searching backwards.
                val initialStartIndex = startIndices[i]
                while (startIndices[i] > 0 && (flags[startIndices[i]] and C.BUFFER_FLAG_KEY_FRAME) == 0) {
                    startIndices[i]--
                }

                // If we searched all the way back and didn't find a sync frame, search forward from the
                // original start.
                if (startIndices[i] == 0 && (flags[0] and C.BUFFER_FLAG_KEY_FRAME) == 0) {
                    startIndices[i] = initialStartIndex
                    while (startIndices[i] < endIndices[i]
                        && (flags[startIndices[i]] and C.BUFFER_FLAG_KEY_FRAME) == 0
                    ) {
                        startIndices[i]++
                    }
                }

                editedSampleCount += endIndices[i] - startIndices[i]
                copyMetadata = copyMetadata or (nextSampleIndex != startIndices[i])
                nextSampleIndex = endIndices[i]
            }
        }
        copyMetadata = copyMetadata or (editedSampleCount != sampleCount)

        // Calculate edited sample timestamps and update the corresponding metadata arrays.
        val editedOffsets: LongArray =
            (if (copyMetadata) kotlin.LongArray(editedSampleCount) else offsets)!!
        val editedSizes = if (copyMetadata) IntArray(editedSampleCount) else sizes
        var editedMaximumSize = if (copyMetadata) 0 else maximumSize
        val editedFlags = if (copyMetadata) IntArray(editedSampleCount) else flags
        val editedSyncSampleIndicesList =
            if (copyMetadata) ArrayList<Int?>() else syncSampleIndicesList
        val editedTimestamps = LongArray(editedSampleCount)
        var pts: Long = 0
        var sampleIndex = 0
        var hasPrerollSamples = false
        for (i in 0..<track.editListDurations.length()) {
            val editMediaTime = track.editListMediaTimes!![i]
            val startIndex = startIndices[i]
            val endIndex = endIndices[i]
            if (copyMetadata) {
                val count = endIndex - startIndex
                System.arraycopy(offsets, startIndex, editedOffsets, sampleIndex, count)
                System.arraycopy(sizes, startIndex, editedSizes, sampleIndex, count)
                System.arraycopy(flags, startIndex, editedFlags, sampleIndex, count)
            }
            for (j in startIndex..<endIndex) {
                val ptsUs = scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale)
                val timeInSegmentUs =
                    scaleLargeTimestamp(
                        timestamps[j] - editMediaTime, C.MICROS_PER_SECOND, track.timescale
                    )
                if (timeInSegmentUs < 0) {
                    hasPrerollSamples = true
                }
                editedTimestamps[sampleIndex] = ptsUs + timeInSegmentUs
                if (copyMetadata && editedSizes[sampleIndex] > editedMaximumSize) {
                    editedMaximumSize = sizes[j]
                }
                if (copyMetadata
                    && !hasOnlySyncSamples && (editedFlags[sampleIndex] and C.BUFFER_FLAG_KEY_FRAME) != 0
                ) {
                    editedSyncSampleIndicesList.add(sampleIndex)
                }
                sampleIndex++
            }
            pts += track.editListDurations[i]
        }
        val editedDurationUs =
            scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale)
        if (hasPrerollSamples) {
            val format = track.format!!.buildUpon().setHasPrerollSamples(true).build()
            track = track.buildUpon().setFormat(format).build()
        }
        return TrackSampleTable(
            track,
            editedOffsets,
            editedSizes,
            editedMaximumSize,
            editedTimestamps,
            editedFlags,
            Ints.toArray(editedSyncSampleIndicesList),
            hasOnlySyncSamples,
            editedDurationUs,
            editedOffsets.size
        )
    }

    private fun parseUdtaMeta(
        meta: ParsableByteArray,
        limit: Int,
        ignoreArtwork: Boolean
    ): Metadata? {
        meta.skipBytes(Mp4Box.HEADER_SIZE)
        maybeSkipRemainingMetaBoxHeaderBytes(meta)
        while (meta.getPosition() < limit) {
            val atomPosition = meta.getPosition()
            val atomSize = meta.readInt()
            val atomType = meta.readInt()
            if (atomType == Mp4Box.TYPE_ilst) {
                meta.setPosition(atomPosition)
                return parseIlst(meta, atomPosition + atomSize, ignoreArtwork)
            }
            meta.setPosition(atomPosition + atomSize)
        }
        return null
    }

    private fun parseIlst(ilst: ParsableByteArray, limit: Int, ignoreArtwork: Boolean): Metadata? {
        ilst.skipBytes(Mp4Box.HEADER_SIZE)
        val entries = ArrayList<Metadata.Entry>()
        while (ilst.getPosition() < limit) {
            val entry = MetadataUtil.parseIlstElement(ilst, ignoreArtwork)
            if (entry != null) {
                entries.add(entry)
            }
        }
        return if (entries.isEmpty()) null else Metadata(entries)
    }

    private fun parseXyz(xyzBox: ParsableByteArray): Metadata? {
        val length = xyzBox.readShort().toInt()
        xyzBox.skipBytes(2) // language code.
        val location = xyzBox.readString(length)
        // The location string looks like "+35.1345-15.1020/".
        val plusSignIndex = location.lastIndexOf('+')
        val minusSignIndex = location.lastIndexOf('-')
        val latitudeEndIndex = max(plusSignIndex, minusSignIndex)
        try {
            val latitude = location.substring(0, latitudeEndIndex).toFloat()
            val longitude = location.substring(latitudeEndIndex, location.length - 1).toFloat()
            return Metadata(listOf(Mp4LocationData(latitude, longitude)))
        } catch (exception: IndexOutOfBoundsException) {
            // Invalid input.
            return null
        } catch (exception: NumberFormatException) {
            return null
        }
    }

    fun parseChpl(chplData: ParsableByteArray): Metadata? {
        try {
            chplData.skipBytes(5) // 1 byte version + 3 bytes flags + 1 byte reserved.
            val chapterCount = chplData.readInt()
            val chapters: MutableList<Metadata.Entry> = ArrayList<Metadata.Entry>()
            for (i in 0..<chapterCount) {
                var startTimeMs =
                    chplData.readLong() / 10000 // Start time in 100-nanoseconds resolution
                if (startTimeMs < 0) {
                    startTimeMs = C.TIME_UNSET
                }
                val titleLength = chplData.readUnsignedByte()
                val title = chplData.readString(titleLength)
                chapters.add(
                    Chapter.Builder()
                        .setStartTimeMs(startTimeMs)
                        .setTitle(Label(null, title))
                        .build()
                )
            }
            return if (chapters.isEmpty()) null else Metadata(chapters)
        } catch (e: IndexOutOfBoundsException) {
            // Invalid input.
            return null
        }
    }

    private fun parseTkhd(tkhd: ParsableByteArray): TkhdData {
        tkhd.setPosition(Mp4Box.HEADER_SIZE)
        val fullAtom = tkhd.readInt()
        val version = parseFullBoxVersion(fullAtom)

        tkhd.skipBytes(if (version == 0) 8 else 16)
        val trackId = tkhd.readInt()

        tkhd.skipBytes(4)
        var durationUnknown = true
        val durationPosition = tkhd.getPosition()
        val durationByteCount = if (version == 0) 4 else 8
        for (i in 0..<durationByteCount) {
            if (tkhd.data[durationPosition + i].toInt() != -1) {
                durationUnknown = false
                break
            }
        }
        var duration: Long
        if (durationUnknown) {
            tkhd.skipBytes(durationByteCount)
            duration = C.TIME_UNSET
        } else {
            duration = if (version == 0) tkhd.readUnsignedInt() else tkhd.readUnsignedLongToLong()
            if (duration == 0L) {
                // 0 duration normally indicates that the file is fully fragmented (i.e. all of the media
                // samples are in fragments). Treat as unknown.
                duration = C.TIME_UNSET
            }
        }

        tkhd.skipBytes(10)
        val alternateGroup = tkhd.readUnsignedShort()
        tkhd.skipBytes(4)
        val a00 = tkhd.readInt()
        val a01 = tkhd.readInt()
        tkhd.skipBytes(4)
        val a10 = tkhd.readInt()
        val a11 = tkhd.readInt()

        // Matrices which imply reflection are resolved to a correct rotation and a horizontal mirror.
        val rotationDegrees: Int
        val fixedOne = 65536
        if (a00 == 0 && a01 == fixedOne && (a10 == -fixedOne || a10 == fixedOne) && a11 == 0) {
            rotationDegrees = 90
        } else if (a00 == 0 && a01 == -fixedOne && (a10 == fixedOne || a10 == -fixedOne) && a11 == 0) {
            rotationDegrees = 270
        } else if ((a00 == -fixedOne || a00 == fixedOne) && a01 == 0 && a10 == 0 && a11 == -fixedOne) {
            rotationDegrees = 180
        } else {
            // Only 0, 90, 180 and 270 are supported. Treat anything else as 0.
            rotationDegrees = 0
        }
        // skip remaining 4 matrix entries
        tkhd.skipBytes(16)
        // ignore fractional part of width and height
        val width = tkhd.readShort().toInt()
        tkhd.skipBytes(2)
        val height = tkhd.readShort().toInt()

        val determinant = a00.toLong() * a11 - a01.toLong() * a10
        val mirrorHorizontal = determinant < 0

        return TkhdData(
            trackId, duration, alternateGroup, rotationDegrees, mirrorHorizontal, width, height
        )
    }

    private fun parseHdlr(hdlr: ParsableByteArray): Int {
        hdlr.setPosition(Mp4Box.FULL_HEADER_SIZE + 4)
        return hdlr.readInt()
    }

    private fun getTrackTypeForHdlr(hdlr: Int): @C.TrackType Int {
        if (hdlr == TYPE_soun) {
            return C.TRACK_TYPE_AUDIO
        } else if (hdlr == TYPE_vide) {
            return C.TRACK_TYPE_VIDEO
        } else if (hdlr == TYPE_text || hdlr == TYPE_sbtl || hdlr == TYPE_subt || hdlr == TYPE_clcp || hdlr == TYPE_subp) {
            return C.TRACK_TYPE_TEXT
        } else if (hdlr == TYPE_meta) {
            return C.TRACK_TYPE_METADATA
        } else {
            return C.TRACK_TYPE_UNKNOWN
        }
    }

    private fun parseMdhd(mdhd: ParsableByteArray): MdhdData {
        mdhd.setPosition(Mp4Box.HEADER_SIZE)
        val fullAtom = mdhd.readInt()
        val version = parseFullBoxVersion(fullAtom)
        mdhd.skipBytes(if (version == 0) 8 else 16)
        val timescale = mdhd.readUnsignedInt()
        var mediaDurationUnknown = true
        val mediaDurationPosition = mdhd.getPosition()
        val mediaDurationByteCount = if (version == 0) 4 else 8
        for (i in 0..<mediaDurationByteCount) {
            if (mdhd.data[mediaDurationPosition + i].toInt() != -1) {
                mediaDurationUnknown = false
                break
            }
        }
        val mediaDurationUs: Long
        if (mediaDurationUnknown) {
            mdhd.skipBytes(mediaDurationByteCount)
            mediaDurationUs = C.TIME_UNSET
        } else {
            val mediaDuration =
                if (version == 0) mdhd.readUnsignedInt() else mdhd.readUnsignedLongToLong()
            if (mediaDuration == 0L) {
                // 0 duration normally indicates that the file is fully fragmented (i.e. all of the media
                // samples are in fragments). Treat as unknown.
                mediaDurationUs = C.TIME_UNSET
            } else {
                mediaDurationUs = scaleLargeTimestamp(mediaDuration, C.MICROS_PER_SECOND, timescale)
            }
        }

        val language = getLanguageFromCode( mdhd.readUnsignedShort())
        return MdhdData(timescale, mediaDurationUs, language)
    }

    private fun getLanguageFromCode(languageCode: Int): String? {
        val chars = charArrayOf(
            (((languageCode shr 10) and 0x1F) + 0x60).toChar(),
            (((languageCode shr 5) and 0x1F) + 0x60).toChar(),
            ((languageCode and 0x1F) + 0x60).toChar()
        )

        for (c in chars) {
            if (c < 'a' || c > 'z') {
                return null
            }
        }
        return String(chars)
    }

    @Throws(ParserException::class)
    private fun parseStsd(
        stsd: ParsableByteArray,
        tkhdData: TkhdData,
        language: String?,
        drmInitData: DrmInitData?,
        isQuickTime: Boolean
    ): StsdData {
        stsd.setPosition(Mp4Box.FULL_HEADER_SIZE)
        val numberOfEntries = stsd.readInt()
        val out = StsdData(numberOfEntries)
        for (i in 0..<numberOfEntries) {
            val childStartPosition = stsd.getPosition()
            val childAtomSize = stsd.readInt()
            checkContainerInput(childAtomSize > 0, "childAtomSize must be positive")
            val childAtomType = stsd.readInt()
            if (childAtomType == Mp4Box.TYPE_avc1 || childAtomType == Mp4Box.TYPE_avc3 || childAtomType == Mp4Box.TYPE_encv || childAtomType == Mp4Box.TYPE_m1v_ || childAtomType == Mp4Box.TYPE_mp4v || childAtomType == Mp4Box.TYPE_hvc1 || childAtomType == Mp4Box.TYPE_hev1 || childAtomType == Mp4Box.TYPE_vvc1 || childAtomType == Mp4Box.TYPE_vvi1 || childAtomType == Mp4Box.TYPE_s263 || childAtomType == Mp4Box.TYPE_H263 || childAtomType == Mp4Box.TYPE_h263 || childAtomType == Mp4Box.TYPE_vp08 || childAtomType == Mp4Box.TYPE_vp09 || childAtomType == Mp4Box.TYPE_av01 || childAtomType == Mp4Box.TYPE_dvav || childAtomType == Mp4Box.TYPE_dva1 || childAtomType == Mp4Box.TYPE_dvhe || childAtomType == Mp4Box.TYPE_dvh1 || childAtomType == Mp4Box.TYPE_apv1 || childAtomType == Mp4Box.TYPE_dav1) {
                parseVideoSampleEntry(
                    stsd,
                    childAtomType,
                    childStartPosition,
                    childAtomSize,
                    tkhdData,
                    language,
                    drmInitData,
                    out,
                    i
                )
            } else if (childAtomType == Mp4Box.TYPE_mp4a || childAtomType == Mp4Box.TYPE_enca || childAtomType == Mp4Box.TYPE_ac_3 || childAtomType == Mp4Box.TYPE_ec_3 || childAtomType == Mp4Box.TYPE_ac_4 || childAtomType == Mp4Box.TYPE_mlpa || childAtomType == Mp4Box.TYPE_dtsc || childAtomType == Mp4Box.TYPE_dtse || childAtomType == Mp4Box.TYPE_dtsh || childAtomType == Mp4Box.TYPE_dtsl || childAtomType == Mp4Box.TYPE_dtsx || childAtomType == Mp4Box.TYPE_samr || childAtomType == Mp4Box.TYPE_sawb || childAtomType == Mp4Box.TYPE_lpcm || childAtomType == Mp4Box.TYPE_sowt || childAtomType == Mp4Box.TYPE_twos || childAtomType == Mp4Box.TYPE__mp2 || childAtomType == Mp4Box.TYPE__mp3 || childAtomType == Mp4Box.TYPE_mha1 || childAtomType == Mp4Box.TYPE_mhm1 || childAtomType == Mp4Box.TYPE_alac || childAtomType == Mp4Box.TYPE_alaw || childAtomType == Mp4Box.TYPE_ulaw || childAtomType == Mp4Box.TYPE_Opus || childAtomType == Mp4Box.TYPE_fLaC || childAtomType == Mp4Box.TYPE_iamf || childAtomType == Mp4Box.TYPE_ipcm || childAtomType == Mp4Box.TYPE_fpcm
            ) {
                parseAudioSampleEntry(
                    stsd,
                    childAtomType,
                    childStartPosition,
                    childAtomSize,
                    tkhdData.id,
                    language,
                    isQuickTime,
                    drmInitData,
                    out,
                    i
                )
            } else if (childAtomType == Mp4Box.TYPE_TTML || childAtomType == Mp4Box.TYPE_tx3g || childAtomType == Mp4Box.TYPE_wvtt || childAtomType == Mp4Box.TYPE_stpp || childAtomType == Mp4Box.TYPE_c608 || childAtomType == Mp4Box.TYPE_mp4s || childAtomType == TYPE_text) {
                parseTextSampleEntry(
                    stsd, childAtomType, childStartPosition, childAtomSize, tkhdData, language, out
                )
            } else if (childAtomType == Mp4Box.TYPE_mett || childAtomType == Mp4Box.TYPE_it35) {
                parseMetaDataSampleEntry(stsd, childAtomType, childStartPosition, tkhdData.id, out)
            } else if (childAtomType == Mp4Box.TYPE_camm) {
                out.format =
                    Format.Builder()
                        .setId(tkhdData.id)
                        .setSampleMimeType(MimeTypes.APPLICATION_CAMERA_MOTION)
                        .build()
            }
            stsd.setPosition(childStartPosition + childAtomSize)
        }
        return out
    }

    private fun parseTextSampleEntry(
        parent: ParsableByteArray,
        atomType: Int,
        position: Int,
        atomSize: Int,
        tkhdData: TkhdData,
        language: String?,
        out: StsdData
    ) {
        parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE)

        // Default values.
        var initializationData: List<ByteArray>? = null
        var subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE

        var mimeType: String? = null
        if (atomType == Mp4Box.TYPE_TTML) {
            mimeType = MimeTypes.APPLICATION_TTML
        } else if (atomType == Mp4Box.TYPE_tx3g) {
            mimeType = MimeTypes.APPLICATION_TX3G
            val sampleDescriptionLength = atomSize - Mp4Box.HEADER_SIZE - 8
            val sampleDescriptionData = ByteArray(sampleDescriptionLength)
            parent.readBytes(sampleDescriptionData, 0, sampleDescriptionLength)
            initializationData = listOf(sampleDescriptionData)
        } else if (atomType == Mp4Box.TYPE_wvtt) {
            mimeType = MimeTypes.APPLICATION_MP4VTT
        } else if (atomType == Mp4Box.TYPE_stpp) {
            mimeType = MimeTypes.APPLICATION_TTML
            subsampleOffsetUs = 0 // Subsample timing is absolute.
        } else if (atomType == Mp4Box.TYPE_c608) {
            // Defined by the QuickTime File Format specification.
            mimeType = MimeTypes.APPLICATION_MP4CEA608
            out.requiredSampleTransformation = Track.TRANSFORMATION_CEA608_CDAT
        } else if (atomType == Mp4Box.TYPE_mp4s) {
            val pos = parent.getPosition()
            parent.skipBytes(4) // child atom size
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_esds) {
                val esds = parseEsdsFromParent(parent, pos)
                if (esds.initializationData == null || esds.initializationData.size != 64) {
                    return
                }
                mimeType = MimeTypes.APPLICATION_VOBSUB
                val idx = formatVobsubIdx(esds.initializationData, tkhdData.width, tkhdData.height)
                initializationData = listOf<ByteArray>(getUtf8Bytes(idx))
            }
        } else if (atomType == TYPE_text) {
            // The TYPE_text track is a generic fallback text format. We assign a placeholder MIME type so
            // the track is not discarded. This preserves the track's sample table for downstream
            // extractors to use (e.g., for QuickTime chapters).
            mimeType = MimeTypes.TEXT_UNKNOWN
        } else {
            // Never happens.
            throw IllegalStateException()
        }

        if (mimeType != null) {
            out.format =
                Format.Builder()
                    .setId(tkhdData.id)
                    .setSampleMimeType(mimeType)
                    .setLanguage(language)
                    .setSubsampleOffsetUs(subsampleOffsetUs)
                    .setInitializationData(initializationData)
                    .build()
        }
    }

    private fun formatVobsubIdx(src: ByteArray, width: Int, height: Int): String {
        Preconditions.checkState(src.size == 64)
        val palette: MutableList<String?> = ArrayList<String?>(16)
        var i = 0
        while (i < src.size - 3) {
            val yuv = Ints.fromBytes(src[i], src[i + 1], src[i + 2], src[i + 3])
            palette.add(String.format("%06x", vobsubYuvToRgb(yuv)))
            i += 4
        }
        return "size: " + width + "x" + height + "\npalette: " + Joiner.on(", ")
            .join(palette) + "\n"
    }

    private fun vobsubYuvToRgb(yuv: Int): Int {
        val y = (yuv shr 16) and 0xFF
        val v = (yuv shr 8) and 0xFF
        val u = yuv and 0xFF

        val r = y + 14075 * (v - 128) / 10000
        val g = y - 3455 * (u - 128) / 10000 - 7169 * (v - 128) / 10000
        val b = y + 17790 * (u - 128) / 10000

        return ((constrainValue(r, 0, 255) shl 16)
                or (constrainValue(g, 0, 255) shl 8)
                or constrainValue(b, 0, 255))
    }

    // hdrStaticInfo is allocated using allocate() in allocateHdrStaticInfo().
    @Throws(ParserException::class)
    private fun parseVideoSampleEntry(
        parent: ParsableByteArray,
        atomType: Int,
        position: Int,
        size: Int,
        tkhdData: TkhdData,
        language: String?,
        drmInitData: DrmInitData?,
        out: StsdData,
        entryIndex: Int
    ) {
        var atomType = atomType
        var drmInitData = drmInitData
        parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE)

        parent.skipBytes(16)
        val width = parent.readUnsignedShort()
        val height = parent.readUnsignedShort()
        var pixelWidthHeightRatioFromPasp = false
        var pixelWidthHeightRatio = 1f
        // Set default luma and chroma bit depths to 8 as old codecs might not even signal them
        var bitdepthLuma = 8
        var bitdepthChroma = 8
        parent.skipBytes(50)

        var childPosition = parent.getPosition()
        if (atomType == Mp4Box.TYPE_encv) {
            val sampleEntryEncryptionData =
                parseSampleEntryEncryptionData(parent, position, size)
            if (sampleEntryEncryptionData != null) {
                atomType = sampleEntryEncryptionData.first!!
                drmInitData =
                    if (drmInitData == null)
                        null
                    else
                        drmInitData.copyWithSchemeType(sampleEntryEncryptionData.second!!.schemeType)
                out.trackEncryptionBoxes[entryIndex] = sampleEntryEncryptionData.second
            }
            parent.setPosition(childPosition)
        }

        // TODO: Uncomment when [Internal: b/63092960] is fixed.
        // else {
        //   drmInitData = null;
        // }
        var mimeType: String? = null
        if (atomType == Mp4Box.TYPE_m1v_) {
            mimeType = MimeTypes.VIDEO_MPEG
        } else if (atomType == Mp4Box.TYPE_H263) {
            mimeType = MimeTypes.VIDEO_H263
        }

        var initializationData: List<ByteArray>? = null
        var codecs: String? = null
        var projectionData: ByteArray? = null
        var stereoMode: @StereoMode Int = Format.NO_VALUE
        var esdsData: EsdsData? = null
        var btrtData: BtrtData? = null
        var maxNumReorderSamples = Format.NO_VALUE
        var maxSubLayers = Format.NO_VALUE
        var vpsData: H265VpsData? = null
        var decodedWidth = Format.NO_VALUE
        var decodedHeight = Format.NO_VALUE

        // HDR related metadata.
        var colorSpace: @C.ColorSpace Int = Format.NO_VALUE
        var colorRange: @ColorRange Int = Format.NO_VALUE
        var colorTransfer: @ColorTransfer Int = Format.NO_VALUE
        // The format of HDR static info is defined in CTA-861-G:2017, Table 45.
        var hdrStaticInfo: ByteBuffer? = null
        var dolbyVisionConfig: DolbyVisionConfig? = null

        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childStartPosition = parent.getPosition()
            val childAtomSize = parent.readInt()
            if (childAtomSize == 0 && parent.getPosition() - position == size) {
                // Handle optional terminating four zero bytes in MOV files.
                break
            }
            checkContainerInput(childAtomSize > 0, "childAtomSize must be positive")
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_avcC) {
                checkContainerInput(mimeType == null,  null)
                mimeType = MimeTypes.VIDEO_H264
                parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE)
                val avcConfig = AvcConfig.parse(parent)
                initializationData = avcConfig.initializationData
                out.nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength
                if (!pixelWidthHeightRatioFromPasp) {
                    pixelWidthHeightRatio = avcConfig.pixelWidthHeightRatio
                }
                codecs = avcConfig.codecs
                maxNumReorderSamples = avcConfig.maxNumReorderFrames
                colorSpace = avcConfig.colorSpace
                colorRange = avcConfig.colorRange
                colorTransfer = avcConfig.colorTransfer
                bitdepthLuma = avcConfig.bitdepthLuma
                bitdepthChroma = avcConfig.bitdepthChroma
            } else if (childAtomType == Mp4Box.TYPE_hvcC) {
                checkContainerInput(mimeType == null,  null)
                mimeType = MimeTypes.VIDEO_H265
                parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE)
                val hevcConfig = HevcConfig.parse(parent)
                initializationData = hevcConfig.initializationData
                out.nalUnitLengthFieldLength = hevcConfig.nalUnitLengthFieldLength
                if (!pixelWidthHeightRatioFromPasp) {
                    pixelWidthHeightRatio = hevcConfig.pixelWidthHeightRatio
                }
                maxNumReorderSamples = hevcConfig.maxNumReorderPics
                maxSubLayers = hevcConfig.maxSubLayers
                codecs = hevcConfig.codecs
                if (hevcConfig.stereoMode != Format.NO_VALUE) {
                    // HEVCDecoderConfigurationRecord may include 3D reference displays information SEI.
                    stereoMode = hevcConfig.stereoMode
                }
                decodedWidth = hevcConfig.decodedWidth
                decodedHeight = hevcConfig.decodedHeight
                colorSpace = hevcConfig.colorSpace
                colorRange = hevcConfig.colorRange
                colorTransfer = hevcConfig.colorTransfer
                bitdepthLuma = hevcConfig.bitdepthLuma
                bitdepthChroma = hevcConfig.bitdepthChroma
                vpsData = hevcConfig.vpsData
            } else if (childAtomType == Mp4Box.TYPE_lhvC) {
                // The lhvC atom must follow the hvcC atom; so the media type must be already set.
                checkContainerInput(
                    MimeTypes.VIDEO_H265 == mimeType, "lhvC must follow hvcC atom"
                )
                checkContainerInput(
                    vpsData != null && vpsData.layerInfos.size >= 2, "must have at least two layers"
                )

                parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE)
                val lhevcConfig =
                    HevcConfig.parseLayered(parent, vpsData!!)
                checkContainerInput(
                    out.nalUnitLengthFieldLength == lhevcConfig.nalUnitLengthFieldLength,
                    "nalUnitLengthFieldLength must be same for both hvcC and lhvC atoms"
                )

                // Only stereo MV-HEVC is currently supported, for which both views must have the same below
                // configuration values.
                if (lhevcConfig.colorSpace != Format.NO_VALUE) {
                    checkContainerInput(
                        colorSpace == lhevcConfig.colorSpace,
                        "colorSpace must be the same for both views"
                    )
                }
                if (lhevcConfig.colorRange != Format.NO_VALUE) {
                    checkContainerInput(
                        colorRange == lhevcConfig.colorRange,
                        "colorRange must be the same for both views"
                    )
                }
                if (lhevcConfig.colorTransfer != Format.NO_VALUE) {
                    checkContainerInput(
                        colorTransfer == lhevcConfig.colorTransfer,
                        "colorTransfer must be the same for both views"
                    )
                }
                checkContainerInput(
                    bitdepthLuma == lhevcConfig.bitdepthLuma,
                    "bitdepthLuma must be the same for both views"
                )
                checkContainerInput(
                    bitdepthChroma == lhevcConfig.bitdepthChroma,
                    "bitdepthChroma must be the same for both views"
                )

                mimeType = MimeTypes.VIDEO_MV_HEVC
                if (initializationData != null) {
                    initializationData =
                        mutableListOf<ByteArray>().apply {
                            addAll(initializationData)
                            addAll(lhevcConfig.initializationData)
                        }
                } else {
                    checkContainerInput(
                        false, "initializationData must be already set from hvcC atom"
                    )
                }
                codecs = lhevcConfig.codecs
            } else if (childAtomType == Mp4Box.TYPE_vvcC) {
                checkContainerInput(mimeType == null,  null)
                mimeType = MimeTypes.VIDEO_H266
                parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE)
                val vvcConfig = VvcConfig.parse(parent)
                initializationData = vvcConfig.initializationData
                out.nalUnitLengthFieldLength = vvcConfig.nalUnitLengthFieldLength
                codecs = vvcConfig.codecs
                bitdepthLuma = vvcConfig.bitdepthLuma
                bitdepthChroma = vvcConfig.bitdepthLuma
                // The VVC specification (ITU-T H.266, Annex A) allows a maximum of 16 pictures for
                // reordering in the decoded picture buffer. Using 16 as a safe default avoids the need to
                // parse the SPS bitstream for the exact value.
                maxNumReorderSamples = 16
            } else if (childAtomType == Mp4Box.TYPE_vexu) {
                val vexuData = parseVideoExtendedUsageBox(parent, childStartPosition, childAtomSize)
                if (vexuData != null && vexuData.eyesData != null) {
                    if (vpsData != null && vpsData.layerInfos.size >= 2) {
                        // This is MV-HEVC case, so both eye views should be marked as available.
                        checkContainerInput(
                            vexuData.hasBothEyeViews(), "both eye views must be marked as available"
                        )
                        // Based on subsection 1.4.3 of Apple’s proposed ISOBMFF extensions for stereo video
                        // (https://developer.apple.com/av-foundation/Stereo-Video-ISOBMFF-Extensions.pdf):
                        // "For multiview coding, there is no implied ordering and the eye_views_reversed field
                        // should be set to 0".
                        checkContainerInput(
                            !vexuData.eyesData.striData.eyeViewsReversed,
                            "for MV-HEVC, eye_views_reversed must be set to false"
                        )
                    } else if (stereoMode == Format.NO_VALUE) {
                        stereoMode =
                            if (vexuData.eyesData.striData.eyeViewsReversed)
                                C.STEREO_MODE_INTERLEAVED_RIGHT_PRIMARY
                            else
                                C.STEREO_MODE_INTERLEAVED_LEFT_PRIMARY
                    }
                }
            } else if (childAtomType == Mp4Box.TYPE_dvcC || childAtomType == Mp4Box.TYPE_dvvC || childAtomType == Mp4Box.TYPE_dvwC) {
                dolbyVisionConfig = DolbyVisionConfig.parse(parent)
            } else if (childAtomType == Mp4Box.TYPE_vpcC) {
                checkContainerInput(mimeType == null,  null)
                mimeType =
                    if (atomType == Mp4Box.TYPE_vp08) MimeTypes.VIDEO_VP8 else MimeTypes.VIDEO_VP9
                parent.setPosition(childStartPosition + Mp4Box.FULL_HEADER_SIZE)
                // See vpcC atom syntax: https://www.webmproject.org/vp9/mp4/#syntax_1
                val profile = parent.readUnsignedByte().toByte()
                val level = parent.readUnsignedByte().toByte()
                val byte3 = parent.readUnsignedByte()
                bitdepthLuma = byte3 shr 4
                bitdepthChroma = bitdepthLuma
                val chromaSubsampling = ((byte3 shr 1) and 7).toByte()
                if (mimeType == MimeTypes.VIDEO_VP9) {
                    // CSD should be in CodecPrivate format according to VP9 Codec spec.
                    initializationData =
                        buildVp9CodecPrivateInitializationData(
                            profile, level, bitdepthLuma.toByte(), chromaSubsampling
                        )
                }
                val fullRangeFlag = (byte3 and 1) != 0
                val colorPrimaries = parent.readUnsignedByte()
                val transferCharacteristics = parent.readUnsignedByte()
                colorSpace = ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries)
                colorRange = if (fullRangeFlag) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED
                colorTransfer =
                    ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics)
            } else if (childAtomType == Mp4Box.TYPE_av1C) {
                mimeType = MimeTypes.VIDEO_AV1

                val childAtomBodySize = childAtomSize - Mp4Box.HEADER_SIZE
                val initializationDataChunk = ByteArray(childAtomBodySize)
                parent.readBytes(initializationDataChunk,  0, childAtomBodySize)

                initializationData = listOf<ByteArray>()
                val av1Config = Av1Config.parse(initializationDataChunk)
                if (av1Config != null) {
                    bitdepthLuma = av1Config.bitdepth
                    bitdepthChroma = av1Config.bitdepth
                    colorSpace = av1Config.colorSpace
                    colorRange = av1Config.colorRange
                    colorTransfer = av1Config.colorTransfer
                    codecs = av1Config.codecs
                }
            } else if (childAtomType == Mp4Box.TYPE_clli) {
                if (hdrStaticInfo == null) {
                    hdrStaticInfo = allocateHdrStaticInfo()
                }
                // The contents of the clli box occupy the last 4 bytes of the HDR static info array. Note
                // that each field is read in big endian and written in little endian.
                hdrStaticInfo.position(21)
                hdrStaticInfo.putShort(parent.readShort()) // max_content_light_level.
                hdrStaticInfo.putShort(parent.readShort()) // max_pic_average_light_level.
            } else if (childAtomType == Mp4Box.TYPE_mdcv) {
                if (hdrStaticInfo == null) {
                    hdrStaticInfo = allocateHdrStaticInfo()
                }
                // The contents of the mdcv box occupy 20 bytes after the first byte of the HDR static info
                // array. Note that each field is read in big endian and written in little endian.
                val displayPrimariesGX = parent.readShort()
                val displayPrimariesGY = parent.readShort()
                val displayPrimariesBX = parent.readShort()
                val displayPrimariesBY = parent.readShort()
                val displayPrimariesRX = parent.readShort()
                val displayPrimariesRY = parent.readShort()
                val whitePointX = parent.readShort()
                val whitePointY = parent.readShort()
                val maxDisplayMasteringLuminance = parent.readUnsignedInt()
                val minDisplayMasteringLuminance = parent.readUnsignedInt()

                hdrStaticInfo.position(1)
                hdrStaticInfo.putShort(displayPrimariesRX)
                hdrStaticInfo.putShort(displayPrimariesRY)
                hdrStaticInfo.putShort(displayPrimariesGX)
                hdrStaticInfo.putShort(displayPrimariesGY)
                hdrStaticInfo.putShort(displayPrimariesBX)
                hdrStaticInfo.putShort(displayPrimariesBY)
                hdrStaticInfo.putShort(whitePointX)
                hdrStaticInfo.putShort(whitePointY)
                hdrStaticInfo.putShort((maxDisplayMasteringLuminance / 10000).toShort())
                hdrStaticInfo.putShort((minDisplayMasteringLuminance / 10000).toShort())
            } else if (childAtomType == Mp4Box.TYPE_d263) {
                checkContainerInput(mimeType == null,  null)
                mimeType = MimeTypes.VIDEO_H263
            } else if (childAtomType == Mp4Box.TYPE_esds) {
                checkContainerInput(mimeType == null,  null)
                esdsData = parseEsdsFromParent(parent, childStartPosition)
                mimeType = esdsData.mimeType
                val initializationDataBytes = esdsData.initializationData
                if (initializationDataBytes != null) {
                    initializationData = listOf(initializationDataBytes)
                }
            } else if (childAtomType == Mp4Box.TYPE_btrt) {
                btrtData = parseBtrtFromParent(parent, childStartPosition)
            } else if (childAtomType == Mp4Box.TYPE_pasp) {
                pixelWidthHeightRatio = parsePaspFromParent(parent, childStartPosition)
                pixelWidthHeightRatioFromPasp = true
            } else if (childAtomType == Mp4Box.TYPE_sv3d) {
                projectionData = parseProjFromParent(parent, childStartPosition, childAtomSize)
            } else if (childAtomType == Mp4Box.TYPE_st3d) {
                val version = parent.readUnsignedByte()
                parent.skipBytes(3) // Flags.
                if (version == 0) {
                    val layout = parent.readUnsignedByte()
                    when (layout) {
                        0 -> stereoMode = C.STEREO_MODE_MONO
                        1 -> stereoMode = C.STEREO_MODE_TOP_BOTTOM
                        2 -> stereoMode = C.STEREO_MODE_LEFT_RIGHT
                        3 -> stereoMode = C.STEREO_MODE_STEREO_MESH
                        else -> {}
                    }
                }
            } else if (childAtomType == Mp4Box.TYPE_apvC) {
                mimeType = MimeTypes.VIDEO_APV

                val childAtomBodySize = childAtomSize - Mp4Box.FULL_HEADER_SIZE
                val initializationDataChunk = ByteArray(childAtomBodySize)
                parent.setPosition(childStartPosition + Mp4Box.FULL_HEADER_SIZE) // Skip version and flags.
                parent.readBytes(initializationDataChunk,  0, childAtomBodySize)
                codecs = buildApvCodecString(initializationDataChunk)
                initializationData = listOf(initializationDataChunk)

                val colorInfo = parseApvc(ParsableByteArray(initializationDataChunk))

                bitdepthLuma = colorInfo.lumaBitdepth
                bitdepthChroma = colorInfo.chromaBitdepth
                colorSpace = colorInfo.colorSpace
                colorRange = colorInfo.colorRange
                colorTransfer = colorInfo.colorTransfer
            } else if (childAtomType == Mp4Box.TYPE_colr) {
                // Only modify these values if 'colorSpace' and 'colorTransfer' have not been previously
                // established by the bitstream. The absence of color descriptors ('colorSpace' and
                // 'colorTransfer') does not necessarily mean that 'colorRange' has default values, hence it
                // is not being verified here.
                // If 'Atom.TYPE_avcC', 'Atom.TYPE_hvcC', 'Atom.TYPE_vpcC' or 'Atom.TYPE_av1c' is available,
                // they will take precedence and overwrite any existing values.
                if (colorSpace == Format.NO_VALUE && colorTransfer == Format.NO_VALUE) {
                    val colorType = parent.readInt()
                    if (colorType == TYPE_nclx || colorType == TYPE_nclc) {
                        // For more info on syntax, see Section 8.5.2.2 in ISO/IEC 14496-12:2012(E) and
                        // https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/QTFFChap3/qtff3.html.
                        val colorPrimaries = parent.readUnsignedShort()
                        val transferCharacteristics = parent.readUnsignedShort()
                        parent.skipBytes(2) // matrix_coefficients.

                        // Only try and read full_range_flag if the box is long enough. It should be present in
                        // all colr boxes with type=nclx (Section 8.5.2.2 in ISO/IEC 14496-12:2012(E)) but some
                        // device cameras record videos with type=nclx without this final flag (and therefore
                        // size=18): https://github.com/google/ExoPlayer/issues/9332
                        val fullRangeFlag =
                            childAtomSize == 19 && (parent.readUnsignedByte() and 128) != 0
                        colorSpace = ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries)
                        colorRange =
                            if (fullRangeFlag) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED
                        colorTransfer =
                            ColorInfo.isoTransferCharacteristicsToColorTransfer(
                                transferCharacteristics
                            )
                    } else {
                        w(TAG, "Unsupported color type: " + Mp4Box.getBoxTypeString(colorType))
                    }
                }
            }
            childPosition += childAtomSize
        }

        if (dolbyVisionConfig != null) {
            mimeType = MimeTypes.VIDEO_DOLBY_VISION
            codecs = dolbyVisionConfig.codecs
        }

        // If the media type was not recognized, ignore the track.
        if (mimeType == null) {
            return
        }

        val formatBuilder =
            Format.Builder()
                .setId(tkhdData.id)
                .setSampleMimeType(mimeType)
                .setCodecs(codecs)
                .setWidth(width)
                .setHeight(height)
                .setDecodedWidth(decodedWidth)
                .setDecodedHeight(decodedHeight)
                .setPixelWidthHeightRatio(pixelWidthHeightRatio)
                .setRotationDegrees(tkhdData.rotationDegrees)
                .setMirrorHorizontal(tkhdData.mirrorHorizontal)
                .setProjectionData(projectionData)
                .setStereoMode(stereoMode)
                .setInitializationData(initializationData)
                .setMaxNumReorderSamples(maxNumReorderSamples)
                .setMaxSubLayers(maxSubLayers)
                .setDrmInitData(drmInitData)
                .setLanguage(language) // Note that if either mdcv or clli are missing, we leave the corresponding HDR static
                // metadata bytes with value zero. See [Internal ref: b/194535665].
                .setColorInfo(
                    ColorInfo.Builder()
                        .setColorSpace(colorSpace)
                        .setColorRange(colorRange)
                        .setColorTransfer(colorTransfer)
                        .setHdrStaticInfo(if (hdrStaticInfo != null) hdrStaticInfo.array() else null)
                        .setLumaBitdepth(bitdepthLuma)
                        .setChromaBitdepth(bitdepthChroma)
                        .build()
                )

        // Prefer btrtData over esdsData for video track.
        if (btrtData != null) {
            formatBuilder
                .setAverageBitrate(Ints.saturatedCast(btrtData.avgBitrate))
                .setPeakBitrate(Ints.saturatedCast(btrtData.maxBitrate))
        } else if (esdsData != null) {
            formatBuilder
                .setAverageBitrate(Ints.saturatedCast(esdsData.bitrate))
                .setPeakBitrate(Ints.saturatedCast(esdsData.peakBitrate))
        }

        out.format = formatBuilder.build()
    }

    private fun parseApvc(data: ParsableByteArray): ColorInfo {
        val colorInfo = ColorInfo.Builder()
        val bitArray = ParsableBitArray(data.data)
        bitArray.position=(data.getPosition() * 8) // Convert byte to bit position.
        // See APVDecoderConfigurationRecord syntax.
        bitArray.skipBytes(1) // configurationVersion
        val numConfigurationEntries = bitArray.readBits(8) // number_of_configuration_entry
        for (i in 0..<numConfigurationEntries) {
            bitArray.skipBytes(1) // pbu_type
            val numberOfFrameInfo = bitArray.readBits(8)
            for (j in 0..<numberOfFrameInfo) {
                bitArray.skipBits(6) // reserved_zero_6bits
                val isColorDescriptionPresent =
                    bitArray.readBit() // color_description_present_flag_info
                bitArray.skipBit() // capture_time_distance_ignored
                // Skip profile_idc (1 byte), level_idc (1 byte), band_idc (1 byte), frame_width (4 bytes),
                // frame_height (4 bytes).
                bitArray.skipBytes(11)
                bitArray.skipBits(4) // chroma_format_idc (4 bits)
                val bitDepth = bitArray.readBits(4) + 8 // bit_depth_minus8 + 8
                colorInfo.setLumaBitdepth(bitDepth)
                colorInfo.setChromaBitdepth(bitDepth)
                bitArray.skipBytes(1) // capture_time_distance
                if (isColorDescriptionPresent) {
                    val colorPrimaries = bitArray.readBits(8) // color_primaries
                    val transferCharacteristics = bitArray.readBits(8) // transfer_characteristics
                    bitArray.skipBytes(1) // matrix_coefficients
                    val fullRangeFlag = bitArray.readBit() // full_range_flag
                    colorInfo
                        .setColorSpace(ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries))
                        .setColorRange(if (fullRangeFlag) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED)
                        .setColorTransfer(
                            ColorInfo.isoTransferCharacteristicsToColorTransfer(
                                transferCharacteristics
                            )
                        )
                }
            }
        }
        return colorInfo.build()
    }

    private fun allocateHdrStaticInfo(): ByteBuffer {
        // For HDR static info, Android decoders expect a 25-byte array. The first byte is zero to
        // represent Static Metadata Type 1, as per CTA-861-G:2017, Table 44. The following 24 bytes
        // follow CTA-861-G:2017, Table 45.
        return ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun parseMetaDataSampleEntry(
        parent: ParsableByteArray, atomType: Int, position: Int, trackId: Int, out: StsdData
    ) {
        parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE)
        if (atomType == Mp4Box.TYPE_mett) {
            parent.readNullTerminatedString() // Skip optional content_encoding
            val mimeType = parent.readNullTerminatedString()
            if (mimeType != null) {
                out.format = Format.Builder().setId(trackId).setSampleMimeType(mimeType).build()
            }
        } else if (atomType == Mp4Box.TYPE_it35) {
            val identifierLength = parent.readUnsignedByte()
            val identifier = ByteArray(identifierLength)
            parent.readBytes(identifier, 0, identifierLength)
            out.format =
                Format.Builder()
                    .setId(trackId)
                    .setSampleMimeType(MimeTypes.APPLICATION_ITUT_T35)
                    .setInitializationData(listOf(identifier))
                    .build()
        }
    }

    private fun parseEdts(
        edtsAtom: ContainerBox
    ): Pair<ImmutableLongArray?, ImmutableLongArray?>? {
        val elstAtom = edtsAtom.getLeafBoxOfType(Mp4Box.TYPE_elst)
        if (elstAtom == null) {
            return null
        }
        val elstData = elstAtom.data
        elstData!!.setPosition(Mp4Box.HEADER_SIZE)
        val fullAtom = elstData.readInt()
        val version = parseFullBoxVersion(fullAtom)
        val entryCount = elstData.readUnsignedIntToInt()
        val editListDurations = ImmutableLongArray.builder(entryCount)
        val editListMediaTimes = ImmutableLongArray.builder(entryCount)
        for (i in 0..<entryCount) {
            editListDurations.add(
                if (version == 1) elstData.readUnsignedLongToLong() else elstData.readUnsignedInt()
            )
            editListMediaTimes.add(
                if (version == 1) elstData.readLong() else elstData.readInt().toLong()
            )
            val mediaRateInteger = elstData.readShort().toInt()
            require(mediaRateInteger == 1) { "Unsupported media rate." }
            elstData.skipBytes(2)
        }
        return Pair.create<ImmutableLongArray?, ImmutableLongArray?>(
            editListDurations.build(),
            editListMediaTimes.build()
        )
    }

    private fun parsePaspFromParent(parent: ParsableByteArray, position: Int): Float {
        parent.setPosition(position + Mp4Box.HEADER_SIZE)
        val hSpacing = parent.readUnsignedIntToInt()
        val vSpacing = parent.readUnsignedIntToInt()
        return hSpacing.toFloat() / vSpacing
    }

    @Throws(ParserException::class)
    private fun parseAudioSampleEntry(
        parent: ParsableByteArray,
        atomType: Int,
        position: Int,
        size: Int,
        trackId: Int,
        language: String?,
        isQuickTime: Boolean,
        drmInitData: DrmInitData?,
        out: StsdData,
        entryIndex: Int
    ) {
        var atomType = atomType
        var drmInitData = drmInitData
        parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE)

        var quickTimeSoundDescriptionVersion = 0
        if (isQuickTime) {
            quickTimeSoundDescriptionVersion = parent.readUnsignedShort()
            parent.skipBytes(6)
        } else {
            parent.skipBytes(8)
        }

        var channelCount: Int
        var sampleRate: Int
        var sampleRateMlp = 0
        var pcmEncoding: @PcmEncoding Int = Format.NO_VALUE
        var codecs: String? = null
        var esdsData: EsdsData? = null
        var btrtData: BtrtData? = null

        if (quickTimeSoundDescriptionVersion == 0 || quickTimeSoundDescriptionVersion == 1) {
            channelCount = parent.readUnsignedShort()
            parent.skipBytes(6) // sampleSize, compressionId, packetSize.

            sampleRate = parent.readUnsignedFixedPoint1616()
            // The sample rate has been redefined as a 32-bit value for Dolby TrueHD (MLP) streams.
            parent.setPosition(parent.getPosition() - 4)
            sampleRateMlp = parent.readInt()

            if (quickTimeSoundDescriptionVersion == 1) {
                parent.skipBytes(16)
            }
        } else if (quickTimeSoundDescriptionVersion == 2) {
            parent.skipBytes(16) // always[3,16,Minus2,0,65536], sizeOfStructOnly

            sampleRate = Math.round(parent.readDouble()).toInt()
            channelCount = parent.readUnsignedIntToInt()

            parent.skipBytes(4) // always7F000000
            val bitsPerSample = parent.readUnsignedIntToInt()
            val formatSpecificFlags = parent.readUnsignedIntToInt()
            val isFloat = (formatSpecificFlags and 1) != 0
            val isBigEndian = (formatSpecificFlags and (1 shl 1)) != 0
            pcmEncoding =
                if (isFloat)
                    getFloatPcmEncoding(
                        bitsPerSample,
                        if (isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                    )
                else
                    getPcmEncoding(
                        bitsPerSample,
                        if (isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                    )
            if (pcmEncoding == C.ENCODING_INVALID) {
                pcmEncoding = Format.NO_VALUE
            }
            parent.skipBytes(8) // constBytesPerAudioPacket, constLPCMFramesPerAudioPacket
        } else {
            // Unsupported version.
            return
        }

        if (atomType == Mp4Box.TYPE_iamf) {
            // As per the IAMF spec (https://aomediacodec.github.io/iamf/#iasampleentry-section),
            // channelCount and sampleRate SHALL be set to 0 and ignored. We ignore it by using
            // Format.NO_VALUE instead of 0.
            channelCount = Format.NO_VALUE
            sampleRate = Format.NO_VALUE
        } else if (atomType == Mp4Box.TYPE_samr) {
            // AMR NB audio is always mono, 8kHz
            channelCount = 1
            sampleRate = SAMPLE_RATE_AMR_NB
        } else if (atomType == Mp4Box.TYPE_sawb) {
            // AMR WB audio is always mono, 16kHz
            channelCount = 1
            sampleRate = SAMPLE_RATE_AMR_WB
        }

        var childPosition = parent.getPosition()
        if (atomType == Mp4Box.TYPE_enca) {
            val sampleEntryEncryptionData =
                parseSampleEntryEncryptionData(parent, position, size)
            if (sampleEntryEncryptionData != null) {
                atomType = sampleEntryEncryptionData.first!!
                drmInitData =
                    if (drmInitData == null)
                        null
                    else
                        drmInitData.copyWithSchemeType(sampleEntryEncryptionData.second!!.schemeType)
                out.trackEncryptionBoxes[entryIndex] = sampleEntryEncryptionData.second
            }
            parent.setPosition(childPosition)
        }

        // TODO: Uncomment when [Internal: b/63092960] is fixed.
        // else {
        //   drmInitData = null;
        // }

        // If the atom type determines a MIME type, set it immediately.
        var mimeType: String? = null
        if (atomType == Mp4Box.TYPE_ac_3) {
            mimeType = MimeTypes.AUDIO_AC3
        } else if (atomType == Mp4Box.TYPE_ec_3) {
            mimeType = MimeTypes.AUDIO_E_AC3
        } else if (atomType == Mp4Box.TYPE_ac_4) {
            mimeType = MimeTypes.AUDIO_AC4
        } else if (atomType == Mp4Box.TYPE_dtsc) {
            mimeType = MimeTypes.AUDIO_DTS
        } else if (atomType == Mp4Box.TYPE_dtsh || atomType == Mp4Box.TYPE_dtsl) {
            mimeType = MimeTypes.AUDIO_DTS_HD
        } else if (atomType == Mp4Box.TYPE_dtse) {
            mimeType = MimeTypes.AUDIO_DTS_EXPRESS
        } else if (atomType == Mp4Box.TYPE_dtsx) {
            mimeType = MimeTypes.AUDIO_DTS_UHD_P2
        } else if (atomType == Mp4Box.TYPE_samr) {
            mimeType = MimeTypes.AUDIO_AMR_NB
        } else if (atomType == Mp4Box.TYPE_sawb) {
            mimeType = MimeTypes.AUDIO_AMR_WB
        } else if (atomType == Mp4Box.TYPE_sowt) {
            mimeType = MimeTypes.AUDIO_RAW
            pcmEncoding = C.ENCODING_PCM_16BIT
        } else if (atomType == Mp4Box.TYPE_twos) {
            mimeType = MimeTypes.AUDIO_RAW
            pcmEncoding = C.ENCODING_PCM_16BIT_BIG_ENDIAN
        } else if (atomType == Mp4Box.TYPE_lpcm) {
            mimeType = MimeTypes.AUDIO_RAW
            if (pcmEncoding == Format.NO_VALUE) {
                pcmEncoding = C.ENCODING_PCM_16BIT
            }
        } else if (atomType == Mp4Box.TYPE__mp2 || atomType == Mp4Box.TYPE__mp3) {
            mimeType = MimeTypes.AUDIO_MPEG
        } else if (atomType == Mp4Box.TYPE_mha1) {
            mimeType = MimeTypes.AUDIO_MPEGH_MHA1
        } else if (atomType == Mp4Box.TYPE_mhm1) {
            mimeType = MimeTypes.AUDIO_MPEGH_MHM1
        } else if (atomType == Mp4Box.TYPE_alac) {
            mimeType = MimeTypes.AUDIO_ALAC
        } else if (atomType == Mp4Box.TYPE_alaw) {
            mimeType = MimeTypes.AUDIO_ALAW
        } else if (atomType == Mp4Box.TYPE_ulaw) {
            mimeType = MimeTypes.AUDIO_MLAW
        } else if (atomType == Mp4Box.TYPE_Opus) {
            mimeType = MimeTypes.AUDIO_OPUS
        } else if (atomType == Mp4Box.TYPE_fLaC) {
            mimeType = MimeTypes.AUDIO_FLAC
        } else if (atomType == Mp4Box.TYPE_mlpa) {
            mimeType = MimeTypes.AUDIO_TRUEHD
        } else if (atomType == Mp4Box.TYPE_iamf) {
            mimeType = MimeTypes.AUDIO_IAMF
        }

        var initializationData: List<ByteArray>? = null
        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childAtomSize = parent.readInt()
            checkContainerInput(childAtomSize > 0, "childAtomSize must be positive")
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_mhaC) {
                // See ISO_IEC_23008-3;2022 MHADecoderConfigurationRecord
                // The header consists of: size (4), boxtype 'mhaC' (4), configurationVersion (1),
                // mpegh3daProfileLevelIndication (1), referenceChannelLayout (1), mpegh3daConfigLength (2).
                parent.setPosition(childPosition + Mp4Box.HEADER_SIZE)
                parent.skipBytes(1) // configurationVersion
                val mpeghProfileLevelIndication = parent.readUnsignedByte()
                parent.skipBytes(1) // mpeghReferenceChannelLayout
                codecs =
                    if (mimeType == MimeTypes.AUDIO_MPEGH_MHM1) String.format(
                        "mhm1.%02X",
                        mpeghProfileLevelIndication
                    ) else String.format("mha1.%02X", mpeghProfileLevelIndication)
                val mpegh3daConfigLength = parent.readUnsignedShort()
                val initializationDataBytes = ByteArray(mpegh3daConfigLength)
                parent.readBytes(initializationDataBytes, 0, mpegh3daConfigLength)
                // The mpegh3daConfig should always be the first entry in initializationData.
                if (initializationData == null) {
                    initializationData = listOf(initializationDataBytes)
                } else {
                    // We assume that the mhaP box has been parsed before and so add the compatible profile
                    // level sets as the second entry.
                    initializationData =listOf(
                        initializationDataBytes,
                        initializationData[0]
                    )
                }
            } else if (childAtomType == Mp4Box.TYPE_mhaP) {
                // See ISO_IEC_23008-3;2022 MHAProfileAndLevelCompatibilitySetBox
                // The header consists of: size (4), boxtype 'mhaP' (4), numCompatibleSets (1).
                parent.setPosition(childPosition + Mp4Box.HEADER_SIZE)
                val numCompatibleSets = parent.readUnsignedByte()
                if (numCompatibleSets > 0) {
                    val mpeghCompatibleProfileLevelSet = ByteArray(numCompatibleSets)
                    parent.readBytes(mpeghCompatibleProfileLevelSet, 0, numCompatibleSets)
                    if (initializationData == null) {
                        initializationData =listOf(mpeghCompatibleProfileLevelSet)
                    } else {
                        // We assume that the mhaC box has been parsed before and so add the compatible profile
                        // level sets as the second entry.
                        initializationData =
                            listOf(
                                initializationData[0],
                                mpeghCompatibleProfileLevelSet
                            )
                    }
                }
            } else if (childAtomType == Mp4Box.TYPE_esds
                || (isQuickTime && childAtomType == Mp4Box.TYPE_wave)
            ) {
                val esdsAtomPosition =
                    if (childAtomType == Mp4Box.TYPE_esds)
                        childPosition
                    else
                        findBoxPosition(parent, Mp4Box.TYPE_esds, childPosition, childAtomSize)
                if (esdsAtomPosition != C.INDEX_UNSET) {
                    esdsData = parseEsdsFromParent(parent, esdsAtomPosition)
                    mimeType = esdsData.mimeType
                    val initializationDataBytes = esdsData.initializationData
                    if (initializationDataBytes != null) {
                        if (MimeTypes.AUDIO_VORBIS == mimeType) {
                            initializationData =
                                parseVorbisCsdFromEsdsInitializationData(initializationDataBytes)
                        } else {
                            if (MimeTypes.AUDIO_AAC == mimeType) {
                                // Update sampleRate and channelCount from the AudioSpecificConfig initialization
                                // data, which is more reliable. See [Internal: b/10903778].
                                val aacConfig =
                                    parseAudioSpecificConfig(initializationDataBytes)
                                sampleRate = aacConfig.sampleRateHz
                                channelCount = aacConfig.channelCount
                                codecs = aacConfig.codecs
                            }
                            initializationData =
                                listOf(initializationDataBytes)
                        }
                    }
                }
            } else if (childAtomType == Mp4Box.TYPE_btrt) {
                btrtData = parseBtrtFromParent(parent, childPosition)
            } else if (childAtomType == Mp4Box.TYPE_dac3) {
                parent.setPosition(Mp4Box.HEADER_SIZE + childPosition)
                out.format =
                    parseAc3AnnexFFormat(parent, trackId.toString(), language, drmInitData)
            } else if (childAtomType == Mp4Box.TYPE_dec3) {
                parent.setPosition(Mp4Box.HEADER_SIZE + childPosition)
                out.format =
                    parseEAc3AnnexFFormat(parent, trackId.toString(), language, drmInitData)
            } else if (childAtomType == Mp4Box.TYPE_dac4) {
                parent.setPosition(Mp4Box.HEADER_SIZE + childPosition)
                out.format =
                    parseAc4AnnexEFormat(parent, trackId.toString(), language, drmInitData)
            } else if (childAtomType == Mp4Box.TYPE_dmlp) {
                if (sampleRateMlp <= 0) {
                    throw ParserException.createForMalformedContainer(
                        "Invalid sample rate for Dolby TrueHD MLP stream: " + sampleRateMlp,  
                        null
                    )
                }
                sampleRate = sampleRateMlp
                // The channel count from the sample entry must be ignored for Dolby TrueHD (MLP) streams
                // because these streams can carry simultaneously multiple representations of the same
                // audio. Use stereo by default.
                channelCount = 2
            } else if (childAtomType == Mp4Box.TYPE_ddts || childAtomType == Mp4Box.TYPE_udts) {
                out.format =
                    Format.Builder()
                        .setId(trackId)
                        .setSampleMimeType(mimeType)
                        .setChannelCount(channelCount)
                        .setSampleRate(sampleRate)
                        .setDrmInitData(drmInitData)
                        .setLanguage(language)
                        .build()
            } else if (childAtomType == Mp4Box.TYPE_dOps) {
                // Build an Opus Identification Header (defined in RFC-7845) by concatenating the Opus Magic
                // Signature and the body of the dOps atom.
                val childAtomBodySize = childAtomSize - Mp4Box.HEADER_SIZE
                val headerBytes = opusMagic!!.copyOf(opusMagic.size + childAtomBodySize)
                parent.setPosition(childPosition + Mp4Box.HEADER_SIZE)
                parent.readBytes(headerBytes, opusMagic.size, childAtomBodySize)
                initializationData = buildInitializationData(headerBytes)
            } else if (childAtomType == Mp4Box.TYPE_dfLa) {
                val childAtomBodySize = childAtomSize - Mp4Box.FULL_HEADER_SIZE
                val initializationDataBytes = ByteArray(4 + childAtomBodySize)
                initializationDataBytes[0] = 0x66 // f
                initializationDataBytes[1] = 0x4C // L
                initializationDataBytes[2] = 0x61 // a
                initializationDataBytes[3] = 0x43 // C
                parent.setPosition(childPosition + Mp4Box.FULL_HEADER_SIZE)
                parent.readBytes(initializationDataBytes,  4, childAtomBodySize)
                initializationData = listOf(initializationDataBytes)
            } else if (childAtomType == Mp4Box.TYPE_alac) {
                val childAtomBodySize = childAtomSize - Mp4Box.FULL_HEADER_SIZE
                val initializationDataBytes = ByteArray(childAtomBodySize)
                parent.setPosition(childPosition + Mp4Box.FULL_HEADER_SIZE)
                parent.readBytes(initializationDataBytes,  0, childAtomBodySize)
                // Update sampleRate and channelCount from the AudioSpecificConfig initialization data,
                // which is more reliable. See https://github.com/google/ExoPlayer/pull/6629.
                val parsedAlacConfig =
                    parseAlacAudioSpecificConfig(initializationDataBytes)
                sampleRate = parsedAlacConfig[0]
                channelCount = parsedAlacConfig[1]
                val bitDepth = parsedAlacConfig[2]
                pcmEncoding = getPcmEncoding(bitDepth)
                if (pcmEncoding == C.ENCODING_INVALID) {
                    pcmEncoding = Format.NO_VALUE
                }
                initializationData = listOf(initializationDataBytes)
            } else if (childAtomType == Mp4Box.TYPE_iacb) {
                parent.setPosition(
                    childPosition + Mp4Box.HEADER_SIZE + 1
                ) // header and configuration version
                val configObusSize = parent.readUnsignedLeb128ToInt()
                val initializationDataBytes = ByteArray(configObusSize)
                parent.readBytes(initializationDataBytes,  0, configObusSize)
                codecs = buildIamfCodecString(initializationDataBytes)
                initializationData = listOf(initializationDataBytes)
            } else if (childAtomType == Mp4Box.TYPE_pcmC) {
                // See ISO 23003-5 for the definition of the pcmC box.
                parent.setPosition(childPosition + Mp4Box.FULL_HEADER_SIZE)
                val formatFlags = parent.readUnsignedByte()
                val byteOrder =
                    if ((formatFlags and 0x1) != 0) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val sampleSize = parent.readUnsignedByte()
                if (atomType == Mp4Box.TYPE_ipcm) {
                    pcmEncoding = getPcmEncoding(sampleSize, byteOrder)
                } else if (atomType == Mp4Box.TYPE_fpcm) {
                    pcmEncoding = getFloatPcmEncoding(sampleSize, byteOrder)
                }
                if (pcmEncoding == C.ENCODING_INVALID) {
                    pcmEncoding = Format.NO_VALUE
                }
                if (pcmEncoding != Format.NO_VALUE) {
                    mimeType = MimeTypes.AUDIO_RAW
                }
            }
            childPosition += childAtomSize
        }

        if (out.format == null && mimeType != null) {
            val formatBuilder =
                Format.Builder()
                    .setId(trackId)
                    .setSampleMimeType(mimeType)
                    .setCodecs(codecs)
                    .setChannelCount(channelCount)
                    .setSampleRate(sampleRate)
                    .setPcmEncoding(pcmEncoding)
                    .setInitializationData(initializationData)
                    .setDrmInitData(drmInitData)
                    .setLanguage(language)

            // Prefer esdsData over btrtData for audio track.
            if (esdsData != null) {
                formatBuilder
                    .setAverageBitrate(Ints.saturatedCast(esdsData.bitrate))
                    .setPeakBitrate(Ints.saturatedCast(esdsData.peakBitrate))
            } else if (btrtData != null) {
                formatBuilder
                    .setAverageBitrate(Ints.saturatedCast(btrtData.avgBitrate))
                    .setPeakBitrate(Ints.saturatedCast(btrtData.maxBitrate))
            }

            out.format = formatBuilder.build()
        }
    }

    @Throws(ParserException::class)
    private fun findBoxPosition(
        parent: ParsableByteArray, boxType: Int, parentBoxPosition: Int, parentBoxSize: Int
    ): Int {
        var childAtomPosition = parent.getPosition()
        checkContainerInput(childAtomPosition >= parentBoxPosition,  null)
        while (childAtomPosition - parentBoxPosition < parentBoxSize) {
            parent.setPosition(childAtomPosition)
            val childAtomSize = parent.readInt()
            checkContainerInput(childAtomSize > 0, "childAtomSize must be positive")
            val childType = parent.readInt()
            if (childType == boxType) {
                return childAtomPosition
            }
            childAtomPosition += childAtomSize
        }
        return C.INDEX_UNSET
    }

    private fun parseEsdsFromParent(parent: ParsableByteArray, position: Int): EsdsData {
        parent.setPosition(position + Mp4Box.HEADER_SIZE + 4)
        // Start of the ES_Descriptor (defined in ISO/IEC 14496-1)
        parent.skipBytes(1) // ES_Descriptor tag
        parseExpandableClassSize(parent)
        parent.skipBytes(2) // ES_ID

        val flags = parent.readUnsignedByte()
        if ((flags and 0x80 ) != 0) {
            parent.skipBytes(2)
        }
        if ((flags and 0x40 ) != 0) {
            parent.skipBytes(parent.readUnsignedByte())
        }
        if ((flags and 0x20 ) != 0) {
            parent.skipBytes(2)
        }

        // Start of the DecoderConfigDescriptor (defined in ISO/IEC 14496-1)
        parent.skipBytes(1) // DecoderConfigDescriptor tag
        parseExpandableClassSize(parent)

        // Set the MIME type based on the object type indication (ISO/IEC 14496-1 table 5).
        val objectTypeIndication = parent.readUnsignedByte()
        val mimeType: String? = getMimeTypeFromMp4ObjectType(objectTypeIndication)
        if (MimeTypes.AUDIO_MPEG == mimeType
            || MimeTypes.AUDIO_DTS == mimeType
            || MimeTypes.AUDIO_DTS_HD == mimeType
        ) {
            return EsdsData(
                mimeType,  
                null,  
                Format.NO_VALUE.toLong(),  
                Format.NO_VALUE.toLong()
            )
        }

        parent.skipBytes(4)
        val peakBitrate = parent.readUnsignedInt()
        val bitrate = parent.readUnsignedInt()

        // Start of the DecoderSpecificInfo.
        parent.skipBytes(1) // DecoderSpecificInfo tag
        val initializationDataSize = parseExpandableClassSize(parent)
        val initializationData = ByteArray(initializationDataSize)
        parent.readBytes(initializationData, 0, initializationDataSize)

        // Skipping zero values as unknown.
        return EsdsData(
            mimeType,  
            initializationData,  
            if (bitrate > 0) bitrate else Format.NO_VALUE.toLong(),  
            if (peakBitrate > 0) peakBitrate else Format.NO_VALUE.toLong()
        )
    }

    private fun parseBtrtFromParent(parent: ParsableByteArray, position: Int): BtrtData {
        parent.setPosition(position + Mp4Box.HEADER_SIZE)

        parent.skipBytes(4) // bufferSizeDB
        val maxBitrate = parent.readUnsignedInt()
        val avgBitrate = parent.readUnsignedInt()

        return BtrtData(avgBitrate, maxBitrate)
    }

    @Throws(ParserException::class)
    fun parseVideoExtendedUsageBox(
        parent: ParsableByteArray, position: Int, size: Int
    ): VexuData? {
        parent.setPosition(position + Mp4Box.HEADER_SIZE)
        var childPosition = parent.getPosition()
        var eyesData: EyesData? = null
        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childAtomSize = parent.readInt()
            checkContainerInput(childAtomSize > 0, "childAtomSize must be positive")
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_eyes) {
                eyesData = parseStereoViewBox(parent, childPosition, childAtomSize)
            }
            childPosition += childAtomSize
        }
        return if (eyesData == null) null else VexuData(eyesData)
    }

    @Throws(ParserException::class)
    private fun parseStereoViewBox(parent: ParsableByteArray, position: Int, size: Int): EyesData? {
        parent.setPosition(position + Mp4Box.HEADER_SIZE)
        var childPosition = parent.getPosition()
        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childAtomSize = parent.readInt()
            checkContainerInput(childAtomSize > 0, "childAtomSize must be positive")
            if (parent.readInt() == Mp4Box.TYPE_stri) {
                // The stri box extends FullBox that includes version (8 bits) and flags (24 bits).
                parent.skipBytes(4)
                val striInfo = parent.readUnsignedByte() and 0x0F
                return EyesData(
                    StriData(
                        ((striInfo and 0x01) == 0x01),
                        ((striInfo and 0x02) == 0x02),
                        ((striInfo and 0x08) == 0x08)
                    )
                )
            }
            childPosition += childAtomSize
        }
        return null
    }

    @Throws(ParserException::class)
    private fun parseSampleEntryEncryptionData(
        parent: ParsableByteArray, position: Int, size: Int
    ): Pair<Int?, TrackEncryptionBox?>? {
        var childPosition = parent.getPosition()
        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childAtomSize = parent.readInt()
            checkContainerInput(childAtomSize > 0, "childAtomSize must be positive")
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_sinf) {
                val result =
                    parseCommonEncryptionSinfFromParent(parent, childPosition, childAtomSize)
                if (result != null) {
                    return result
                }
            }
            childPosition += childAtomSize
        }
        return null
    }

    @Throws(ParserException::class)
    fun parseCommonEncryptionSinfFromParent(
        parent: ParsableByteArray, position: Int, size: Int
    ): Pair<Int?, TrackEncryptionBox?>? {
        var childPosition = position + Mp4Box.HEADER_SIZE
        var schemeInformationBoxPosition = C.INDEX_UNSET
        var schemeInformationBoxSize = 0
        var schemeType: String? = null
        var dataFormat: Int? = null
        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childAtomSize = parent.readInt()
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_frma) {
                dataFormat = parent.readInt()
            } else if (childAtomType == Mp4Box.TYPE_schm) {
                parent.skipBytes(4)
                // Common encryption scheme_type values are defined in ISO/IEC 23001-7:2016, section 4.1.
                schemeType = parent.readString(4)
            } else if (childAtomType == Mp4Box.TYPE_schi) {
                schemeInformationBoxPosition = childPosition
                schemeInformationBoxSize = childAtomSize
            }
            childPosition += childAtomSize
        }

        if (C.CENC_TYPE_cenc == schemeType
            || C.CENC_TYPE_cbc1 == schemeType
            || C.CENC_TYPE_cens == schemeType
            || C.CENC_TYPE_cbcs == schemeType
        ) {
            checkContainerInput(dataFormat != null, "frma atom is mandatory")
            checkContainerInput(
                schemeInformationBoxPosition != C.INDEX_UNSET, "schi atom is mandatory"
            )
            val encryptionBox =
                parseSchiFromParent(
                    parent, schemeInformationBoxPosition, schemeInformationBoxSize, schemeType
                )
            checkContainerInput(encryptionBox != null, "tenc atom is mandatory")
            return Pair.create(dataFormat, encryptionBox)
        } else {
            return null
        }
    }

    private fun parseSchiFromParent(
        parent: ParsableByteArray, position: Int, size: Int, schemeType: String?
    ): TrackEncryptionBox? {
        var childPosition = position + Mp4Box.HEADER_SIZE
        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childAtomSize = parent.readInt()
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_tenc) {
                val fullAtom = parent.readInt()
                val version = parseFullBoxVersion(fullAtom)
                parent.skipBytes(1) // reserved = 0.
                var defaultCryptByteBlock = 0
                var defaultSkipByteBlock = 0
                if (version == 0) {
                    parent.skipBytes(1) // reserved = 0.
                } else   {
                    val patternByte = parent.readUnsignedByte()
                    defaultCryptByteBlock = (patternByte and 0xF0) shr 4
                    defaultSkipByteBlock = patternByte and 0x0F
                }
                val defaultIsProtected = parent.readUnsignedByte() == 1
                val defaultPerSampleIvSize = parent.readUnsignedByte()
                val defaultKeyId = ByteArray(16)
                parent.readBytes(defaultKeyId, 0, defaultKeyId.size)
                var constantIv: ByteArray? = null
                if (defaultIsProtected && defaultPerSampleIvSize == 0) {
                    val constantIvSize = parent.readUnsignedByte()
                    constantIv = ByteArray(constantIvSize)
                    parent.readBytes(constantIv, 0, constantIvSize)
                }
                return TrackEncryptionBox(
                    defaultIsProtected,
                    schemeType,
                    defaultPerSampleIvSize,
                    defaultKeyId,
                    defaultCryptByteBlock,
                    defaultSkipByteBlock,
                    constantIv
                )
            }
            childPosition += childAtomSize
        }
        return null
    }

    private fun parseProjFromParent(
        parent: ParsableByteArray,
        position: Int,
        size: Int
    ): ByteArray? {
        var childPosition = position + Mp4Box.HEADER_SIZE
        while (childPosition - position < size) {
            parent.setPosition(childPosition)
            val childAtomSize = parent.readInt()
            val childAtomType = parent.readInt()
            if (childAtomType == Mp4Box.TYPE_proj) {
                return Arrays.copyOfRange(
                    parent.data,
                    childPosition,
                    childPosition + childAtomSize
                )
            }
            childPosition += childAtomSize
        }
        return null
    }

    private fun parseExpandableClassSize(data: ParsableByteArray): Int {
        var currentByte = data.readUnsignedByte()
        var size = currentByte and 0x7F
        while ((currentByte and 0x80) == 0x80) {
            currentByte = data.readUnsignedByte()
            size = (size shl 7) or (currentByte and 0x7F)
        }
        return size
    }

    private fun canApplyEditWithGaplessInfo(
        timestamps: LongArray, duration: Long, editStartTime: Long, editEndTime: Long
    ): Boolean {
        val lastIndex = timestamps.size - 1
        val latestDelayIndex = constrainValue(MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, lastIndex)
        val earliestPaddingIndex =
            constrainValue(timestamps.size - MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, lastIndex)
        return timestamps[0] <= editStartTime && editStartTime < timestamps[latestDelayIndex] && timestamps[earliestPaddingIndex] < editEndTime && editEndTime <= duration + EDIT_LIST_DURATION_TOLERANCE_TIMESCALE_UNITS
    }

    private class ChunkIterator(
        private val stsc: ParsableByteArray,
        private val chunkOffsets: ParsableByteArray,
        private val chunkOffsetsAreLongs: Boolean
    ) {
        val length: Int

        var index: Int
        var numSamples: Int = 0
        var offset: Long = 0

        private var nextSamplesPerChunkChangeIndex = 0
        private var remainingSamplesPerChunkChanges: Int

        init {
            chunkOffsets.setPosition(Mp4Box.FULL_HEADER_SIZE)
            length = chunkOffsets.readUnsignedIntToInt()
            stsc.setPosition(Mp4Box.FULL_HEADER_SIZE)
            remainingSamplesPerChunkChanges = stsc.readUnsignedIntToInt()
            checkContainerInput(stsc.readInt() == 1, "first_chunk must be 1")
            index = -1
        }

        fun moveNext(): Boolean {
            if (++index == length) {
                return false
            }
            offset =
                if (chunkOffsetsAreLongs)
                    chunkOffsets.readUnsignedLongToLong()
                else
                    chunkOffsets.readUnsignedInt()
            if (index == nextSamplesPerChunkChangeIndex) {
                numSamples = stsc.readUnsignedIntToInt()
                stsc.skipBytes(4) // Skip sample_description_index
                nextSamplesPerChunkChangeIndex =
                    if (--remainingSamplesPerChunkChanges > 0)
                        (stsc.readUnsignedIntToInt() - 1)
                    else
                        C.INDEX_UNSET
            }
            return true
        }
    }

    private class TkhdData(
         val id: Int,
         val duration: Long,
         val alternateGroup: Int,
         val rotationDegrees: Int,
         val mirrorHorizontal: Boolean,
         val width: Int,
         val height: Int
    )

    private class StsdData(numberOfEntries: Int) {
        val trackEncryptionBoxes: Array<TrackEncryptionBox?>

        var format: Format? = null
        var nalUnitLengthFieldLength: Int = 0
        var requiredSampleTransformation: @Track.Transformation Int

        init {
            trackEncryptionBoxes = arrayOfNulls<TrackEncryptionBox>(numberOfEntries)
            requiredSampleTransformation = Track.TRANSFORMATION_NONE
        }

        companion object {
            const val STSD_HEADER_SIZE: Int = 8
        }
    }

    private class EsdsData(
         val mimeType: String?,
         val initializationData: ByteArray?,
         val bitrate: Long,
         val peakBitrate: Long
    )

     class BtrtData( val avgBitrate: Long,  val maxBitrate: Long)

     class StriData(
         val hasLeftEyeView: Boolean,
         val hasRightEyeView: Boolean,
         val eyeViewsReversed: Boolean
    )

     class EyesData( val striData: StriData)

    private class MdhdData(
         val timescale: Long,
         val mediaDurationUs: Long,
         val language: String?
    )

     class VexuData( val eyesData: EyesData?) {
        fun hasBothEyeViews(): Boolean {
            return eyesData != null && eyesData.striData.hasLeftEyeView
                    && eyesData.striData.hasRightEyeView
        }
    }

    private interface SampleSizeBox {
        
        val sampleCount: Int

        val fixedSampleSize: Int

        fun readNextSampleSize(): Int
    }

    internal class StszSampleSizeBox(stszAtom: LeafBox, trackFormat: Format) : SampleSizeBox {
        override val fixedSampleSize: Int
        override val sampleCount: Int
        private val data: ParsableByteArray?

        init {
            data = stszAtom.data
            data!!.setPosition(Mp4Box.FULL_HEADER_SIZE)
            var fixedSampleSize = data.readUnsignedIntToInt()
            if (MimeTypes.AUDIO_RAW == trackFormat.sampleMimeType) {
                val pcmFrameSize =
                    getPcmFrameSize(trackFormat.pcmEncoding, trackFormat.channelCount)
                if (fixedSampleSize % pcmFrameSize != 0) {
                    // The sample size from the stsz box is inconsistent with the PCM encoding and channel
                    // count derived from the stsd box. Choose stsd box as source of truth
                    // [Internal ref: b/171627904].
                    w(
                        TAG,
                        ("Audio sample size mismatch. stsd sample size: "
                                + pcmFrameSize
                                + ", stsz sample size: "
                                + fixedSampleSize)
                    )
                    fixedSampleSize = pcmFrameSize
                }
            }
            this.fixedSampleSize = if (fixedSampleSize == 0) C.LENGTH_UNSET else fixedSampleSize
            sampleCount = data.readUnsignedIntToInt()
        }

        override fun readNextSampleSize(): Int {
            return if (fixedSampleSize == C.LENGTH_UNSET) data!!.readUnsignedIntToInt() else fixedSampleSize
        }
    }

    internal class Stz2SampleSizeBox(stz2Atom: LeafBox) : SampleSizeBox {
        private val data: ParsableByteArray?
        override val sampleCount: Int
        private val fieldSize: Int // Can be 4, 8, or 16.

        // Used only if fieldSize == 4.
        private var sampleIndex = 0
        private var currentByte = 0

        init {
            data = stz2Atom.data
            data!!.setPosition(Mp4Box.FULL_HEADER_SIZE)
            fieldSize = data.readUnsignedIntToInt() and 0x000000FF
            sampleCount = data.readUnsignedIntToInt()
        }

        override val fixedSampleSize: Int= C.LENGTH_UNSET

        override fun readNextSampleSize(): Int {
            if (fieldSize == 8) {
                return data!!.readUnsignedByte()
            } else if (fieldSize == 16) {
                return data!!.readUnsignedShort()
            } else {
                // fieldSize == 4.
                if ((sampleIndex++ % 2) == 0) {
                    // Read the next byte into our cached byte when we are reading the upper bits.
                    currentByte = data!!.readUnsignedByte()
                    // Read the upper bits from the byte and shift them to the lower 4 bits.
                    return (currentByte and 0xF0) shr 4
                } else {
                    // Mask out the upper 4 bits of the last byte we read.
                    return currentByte and 0x0F
                }
            }
        }
    }
}
