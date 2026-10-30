/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.metadata.scte35

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray

import java.util.Collections

class SpliceScheduleCommand private constructor(events: MutableList<Event?>) : SpliceCommand() {

    class Event private constructor(

        val spliceEventId: Long,

        val spliceEventCancelIndicator: Boolean,

        val outOfNetworkIndicator: Boolean,

        val programSpliceFlag: Boolean,
        componentSpliceList: MutableList<ComponentSplice>,

        val utcSpliceTime: Long,

        val autoReturn: Boolean,

        val breakDurationUs: Long,

        val uniqueProgramId: Int,

        val availNum: Int,

        val availsExpected: Int
    ) {
        val componentSpliceList: List<ComponentSplice> = componentSpliceList.toList()

        companion object {
            fun parseFromSection(sectionData: ParsableByteArray): Event {
                val spliceEventId = sectionData.readUnsignedInt()
                // splice_event_cancel_indicator(1), reserved(7).
                val spliceEventCancelIndicator = (sectionData.readUnsignedByte() and 0x80) != 0
                var outOfNetworkIndicator = false
                var programSpliceFlag = false
                var utcSpliceTime = C.TIME_UNSET
                var componentSplices = mutableListOf<ComponentSplice>()
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
                    if (programSpliceFlag) {
                        utcSpliceTime = sectionData.readUnsignedInt()
                    }
                    if (!programSpliceFlag) {
                        val componentCount = sectionData.readUnsignedByte()
                        componentSplices = mutableListOf()
                        for (i in 0..<componentCount) {
                            val componentTag = sectionData.readUnsignedByte()
                            val componentUtcSpliceTime = sectionData.readUnsignedInt()
                            componentSplices.add(
                                SpliceScheduleCommand.ComponentSplice(
                                    componentTag,
                                    componentUtcSpliceTime
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
                return Event(
                    spliceEventId,
                    spliceEventCancelIndicator,
                    outOfNetworkIndicator,
                    programSpliceFlag,
                    componentSplices,
                    utcSpliceTime,
                    autoReturn,
                    breakDurationUs,
                    uniqueProgramId,
                    availNum,
                    availsExpected
                )
            }
        }
    }

    class ComponentSplice constructor(val componentTag: Int, val utcSpliceTime: Long)

    val events: MutableList<Event?>

    init {
        this.events = Collections.unmodifiableList<Event?>(events)
    }

    companion object {

        fun parseFromSection(sectionData: ParsableByteArray): SpliceScheduleCommand {
            val spliceCount = sectionData.readUnsignedByte()
            val events = ArrayList<Event?>(spliceCount)
            for (i in 0..<spliceCount) {
                events.add(Event.parseFromSection(sectionData))
            }
            return SpliceScheduleCommand(events)
        }
    }
}
