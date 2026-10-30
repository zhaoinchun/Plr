
package androidx.media3.exoplayer

import androidx.media3.common.util.Util.addWithOverflowDefault
import androidx.media3.common.util.Util.subtractWithOverflowDefault
import com.google.common.base.Preconditions
import kotlin.math.abs

class SeekParameters(toleranceBeforeUs: Long, toleranceAfterUs: Long) {
    val toleranceBeforeUs: Long
    val toleranceAfterUs: Long

    init {
        Preconditions.checkArgument(toleranceBeforeUs >= 0)
        Preconditions.checkArgument(toleranceAfterUs >= 0)
        this.toleranceBeforeUs = toleranceBeforeUs
        this.toleranceAfterUs = toleranceAfterUs
    }

    fun resolveSeekPositionUs(positionUs: Long, firstSyncUs: Long, secondSyncUs: Long): Long {
        if (toleranceBeforeUs == 0L && toleranceAfterUs == 0L) {
            return positionUs
        }
        val minPositionUs =
            subtractWithOverflowDefault(positionUs, toleranceBeforeUs, Long.MIN_VALUE)
        val maxPositionUs = addWithOverflowDefault(positionUs, toleranceAfterUs, Long.MAX_VALUE)
        val firstSyncPositionValid = minPositionUs <= firstSyncUs && firstSyncUs <= maxPositionUs
        val secondSyncPositionValid =
            minPositionUs <= secondSyncUs && secondSyncUs <= maxPositionUs
        if (firstSyncPositionValid && secondSyncPositionValid) {
            if (abs(firstSyncUs - positionUs) <= abs(secondSyncUs - positionUs)) {
                return firstSyncUs
            } else {
                return secondSyncUs
            }
        } else if (firstSyncPositionValid) {
            return firstSyncUs
        } else if (secondSyncPositionValid) {
            return secondSyncUs
        } else {
            return minPositionUs
        }
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as SeekParameters
        return toleranceBeforeUs == other.toleranceBeforeUs
                && toleranceAfterUs == other.toleranceAfterUs
    }

    override fun hashCode(): Int {
        return (31 * toleranceBeforeUs.toInt()) + toleranceAfterUs.toInt()
    }

    companion object {
        val EXACT: SeekParameters = SeekParameters(0, 0)
        val CLOSEST_SYNC: SeekParameters = SeekParameters(Long.MAX_VALUE, Long.MAX_VALUE)
        val PREVIOUS_SYNC: SeekParameters = SeekParameters(Long.MAX_VALUE, 0)
        val NEXT_SYNC: SeekParameters = SeekParameters(0, Long.MAX_VALUE)
        val DEFAULT: SeekParameters = EXACT
    }
}
