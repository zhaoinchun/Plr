package androidx.media3.common

import java.io.IOException
import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.max

class PriorityTaskManager {
    class PriorityTooLowException(priority: @C.Priority Int, highestPriority: @C.Priority Int) :
        IOException("Priority too low [priority=" + priority + ", highest=" + highestPriority + "]")

    private val lock = Any()

    // Guarded by lock.
    private val queue: PriorityQueue<Int?>
    private var highestPriority: @C.Priority Int

    init {
        queue = PriorityQueue<Int?>(10, Collections.reverseOrder<@C.Priority Int?>())
        highestPriority = Int.MIN_VALUE
    }

    fun add(priority: @C.Priority Int) {
        synchronized(lock) {
            queue.add(priority)
            highestPriority = max(highestPriority, priority)
        }
    }

    @Throws(InterruptedException::class)
    fun proceed(priority: @C.Priority Int) {
        synchronized(lock) {
            while (highestPriority != priority) {
                (lock as Object).wait()
            }
        }
    }

    fun proceedNonBlocking(priority: @C.Priority Int): Boolean {
        synchronized(lock) {
            return highestPriority == priority
        }
    }

    @Throws(PriorityTooLowException::class)
    fun proceedOrThrow(priority: @C.Priority Int) {
        synchronized(lock) {
            if (highestPriority != priority) {
                throw PriorityTooLowException(priority, highestPriority)
            }
        }
    }

    fun remove(priority: @C.Priority Int) {
        synchronized(lock) {
            queue.remove(priority)
            highestPriority =
                (if (queue.isEmpty()) kotlin.Int.MIN_VALUE else queue.peek())!!
            (lock as Object).notifyAll()
        }
    }
}
