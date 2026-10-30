
package androidx.media3.extractor.ts

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Ac4Util
import androidx.media3.extractor.Ac4Util.parseAc4SyncframeInfo
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.min

class Ac4Reader(
    language: String?,
    @RoleFlags roleFlags: @RoleFlags Int,
    containerMimeType: String?
) : ElementaryStreamReader {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [STATE_FINDING_SYNC, STATE_READING_HEADER, STATE_READING_SAMPLE])
    private annotation class State

    private val headerScratchBits: ParsableBitArray
    private val headerScratchBytes: ParsableByteArray
    private val language: String?

    @RoleFlags
    private val roleFlags: @RoleFlags Int
    private val containerMimeType: String?

    private var formatId:String? = null
    private var output:TrackOutput? = null

    private var state: @State Int
    private var bytesRead: Int

    // Used to find the header.
    private var lastByteWasAC: Boolean
    private var hasCRC: Boolean

    // Used when parsing the header.
    private var sampleDurationUs: Long = 0
    private var format:Format? = null
    private var sampleSize = 0

    // Used when reading the samples.
    private var timeUs: Long

    constructor(containerMimeType: String?) : this(null,  0, containerMimeType)

    init {
        headerScratchBits = ParsableBitArray(ByteArray(Ac4Util.HEADER_SIZE_FOR_PARSER))
        headerScratchBytes = ParsableByteArray(headerScratchBits.data)
        state = STATE_FINDING_SYNC
        bytesRead = 0
        lastByteWasAC = false
        hasCRC = false
        timeUs = C.TIME_UNSET
        this.language = language
        this.roleFlags = roleFlags
        this.containerMimeType = containerMimeType
    }

    override fun seek() {
        state = STATE_FINDING_SYNC
        bytesRead = 0
        lastByteWasAC = false
        hasCRC = false
        timeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        timeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        // Asserts that createTracks has been called.
       val output= output!!
        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_SYNC -> if (skipToNextSync(data)) {
                    state = STATE_READING_HEADER
                    headerScratchBytes.data[0] = 0xAC.toByte()
                    headerScratchBytes.data[1] = (if (hasCRC) 0x41 else 0x40).toByte()
                    bytesRead = 2
                }

                STATE_READING_HEADER -> if (continueRead(
                        data,
                        headerScratchBytes.data,
                        Ac4Util.HEADER_SIZE_FOR_PARSER
                    )
                ) {
                    parseHeader()
                    headerScratchBytes.setPosition(0)
                    output!!.sampleData(headerScratchBytes, Ac4Util.HEADER_SIZE_FOR_PARSER)
                    state = STATE_READING_SAMPLE
                }

                STATE_READING_SAMPLE -> {
                    val bytesToRead = min(data.bytesLeft(), sampleSize - bytesRead)
                    output!!.sampleData(data, bytesToRead)
                    bytesRead += bytesToRead
                    if (bytesRead == sampleSize) {
                        // packetStarted method must be called before reading samples.
                        Preconditions.checkState(timeUs != C.TIME_UNSET)
                        output!!.sampleMetadata(
                            timeUs,
                            C.BUFFER_FLAG_KEY_FRAME,
                            sampleSize,
                            0,
                            null
                        )
                        timeUs += sampleDurationUs
                        state = STATE_FINDING_SYNC
                    }
                }

                else -> {}
            }
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

    private fun skipToNextSync(pesBuffer: ParsableByteArray): Boolean {
        while (pesBuffer.bytesLeft() > 0) {
            if (!lastByteWasAC) {
                lastByteWasAC = (pesBuffer.readUnsignedByte() == 0xAC)
                continue
            }
            val secondByte = pesBuffer.readUnsignedByte()
            lastByteWasAC = secondByte == 0xAC
            if (secondByte == 0x40 || secondByte == 0x41) {
                hasCRC = secondByte == 0x41
                return true
            }
        }
        return false
    }

    @RequiresNonNull("output")
    private fun parseHeader() {
        headerScratchBits.position=(0)
        val frameInfo = parseAc4SyncframeInfo(headerScratchBits)
        if (format == null || frameInfo.channelCount != format!!.channelCount || frameInfo.sampleRate != format!!.sampleRate || (MimeTypes.AUDIO_AC4 != format!!.sampleMimeType)) {
            format =
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(MimeTypes.AUDIO_AC4)
                    .setChannelCount(frameInfo.channelCount)
                    .setSampleRate(frameInfo.sampleRate)
                    .setLanguage(language)
                    .setRoleFlags(roleFlags)
                    .build()
            output!!.format(format!!)
        }
        sampleSize = frameInfo.frameSize
        // In this class a sample is an AC-4 sync frame, but Format#sampleRate specifies the number of
        // PCM audio samples per second.
        sampleDurationUs = C.MICROS_PER_SECOND * frameInfo.sampleCount / format!!.sampleRate
    }

    companion object {
        private const val STATE_FINDING_SYNC = 0
        private const val STATE_READING_HEADER = 1
        private const val STATE_READING_SAMPLE = 2
    }
}
