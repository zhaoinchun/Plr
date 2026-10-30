package androidx.media3.extractor.ts

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.MpeghUtil.MhasPacketHeader
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.min

class MpeghReader(private val containerMimeType: String?) : ElementaryStreamReader {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [STATE_FINDING_SYNC, STATE_READING_PACKET_HEADER, STATE_READING_PACKET_PAYLOAD])
    private annotation class State

    private val headerScratchBytes: ParsableByteArray
    private val headerScratchBits: ParsableBitArray
    private val dataScratchBytes: ParsableByteArray

    private var state: @State Int

    private var formatId:String? = null
    private var output:TrackOutput? = null

    // The timestamp to attach to the next sample in the current packet.
    private var timeUs: Double
    private var timeUsPending: Double
    private var dataPending = false
    private var rapPending: Boolean
    private var flags: @TsPayloadReader.Flags Int = 0

    private var syncBytes = 0

    private var headerDataFinished: Boolean
    private var payloadBytesRead = 0
    private var frameBytes = 0

    private val header: MhasPacketHeader
    private var samplingRate: Int
    private var standardFrameLength: Int
    private var truncationSamples = 0
    private var mainStreamLabel: Long
    private var configFound = false

    init {
        state = STATE_FINDING_SYNC
        headerScratchBytes =
            ParsableByteArray(ByteArray(MAX_MHAS_PACKET_HEADER_SIZE), MIN_MHAS_PACKET_HEADER_SIZE)
        headerScratchBits = ParsableBitArray()
        dataScratchBytes = ParsableByteArray()
        header = MhasPacketHeader()
        samplingRate = C.RATE_UNSET_INT
        standardFrameLength = C.LENGTH_UNSET
        mainStreamLabel = C.INDEX_UNSET.toLong()
        rapPending = true
        headerDataFinished = true
        timeUs = C.TIME_UNSET.toDouble()
        timeUsPending = C.TIME_UNSET.toDouble()
    }

    override fun seek() {
        state = STATE_FINDING_SYNC
        syncBytes = 0
        headerScratchBytes.reset(MIN_MHAS_PACKET_HEADER_SIZE)
        payloadBytesRead = 0
        frameBytes = 0
        samplingRate = C.RATE_UNSET_INT
        standardFrameLength = C.LENGTH_UNSET
        truncationSamples = 0
        mainStreamLabel = C.INDEX_UNSET.toLong()
        configFound = false
        dataPending = false
        headerDataFinished = true
        rapPending = true
        timeUs = C.TIME_UNSET.toDouble()
        timeUsPending = C.TIME_UNSET.toDouble()
    }

    override fun createTracks(
        extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator
    ) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        this.flags = flags

        // check if data is pending (an MPEG-H frame could not be completed)
        if (!rapPending && (frameBytes != 0 || !headerDataFinished)) {
            dataPending = true
        }

        if (pesTimeUs != C.TIME_UNSET) {
            if (dataPending) {
                timeUsPending = pesTimeUs.toDouble()
            } else {
                timeUs = pesTimeUs.toDouble()
            }
        }
    }

    @Throws(ParserException::class)
    override fun consume(data: ParsableByteArray) {
        // Asserts that createTracks has been called.
        val output = output!!

        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_SYNC -> if (skipToNextSync(data)) {
                    state = STATE_READING_PACKET_HEADER
                }

                STATE_READING_PACKET_HEADER -> {
                    copyData(data, headerScratchBytes,  false)
                    if (headerScratchBytes.bytesLeft() == 0) {
                        if (parseHeader()) {
                            // write the MHAS packet header to output
                            headerScratchBytes.setPosition(0)
                            output!!.sampleData(headerScratchBytes, headerScratchBytes.limit())

                            // Prepare headerScratchBytes to read next header in the stream
                            headerScratchBytes.reset(MIN_MHAS_PACKET_HEADER_SIZE)

                            // Prepare dataScratchBytes to read new MHAS packet
                            dataScratchBytes.reset(header.packetLength)

                            headerDataFinished = true

                            // MHAS packet header finished -> obtain the packet payload
                            state = STATE_READING_PACKET_PAYLOAD
                        } else if (headerScratchBytes.limit() < MAX_MHAS_PACKET_HEADER_SIZE) {
                            headerScratchBytes.setLimit(headerScratchBytes.limit() + 1)
                            headerDataFinished = false
                        }
                    } else {
                        headerDataFinished = false
                    }
                }

                STATE_READING_PACKET_PAYLOAD -> {
                    if (shouldParsePacket(header.packetType)) {
                        copyData(data, dataScratchBytes,  true)
                    }
                    writeSampleData(data)
                    if (payloadBytesRead == header.packetLength) {
                        if (header.packetType == MhasPacketHeader.PACTYP_MPEGH3DACFG) {
                            parseConfig(ParsableBitArray(dataScratchBytes.data))
                        } else if (header.packetType == MhasPacketHeader.PACTYP_AUDIOTRUNCATION) {
                            truncationSamples =
                                MpeghUtil.parseAudioTruncationInfo(
                                    ParsableBitArray(dataScratchBytes.data)
                                )
                        } else if (header.packetType == MhasPacketHeader.PACTYP_MPEGH3DAFRAME) {
                            finalizeFrame()
                        }
                        // MHAS packet payload finished -> obtain a new packet header
                        state = STATE_READING_PACKET_HEADER
                    }
                }

                else -> throw IllegalStateException()
            }
        }
    }

    private fun copyData(
        source: ParsableByteArray, target: ParsableByteArray, resetSourcePosition: Boolean
    ) {
        val sourcePosition = source.getPosition()
        val bytesToRead = min(source.bytesLeft(), target.bytesLeft())
        source.readBytes(target.data, target.getPosition(), bytesToRead)
        target.skipBytes(bytesToRead)
        if (resetSourcePosition) {
            source.setPosition(sourcePosition)
        }
    }

    private fun skipToNextSync(pesBuffer: ParsableByteArray): Boolean {
        if ((flags and TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR) == 0) {
            // RAI is not signalled -> drop the PES data
            pesBuffer.setPosition(pesBuffer.limit())
            return false
        }

        if ((flags and TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
            // if RAI is signalled but the data is not aligned we need to find the sync packet
            while (pesBuffer.bytesLeft() > 0) {
                syncBytes = syncBytes shl C.BITS_PER_BYTE
                syncBytes = syncBytes or pesBuffer.readUnsignedByte()
                if (MpeghUtil.isSyncWord(syncBytes)) {
                    pesBuffer.setPosition(pesBuffer.getPosition() - MHAS_SYNC_WORD_LENGTH)
                    syncBytes = 0
                    return true
                }
            }
        } else {
            return true
        }
        return false
    }

    @Throws(ParserException::class)
    private fun parseHeader(): Boolean {
        val headerLength = headerScratchBytes.limit()
        headerScratchBits.reset(headerScratchBytes.data, headerLength)

        // parse the MHAS packet header
        val result = MpeghUtil.parseMhasPacketHeader(headerScratchBits, header)

        if (result) {
            payloadBytesRead = 0
            frameBytes += header.packetLength + headerLength
        }

        return result
    }

    private fun shouldParsePacket(packetType: @MhasPacketHeader.Type Int): Boolean {
        return packetType == MhasPacketHeader.PACTYP_MPEGH3DACFG
                || packetType == MhasPacketHeader.PACTYP_AUDIOTRUNCATION
    }

    @RequiresNonNull("output")
    private fun writeSampleData(data: ParsableByteArray) {
        // read bytes from input data and write them into the output
        val bytesToRead = min(data.bytesLeft(), header.packetLength - payloadBytesRead)
        output!!.sampleData(data, bytesToRead)
        payloadBytesRead += bytesToRead
    }

    @RequiresNonNull("output")
    @Throws(ParserException::class)
    private fun parseConfig(bitArray: ParsableBitArray) {
        val config = MpeghUtil.parseMpegh3daConfig(bitArray)
        samplingRate = config.samplingFrequency
        standardFrameLength = config.standardFrameLength
        if (mainStreamLabel != header.packetLabel) {
            mainStreamLabel = header.packetLabel
            // set the output format
            var codecs = "mhm1"
            if (config.profileLevelIndication != C.INDEX_UNSET) {
                codecs += String.format(".%02X", config.profileLevelIndication)
            }
            var initializationData: MutableList<ByteArray>? = null
            if (config.compatibleProfileLevelSet != null && config.compatibleProfileLevelSet.size > 0) {
                // The first entry in initializationData is reserved for the audio specific
                // config.
                initializationData =
                    mutableListOf<ByteArray>(
                        Util.EMPTY_BYTE_ARRAY,
                        config.compatibleProfileLevelSet
                    )
            }
            val format =
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(MimeTypes.AUDIO_MPEGH_MHM1)
                    .setSampleRate(samplingRate)
                    .setCodecs(codecs)
                    .setInitializationData(initializationData)
                    .build()
            output!!.format(format)
        }
        configFound = true
    }

    @RequiresNonNull("output")
    private fun finalizeFrame() {
        var flag: @BufferFlags Int = 0
        // if we have a frame with an mpegh3daConfig, set the obtained AU to a key frame
        if (configFound) {
            flag = C.BUFFER_FLAG_KEY_FRAME
            rapPending = false
        }
        val sampleDurationUs =
            C.MICROS_PER_SECOND.toDouble() * (standardFrameLength - truncationSamples) / samplingRate
        val pts = Math.round(timeUs)
        if (dataPending) {
            dataPending = false
            timeUs = timeUsPending
        } else {
            timeUs += sampleDurationUs
        }
        output!!.sampleMetadata(pts, flag, frameBytes, 0, null)
        configFound = false
        truncationSamples = 0
        frameBytes = 0
    }

    companion object {
        private const val STATE_FINDING_SYNC = 0
        private const val STATE_READING_PACKET_HEADER = 1
        private const val STATE_READING_PACKET_PAYLOAD = 2

        private const val MHAS_SYNC_WORD_LENGTH = 3
        private const val MIN_MHAS_PACKET_HEADER_SIZE = 2
        private const val MAX_MHAS_PACKET_HEADER_SIZE = 15
    }
}
