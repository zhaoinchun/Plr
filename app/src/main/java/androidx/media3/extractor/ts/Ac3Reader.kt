
package androidx.media3.extractor.ts

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.Ac3Util.parseAc3SyncframeInfo
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.min

class Ac3Reader(
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
    private var bytesRead = 0

    // Used to find the header.
    private var lastByteWas0B = false

    // Used when parsing the header.
    private var sampleDurationUs: Long = 0
    private var format:Format? = null
    private var sampleSize = 0

    // Used when reading the samples.
    private var timeUs: Long

    constructor(containerMimeType: String?) : this(null,  0, containerMimeType)

    init {
        headerScratchBits = ParsableBitArray(ByteArray(HEADER_SIZE))
        headerScratchBytes = ParsableByteArray(headerScratchBits.data)
        state = STATE_FINDING_SYNC
        timeUs = C.TIME_UNSET
        this.language = language
        this.roleFlags = roleFlags
        this.containerMimeType = containerMimeType
    }

    override fun seek() {
        state = STATE_FINDING_SYNC
        bytesRead = 0
        lastByteWas0B = false
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
        val output=output!!
        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_SYNC -> if (skipToNextSync(data)) {
                    state = STATE_READING_HEADER
                    headerScratchBytes.data[0] = 0x0B
                    headerScratchBytes.data[1] = 0x77
                    bytesRead = 2
                }

                STATE_READING_HEADER -> if (continueRead(
                        data,
                        headerScratchBytes.data,
                        HEADER_SIZE
                    )
                ) {
                    parseHeader()
                    headerScratchBytes.setPosition(0)
                    output!!.sampleData(headerScratchBytes, HEADER_SIZE)
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
            if (!lastByteWas0B) {
                lastByteWas0B = pesBuffer.readUnsignedByte() == 0x0B
                continue
            }
            val secondByte = pesBuffer.readUnsignedByte()
            if (secondByte == 0x77) {
                lastByteWas0B = false
                return true
            } else {
                lastByteWas0B = secondByte == 0x0B
            }
        }
        return false
    }

    @RequiresNonNull("output")
    private fun parseHeader() {
        headerScratchBits.position=(0)
        val frameInfo = parseAc3SyncframeInfo(headerScratchBits)
        if (format == null || frameInfo.channelCount != format!!.channelCount || frameInfo.sampleRate != format!!.sampleRate || (frameInfo.mimeType != format!!.sampleMimeType)) {
            val formatBuilder =
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(frameInfo.mimeType)
                    .setChannelCount(frameInfo.channelCount)
                    .setSampleRate(frameInfo.sampleRate)
                    .setLanguage(language)
                    .setRoleFlags(roleFlags)
                    .setPeakBitrate(frameInfo.bitrate)
            // AC3 has constant bitrate, so averageBitrate = peakBitrate
            if (MimeTypes.AUDIO_AC3 == frameInfo.mimeType) {
                formatBuilder.setAverageBitrate(frameInfo.bitrate)
            }
            format = formatBuilder.build()
            output!!.format(format!!)
        }
        sampleSize = frameInfo.frameSize
        // In this class a sample is an access unit (syncframe in AC-3), but Format#sampleRate
        // specifies the number of PCM audio samples per second.
        sampleDurationUs = C.MICROS_PER_SECOND * frameInfo.sampleCount / format!!.sampleRate
    }

    companion object {
        private const val STATE_FINDING_SYNC = 0
        private const val STATE_READING_HEADER = 1
        private const val STATE_READING_SAMPLE = 2

        private const val HEADER_SIZE = 128
    }
}
