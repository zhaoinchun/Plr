package androidx.media3.common

import java.io.IOException
import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.max

class PriorityTaskManager {
    class PriorityTooLowException(priority: Int, highestPriority: Int) :
        IOException("Priority too low [priority=$priority, highest=$highestPriority]")

    private val lock = Any()

    // Guarded by lock.
    private val queue: PriorityQueue<Int?> = PriorityQueue<Int?>(10, Collections.reverseOrder<Int?>())
    private var highestPriority: Int = Int.MIN_VALUE

    fun add(priority: Int) {
        synchronized(lock) {
            queue.add(priority)
            highestPriority = max(highestPriority, priority)
        }
    }

    @Throws(InterruptedException::class)
    fun proceed(priority: Int) {
        synchronized(lock) {
            while (highestPriority != priority) {
                (lock as Object).wait()
            }
        }
    }

    fun proceedNonBlocking(priority: Int): Boolean {
        synchronized(lock) {
            return highestPriority == priority
        }
    }

    @Throws(PriorityTooLowException::class)
    fun proceedOrThrow(priority: Int) {
        synchronized(lock) {
            if (highestPriority != priority) {
                throw PriorityTooLowException(priority, highestPriority)
            }
        }
    }

    fun remove(priority: Int) {
        synchronized(lock) {
            queue.remove(priority)
            highestPriority =
                (if (queue.isEmpty()) kotlin.Int.MIN_VALUE else queue.peek())!!
            (lock as Object).notifyAll()
        }
    }
}
