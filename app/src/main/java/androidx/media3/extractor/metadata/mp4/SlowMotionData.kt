package androidx.media3.extractor.metadata.mp4

import androidx.media3.common.Metadata

import androidx.media3.common.util.Util.formatInvariant
import com.google.common.base.Preconditions
import com.google.common.collect.ComparisonChain
import java.util.Objects

class SlowMotionData(val segments: MutableList<Segment?>) : Metadata.Entry {
    class Segment(startTimeMs: Long, endTimeMs: Long, speedDivisor: Int) {
        val startTimeMs: Long

        val endTimeMs: Long
        val speedDivisor: Int

        init {
            Preconditions.checkArgument(startTimeMs < endTimeMs)
            this.startTimeMs = startTimeMs
            this.endTimeMs = endTimeMs
            this.speedDivisor = speedDivisor
        }

        override fun toString(): String {
            return formatInvariant(
                "Segment: startTimeMs=%d, endTimeMs=%d, speedDivisor=%d",
                startTimeMs, endTimeMs, speedDivisor
            )
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val segment = o as Segment
            return startTimeMs == segment.startTimeMs && endTimeMs == segment.endTimeMs && speedDivisor == segment.speedDivisor
        }

        override fun hashCode(): Int {
            return Objects.hash(startTimeMs, endTimeMs, speedDivisor)
        }

        companion object {
            val BY_START_THEN_END_THEN_DIVISOR: Comparator<Segment?> =
                Comparator { s1: Segment?, s2: Segment? ->
                    ComparisonChain.start()
                        .compare(s1!!.startTimeMs, s2!!.startTimeMs)
                        .compare(s1.endTimeMs, s2.endTimeMs)
                        .compare(s1.speedDivisor, s2.speedDivisor)
                        .result()
                }
        }
    }

    init {
        Preconditions.checkArgument(
            !doSegmentsOverlap(
                segments
            )
        )
    }

    override fun toString(): String {
        return "SlowMotion: segments=" + segments
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val that = o as SlowMotionData
        return segments == that.segments
    }

    override fun hashCode(): Int {
        return segments.hashCode()
    }

    companion object {
        private fun doSegmentsOverlap(segments: MutableList<Segment?>): Boolean {
            if (segments.isEmpty()) {
                return false
            }
            var previousEndTimeMs = segments[0]!!.endTimeMs
            for (i in 1..<segments.size) {
                if (segments[i]!!.startTimeMs < previousEndTimeMs) {
                    return true
                }
                previousEndTimeMs = segments[i]!!.endTimeMs
            }

            return false
        }
    }
}
