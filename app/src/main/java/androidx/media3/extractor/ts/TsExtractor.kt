
package androidx.media3.extractor.ts

import android.util.SparseArray
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.google.common.base.Preconditions

import java.io.IOException
import java.util.Arrays

class TsExtractor(
    private val mode: @Mode Int,
    private val extractorFlags: @Flags Int,
    private val subtitleParserFactory: SubtitleParser.Factory,
    timestampAdjuster: TimestampAdjuster,
    payloadReaderFactory: TsPayloadReader.Factory?,
    private val timestampSearchBytes: Int
) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [MODE_MULTI_PMT, MODE_SINGLE_PMT, MODE_HLS])
    annotation class Mode

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(flag = true, value = [FLAG_EMIT_RAW_SUBTITLE_DATA])
    annotation class Flags

    private val timestampAdjusters: MutableList<TimestampAdjuster>
    private val tsPacketBuffer: ParsableByteArray
    private val continuityCounters: SparseIntArray
    private val payloadReaderFactory: TsPayloadReader.Factory
    private val tsPayloadReaders: SparseArray<TsPayloadReader?> // Indexed by pid
    private val trackIds: SparseBooleanArray
    private val trackPids: SparseBooleanArray
    private val durationReader: TsDurationReader

    // Accessed only by the loading thread.
    private var tsBinarySearchSeeker:TsBinarySearchSeeker? = null
    private var output: ExtractorOutput
    private var remainingPmts = 0
    private var tracksEnded = false
    private var hasOutputSeekMap = false
    private var pendingSeekToStart = false
    private var id3Reader: TsPayloadReader? = null
    private var bytesSinceLastSync = 0
    private var pcrPid: Int

    @Deprecated("Use {@link #TsExtractor(SubtitleParser.Factory)} instead.")
    constructor() : this(
        MODE_SINGLE_PMT,  
        FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED,
        TimestampAdjuster(0),
        DefaultTsPayloadReaderFactory( 0),
        DEFAULT_TIMESTAMP_SEARCH_BYTES
    )

    constructor(subtitleParserFactory: SubtitleParser.Factory) : this(
        MODE_SINGLE_PMT,  
        0,
        subtitleParserFactory,
        TimestampAdjuster(0),
        DefaultTsPayloadReaderFactory( 0),
        DEFAULT_TIMESTAMP_SEARCH_BYTES
    )

    constructor(extractorFlags: @Flags Int, subtitleParserFactory: SubtitleParser.Factory) : this(
        MODE_SINGLE_PMT,
        extractorFlags,
        subtitleParserFactory,
        TimestampAdjuster(0),
        DefaultTsPayloadReaderFactory( 0),
        DEFAULT_TIMESTAMP_SEARCH_BYTES
    )

    @Deprecated(
        """Use {@link #TsExtractor(int, int, SubtitleParser.Factory, TimestampAdjuster,
   *     TsPayloadReader.Factory, int)} instead."""
    )
    constructor(defaultTsPayloadReaderFlags: @DefaultTsPayloadReaderFactory.Flags Int) : this(
        MODE_SINGLE_PMT,
        FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED,
        TimestampAdjuster(0),
        DefaultTsPayloadReaderFactory(defaultTsPayloadReaderFlags),
        DEFAULT_TIMESTAMP_SEARCH_BYTES
    )

    @Deprecated(
        """Use {@link #TsExtractor(int, int, SubtitleParser.Factory, TimestampAdjuster,
   *     TsPayloadReader.Factory, int)} instead."""
    )
    constructor(
        mode: @Mode Int,
        defaultTsPayloadReaderFlags: @DefaultTsPayloadReaderFactory.Flags Int,
        timestampSearchBytes: Int
    ) : this(
        mode,
        FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED,
        TimestampAdjuster(0),
        DefaultTsPayloadReaderFactory(defaultTsPayloadReaderFlags),
        timestampSearchBytes
    )

    @Deprecated(
        """Use {@link #TsExtractor(int, int, SubtitleParser.Factory, TimestampAdjuster,
   *     TsPayloadReader.Factory, int)} instead."""
    )
    constructor(
        mode: @Mode Int,
        timestampAdjuster: TimestampAdjuster,
        payloadReaderFactory: TsPayloadReader.Factory?
    ) : this(
        mode,
        FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED,
        timestampAdjuster,
        payloadReaderFactory,
        DEFAULT_TIMESTAMP_SEARCH_BYTES
    )

    @Deprecated(
        """Use {@link #TsExtractor(int, int, SubtitleParser.Factory, TimestampAdjuster,
   *     TsPayloadReader.Factory, int)} instead."""
    )
    constructor(
        mode: @Mode Int,
        timestampAdjuster: TimestampAdjuster,
        payloadReaderFactory: TsPayloadReader.Factory?,
        timestampSearchBytes: Int
    ) : this(
        mode,
        FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED,
        timestampAdjuster,
        payloadReaderFactory,
        timestampSearchBytes
    )

    init {
        this.payloadReaderFactory =payloadReaderFactory!!
        if (mode == MODE_SINGLE_PMT || mode == MODE_HLS) {
            timestampAdjusters = mutableListOf<TimestampAdjuster>(timestampAdjuster)
        } else {
            timestampAdjusters = ArrayList<TimestampAdjuster>()
            timestampAdjusters.add(timestampAdjuster)
        }
        tsPacketBuffer = ParsableByteArray(ByteArray(BUFFER_SIZE), 0)
        trackIds = SparseBooleanArray()
        trackPids = SparseBooleanArray()
        tsPayloadReaders = SparseArray<TsPayloadReader?>()
        continuityCounters = SparseIntArray()
        durationReader = TsDurationReader(timestampSearchBytes)
        output = ExtractorOutput.PLACEHOLDER
        pcrPid = -1
        resetPayloadReaders()
    }

    // Extractor implementation.
    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        val buffer = tsPacketBuffer.data
        input.peekFully(buffer, 0, TS_PACKET_SIZE * SNIFF_TS_PACKET_COUNT)
        for (startPosCandidate in 0..<TS_PACKET_SIZE) {
            // Try to identify at least SNIFF_TS_PACKET_COUNT packets starting with TS_SYNC_BYTE.
            var isSyncBytePatternCorrect = true
            for (i in 0..<SNIFF_TS_PACKET_COUNT) {
                if (buffer[startPosCandidate + i * TS_PACKET_SIZE].toInt() != TS_SYNC_BYTE) {
                    isSyncBytePatternCorrect = false
                    break
                }
            }
            if (isSyncBytePatternCorrect) {
                input.skipFully(startPosCandidate)
                return true
            }
        }
        return false
    }

    override fun init(output: ExtractorOutput) {
        this.output =
            if ((extractorFlags and FLAG_EMIT_RAW_SUBTITLE_DATA) == 0)
                SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            else
                output
    }

    override fun seek(position: Long, timeUs: Long) {
        Preconditions.checkState(mode != MODE_HLS)
        val timestampAdjustersCount = timestampAdjusters.size
        for (i in 0..<timestampAdjustersCount) {
            val timestampAdjuster: TimestampAdjuster = timestampAdjusters[i]
            // If the timestamp adjuster has not yet established a timestamp offset, we need to reset its
            // expected first sample timestamp to be the new seek position. Without this, the timestamp
            // adjuster would incorrectly establish its timestamp offset assuming that the first sample
            // after this seek corresponds to the start of the stream (or a previous seek position, if
            // there was one).
            var resetTimestampAdjuster = timestampAdjuster.timestampOffsetUs === C.TIME_UNSET
            if (!resetTimestampAdjuster) {
                val adjusterFirstSampleTimestampUs: Long =
                    timestampAdjuster.getFirstSampleTimestampUs()
                // Also reset the timestamp adjuster if its offset was calculated based on a non-zero
                // position in the stream (other than the position being seeked to), since in this case the
                // offset may not be accurate.
                resetTimestampAdjuster =
                    adjusterFirstSampleTimestampUs != C.TIME_UNSET && adjusterFirstSampleTimestampUs != 0L && adjusterFirstSampleTimestampUs != timeUs
            }
            if (resetTimestampAdjuster) {
                timestampAdjuster.reset(timeUs)
            }
        }
        if (timeUs != 0L && tsBinarySearchSeeker != null) {
            tsBinarySearchSeeker!!.setSeekTargetUs(timeUs)
        }
        tsPacketBuffer.reset( 0)
        continuityCounters.clear()
        for (i in 0..<tsPayloadReaders.size()) {
            tsPayloadReaders.valueAt(i)!!.seek()
        }
        bytesSinceLastSync = 0
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        val inputLength: Long = input.length
        val isModeHls = mode == MODE_HLS
        if (tracksEnded) {
            val canReadDuration = inputLength != C.LENGTH_UNSET.toLong() && !isModeHls
            if (canReadDuration && !durationReader.isDurationReadFinished) {
                return durationReader.readDuration(input, seekPosition, pcrPid)
            }
            maybeOutputSeekMap(inputLength)

            if (pendingSeekToStart) {
                pendingSeekToStart = false
                seek( 0,  0)
                if (input.position != 0L) {
                    seekPosition.position = 0
                    return Extractor.RESULT_SEEK
                }
            }

            if (tsBinarySearchSeeker != null && tsBinarySearchSeeker!!.isSeeking) {
                return tsBinarySearchSeeker!!.handlePendingSeek(input, seekPosition)
            }
        }

        if (!fillBufferWithAtLeastOnePacket(input)) {
            // Send a synthesized empty pusi to allow for packetFinished to be triggered on the last unit.
            for (i in 0..<tsPayloadReaders.size()) {
                val payloadReader = tsPayloadReaders.valueAt(i)
                if (payloadReader is PesReader) {
                    val pesReader = payloadReader
                    if (pesReader.canConsumeSynthesizedEmptyPusi(isModeHls)) {
                        pesReader.consume(
                            ParsableByteArray(),
                            TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR
                        )
                    }
                }
            }
            return Extractor.RESULT_END_OF_INPUT
        }

        val endOfPacket = findEndOfFirstTsPacketInBuffer()
        val limit = tsPacketBuffer.limit()
        if (endOfPacket > limit) {
            return Extractor.RESULT_CONTINUE
        }

        var packetHeaderFlags: @TsPayloadReader.Flags Int = 0

        // Note: See ISO/IEC 13818-1, section 2.4.3.2 for details of the header format.
        val tsPacketHeader = tsPacketBuffer.readInt()
        if ((tsPacketHeader and 0x800000) != 0) { // transport_error_indicator
            // There are uncorrectable errors in this packet.
            tsPacketBuffer.setPosition(endOfPacket)
            return Extractor.RESULT_CONTINUE
        }
        packetHeaderFlags =
            packetHeaderFlags or if ((tsPacketHeader and 0x400000) != 0) TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR else 0
        // Ignoring transport_priority (tsPacketHeader & 0x200000)
        val pid = (tsPacketHeader and 0x1FFF00) shr 8
        // Ignoring transport_scrambling_control (tsPacketHeader & 0xC0)
        val adaptationFieldExists = (tsPacketHeader and 0x20) != 0
        val payloadExists = (tsPacketHeader and 0x10) != 0

        val payloadReader = if (payloadExists) tsPayloadReaders.get(pid) else null
        if (payloadReader == null) {
            tsPacketBuffer.setPosition(endOfPacket)
            return Extractor.RESULT_CONTINUE
        }

        // Discontinuity check.
        if (mode != MODE_HLS) {
            val continuityCounter = tsPacketHeader and 0xF
            val previousCounter = continuityCounters.get(pid, continuityCounter - 1)
            continuityCounters.put(pid, continuityCounter)
            if (previousCounter == continuityCounter) {
                // Duplicate packet found.
                tsPacketBuffer.setPosition(endOfPacket)
                return Extractor.RESULT_CONTINUE
            } else if (continuityCounter != ((previousCounter + 1) and 0xF)) {
                // Discontinuity found.
                payloadReader.seek()
            }
        }

        // Skip the adaptation field.
        if (adaptationFieldExists) {
            val adaptationFieldLength = tsPacketBuffer.readUnsignedByte()
            val adaptationFieldFlags = tsPacketBuffer.readUnsignedByte()

            packetHeaderFlags =
                packetHeaderFlags or if ((adaptationFieldFlags and 0x40) != 0 // random_access_indicator.
                )
                    TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR
                else
                    0
            tsPacketBuffer.skipBytes(adaptationFieldLength - 1 )
        }

        // Read the payload.
        val wereTracksEnded = tracksEnded
        if (shouldConsumePacketPayload(pid)) {
            tsPacketBuffer.setLimit(endOfPacket)
            payloadReader.consume(tsPacketBuffer, packetHeaderFlags)
            tsPacketBuffer.setLimit(limit)
        }
        if (mode != MODE_HLS && !wereTracksEnded && tracksEnded && inputLength != C.LENGTH_UNSET.toLong()) {
            // We have read all tracks from all PMTs in this non-live stream. Now seek to the beginning
            // and read again to make sure we output all media, including any contained in packets prior
            // to those containing the track information.
            pendingSeekToStart = true
        }

        tsPacketBuffer.setPosition(endOfPacket)
        return Extractor.RESULT_CONTINUE
    }

    // Internals.
    private fun maybeOutputSeekMap(inputLength: Long) {
        if (!hasOutputSeekMap) {
            hasOutputSeekMap = true
            if (durationReader.durationUs != C.TIME_UNSET) {
                tsBinarySearchSeeker =
                    TsBinarySearchSeeker(
                        durationReader.getPcrTimestampAdjuster(),
                        durationReader.durationUs,
                        inputLength,
                        pcrPid,
                        timestampSearchBytes
                    )
                output.seekMap(tsBinarySearchSeeker!!.getSeekMap())
            } else {
                output.seekMap(SeekMap.Unseekable(durationReader.durationUs))
            }
        }
    }

    @Throws(IOException::class)
    private fun fillBufferWithAtLeastOnePacket(input: ExtractorInput): Boolean {
        val data = tsPacketBuffer.data
        // Shift bytes to the start of the buffer if there isn't enough space left at the end.
        if (BUFFER_SIZE - tsPacketBuffer.getPosition() < TS_PACKET_SIZE) {
            val bytesLeft = tsPacketBuffer.bytesLeft()
            if (bytesLeft > 0) {
                System.arraycopy(data, tsPacketBuffer.getPosition(), data, 0, bytesLeft)
            }
            tsPacketBuffer.reset(data, bytesLeft)
        }
        // Read more bytes until we have at least one packet.
        while (tsPacketBuffer.bytesLeft() < TS_PACKET_SIZE) {
            val limit = tsPacketBuffer.limit()
            val read: Int = input.read(data, limit, BUFFER_SIZE - limit)
            if (read == C.RESULT_END_OF_INPUT) {
                return false
            }
            tsPacketBuffer.setLimit(limit + read)
        }
        return true
    }

    @Throws(ParserException::class)
    private fun findEndOfFirstTsPacketInBuffer(): Int {
        val searchStart = tsPacketBuffer.getPosition()
        val limit = tsPacketBuffer.limit()
        val syncBytePosition =
            TsUtil.findSyncBytePosition(tsPacketBuffer.data, searchStart, limit)
        // Discard all bytes before the sync byte.
        // If sync byte is not found, this means discard the whole buffer.
        tsPacketBuffer.setPosition(syncBytePosition)
        val endOfPacket: Int = syncBytePosition + TS_PACKET_SIZE
        if (endOfPacket > limit) {
            bytesSinceLastSync += syncBytePosition - searchStart
            if (mode == MODE_HLS && bytesSinceLastSync > TS_PACKET_SIZE * 2) {
                throw ParserException.createForMalformedContainer(
                    "Cannot find sync byte. Most likely not a Transport Stream.",  null
                )
            }
        } else {
            // We have found a packet within the buffer.
            bytesSinceLastSync = 0
        }
        return endOfPacket
    }

    private fun shouldConsumePacketPayload(packetPid: Int): Boolean {
        return mode == MODE_HLS || tracksEnded
                || !trackPids.get(packetPid,  false) // It's a PSI packet
    }

    private fun resetPayloadReaders() {
        trackIds.clear()
        tsPayloadReaders.clear()
        val initialPayloadReaders =
            payloadReaderFactory.createInitialPayloadReaders()
        val initialPayloadReadersSize = initialPayloadReaders.size()
        for (i in 0..<initialPayloadReadersSize) {
            tsPayloadReaders.put(initialPayloadReaders.keyAt(i), initialPayloadReaders.valueAt(i))
        }
        tsPayloadReaders.put(TS_PAT_PID, SectionReader(PatReader()))
        id3Reader = null
    }

    private inner class PatReader : SectionPayloadReader {
        private val patScratch: ParsableBitArray

        init {
            patScratch = ParsableBitArray(ByteArray(4))
        }

        override fun init(
            timestampAdjuster: TimestampAdjuster,
            extractorOutput: ExtractorOutput,
            idGenerator: TsPayloadReader.TrackIdGenerator
        ) {
            // Do nothing.
        }

        override fun consume(sectionData: ParsableByteArray) {
            val tableId: Int = sectionData.readUnsignedByte()
            if (tableId != 0x00 ) {
                // See ISO/IEC 13818-1, section 2.4.4.4 for more information on table id assignment.
                return
            }
            // section_syntax_indicator(1), '0'(1), reserved(2), section_length(4)
            val secondHeaderByte: Int = sectionData.readUnsignedByte()
            if ((secondHeaderByte and 0x80) == 0) {
                // section_syntax_indicator must be 1. See ISO/IEC 13818-1, section 2.4.4.5.
                return
            }
            // section_length(8), transport_stream_id (16), reserved (2), version_number (5),
            // current_next_indicator (1), section_number (8), last_section_number (8)
            sectionData.skipBytes(6)

            val programCount: Int = sectionData.bytesLeft() / 4
            for (i in 0..<programCount) {
                sectionData.readBytes(patScratch, 4)
                val programNumber = patScratch.readBits(16)
                patScratch.skipBits(3) // reserved (3)
                if (programNumber == 0) {
                    patScratch.skipBits(13) // network_PID (13)
                } else {
                    val pid = patScratch.readBits(13)
                    if (tsPayloadReaders.get(pid) == null) {
                        tsPayloadReaders.put(pid, SectionReader(PmtReader(pid)))
                        remainingPmts++
                    }
                }
            }
            if (mode != MODE_HLS) {
                tsPayloadReaders.remove(TS_PAT_PID)
            }
        }
    }

    private inner class PmtReader(pid: Int) : SectionPayloadReader {
        private val pmtScratch: ParsableBitArray
        private val trackIdToReaderScratch: SparseArray<TsPayloadReader?>
        private val trackIdToPidScratch: SparseIntArray
        private val pid: Int

        init {
            pmtScratch = ParsableBitArray(ByteArray(5))
            trackIdToReaderScratch = SparseArray<TsPayloadReader?>()
            trackIdToPidScratch = SparseIntArray()
            this.pid = pid
        }

        override fun init(
            timestampAdjuster: TimestampAdjuster,
            extractorOutput: ExtractorOutput,
            idGenerator: TsPayloadReader.TrackIdGenerator
        ) {
            // Do nothing.
        }

        override fun consume(sectionData: ParsableByteArray) {
            val tableId: Int = sectionData.readUnsignedByte()
            if (tableId != 0x02 ) {
                // See ISO/IEC 13818-1, section 2.4.4.4 for more information on table id assignment.
                return
            }
            // TimestampAdjuster assignment.
            val timestampAdjuster: TimestampAdjuster?
            if (mode == MODE_SINGLE_PMT || mode == MODE_HLS || remainingPmts == 1) {
                timestampAdjuster = timestampAdjusters[0]
            } else {
                timestampAdjuster =
                    TimestampAdjuster(timestampAdjusters[0].getFirstSampleTimestampUs())
                timestampAdjusters.add(timestampAdjuster)
            }

            // section_syntax_indicator(1), '0'(1), reserved(2), section_length(4)
            val secondHeaderByte: Int = sectionData.readUnsignedByte()
            if ((secondHeaderByte and 0x80) == 0) {
                // section_syntax_indicator must be 1. See ISO/IEC 13818-1, section 2.4.4.9.
                return
            }
            // section_length(8)
            sectionData.skipBytes(1)
            val programNumber: Int = sectionData.readUnsignedShort()

            // Skip 3 bytes (24 bits), including:
            // reserved (2), version_number (5), current_next_indicator (1), section_number (8),
            // last_section_number (8)
            sectionData.skipBytes(3)

            sectionData.readBytes(pmtScratch, 2)
            // reserved (3), PCR_PID (13)
            pmtScratch.skipBits(3)
            pcrPid = pmtScratch.readBits(13)

            // Read program_info_length.
            sectionData.readBytes(pmtScratch, 2)
            pmtScratch.skipBits(4)
            val programInfoLength = pmtScratch.readBits(12)

            // Skip the descriptors.
            sectionData.skipBytes(programInfoLength)

            if (mode == MODE_HLS && id3Reader == null) {
                // Setup an ID3 track regardless of whether there's a corresponding entry, in case one
                // appears intermittently during playback. See [Internal: b/20261500].
                val id3EsInfo: TsPayloadReader.EsInfo =
                    TsPayloadReader.EsInfo(
                        TS_STREAM_TYPE_ID3,
                        null,
                        TsPayloadReader.EsInfo.AUDIO_TYPE_UNDEFINED,
                        null,
                        Util.EMPTY_BYTE_ARRAY
                    )
                id3Reader = payloadReaderFactory.createPayloadReader(TS_STREAM_TYPE_ID3, id3EsInfo)
                if (id3Reader != null) {
                    id3Reader!!.init(
                        timestampAdjuster,
                        output,
                        TsPayloadReader.TrackIdGenerator(
                            programNumber,
                            TS_STREAM_TYPE_ID3,
                            MAX_PID_PLUS_ONE
                        )
                    )
                }
            }

            trackIdToReaderScratch.clear()
            trackIdToPidScratch.clear()
            var remainingEntriesLength: Int = sectionData.bytesLeft()
            while (remainingEntriesLength > 0) {
                sectionData.readBytes(pmtScratch, 5)
                var streamType = pmtScratch.readBits(8)
                pmtScratch.skipBits(3) // reserved
                val elementaryPid = pmtScratch.readBits(13)
                pmtScratch.skipBits(4) // reserved
                val esInfoLength = pmtScratch.readBits(12) // ES_info_length.
                val esInfo: TsPayloadReader.EsInfo = readEsInfo(sectionData, esInfoLength)
                if (streamType == 0x06 || streamType == 0x05) {
                    streamType = esInfo.streamType
                }
                remainingEntriesLength -= esInfoLength + 5

                val trackId = if (mode == MODE_HLS) streamType else elementaryPid
                if (trackIds.get(trackId)) {
                    continue
                }

                val reader =
                    if (mode == MODE_HLS && streamType == TS_STREAM_TYPE_ID3)
                        id3Reader
                    else
                        payloadReaderFactory.createPayloadReader(streamType, esInfo)
                if (mode != MODE_HLS
                    || elementaryPid < trackIdToPidScratch.get(trackId, MAX_PID_PLUS_ONE)
                ) {
                    trackIdToPidScratch.put(trackId, elementaryPid)
                    trackIdToReaderScratch.put(trackId, reader)
                }
            }

            val trackIdCount = trackIdToPidScratch.size()
            for (i in 0..<trackIdCount) {
                val trackId = trackIdToPidScratch.keyAt(i)
                val trackPid = trackIdToPidScratch.valueAt(i)
                trackIds.put(trackId, true)
                trackPids.put(trackPid, true)
                val reader = trackIdToReaderScratch.valueAt(i)
                if (reader != null) {
                    if (reader !== id3Reader) {
                        reader.init(
                            timestampAdjuster,
                            output,
                            TsPayloadReader.TrackIdGenerator(
                                programNumber,
                                trackId,
                                MAX_PID_PLUS_ONE
                            )
                        )
                    }
                    tsPayloadReaders.put(trackPid, reader)
                }
            }

            if (mode == MODE_HLS) {
                if (!tracksEnded) {
                    output.endTracks()
                    remainingPmts = 0
                    tracksEnded = true
                }
            } else {
                tsPayloadReaders.remove(pid)
                remainingPmts = if (mode == MODE_SINGLE_PMT) 0 else remainingPmts - 1
                if (remainingPmts == 0) {
                    output.endTracks()
                    tracksEnded = true
                }
            }
        }

        fun readEsInfo(data: ParsableByteArray, length: Int): TsPayloadReader.EsInfo {
            val descriptorsStartPosition: Int = data.getPosition()
            val descriptorsEndPosition = descriptorsStartPosition + length
            var streamType = -1
            var language: String? = null
            var audioType: @TsPayloadReader.EsInfo.AudioType Int = TsPayloadReader.EsInfo.AUDIO_TYPE_UNDEFINED
            var dvbSubtitleInfos: MutableList<TsPayloadReader.DvbSubtitleInfo?>? = null
            while (data.getPosition() < descriptorsEndPosition) {
                val descriptorTag: Int = data.readUnsignedByte()
                val descriptorLength: Int = data.readUnsignedByte()
                val positionOfNextDescriptor: Int = data.getPosition() + descriptorLength
                if (positionOfNextDescriptor > descriptorsEndPosition) {
                    // Descriptor claims to extend past the end position. Skip it.
                    break
                }
                if (descriptorTag == TS_PMT_DESC_REGISTRATION) { // registration_descriptor
                    val formatIdentifier: Long = data.readUnsignedInt()
                    if (formatIdentifier == AC3_FORMAT_IDENTIFIER) {
                        streamType = TS_STREAM_TYPE_AC3
                    } else if (formatIdentifier == E_AC3_FORMAT_IDENTIFIER) {
                        streamType = TS_STREAM_TYPE_E_AC3
                    } else if (formatIdentifier == AC4_FORMAT_IDENTIFIER) {
                        streamType = TS_STREAM_TYPE_AC4
                    } else if (formatIdentifier == HEVC_FORMAT_IDENTIFIER) {
                        streamType = TS_STREAM_TYPE_H265
                    }
                } else if (descriptorTag == TS_PMT_DESC_AC3) { // AC-3_descriptor in DVB (ETSI EN 300 468)
                    streamType = TS_STREAM_TYPE_AC3
                } else if (descriptorTag == TS_PMT_DESC_EAC3) { // enhanced_AC-3_descriptor
                    streamType = TS_STREAM_TYPE_E_AC3
                } else if (descriptorTag == TS_PMT_DESC_DVB_EXT) {
                    // Extension descriptor in DVB (ETSI EN 300 468).
                    val descriptorTagExt: Int = data.readUnsignedByte()
                    if (descriptorTagExt == TS_PMT_DESC_DVB_EXT_AC4) {
                        // AC-4_descriptor in DVB (ETSI EN 300 468).
                        streamType = TS_STREAM_TYPE_AC4
                    } else if (descriptorTagExt == TS_PMT_DESC_DVB_EXT_DTS_HD) {
                        // DTS-HD descriptor in DVB (ETSI EN 300 468).
                        streamType = TS_STREAM_TYPE_DTS_HD
                    } else if (descriptorTagExt == TS_PMT_DESC_DVB_EXT_DTS_UHD) {
                        // DTS-UHD descriptor in DVB (ETSI EN 300 468).
                        streamType = TS_STREAM_TYPE_DTS_UHD
                    }
                } else if (descriptorTag == TS_PMT_DESC_DTS) { // DTS_descriptor
                    streamType = TS_STREAM_TYPE_DTS
                } else if (descriptorTag == TS_PMT_DESC_ISO639_LANG) {
                    language = data.readString(3).trim { it <= ' ' }
                    audioType = data.readUnsignedByte()
                } else if (descriptorTag == TS_PMT_DESC_DVBSUBS) {
                    streamType = TS_STREAM_TYPE_DVBSUBS
                    dvbSubtitleInfos = ArrayList<TsPayloadReader.DvbSubtitleInfo?>()
                    while (data.getPosition() < positionOfNextDescriptor) {
                        val dvbLanguage = data.readString(3).trim { it <= ' ' }
                        val dvbSubtitlingType: Int = data.readUnsignedByte()
                        val initializationData = ByteArray(4)
                        data.readBytes(initializationData, 0, 4)
                        dvbSubtitleInfos.add(
                            TsPayloadReader.DvbSubtitleInfo(
                                dvbLanguage,
                                dvbSubtitlingType,
                                initializationData
                            )
                        )
                    }
                } else if (descriptorTag == TS_PMT_DESC_AIT) {
                    streamType = TS_STREAM_TYPE_AIT
                }
                // Skip unused bytes of current descriptor.
                data.skipBytes(positionOfNextDescriptor - data.getPosition())
            }
            data.setPosition(descriptorsEndPosition)
            return TsPayloadReader.EsInfo(
                streamType,
                language,
                audioType,
                dvbSubtitleInfos,
                Arrays.copyOfRange(data.data, descriptorsStartPosition, descriptorsEndPosition)
            )
        }

        private  val TS_PMT_DESC_REGISTRATION = 0x05
        private  val TS_PMT_DESC_ISO639_LANG = 0x0A
        private  val TS_PMT_DESC_AC3 = 0x6A
        private  val TS_PMT_DESC_AIT = 0x6F
        private  val TS_PMT_DESC_EAC3 = 0x7A
        private  val TS_PMT_DESC_DTS = 0x7B
        private  val TS_PMT_DESC_DVB_EXT = 0x7F
        private  val TS_PMT_DESC_DVBSUBS = 0x59

        private  val TS_PMT_DESC_DVB_EXT_AC4 = 0x15
        private  val TS_PMT_DESC_DVB_EXT_DTS_HD = 0x0E
        private  val TS_PMT_DESC_DVB_EXT_DTS_UHD = 0x21
    }

    companion object {
        
        fun newFactory(subtitleParserFactory: SubtitleParser.Factory?): ExtractorsFactory {
            return object :ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> {
return arrayOf<Extractor>(
                        TsExtractor(
                            subtitleParserFactory!!
                        )
                    )
                }
            }
        }

        const val MODE_MULTI_PMT: Int = 0

        const val MODE_SINGLE_PMT: Int = 1

        const val MODE_HLS: Int = 2

        const val FLAG_EMIT_RAW_SUBTITLE_DATA: Int = 1

        @Deprecated("Use {@link #newFactory(SubtitleParser.Factory)} instead.")
        val FACTORY: ExtractorsFactory = object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(
                    TsExtractor(FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)
                )
            }
        }

        const val TS_PACKET_SIZE: Int = 188
        val DEFAULT_TIMESTAMP_SEARCH_BYTES: Int = 600 * TS_PACKET_SIZE

        const val TS_STREAM_TYPE_MPA: Int = 0x03
        const val TS_STREAM_TYPE_MPA_LSF: Int = 0x04
        const val TS_STREAM_TYPE_AAC_ADTS: Int = 0x0F
        const val TS_STREAM_TYPE_AAC_LATM: Int = 0x11
        const val TS_STREAM_TYPE_AC3: Int = 0x81
        const val TS_STREAM_TYPE_DTS: Int = 0x8A
        const val TS_STREAM_TYPE_HDMV_DTS: Int = 0x82
        const val TS_STREAM_TYPE_E_AC3: Int = 0x87
        const val TS_STREAM_TYPE_AC4: Int = 0xAC // DVB/ATSC AC-4 Descriptor
        const val TS_STREAM_TYPE_H262: Int = 0x02
        const val TS_STREAM_TYPE_H263: Int = 0x10 // MPEG-4 Part 2 and H.263
        const val TS_STREAM_TYPE_H264: Int = 0x1B
        const val TS_STREAM_TYPE_H265: Int = 0x24
        const val TS_STREAM_TYPE_MHAS: Int = 0x2D
        const val TS_STREAM_TYPE_ID3: Int = 0x15
        const val TS_STREAM_TYPE_SPLICE_INFO: Int = 0x86
        const val TS_STREAM_TYPE_DVBSUBS: Int = 0x59
        const val TS_STREAM_TYPE_DTS_HD: Int = 0x88 // As per ATSC Code Point Registry
        const val TS_STREAM_TYPE_DTS_UHD: Int = 0x8B

        // Stream types that aren't defined by the MPEG-2 TS specification.
        const val TS_STREAM_TYPE_DC2_H262: Int = 0x80
        const val TS_STREAM_TYPE_AIT: Int = 0x101

        const val TS_SYNC_BYTE: Int = 0x47 // First byte of each TS packet.

        private const val TS_PAT_PID = 0
        private const val MAX_PID_PLUS_ONE = 0x2000

        private const val AC3_FORMAT_IDENTIFIER: Long = 0x41432d33
        private const val E_AC3_FORMAT_IDENTIFIER: Long = 0x45414333
        private const val AC4_FORMAT_IDENTIFIER: Long = 0x41432d34
        private const val HEVC_FORMAT_IDENTIFIER: Long = 0x48455643

        private val BUFFER_SIZE: Int = TS_PACKET_SIZE * 50
        private const val SNIFF_TS_PACKET_COUNT = 5
    }
}
