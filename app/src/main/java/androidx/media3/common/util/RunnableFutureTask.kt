package androidx.media3.common.util

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RunnableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

abstract class RunnableFutureTask<R, E : Exception?> protected constructor() : RunnableFuture<R> {
    private val started: ConditionVariable
    private val finished: ConditionVariable
    private val cancelLock: Any

    private var exception: Exception? = null
    private var result: R? = null

    private var workThread: Thread? = null
    private var canceled = false

    init {
        started = ConditionVariable()
        finished = ConditionVariable()
        cancelLock = Any()
    }

    fun blockUntilStarted() {
        started.blockUninterruptible()
    }

    fun blockUntilFinished() {
        finished.blockUninterruptible()
    }

    // Future implementation.
    @Throws(ExecutionException::class, InterruptedException::class)
    override fun get(): R {
        finished.block()
        return getResult()
    }

    @Throws(ExecutionException::class, InterruptedException::class, TimeoutException::class)
    override fun get(timeout: Long, unit: TimeUnit?): R? {
        val timeoutMs = TimeUnit.MILLISECONDS.convert(timeout, unit)
        if (!finished.block(timeoutMs)) {
            throw TimeoutException()
        }
        return getResult()
    }

    override fun cancel(interruptIfRunning: Boolean): Boolean {
        synchronized(cancelLock) {
            if (canceled || finished.isOpen) {
                return false
            }
            canceled = true
            cancelWork()
            val workThread = this.workThread
            if (workThread != null) {
                if (interruptIfRunning) {
                    workThread.interrupt()
                }
            } else {
                started.open()
                finished.open()
            }
            return true
        }
    }

    override fun isDone(): Boolean {
        return finished.isOpen
    }

    override fun isCancelled(): Boolean {
        return canceled
    }

    // Runnable implementation.
    override fun run() {
        synchronized(cancelLock) {
            if (canceled) {
                return
            }
            workThread = Thread.currentThread()
        }
        started.open()
        try {
            result = doWork()
        } catch (e: Exception) {
            // Must be an instance of E or RuntimeException.
            exception = e
        } finally {
            synchronized(cancelLock) {
                finished.open()
                workThread = null
                // Clear the interrupted flag if set, to avoid it leaking into any subsequent tasks executed
                // using the calling thread.
                Thread.interrupted()
            }
        }
    }

    // Internal methods.
    @Throws(Exception::class)
    protected abstract fun doWork(): R

    protected open fun cancelWork() {
        // Do nothing.
    }

    // The return value is guaranteed to be non-null if and only if R is a non-null type, but there's
    // no way to assert this. Suppress the warning instead.
    @Throws(ExecutionException::class)
    private fun getResult(): R {
        if (canceled) {
            throw CancellationException()
        } else if (exception != null) {
            throw ExecutionException(exception)
        }
        return result!!
    }
}
