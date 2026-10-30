package androidx.media3.common.util

class ConditionVariable  constructor(private val clock: Clock = Clock.DEFAULT) {
    @get:Synchronized
    var isOpen: Boolean = false
        private set

    @Synchronized
    fun open(): Boolean {
        if (isOpen) {
            return false
        }
        isOpen = true
        (this as Object).notifyAll()
        return true
    }

    @Synchronized
    fun close(): Boolean {
        val wasOpen = isOpen
        isOpen = false
        return wasOpen
    }

    @Synchronized
    @Throws(InterruptedException::class)
    fun block() {
        while (!isOpen) {
            clock.onThreadBlocked()
            (this as Object).wait()
        }
    }

    @Synchronized
    @Throws(InterruptedException::class)
    fun block(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) {
            return isOpen
        }
        var nowMs = clock.elapsedRealtime()
        val endMs = nowMs + timeoutMs
        if (endMs < nowMs) {
            // timeoutMs is large enough for (nowMs + timeoutMs) to rollover. Block indefinitely.
            block()
        } else {
            while (!isOpen && nowMs < endMs) {
                clock.onThreadBlocked()
                (this as Object).wait(endMs - nowMs)
                nowMs = clock.elapsedRealtime()
            }
        }
        return isOpen
    }

    @Synchronized
    fun blockUninterruptible() {
        var wasInterrupted = false
        while (!isOpen) {
            try {
                clock.onThreadBlocked()
                (this as Object).wait()
            } catch (e: InterruptedException) {
                wasInterrupted = true
            }
        }
        if (wasInterrupted) {
            // Restore the interrupted status.
            Thread.currentThread().interrupt()
        }
    }

    @Synchronized
    fun blockUninterruptible(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) {
            return isOpen
        }
        var nowMs = clock.elapsedRealtime()
        val endMs = nowMs + timeoutMs
        if (endMs < nowMs) {
            // timeoutMs is large enough for (nowMs + timeoutMs) to rollover. Block indefinitely.
            blockUninterruptible()
        } else {
            var wasInterrupted = false
            while (!isOpen && nowMs < endMs) {
                try {
                    clock.onThreadBlocked()
                    (this as Object).wait(endMs - nowMs)
                } catch (e: InterruptedException) {
                    wasInterrupted = true
                }
                nowMs = clock.elapsedRealtime()
            }
            if (wasInterrupted) {
                // Restore the interrupted status.
                Thread.currentThread().interrupt()
            }
        }
        return isOpen
    }
}
