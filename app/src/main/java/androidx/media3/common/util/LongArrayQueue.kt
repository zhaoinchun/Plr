package androidx.media3.common.util

import androidx.annotation.VisibleForTesting
import com.google.common.base.Preconditions
import kotlin.LongArray

class LongArrayQueue  constructor(minCapacity: Int = DEFAULT_INITIAL_CAPACITY) {
    private var headIndex: Int
    private var tailIndex: Int
    private var size: Int
    private var data: LongArray
    private var wrapAroundMask: Int

    init {
        var minCapacity = minCapacity
        Preconditions.checkArgument(minCapacity >= 0 && minCapacity <= (1 shl 30))
        minCapacity = if (minCapacity == 0) 1 else minCapacity
        // If capacity isn't a power of 2, round up to the next highest power of 2.
        if (Integer.bitCount(minCapacity) != 1) {
            minCapacity = Integer.highestOneBit(minCapacity - 1) shl 1
        }
        headIndex = 0
        tailIndex = -1
        size = 0
        data = LongArray(minCapacity)
        wrapAroundMask = data.size - 1
    }

    fun add(value: Long) {
        if (size == data.size) {
            doubleArraySize()
        }

        tailIndex = (tailIndex + 1) and wrapAroundMask
        data[tailIndex] = value
        size++
    }

    fun remove(): Long {
        if (size == 0) {
            throw NoSuchElementException()
        }

        val value = data[headIndex]
        headIndex = (headIndex + 1) and wrapAroundMask
        size--

        return value
    }

    fun element(): Long {
        if (size == 0) {
            throw NoSuchElementException()
        }

        return data[headIndex]
    }

    fun size(): Int {
        return size
    }

    val isEmpty: Boolean
        
        get() = size == 0

    fun clear() {
        headIndex = 0
        tailIndex = -1
        size = 0
    }

    @VisibleForTesting
    fun capacity(): Int {
        return data.size
    }

    private fun doubleArraySize() {
        val newCapacity = data.size shl 1
        check(newCapacity >= 0)

        val newData = LongArray(newCapacity)
        val itemsToRight = data.size - headIndex
        val itemsToLeft = headIndex
        System.arraycopy(data, headIndex, newData, 0, itemsToRight)
        System.arraycopy(data, 0, newData, itemsToRight, itemsToLeft)

        headIndex = 0
        tailIndex = size - 1
        data = newData
        wrapAroundMask = data.size - 1
    }

    companion object {
        
        const val DEFAULT_INITIAL_CAPACITY: Int = 16
    }
}
