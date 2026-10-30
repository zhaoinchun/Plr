package androidx.media3.extractor.mp3

import androidx.media3.common.C
import androidx.media3.common.util.Util.scaleLargeValue
import java.math.RoundingMode

internal object Mp3Util {
    fun computeAverageBitrate(dataSize: Long, durationUs: Long): Int {
        if (dataSize <= 0 || durationUs <= 0) {
            return C.RATE_UNSET_INT
        }
        val averageBitrate =
            scaleLargeValue(
                dataSize, C.BITS_PER_BYTE * C.MICROS_PER_SECOND, durationUs, RoundingMode.HALF_UP
            )
        return if (averageBitrate > 0 && averageBitrate <= Int.MAX_VALUE) averageBitrate.toInt() else
            C.RATE_UNSET_INT
    }
}
