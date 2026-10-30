package androidx.media3.extractor.metadata.scte35

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster

import androidx.media3.extractor.metadata.MetadataInputBuffer
import androidx.media3.extractor.metadata.SimpleMetadataDecoder

import java.nio.ByteBuffer
class SpliceInfoDecoder : SimpleMetadataDecoder() {
    private val sectionData: ParsableByteArray
    private val sectionHeader: ParsableBitArray

    private var timestampAdjuster:TimestampAdjuster? = null

    init {
        sectionData = ParsableByteArray()
        sectionHeader = ParsableBitArray()
    }

    // Buffer validated by SimpleMetadataDecoder.decode
        override fun decode(inputBuffer: MetadataInputBuffer, buffer: ByteBuffer): Metadata {
        // Internal timestamps adjustment.
        if (timestampAdjuster == null
            || inputBuffer.subsampleOffsetUs != timestampAdjuster!!.timestampOffsetUs
        ) {
            timestampAdjuster = TimestampAdjuster(inputBuffer.timeUs)
            timestampAdjuster!!.adjustSampleTimestamp(inputBuffer.timeUs - inputBuffer.subsampleOffsetUs)
        }

        val data = buffer.array()
        val size = buffer.limit()
        sectionData.reset(data, size)
        sectionHeader.reset(data, size)
        // table_id(8), section_syntax_indicator(1), private_indicator(1), reserved(2),
        // section_length(12), protocol_version(8), encrypted_packet(1), encryption_algorithm(6).
        sectionHeader.skipBits(39)
        var ptsAdjustment = sectionHeader.readBits(1).toLong()
        ptsAdjustment = (ptsAdjustment shl 32) or sectionHeader.readBits(32).toLong()
        // cw_index(8), tier(12).
        sectionHeader.skipBits(20)
        val spliceCommandLength = sectionHeader.readBits(12)
        val spliceCommandType = sectionHeader.readBits(8)
        var command: SpliceCommand? = null
        // Go to the start of the command by skipping all fields up to command_type.
        sectionData.skipBytes(14)
        when (spliceCommandType) {
            TYPE_SPLICE_NULL -> command = SpliceNullCommand()
            TYPE_SPLICE_SCHEDULE -> command =
                SpliceScheduleCommand.parseFromSection(sectionData)

            TYPE_SPLICE_INSERT -> command =
                SpliceInsertCommand.parseFromSection(
                    sectionData,
                    ptsAdjustment,
                    timestampAdjuster!!
                )

            TYPE_TIME_SIGNAL -> command = TimeSignalCommand.parseFromSection(
                sectionData,
                ptsAdjustment,
                timestampAdjuster!!
            )

            TYPE_PRIVATE_COMMAND -> command = PrivateCommand.parseFromSection(
                sectionData,
                spliceCommandLength,
                ptsAdjustment
            )

            else -> {}
        }
        return (if (command == null) Metadata() else Metadata(listOf(command))) as Metadata
    }

    companion object {
        private const val TYPE_SPLICE_NULL = 0x00
        private const val TYPE_SPLICE_SCHEDULE = 0x04
        private const val TYPE_SPLICE_INSERT = 0x05
        private const val TYPE_TIME_SIGNAL = 0x06
        private const val TYPE_PRIVATE_COMMAND = 0xFF
    }
}
