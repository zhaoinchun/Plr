
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.ExtractorOutput
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.min

class PesReader(private val reader: ElementaryStreamReader) : TsPayloadReader {
    private val pesScratch: ParsableBitArray

    private var state: Int
    private var bytesRead = 0

    private lateinit var timestampAdjuster:TimestampAdjuster
    private var ptsFlag = false
    private var dtsFlag = false
    private var seenFirstDts = false
    private var extendedHeaderLength = 0
    private var payloadSize = 0
    private var dataAlignmentIndicator = false
    private var timeUs: Long = 0

    init {
        pesScratch = ParsableBitArray(ByteArray(PES_SCRATCH_SIZE))
        state = STATE_FINDING_HEADER
    }

    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    ) {
        this.timestampAdjuster = timestampAdjuster
        reader.createTracks(extractorOutput, idGenerator)
    }

    // TsPayloadReader implementation.
    override fun seek() {
        state = STATE_FINDING_HEADER
        bytesRead = 0
        seenFirstDts = false
        reader.seek()
    }

    @Throws(ParserException::class)
    override fun consume(data: ParsableByteArray, flags: @TsPayloadReader.Flags Int) {
        // Asserts init has been called.
        var flags = flags
        timestampAdjuster!!

        if ((flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0) {
            when (state) {
                STATE_FINDING_HEADER, STATE_READING_HEADER -> {}
                STATE_READING_HEADER_EXTENSION -> Log.w(
                    TAG,
                    "Unexpected start indicator reading extended header"
                )

                STATE_READING_BODY -> {
                    // If payloadSize is unset then the length of the previous packet was unspecified, and so
                    // we only know that it's finished now that we've seen the start of the next one. This is
                    // expected. If payloadSize is set, then the length of the previous packet was known, but
                    // we didn't receive that amount of data. This is not expected.
                    if (payloadSize != C.LENGTH_UNSET) {
                        Log.w(
                            TAG,
                            "Unexpected start indicator: expected " + payloadSize + " more bytes"
                        )
                    }
                    // Either way, notify the reader that it has now finished.
                    reader.packetFinished()
                }

                else -> throw IllegalStateException()
            }
            if (data.limit() == 0) {
                reader.endOfInputReached()
            }
            setState(STATE_READING_HEADER)
        }

        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_HEADER -> data.skipBytes(data.bytesLeft())
                STATE_READING_HEADER -> if (continueRead(data, pesScratch.data, HEADER_SIZE)) {
                    setState(if (parseHeader()) STATE_READING_HEADER_EXTENSION else STATE_FINDING_HEADER)
                }

                STATE_READING_HEADER_EXTENSION -> {
                    val readLength = min(MAX_HEADER_EXTENSION_SIZE, extendedHeaderLength)
                    // Read as much of the extended header as we're interested in, and skip the rest.
                    if (continueRead(data, pesScratch.data, readLength)
                        && continueRead(data,  null, extendedHeaderLength)
                    ) {
                        parseHeaderExtension()
                        flags =
                            flags or if (dataAlignmentIndicator) TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR else 0
                        reader.packetStarted(timeUs, flags)
                        setState(STATE_READING_BODY)
                    }
                }

                STATE_READING_BODY -> {
                    var readLength = data.bytesLeft()
                    val padding = if (payloadSize == C.LENGTH_UNSET) 0 else readLength - payloadSize
                    if (padding > 0) {
                        readLength -= padding
                        data.setLimit(data.getPosition() + readLength)
                    }
                    reader.consume(data)
                    if (payloadSize != C.LENGTH_UNSET) {
                        payloadSize -= readLength
                        if (payloadSize == 0) {
                            reader.packetFinished()
                            setState(STATE_READING_HEADER)
                        }
                    }
                }

                else -> throw IllegalStateException()
            }
        }
    }

    fun canConsumeSynthesizedEmptyPusi(isModeHls: Boolean): Boolean {
        // Only call parseHeader if isModeHls is true and can parse header as some HLS streams may
        // contain packages.
        val headerParsed = !isModeHls || parseHeader()
        // Either the PES packet does not have a length field and the body is being read (where the end
        // of the stream also signals the end of the body), or we are waiting for the next PES packet to
        // start and the end of the stream means there won't be any more data.
        return ((state == STATE_READING_BODY && payloadSize == C.LENGTH_UNSET)
                || (state == STATE_READING_HEADER)) // An empty PES packet with only the PUSI (Payload Unit Start Indicator) flag set, used to
                // trigger the end of the sample data, is not applicable for H262 streams. These streams
                // may, in HLS mode, have a PES packet spanning across more than one segment, which would
                // incorrectly trigger committing an unfinished sample in the middle of the access unit.
                && !(isModeHls && reader is H262Reader) && headerParsed
    }

    private fun setState(state: Int) {
        this.state = state
        bytesRead = 0
    }

    private fun continueRead(
        source: ParsableByteArray, target: ByteArray?, targetLength: Int
    ): Boolean {
        val bytesToRead = min(source.bytesLeft(), targetLength - bytesRead)
        if (bytesToRead <= 0) {
            return true
        } else if (target == null) {
            source.skipBytes(bytesToRead)
        } else {
            source.readBytes(target, bytesRead, bytesToRead)
        }
        bytesRead += bytesToRead
        return bytesRead == targetLength
    }

    private fun parseHeader(): Boolean {
        // Note: see ISO/IEC 13818-1, section 2.4.3.6 for detailed information on the format of
        // the header.
        pesScratch.position=(0)
        val startCodePrefix = pesScratch.readBits(24)
        if (startCodePrefix != 0x000001) {
            Log.w(TAG, "Unexpected start code prefix: " + startCodePrefix)
            payloadSize = C.LENGTH_UNSET
            return false
        }

        pesScratch.skipBits(8) // stream_id.
        val packetLength = pesScratch.readBits(16)
        pesScratch.skipBits(5) // '10' (2), PES_scrambling_control (2), PES_priority (1)
        dataAlignmentIndicator = pesScratch.readBit()
        pesScratch.skipBits(2) // copyright (1), original_or_copy (1)
        ptsFlag = pesScratch.readBit()
        dtsFlag = pesScratch.readBit()
        // ESCR_flag (1), ES_rate_flag (1), DSM_trick_mode_flag (1),
        // additional_copy_info_flag (1), PES_CRC_flag (1), PES_extension_flag (1)
        pesScratch.skipBits(6)
        extendedHeaderLength = pesScratch.readBits(8)

        if (packetLength == 0) {
            payloadSize = C.LENGTH_UNSET
        } else {
            payloadSize =
                ((packetLength
                        + 6 
                        ) - HEADER_SIZE
                        - extendedHeaderLength)
            if (payloadSize < 0) {
                Log.w(TAG, "Found negative packet payload size: " + payloadSize)
                payloadSize = C.LENGTH_UNSET
            }
        }
        return true
    }

    @RequiresNonNull("timestampAdjuster")
    private fun parseHeaderExtension() {
        pesScratch.position=(0)
        timeUs = C.TIME_UNSET
        if (ptsFlag) {
            pesScratch.skipBits(4) // '0010' or '0011'
            var pts = pesScratch.readBits(3).toLong() shl 30
            pesScratch.skipBits(1) // marker_bit
            pts = pts or (pesScratch.readBits(15) shl 15).toLong()
            pesScratch.skipBits(1) // marker_bit
            pts = pts or pesScratch.readBits(15).toLong()
            pesScratch.skipBits(1) // marker_bit
            if (!seenFirstDts && dtsFlag) {
                pesScratch.skipBits(4) // '0011'
                var dts = pesScratch.readBits(3).toLong() shl 30
                pesScratch.skipBits(1) // marker_bit
                dts = dts or (pesScratch.readBits(15) shl 15).toLong()
                pesScratch.skipBits(1) // marker_bit
                dts = dts or pesScratch.readBits(15).toLong()
                pesScratch.skipBits(1) // marker_bit
                // Subsequent PES packets may have earlier presentation timestamps than this one, but they
                // should all be greater than or equal to this packet's decode timestamp. We feed the
                // decode timestamp to the adjuster here so that in the case that this is the first to be
                // fed, the adjuster will be able to compute an offset to apply such that the adjusted
                // presentation timestamps of all future packets are non-negative.
                timestampAdjuster.adjustTsTimestamp(dts)
                seenFirstDts = true
            }
            timeUs = timestampAdjuster.adjustTsTimestamp(pts)
        }
    }

    companion object {
        private const val TAG = "PesReader"

        private const val STATE_FINDING_HEADER = 0
        private const val STATE_READING_HEADER = 1
        private const val STATE_READING_HEADER_EXTENSION = 2
        private const val STATE_READING_BODY = 3

        private const val HEADER_SIZE = 9
        private const val MAX_HEADER_EXTENSION_SIZE = 10
        private const val PES_SCRATCH_SIZE = 10 // max(HEADER_SIZE, MAX_HEADER_EXTENSION_SIZE)
    }
}
