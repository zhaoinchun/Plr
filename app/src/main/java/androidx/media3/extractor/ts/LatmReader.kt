package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.AacUtil.parseAudioSpecificConfig
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.min

class LatmReader(
    private val language: String?,
    @field:RoleFlags @param:RoleFlags private val roleFlags: @RoleFlags Int,
    private val containerMimeType: String?
) : ElementaryStreamReader {
    private val sampleDataBuffer: ParsableByteArray
    private val sampleBitArray: ParsableBitArray

    // Track output info.
    private var output:TrackOutput? = null
    private var formatId:String? = null
    private var format:Format? = null

    // Parser state info.
    private var state = 0
    private var bytesRead = 0
    private var sampleSize = 0
    private var secondHeaderByte = 0
    private var timeUs: Long

    // Container data.
    private var streamMuxRead = false
    private var audioMuxVersionA = 0
    private var numSubframes = 0
    private var frameLengthType = 0
    private var otherDataPresent = false
    private var otherDataLenBits: Long = 0
    private var sampleRateHz = 0
    private var sampleDurationUs: Long = 0
    private var channelCount = 0
    private var codecs: String? = null

    init {
        sampleDataBuffer = ParsableByteArray(INITIAL_BUFFER_SIZE)
        sampleBitArray = ParsableBitArray(sampleDataBuffer.data)
        timeUs = C.TIME_UNSET
    }

    override fun seek() {
        state = STATE_FINDING_SYNC_1
        timeUs = C.TIME_UNSET
        streamMuxRead = false
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO)
        formatId = idGenerator.getFormatId()
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        timeUs = pesTimeUs
    }

    @Throws(ParserException::class)
    override fun consume(data: ParsableByteArray) {
        // Asserts that createTracks has been called.
        val output = output!!
        var bytesToRead: Int
        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_SYNC_1 -> if (data.readUnsignedByte() == SYNC_BYTE_FIRST) {
                    state = STATE_FINDING_SYNC_2
                }

                STATE_FINDING_SYNC_2 -> {
                    val secondByte = data.readUnsignedByte()
                    if ((secondByte and SYNC_BYTE_SECOND) == SYNC_BYTE_SECOND) {
                        secondHeaderByte = secondByte
                        state = STATE_READING_HEADER
                    } else if (secondByte != SYNC_BYTE_FIRST) {
                        state = STATE_FINDING_SYNC_1
                    }
                }

                STATE_READING_HEADER -> {
                    sampleSize =
                        ((secondHeaderByte and SYNC_BYTE_SECOND.inv()) shl 8) or data.readUnsignedByte()
                    if (sampleSize > sampleDataBuffer.data.size) {
                        resetBufferForSize(sampleSize)
                    }
                    bytesRead = 0
                    state = STATE_READING_SAMPLE
                }

                STATE_READING_SAMPLE -> {
                    bytesToRead = min(data.bytesLeft(), sampleSize - bytesRead)
                    data.readBytes(sampleBitArray.data, bytesRead, bytesToRead)
                    bytesRead += bytesToRead
                    if (bytesRead == sampleSize) {
                        sampleBitArray.position=(0)
                        parseAudioMuxElement(sampleBitArray)
                        state = STATE_FINDING_SYNC_1
                    }
                }

                else -> throw IllegalStateException()
            }
        }
    }

    @RequiresNonNull("output")
    @Throws(ParserException::class)
    private fun parseAudioMuxElement(data: ParsableBitArray) {
        val useSameStreamMux = data.readBit()
        if (!useSameStreamMux) {
            streamMuxRead = true
            parseStreamMuxConfig(data)
        } else if (!streamMuxRead) {
            return  // Parsing cannot continue without StreamMuxConfig information.
        }

        if (audioMuxVersionA == 0) {
            if (numSubframes != 0) {
                throw ParserException.createForMalformedContainer( null,  
                    null
                )
            }
            val muxSlotLengthBytes = parsePayloadLengthInfo(data)
            parsePayloadMux(data, muxSlotLengthBytes)
            if (otherDataPresent) {
                data.skipBits(otherDataLenBits.toInt())
            }
        } else {
            // Not defined by ISO/IEC 14496-3:2009.
            throw ParserException.createForMalformedContainer( null,  
                null
            )
        }
    }

    @RequiresNonNull("output")
    @Throws(ParserException::class)
    private fun parseStreamMuxConfig(data: ParsableBitArray) {
        val audioMuxVersion = data.readBits(1)
        audioMuxVersionA = if (audioMuxVersion == 1) data.readBits(1) else 0
        if (audioMuxVersionA == 0) {
            if (audioMuxVersion == 1) {
                latmGetValue(data) // Skip taraBufferFullness.
            }
            if (!data.readBit()) {
                throw ParserException.createForMalformedContainer( null,  
                    null
                )
            }
            numSubframes = data.readBits(6)
            val numProgram = data.readBits(4)
            val numLayer = data.readBits(3)
            if (numProgram != 0 || numLayer != 0) {
                throw ParserException.createForMalformedContainer( null,  
                    null
                )
            }
            if (audioMuxVersion == 0) {
                val startPosition = data.position
                val readBits = parseAudioSpecificConfig(data)
                data.position=(startPosition)
                val initData = ByteArray((readBits + 7) / 8)
                data.readBits(initData, 0, readBits)
                val format =
                    Format.Builder()
                        .setId(formatId)
                        .setContainerMimeType(containerMimeType)
                        .setSampleMimeType(MimeTypes.AUDIO_AAC)
                        .setCodecs(codecs)
                        .setChannelCount(channelCount)
                        .setSampleRate(sampleRateHz)
                        .setInitializationData(mutableListOf<ByteArray>(initData))
                        .setLanguage(language)
                        .setRoleFlags(roleFlags)
                        .build()
                if (!format.equals(this.format)) {
                    this.format = format
                    sampleDurationUs = (C.MICROS_PER_SECOND * 1024) / format.sampleRate
                    output!!.format(format)
                }
            } else {
                val ascLen = latmGetValue(data).toInt()
                val bitsRead = parseAudioSpecificConfig(data)
                data.skipBits(ascLen - bitsRead) // fillBits.
            }
            parseFrameLength(data)
            otherDataPresent = data.readBit()
            otherDataLenBits = 0
            if (otherDataPresent) {
                if (audioMuxVersion == 1) {
                    otherDataLenBits = latmGetValue(data)
                } else {
                    var otherDataLenEsc: Boolean
                    do {
                        otherDataLenEsc = data.readBit()
                        otherDataLenBits = (otherDataLenBits shl 8) + data.readBits(8)
                    } while (otherDataLenEsc)
                }
            }
            val crcCheckPresent = data.readBit()
            if (crcCheckPresent) {
                data.skipBits(8) // crcCheckSum.
            }
        } else {
            // This is not defined by ISO/IEC 14496-3:2009.
            throw ParserException.createForMalformedContainer( null,  
                null
            )
        }
    }

    private fun parseFrameLength(data: ParsableBitArray) {
        frameLengthType = data.readBits(3)
        when (frameLengthType) {
            0 -> data.skipBits(8) // latmBufferFullness.
            1 -> data.skipBits(9) // frameLength.
            3, 4, 5 -> data.skipBits(6) // CELPframeLengthTableIndex.
            6, 7 -> data.skipBits(1) // HVXCframeLengthTableIndex.
            else -> throw IllegalStateException()
        }
    }

    @Throws(ParserException::class)
    private fun parseAudioSpecificConfig(data: ParsableBitArray): Int {
        val bitsLeft = data.bitsLeft()
        val config = parseAudioSpecificConfig(data,  true)
        codecs = config.codecs
        sampleRateHz = config.sampleRateHz
        channelCount = config.channelCount
        return bitsLeft - data.bitsLeft()
    }

    @Throws(ParserException::class)
    private fun parsePayloadLengthInfo(data: ParsableBitArray): Int {
        var muxSlotLengthBytes = 0
        // Assuming single program and single layer.
        if (frameLengthType == 0) {
            var tmp: Int
            do {
                tmp = data.readBits(8)
                muxSlotLengthBytes += tmp
            } while (tmp == 255)
            return muxSlotLengthBytes
        } else {
            throw ParserException.createForMalformedContainer( null,  
                null
            )
        }
    }

    @RequiresNonNull("output")
    private fun parsePayloadMux(data: ParsableBitArray, muxLengthBytes: Int) {
        // The start of sample data in
        val bitPosition = data.position
        if ((bitPosition and 0x07) == 0) {
            // Sample data is byte-aligned. We can output it directly.
            sampleDataBuffer.setPosition(bitPosition shr 3)
        } else {
            // Sample data is not byte-aligned and we need align it ourselves before outputting.
            // Byte alignment is needed because LATM framing is not supported by MediaCodec.
            data.readBits(sampleDataBuffer.data, 0, muxLengthBytes * 8)
            sampleDataBuffer.setPosition(0)
        }
        output!!.sampleData(sampleDataBuffer, muxLengthBytes)
        // packetStarted method must be called before consuming samples.
        Preconditions.checkState(timeUs != C.TIME_UNSET)
        output!!.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, muxLengthBytes, 0, null)
        timeUs += sampleDurationUs
    }

    private fun resetBufferForSize(newSize: Int) {
        sampleDataBuffer.reset(newSize)
        sampleBitArray.reset(sampleDataBuffer.data)
    }

    companion object {
        private const val STATE_FINDING_SYNC_1 = 0
        private const val STATE_FINDING_SYNC_2 = 1
        private const val STATE_READING_HEADER = 2
        private const val STATE_READING_SAMPLE = 3

        private const val INITIAL_BUFFER_SIZE = 1024
        private const val SYNC_BYTE_FIRST = 0x56
        private const val SYNC_BYTE_SECOND = 0xE0

        private fun latmGetValue(data: ParsableBitArray): Long {
            val bytesForValue = data.readBits(2)
            return data.readBits((bytesForValue + 1) * 8).toLong()
        }
    }
}
