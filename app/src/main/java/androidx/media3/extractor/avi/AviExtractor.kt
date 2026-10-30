package androidx.media3.extractor.avi

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.allSamplesAreSyncSamples
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.NoOpExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekMap.Unseekable
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.google.common.base.Preconditions

import java.io.IOException
import kotlin.collections.toTypedArray
import kotlin.math.max

class AviExtractor(
    extractorFlags: @Flags Int,
    private val subtitleParserFactory: SubtitleParser.Factory
) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_SKIPPING_TO_HDRL, STATE_READING_HDRL_HEADER, STATE_READING_HDRL_BODY, STATE_FINDING_MOVI_HEADER, STATE_FINDING_IDX1_HEADER, STATE_READING_IDX1_BODY, STATE_READING_SAMPLES
        ]
    )
    private annotation class State

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(flag = true, value = [FLAG_EMIT_RAW_SUBTITLE_DATA])
    annotation class Flags

    private val scratch: ParsableByteArray
    private val chunkHeaderHolder: ChunkHeaderHolder
    private val parseSubtitlesDuringExtraction: Boolean

    private var state: @State Int = 0
    private var extractorOutput: ExtractorOutput
    private var aviHeader:AviMainHeaderChunk? = null
    private var durationUs: Long
    private var chunkReaders: Array<ChunkReader>

    private var pendingReposition: Long = 0
    private var currentChunkReader: ChunkReader? = null
    private var hdrlSize: Int
    private var moviStart: Long
    private var moviEnd: Long
    private var idx1BodySize = 0
    private var seekMapHasBeenOutput = false

    @Deprecated("Use {@link #AviExtractor(int, SubtitleParser.Factory)} instead.")
    constructor() : this(FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)

    init {
        parseSubtitlesDuringExtraction = (extractorFlags and FLAG_EMIT_RAW_SUBTITLE_DATA) == 0
        scratch = ParsableByteArray( 12)
        chunkHeaderHolder = ChunkHeaderHolder()
        extractorOutput = NoOpExtractorOutput()
        chunkReaders = emptyArray()
        moviStart = C.INDEX_UNSET.toLong()
        moviEnd = C.INDEX_UNSET.toLong()
        hdrlSize = C.LENGTH_UNSET
        durationUs = C.TIME_UNSET
    }

    // Extractor implementation.
    override fun init(output: ExtractorOutput) {
        this.state = STATE_SKIPPING_TO_HDRL
        this.extractorOutput =
            (if (parseSubtitlesDuringExtraction)
                SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            else
                output)!!
        pendingReposition = C.INDEX_UNSET.toLong()
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        input.peekFully(scratch.data,  0,  12)
        scratch.setPosition(0)
        if (scratch.readLittleEndianInt() != FOURCC_RIFF) {
            return false
        }
        scratch.skipBytes(4) // Skip the RIFF chunk length.
        return scratch.readLittleEndianInt() == FOURCC_AVI_
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (resolvePendingReposition(input, seekPosition)) {
            return Extractor.RESULT_SEEK
        }
        when (state) {
            STATE_SKIPPING_TO_HDRL -> {
                // Check for RIFF and AVI fourcc's just in case the caller did not sniff, in order to
                // provide a meaningful error if the input is not an AVI file.
                if (sniff(input)) {
                    input.skipFully( 12)
                } else {
                    throw ParserException.createForMalformedContainer( 
                        "AVI Header List not found",  null
                    )
                }
                state = STATE_READING_HDRL_HEADER
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_HDRL_HEADER -> {
                input.readFully(scratch.data,  0,  12)
                scratch.setPosition(0)
                chunkHeaderHolder.populateWithListHeaderFrom(scratch)
                if (chunkHeaderHolder.listType != FOURCC_hdrl) {
                    throw ParserException.createForMalformedContainer( 
                        "hdrl expected, found: " + chunkHeaderHolder.listType,  
                        null
                    )
                }
                hdrlSize = chunkHeaderHolder.size
                state = STATE_READING_HDRL_BODY
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_HDRL_BODY -> {
                // hdrlSize includes the LIST type (hdrl), so we subtract 4 to the size.
                val bytesToRead = hdrlSize - 4
                val hdrlBody = ParsableByteArray(bytesToRead)
                input.readFully(hdrlBody.data,  0, bytesToRead)
                parseHdrlBody(hdrlBody)
                state = STATE_FINDING_MOVI_HEADER
                return Extractor.RESULT_CONTINUE
            }

            STATE_FINDING_MOVI_HEADER -> {
                if (moviStart != C.INDEX_UNSET.toLong() && input.position !== moviStart) {
                    pendingReposition = moviStart
                    return Extractor.RESULT_CONTINUE
                }
                input.peekFully(scratch.data,  0,  12)
                input.resetPeekPosition()
                scratch.setPosition(0)
                chunkHeaderHolder.populateFrom(scratch)
                val listType = scratch.readLittleEndianInt()
                if (chunkHeaderHolder.chunkType == FOURCC_RIFF) {
                    // We are at the start of the file. The movi chunk is in the RIFF chunk, so we skip the
                    // header, so as to read the RIFF chunk's body.
                    input.skipFully(12)
                    return Extractor.RESULT_CONTINUE
                }
                if (chunkHeaderHolder.chunkType != FOURCC_LIST || listType != FOURCC_movi) {
                    // The chunk header (8 bytes) plus the whole body.
                    pendingReposition = input.position + chunkHeaderHolder.size + 8
                    return Extractor.RESULT_CONTINUE
                }
                moviStart = input.position
                // Size includes the list type, but not the LIST or size fields, so we add 8.
                moviEnd = moviStart + chunkHeaderHolder.size + 8
                if (!seekMapHasBeenOutput) {
                    if (aviHeader!!.hasIndex()) {
                        state = STATE_FINDING_IDX1_HEADER
                        pendingReposition = moviEnd
                        return Extractor.RESULT_CONTINUE
                    } else {
                        extractorOutput.seekMap(Unseekable(durationUs))
                        seekMapHasBeenOutput = true
                    }
                }
                // No need to parse the idx1, so we start reading the samples from the movi chunk straight
                // away. We skip 12 bytes to move to the start of the movi's body.
                pendingReposition = input.position + 12
                state = STATE_READING_SAMPLES
                return Extractor.RESULT_CONTINUE
            }

            STATE_FINDING_IDX1_HEADER -> {
                input.readFully(scratch.data,  0,  8)
                scratch.setPosition(0)
                val idx1Fourcc = scratch.readLittleEndianInt()
                val boxSize = scratch.readLittleEndianInt()
                if (idx1Fourcc == FOURCC_idx1) {
                    state = STATE_READING_IDX1_BODY
                    idx1BodySize = boxSize
                } else {
                    // This one is not idx1, skip to the next box.
                    pendingReposition = input.position + boxSize
                }
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_IDX1_BODY -> {
                val idx1Body = ParsableByteArray(idx1BodySize)
                input.readFully(idx1Body.data,  0,  idx1BodySize)
                parseIdx1Body(idx1Body)
                state = STATE_READING_SAMPLES
                pendingReposition = moviStart
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_SAMPLES -> return readMoviChunks(input)
            else -> throw AssertionError() // Should never happen.

        }
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingReposition = C.INDEX_UNSET.toLong()
        currentChunkReader = null
        for (chunkReader in chunkReaders) {
            chunkReader.seekToPosition(position)
        }
        if (position == 0L) {
            if (chunkReaders.size == 0) {
                // Still unprepared.
                state = STATE_SKIPPING_TO_HDRL
            } else {
                state = STATE_FINDING_MOVI_HEADER
            }
            return
        }
        state = STATE_READING_SAMPLES
    }

    override fun release() {
        // Nothing to release.
    }

    // Internal methods.
    
    @Throws(IOException::class)
    private fun resolvePendingReposition(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): Boolean {
        var needSeek = false
        if (pendingReposition != C.INDEX_UNSET.toLong()) {
            val currentPosition = input.position
            if (pendingReposition < currentPosition
                || pendingReposition > currentPosition + RELOAD_MINIMUM_SEEK_DISTANCE
            ) {
                seekPosition.position = pendingReposition
                needSeek = true
            } else {
                // The distance to the target position is short enough that it makes sense to just skip the
                // bytes, instead of doing a seek which might re-create an HTTP connection.
                input.skipFully((pendingReposition - currentPosition).toInt())
            }
        }
        pendingReposition = C.INDEX_UNSET.toLong()
        return needSeek
    }

    @Throws(IOException::class)
    private fun parseHdrlBody(hrdlBody: ParsableByteArray) {
        val headerList: ListChunk = ListChunk.parseFrom(FOURCC_hdrl, hrdlBody)
        if (headerList.type != FOURCC_hdrl) {
            throw ParserException.createForMalformedContainer( 
                "Unexpected header list type " + headerList.type,  null
            )
        }
        val aviHeader = headerList.getChild<AviMainHeaderChunk>(AviMainHeaderChunk::class.java)
        if (aviHeader == null) {
            throw ParserException.createForMalformedContainer( 
                "AviHeader not found",  null
            )
        }
        this.aviHeader = aviHeader
        // This is usually wrong, so it will be overwritten by video if present
        durationUs = aviHeader.totalFrames * aviHeader.frameDurationUs.toLong()
        val chunkReaderList = ArrayList<ChunkReader>()
        var streamId = 0
        for (aviChunk in headerList.children) {
            if (aviChunk.type == FOURCC_strl) {
                val streamList = aviChunk as ListChunk
                // Note the streamId needs to increment even if the corresponding `strl` is discarded.
                // See
                // https://docs.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference#avi-stream-headers.
                val chunkReader = processStreamList(streamList, streamId++)
                if (chunkReader != null) {
                    chunkReaderList.add(chunkReader)
                }
            }
        }
        chunkReaders = chunkReaderList.toTypedArray()
        extractorOutput.endTracks()
    }

    private fun parseIdx1Body(body: ParsableByteArray) {
        val seekOffset = peekSeekOffset(body)
        while (body.bytesLeft() >= 16) {
            val chunkId = body.readLittleEndianInt()
            val flags = body.readLittleEndianInt()
            val offset = body.readLittleEndianInt() + seekOffset
            body.skipBytes(4) // Ignore size.
            val chunkReader = getChunkReader(chunkId)
            if (chunkReader == null) {
                // We ignore unknown chunk IDs.
                continue
            }
            chunkReader.appendIndexChunk(
                offset,  (flags and AVIIF_KEYFRAME) == AVIIF_KEYFRAME
            )
        }
        for (chunkReader in chunkReaders) {
            chunkReader.commitIndex()
        }
        seekMapHasBeenOutput = true
        if (chunkReaders.size == 0) {
            extractorOutput.seekMap(Unseekable(durationUs))
        } else {
            extractorOutput.seekMap(AviSeekMap(durationUs))
        }
    }

    private fun peekSeekOffset(idx1Body: ParsableByteArray): Long {
        // The spec states the offset is based on the start of the movi list type fourcc, but it also
        // says some files base the offset on the start of the file. We use a best effort approach to
        // figure out which is the case. See:
        // https://docs.microsoft.com/en-us/previous-versions/windows/desktop/api/Aviriff/ns-aviriff-avioldindex#dwoffset.
        if (idx1Body.bytesLeft() < 16) {
            // There are no full entries in the index, meaning we don't need to apply an offset.
            return 0
        }
        val startingPosition = idx1Body.getPosition()
        idx1Body.skipBytes(8) // Skip chunkId (4 bytes) and flags (4 bytes).
        val offset = idx1Body.readLittleEndianInt()

        // moviStart points at the start of the LIST, while the seek offset is based at the start of the
        // movi fourCC, so we add 8 to reconcile the difference.
        val seekOffset = if (offset > moviStart) 0L else moviStart + 8
        idx1Body.setPosition(startingPosition)
        return seekOffset
    }

    private fun getChunkReader(chunkId: Int): ChunkReader? {
        for (chunkReader in chunkReaders) {
            if (chunkReader.handlesChunkId(chunkId)) {
                return chunkReader
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun readMoviChunks(input: ExtractorInput): Int {
        if (input.position >= moviEnd) {
            return C.RESULT_END_OF_INPUT
        } else if (currentChunkReader != null) {
            if (currentChunkReader!!.onChunkData(input)) {
                currentChunkReader = null
            }
        } else {
            alignInputToEvenPosition(input)
            input.peekFully(scratch.data,  0, 12)
            scratch.setPosition(0)
            val chunkType = scratch.readLittleEndianInt()
            if (chunkType == FOURCC_LIST) {
                scratch.setPosition(8)
                val listType = scratch.readLittleEndianInt()
                input.skipFully(if (listType == FOURCC_movi) 12 else 8)
                input.resetPeekPosition()
                return Extractor.RESULT_CONTINUE
            }
            val size = scratch.readLittleEndianInt()
            if (chunkType == FOURCC_JUNK) {
                pendingReposition = input.position + size + 8
                return Extractor.RESULT_CONTINUE
            }
            input.skipFully(8)
            input.resetPeekPosition()
            val chunkReader = getChunkReader(chunkType)
            if (chunkReader == null) {
                // No handler for this chunk. We skip it.
                pendingReposition = input.position + size
                return Extractor.RESULT_CONTINUE
            } else {
                chunkReader.onChunkStart(size)
                this.currentChunkReader = chunkReader
            }
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun processStreamList(streamList: ListChunk, streamId: Int): ChunkReader? {
        val aviStreamHeaderChunk =
            streamList.getChild<AviStreamHeaderChunk>(AviStreamHeaderChunk::class.java)
        val streamFormatChunk =
            streamList.getChild<StreamFormatChunk>(StreamFormatChunk::class.java)
        if (aviStreamHeaderChunk == null) {
            w(TAG, "Missing Stream Header")
            return null
        }
        if (streamFormatChunk == null) {
            w(TAG, "Missing Stream Format")
            return null
        }
        val durationUs = aviStreamHeaderChunk.durationUs
        val streamFormat = streamFormatChunk.format
        val builder = streamFormat.buildUpon()
        builder.setId(streamId)
        val suggestedBufferSize = aviStreamHeaderChunk.suggestedBufferSize
        if (suggestedBufferSize != 0) {
            builder.setMaxInputSize(suggestedBufferSize)
        }
        val streamName = streamList.getChild<StreamNameChunk>(StreamNameChunk::class.java)
        if (streamName != null) {
            builder.setLabel(streamName.name)
        }
        val trackType = getTrackType(streamFormat.sampleMimeType)
        if (trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO) {
            val trackOutput = extractorOutput.track(streamId, trackType)
            val format = builder.build()
            trackOutput!!.format(format)
            trackOutput.durationUs(durationUs)
            this.durationUs = max(this.durationUs, durationUs)
            return ChunkReader(
                streamId,
                aviStreamHeaderChunk,
                trackOutput,
                allSamplesAreSyncSamples(format.sampleMimeType, format.codecs)
            )
        } else {
            // We don't currently support tracks other than video and audio.
            return null
        }
    }

    // Internal classes.
    private inner class AviSeekMap(override val durationUs: Long) : SeekMap {
        override val isSeekable: Boolean
            get() = true

        override fun getSeekPoints(timeUs: Long): SeekPoints {
            var result = chunkReaders[0].getSeekPoints(timeUs)
            for (i in 1..<chunkReaders.size) {
                val seekPoints = chunkReaders[i].getSeekPoints(timeUs)
                if (seekPoints.first.position < result.first.position) {
                    result = seekPoints
                }
            }
            return result
        }
    }

    private class ChunkHeaderHolder {
        var chunkType: Int = 0
        var size: Int = 0
        var listType: Int = 0

        @Throws(ParserException::class)
        fun populateWithListHeaderFrom(headerBytes: ParsableByteArray) {
            populateFrom(headerBytes)
            if (chunkType != FOURCC_LIST) {
                throw ParserException.createForMalformedContainer( 
                    "LIST expected, found: " + chunkType,  null
                )
            }
            listType = headerBytes.readLittleEndianInt()
        }

        fun populateFrom(headerBytes: ParsableByteArray) {
            chunkType = headerBytes.readLittleEndianInt()
            size = headerBytes.readLittleEndianInt()
            listType = 0
        }
    }

    companion object {
        private const val TAG = "AviExtractor"

        const val FOURCC_RIFF: Int = 0x46464952
        const val FOURCC_AVI_: Int = 0x20495641 // AVI<space>
        const val FOURCC_LIST: Int = 0x5453494c

        const val FOURCC_avih: Int = 0x68697661

        const val FOURCC_hdrl: Int = 0x6c726468

        const val FOURCC_strl: Int = 0x6c727473

        const val FOURCC_movi: Int = 0x69766f6d

        const val FOURCC_idx1: Int = 0x31786469

        const val FOURCC_JUNK: Int = 0x4b4e554a

        const val FOURCC_strf: Int = 0x66727473

        const val FOURCC_strn: Int = 0x6e727473

        const val FOURCC_strh: Int = 0x68727473

        const val FOURCC_auds: Int = 0x73647561

        const val FOURCC_txts: Int = 0x73747874

        const val FOURCC_vids: Int = 0x73646976

        private const val STATE_SKIPPING_TO_HDRL = 0
        private const val STATE_READING_HDRL_HEADER = 1
        private const val STATE_READING_HDRL_BODY = 2
        private const val STATE_FINDING_MOVI_HEADER = 3
        private const val STATE_FINDING_IDX1_HEADER = 4
        private const val STATE_READING_IDX1_BODY = 5
        private const val STATE_READING_SAMPLES = 6
        private const val AVIIF_KEYFRAME = 16
        const val FLAG_EMIT_RAW_SUBTITLE_DATA: Int = 1
        private val RELOAD_MINIMUM_SEEK_DISTANCE = (256 * 1024).toLong()

        @Throws(IOException::class)
        private fun alignInputToEvenPosition(input: ExtractorInput) {
            if ((input.position and 1L) == 1L) {
                input.skipFully(1)
            }
        }
    }
}
