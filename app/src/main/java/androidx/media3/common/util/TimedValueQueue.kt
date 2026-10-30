package androidx.media3.common.util

import com.google.common.base.Preconditions
import java.util.Arrays
import kotlin.LongArray

class TimedValueQueue<V>  constructor(initialBufferSize: Int = INITIAL_BUFFER_SIZE) {
    // Looping buffer for timestamps and values
    private var timestamps: LongArray
    private var values: Array<V?>
    private var first = 0
    private var size = 0

    init {
        timestamps = LongArray(initialBufferSize)
        values = newArray<V?>(initialBufferSize)
    }

    @Synchronized
    fun add(timestamp: Long, value: V?) {
        clearBufferOnTimeDiscontinuity(timestamp)
        doubleCapacityIfFull()
        addUnchecked(timestamp, value)
    }

    @Synchronized
    fun clear() {
        first = 0
        size = 0
        Arrays.fill(values, null)
    }

    @Synchronized
    fun size(): Int {
        return size
    }

    @Synchronized
    fun pollFirst(): V? {
        return if (size == 0) null else popFirst()
    }

    @Synchronized
    fun pollFloor(timestamp: Long): V? {
        return poll(timestamp,  true)
    }

    @Synchronized
    fun poll(timestamp: Long): V? {
        return poll(timestamp,  false)
    }

    private fun poll(timestamp: Long, onlyOlder: Boolean): V? {
        var value: V? = null
        var previousTimeDiff = Long.MAX_VALUE
        while (size > 0) {
            val timeDiff = timestamp - timestamps[first]
            if (timeDiff < 0 && (onlyOlder || -timeDiff >= previousTimeDiff)) {
                break
            }
            previousTimeDiff = timeDiff
            value = popFirst()
        }
        return value
    }

    private fun popFirst(): V? {
        Preconditions.checkState(size > 0)
        val value = values[first]
        values[first] = null
        first = (first + 1) % values.size
        size--
        return value
    }

    private fun clearBufferOnTimeDiscontinuity(timestamp: Long) {
        if (size > 0) {
            val last = (first + size - 1) % values.size
            if (timestamp <= timestamps[last]) {
                clear()
            }
        }
    }

    private fun doubleCapacityIfFull() {
        val capacity = values.size
        if (size < capacity) {
            return
        }
        val newCapacity = capacity * 2
        val newTimestamps = LongArray(newCapacity)
        val newValues: Array<V?> = newArray<V?>(newCapacity)
        // Reset the loop starting index to 0 while coping to the new buffer.
        // First copy the values from 'first' index to the end of original array.
        val length = capacity - first
        System.arraycopy(timestamps, first, newTimestamps, 0, length)
        System.arraycopy(values, first, newValues, 0, length)
        // Then the values from index 0 to 'first' index.
        if (first > 0) {
            System.arraycopy(timestamps, 0, newTimestamps, length, first)
            System.arraycopy(values, 0, newValues, length, first)
        }
        timestamps = newTimestamps
        values = newValues
        first = 0
    }

    private fun addUnchecked(timestamp: Long, value: V?) {
        val next = (first + size) % values.size
        timestamps[next] = timestamp
        values[next] = value
        size++
    }

    companion object {
        private const val INITIAL_BUFFER_SIZE = 10

        private fun <V> newArray(length: Int): Array<V?> {
            return arrayOfNulls<Any>(length) as Array<V?>
        }
    }
}
