
package androidx.media3.extractor.ts

import com.google.common.base.Preconditions

class NalUnitTargetBuffer(private val targetType: Int, initialCapacity: Int) {
    private var isFilling = false

    var isCompleted: Boolean = false
        private set

    var nalData: ByteArray
    var nalLength: Int = 0

    init {
        nalData = ByteArray(3 + initialCapacity)
        nalData[2] = 1
    }

    fun reset() {
        isFilling = false
        isCompleted = false
    }

    fun startNalUnit(type: Int) {
        Preconditions.checkState(!isFilling)
        isFilling = type == targetType
        if (isFilling) {
            // Skip the three byte start code when writing data.
            nalLength = 3
            isCompleted = false
        }
    }

    fun appendToNalUnit(data: ByteArray, offset: Int, limit: Int) {
        if (!isFilling) {
            return
        }
        val readLength = limit - offset
        if (nalData.size < nalLength + readLength) {
            nalData = nalData.copyOf((nalLength + readLength) * 2)
        }
        System.arraycopy(data, offset, nalData, nalLength, readLength)
        nalLength += readLength
    }

    fun endNalUnit(discardPadding: Int): Boolean {
        if (!isFilling) {
            return false
        }
        nalLength -= discardPadding
        isFilling = false
        isCompleted = true
        return true
    }
}
