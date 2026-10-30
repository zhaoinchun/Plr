package androidx.media3.extractor.metadata.scte35

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster

class TimeSignalCommand private constructor(
    
    val ptsTime: Long,
    
    val playbackPositionUs: Long
) : SpliceCommand() {
    override fun toString(): String {
        return ("SCTE-35 TimeSignalCommand { ptsTime="
                + ptsTime
                + ", playbackPositionUs= "
                + playbackPositionUs
                + " }")
    }

    companion object {
        
        fun parseFromSection(
            sectionData: ParsableByteArray,
            ptsAdjustment: Long,
            timestampAdjuster: TimestampAdjuster
        ): TimeSignalCommand {
            val ptsTime: Long = parseSpliceTime(sectionData, ptsAdjustment)
            val playbackPositionUs = timestampAdjuster.adjustTsTimestamp(ptsTime)
            return TimeSignalCommand(ptsTime, playbackPositionUs)
        }

        fun parseSpliceTime(sectionData: ParsableByteArray, ptsAdjustment: Long): Long {
            val firstByte = sectionData.readUnsignedByte().toLong()
            var ptsTime = C.TIME_UNSET
            if ((firstByte and 0x80L) != 0L ) {
                // See SCTE35 9.2.1 for more information about pts adjustment.
                ptsTime = (firstByte and 0x01L) shl 32 or sectionData.readUnsignedInt()
                ptsTime += ptsAdjustment
                ptsTime = ptsTime and 0x1FFFFFFFFL
            }
            return ptsTime
        }
    }
}
