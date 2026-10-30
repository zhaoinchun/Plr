
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.sampleCountToDurationUs
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.DtsUtil.DtsHeader
import androidx.media3.extractor.DtsUtil.FrameType
import androidx.media3.extractor.DtsUtil.getDtsFrameSize
import androidx.media3.extractor.DtsUtil.getFrameType
import androidx.media3.extractor.DtsUtil.parseDtsAudioSampleCount
import androidx.media3.extractor.DtsUtil.parseDtsFormat
import androidx.media3.extractor.DtsUtil.parseDtsHdHeader
import androidx.media3.extractor.DtsUtil.parseDtsHdHeaderSize
import androidx.media3.extractor.DtsUtil.parseDtsUhdHeader
import androidx.media3.extractor.DtsUtil.parseDtsUhdHeaderSize
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions
import com.google.common.primitives.Ints

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class DtsReader(
    language: String?,
    @RoleFlags roleFlags: @RoleFlags Int,
    maxHeaderSize: Int,
    containerMimeType: String?
) : ElementaryStreamReader {
    private val headerScratchBytes: ParsableByteArray

    private val uhdAudioChunkId: AtomicInteger

    private val language: String?

    @RoleFlags
    private val roleFlags: @RoleFlags Int
    private val containerMimeType: String?

    private var formatId:String? = null
    private var output:TrackOutput? = null

    private var state: Int
    private var bytesRead = 0
    private var extSyncBytes = 0

    private var syncBytes = 0

    // Used when parsing the header.
    private var sampleDurationUs: Long = 0
    private var format:Format? = null
    private var sampleSize = 0
    private var coreSampleSize = 0
    private var frameType: @FrameType Int = 0
    private var extensionSubstreamHeaderSize: Int
    private var uhdHeaderSize: Int

    // Used when reading the samples.
    private var coreFormatPendingEmit = false
    private var timeUs: Long
    private var pendingTimeUs: Long
    private var hasCore = false
    private var skipExtssUntilCore = false

    init {
        headerScratchBytes = ParsableByteArray(ByteArray(maxHeaderSize))
        state = STATE_FINDING_SYNC
        timeUs = C.TIME_UNSET
        pendingTimeUs = C.TIME_UNSET
        uhdAudioChunkId = AtomicInteger()
        extensionSubstreamHeaderSize = C.LENGTH_UNSET
        uhdHeaderSize = C.LENGTH_UNSET
        this.language = language
        this.roleFlags = roleFlags
        this.containerMimeType = containerMimeType
    }

    override fun seek() {
        state = STATE_FINDING_SYNC
        bytesRead = 0
        syncBytes = 0
        extSyncBytes = 0
        coreSampleSize = 0
        timeUs = C.TIME_UNSET
        pendingTimeUs = C.TIME_UNSET
        uhdAudioChunkId.set(0)
        coreFormatPendingEmit = false
        skipExtssUntilCore = hasCore
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        if (pesTimeUs != C.TIME_UNSET) {
            if (state != STATE_FINDING_SYNC) {
                pendingTimeUs = pesTimeUs
            } else {
                timeUs = pesTimeUs
                pendingTimeUs = C.TIME_UNSET
            }
        }
    }

    @Throws(ParserException::class)
    override fun consume(data: ParsableByteArray) {
        // Asserts that createTracks has been called.
        val output = output!!
        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_SYNC -> if (skipToNextSyncWord(data)) {
                    if (skipExtssUntilCore && frameType == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
                        bytesRead = 0
                    } else {
                        if (frameType == DtsUtil.FRAME_TYPE_CORE) {
                            skipExtssUntilCore = false
                        }
                        if (frameType == DtsUtil.FRAME_TYPE_UHD_SYNC
                            || frameType == DtsUtil.FRAME_TYPE_UHD_NON_SYNC
                        ) {
                            state = STATE_FINDING_UHD_HEADER_SIZE
                        } else if (frameType == DtsUtil.FRAME_TYPE_CORE) {
                            state = STATE_READING_CORE_HEADER
                        } else {
                            state = STATE_FINDING_EXTSS_HEADER_SIZE
                        }
                    }
                }

                STATE_READING_CORE_HEADER -> if (continueRead(
                        data,
                        headerScratchBytes.data,
                        CORE_HEADER_SIZE
                    )
                ) {
                    parseCoreHeader()
                    headerScratchBytes.setPosition(0)
                    output!!.sampleData(headerScratchBytes, CORE_HEADER_SIZE)
                    state = STATE_READING_SAMPLE
                }

                STATE_FINDING_EXTSS_HEADER_SIZE ->           // Read enough bytes to parse the header size information.
                    if (continueRead(data, headerScratchBytes.data,  7)) {
                        extensionSubstreamHeaderSize =
                            parseDtsHdHeaderSize(headerScratchBytes.data)
                        state = STATE_READING_EXTSS_HEADER
                    }

                STATE_READING_EXTSS_HEADER -> if (continueRead(
                        data,
                        headerScratchBytes.data,
                        extensionSubstreamHeaderSize
                    )
                ) {
                    parseExtensionSubstreamHeader()
                    headerScratchBytes.setPosition(0)
                    output!!.sampleData(headerScratchBytes, extensionSubstreamHeaderSize)
                    state = STATE_READING_SAMPLE
                }

                STATE_FINDING_UHD_HEADER_SIZE ->           // Read enough bytes to parse the header size information.
                    if (continueRead(data, headerScratchBytes.data,  6)) {
                        uhdHeaderSize = parseDtsUhdHeaderSize(headerScratchBytes.data)
                        // Adjust the array read position if data read is more than the actual header size.
                        if (bytesRead > uhdHeaderSize) {
                            val extraBytes = bytesRead - uhdHeaderSize
                            bytesRead -= extraBytes
                            data.setPosition(data.getPosition() - extraBytes)
                        }
                        state = STATE_READING_UHD_HEADER
                    }

                STATE_READING_UHD_HEADER -> if (continueRead(
                        data,
                        headerScratchBytes.data,
                        uhdHeaderSize
                    )
                ) {
                    parseUhdHeader()
                    headerScratchBytes.setPosition(0)
                    output!!.sampleData(headerScratchBytes, uhdHeaderSize)
                    state = STATE_READING_SAMPLE
                }

                STATE_READING_SAMPLE -> {
                    val bytesToRead = min(data.bytesLeft(), sampleSize - bytesRead)
                    output!!.sampleData(data, bytesToRead)
                    bytesRead += bytesToRead
                    if (bytesRead == sampleSize) {
                        if (frameType == DtsUtil.FRAME_TYPE_CORE) {
                            coreSampleSize = sampleSize
                            bytesRead = 0
                            extSyncBytes = 0
                            state = STATE_CHECKING_FOR_EXTSS_AFTER_CORE
                        } else {
                            // packetStarted method must be called before consuming samples.
                            Preconditions.checkState(timeUs != C.TIME_UNSET)
                            val combinedSize =
                                (sampleSize
                                        + (if (frameType == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) coreSampleSize else 0))
                            val emittedTimeUs = timeUs
                            output!!.sampleMetadata(
                                timeUs,
                                if (frameType == DtsUtil.FRAME_TYPE_UHD_NON_SYNC) 0 else C.BUFFER_FLAG_KEY_FRAME,
                                combinedSize,
                                0,
                                null
                            )
                            timeUs += sampleDurationUs
                            if (pendingTimeUs != C.TIME_UNSET) {
                                if (pendingTimeUs != emittedTimeUs) {
                                    timeUs = pendingTimeUs
                                }
                                pendingTimeUs = C.TIME_UNSET
                            }
                            coreSampleSize = 0
                            state = STATE_FINDING_SYNC
                        }
                    }
                }

                STATE_CHECKING_FOR_EXTSS_AFTER_CORE -> {
                    while (data.bytesLeft() > 0 && bytesRead < 4) {
                        extSyncBytes = extSyncBytes shl 8
                        extSyncBytes = extSyncBytes or data.readUnsignedByte()
                        bytesRead++
                    }
                    if (bytesRead == 4) {
                        if (getFrameType(extSyncBytes) == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
                            setHeaderScratchBytesFromSyncWord(extSyncBytes)
                            frameType = DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM
                            extSyncBytes = 0
                            state = STATE_FINDING_EXTSS_HEADER_SIZE
                        } else {
                            if (coreFormatPendingEmit) {
                                output!!.format(format!! )
                                coreFormatPendingEmit = false
                            }
                            Preconditions.checkState(timeUs != C.TIME_UNSET)
                            val emittedTimeUs = timeUs
                            output!!.sampleMetadata(
                                timeUs,
                                C.BUFFER_FLAG_KEY_FRAME,
                                coreSampleSize,
                                0,
                                null
                            )
                            timeUs += sampleDurationUs
                            if (pendingTimeUs != C.TIME_UNSET) {
                                if (pendingTimeUs != emittedTimeUs) {
                                    timeUs = pendingTimeUs
                                }
                                pendingTimeUs = C.TIME_UNSET
                            }
                            coreSampleSize = 0

                            syncBytes = extSyncBytes
                            extSyncBytes = 0
                            frameType = getFrameType(syncBytes)
                            if (frameType == DtsUtil.FRAME_TYPE_UHD_SYNC
                                || frameType == DtsUtil.FRAME_TYPE_UHD_NON_SYNC
                            ) {
                                setHeaderScratchBytesFromSyncWord(syncBytes)
                                syncBytes = 0
                                state = STATE_FINDING_UHD_HEADER_SIZE
                            } else if (frameType == DtsUtil.FRAME_TYPE_CORE) {
                                setHeaderScratchBytesFromSyncWord(syncBytes)
                                syncBytes = 0
                                state = STATE_READING_CORE_HEADER
                            } else {
                                bytesRead = 0
                                state = STATE_FINDING_SYNC
                            }
                        }
                    }
                }

                else -> throw IllegalStateException()
            }
        }
    }

    override fun endOfInputReached() {
        if (state == STATE_CHECKING_FOR_EXTSS_AFTER_CORE) {
            val output = output!!
            if (coreFormatPendingEmit) {
                output!!.format(format!!)
                coreFormatPendingEmit = false
            }
            if (timeUs != C.TIME_UNSET) {
                output!!.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, coreSampleSize, 0, null)
                timeUs += sampleDurationUs
            }
            coreSampleSize = 0
            bytesRead = 0
            syncBytes = 0
            extSyncBytes = 0
            state = STATE_FINDING_SYNC
        }
    }

    private fun continueRead(
        source: ParsableByteArray,
        target: ByteArray,
        targetLength: Int
    ): Boolean {
        val bytesToRead = min(source.bytesLeft(), targetLength - bytesRead)
        source.readBytes(target, bytesRead, bytesToRead)
        bytesRead += bytesToRead
        return bytesRead == targetLength
    }

    private fun skipToNextSyncWord(pesBuffer: ParsableByteArray): Boolean {
        while (pesBuffer.bytesLeft() > 0) {
            syncBytes = syncBytes shl 8
            syncBytes = syncBytes or pesBuffer.readUnsignedByte()
            frameType = getFrameType(syncBytes)
            if (frameType != DtsUtil.FRAME_TYPE_UNKNOWN) {
                setHeaderScratchBytesFromSyncWord(syncBytes)
                syncBytes = 0
                return true
            }
        }
        return false
    }

    private fun setHeaderScratchBytesFromSyncWord(syncWord: Int) {
        val headerData = headerScratchBytes.data
        headerData[0] = ((syncWord shr 24) and 0xFF).toByte()
        headerData[1] = ((syncWord shr 16) and 0xFF).toByte()
        headerData[2] = ((syncWord shr 8) and 0xFF).toByte()
        headerData[3] = (syncWord and 0xFF).toByte()
        bytesRead = 4
    }

    @RequiresNonNull("output")
    private fun parseCoreHeader() {
        hasCore = true
        val frameData = headerScratchBytes.data
        if (format == null) {
            format =
                parseDtsFormat(frameData, formatId, language, roleFlags, containerMimeType, null)
            coreFormatPendingEmit = true
        }
        sampleSize = getDtsFrameSize(frameData)
        // In this class a sample is an access unit (frame in DTS), but the format's sample rate
        // specifies the number of PCM audio samples per second.
        sampleDurationUs =
            Ints.checkedCast(
                sampleCountToDurationUs(
                    parseDtsAudioSampleCount(frameData).toLong(), format!!.sampleRate
                )
            ).toLong()
    }

    @RequiresNonNull("output")
    @Throws(ParserException::class)
    private fun parseExtensionSubstreamHeader() {
        val dtsHeader = parseDtsHdHeader(headerScratchBytes.data)
        updateFormatWithDtsHeaderInfo(dtsHeader)
        sampleSize = dtsHeader.frameSize
        if (dtsHeader.frameDurationUs != C.TIME_UNSET) {
            sampleDurationUs = dtsHeader.frameDurationUs
        }
    }

    @RequiresNonNull("output")
    @Throws(ParserException::class)
    private fun parseUhdHeader() {
        val dtsHeader =
            parseDtsUhdHeader(headerScratchBytes.data, uhdAudioChunkId)
        // Format updates will happen only in FTOC sync frames.
        if (frameType == DtsUtil.FRAME_TYPE_UHD_SYNC) {
            updateFormatWithDtsHeaderInfo(dtsHeader)
        }
        sampleSize = dtsHeader.frameSize
        sampleDurationUs =
            if (dtsHeader.frameDurationUs == C.TIME_UNSET) 0 else dtsHeader.frameDurationUs
    }

    @RequiresNonNull("output")
    private fun updateFormatWithDtsHeaderInfo(dtsHeader: DtsHeader) {
        if (dtsHeader.sampleRate == C.RATE_UNSET_INT || dtsHeader.channelCount == C.LENGTH_UNSET) {
            return
        }
        val sampleMimeType =
            if (dtsHeader.mimeType != null)
                dtsHeader.mimeType
            else
                if (format != null) format!!.sampleMimeType else null
        if (format == null || coreFormatPendingEmit
            || dtsHeader.channelCount != format!!.channelCount || dtsHeader.sampleRate != format!!.sampleRate || (sampleMimeType != format!!.sampleMimeType)
        ) {
            val formatBuilder = if (format == null) Format.Builder() else format!!.buildUpon()
            format =
                formatBuilder
                    .setId(formatId)
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(sampleMimeType)
                    .setChannelCount(dtsHeader.channelCount)
                    .setSampleRate(dtsHeader.sampleRate)
                    .setLanguage(language)
                    .setRoleFlags(roleFlags)
                    .build()
            output!!.format(format!!)
            coreFormatPendingEmit = false
        }
    }

    companion object {
        private const val STATE_FINDING_SYNC = 0
        private const val STATE_READING_CORE_HEADER = 1
        private const val STATE_FINDING_EXTSS_HEADER_SIZE = 2
        private const val STATE_READING_EXTSS_HEADER = 3
        private const val STATE_FINDING_UHD_HEADER_SIZE = 4
        private const val STATE_READING_UHD_HEADER = 5
        private const val STATE_READING_SAMPLE = 6
        private const val STATE_CHECKING_FOR_EXTSS_AFTER_CORE = 7

        private const val CORE_HEADER_SIZE = 18

        const val EXTSS_HEADER_SIZE_MAX: Int = 4096

        const val FTOC_MAX_HEADER_SIZE: Int = 5408
    }
}
