package androidx.media3.extractor.metadata.scte35

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster

import java.util.Collections

class SpliceInsertCommand private constructor(
    
    val spliceEventId: Long,
    
    val spliceEventCancelIndicator: Boolean,
    
    val outOfNetworkIndicator: Boolean,
    
    val programSpliceFlag: Boolean,
    
    val spliceImmediateFlag: Boolean,
    
    val programSplicePts: Long,
    
    val programSplicePlaybackPositionUs: Long,
    componentSpliceList: MutableList<ComponentSplice?>,
    
    val autoReturn: Boolean,
    
    val breakDurationUs: Long,
    
    val uniqueProgramId: Int,
    
    val availNum: Int,
    
    val availsExpected: Int
) : SpliceCommand() {
    
    val componentSpliceList: MutableList<ComponentSplice?>

    init {
        this.componentSpliceList =
            Collections.unmodifiableList<ComponentSplice?>(componentSpliceList)
    }

    class ComponentSplice  constructor(
        val componentTag: Int,
        val componentSplicePts: Long,
        val componentSplicePlaybackPositionUs: Long
    )

    override fun toString(): String {
        return ("SCTE-35 SpliceInsertCommand { programSplicePts="
                + programSplicePts
                + ", programSplicePlaybackPositionUs= "
                + programSplicePlaybackPositionUs
                + " }")
    }

    companion object {
        
        fun parseFromSection(
            sectionData: ParsableByteArray,
            ptsAdjustment: Long,
            timestampAdjuster: TimestampAdjuster
        ): SpliceInsertCommand {
            val spliceEventId = sectionData.readUnsignedInt()
            // splice_event_cancel_indicator(1), reserved(7).
            val spliceEventCancelIndicator = (sectionData.readUnsignedByte() and 0x80) != 0
            var outOfNetworkIndicator = false
            var programSpliceFlag = false
            var spliceImmediateFlag = false
            var programSplicePts = C.TIME_UNSET
            var componentSplices = mutableListOf<ComponentSplice?>()
            var uniqueProgramId = 0
            var availNum = 0
            var availsExpected = 0
            var autoReturn = false
            var breakDurationUs = C.TIME_UNSET
            if (!spliceEventCancelIndicator) {
                val headerByte = sectionData.readUnsignedByte()
                outOfNetworkIndicator = (headerByte and 0x80) != 0
                programSpliceFlag = (headerByte and 0x40) != 0
                val durationFlag = (headerByte and 0x20) != 0
                spliceImmediateFlag = (headerByte and 0x10) != 0
                if (programSpliceFlag && !spliceImmediateFlag) {
                    programSplicePts =
                        TimeSignalCommand.parseSpliceTime(sectionData, ptsAdjustment)
                }
                if (!programSpliceFlag) {
                    val componentCount = sectionData.readUnsignedByte()
                    componentSplices = ArrayList<ComponentSplice?>(componentCount)
                    for (i in 0..<componentCount) {
                        val componentTag = sectionData.readUnsignedByte()
                        var componentSplicePts = C.TIME_UNSET
                        if (!spliceImmediateFlag) {
                            componentSplicePts = TimeSignalCommand.parseSpliceTime(
                                sectionData,
                                ptsAdjustment
                            )
                        }
                        componentSplices.add(
                            SpliceInsertCommand.ComponentSplice(
                                componentTag,
                                componentSplicePts,
                                timestampAdjuster.adjustTsTimestamp(componentSplicePts)
                            )
                        )
                    }
                }
                if (durationFlag) {
                    val firstByte = sectionData.readUnsignedByte().toLong()
                    autoReturn = (firstByte and 0x80L) != 0L
                    val breakDuration90khz =
                        ((firstByte and 0x01L) shl 32) or sectionData.readUnsignedInt()
                    breakDurationUs = breakDuration90khz * 1000 / 90
                }
                uniqueProgramId = sectionData.readUnsignedShort()
                availNum = sectionData.readUnsignedByte()
                availsExpected = sectionData.readUnsignedByte()
            }
            return SpliceInsertCommand(
                spliceEventId,
                spliceEventCancelIndicator,
                outOfNetworkIndicator,
                programSpliceFlag,
                spliceImmediateFlag,
                programSplicePts,
                timestampAdjuster.adjustTsTimestamp(programSplicePts),
                componentSplices,
                autoReturn,
                breakDurationUs,
                uniqueProgramId,
                availNum,
                availsExpected
            )
        }
    }
}
