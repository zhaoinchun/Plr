package androidx.media3.common.util

import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class CircularIntArray {
    private var elements: IntArray
    private var head = 0
    private var tail = 0
    private var capacityBitmask = 7

    init {
        elements = IntArray(8)
    }

    fun addLast(e: Int) {
        elements[tail] = e
        tail = (tail + 1) and capacityBitmask
        if (tail == head) {
            doubleCapacity()
        }
    }

    fun popFirst(): Int {
        if (head == tail) {
            throw ArrayIndexOutOfBoundsException()
        }
        val result = elements[head]
        head = (head + 1) and capacityBitmask
        return result
    }

    fun clear() {
        tail = head
    }

    val isEmpty: Boolean
        
        get() = head == tail

    private fun doubleCapacity() {
        val n = elements.size
        val r = n - head
        val newCapacity = n shl 1
        val a = IntArray(newCapacity)
        System.arraycopy(elements, head, a, 0, r)
        System.arraycopy(elements, 0, a, r, head)
        elements = a
        head = 0
        tail = n
        capacityBitmask = newCapacity - 1
    }
}
