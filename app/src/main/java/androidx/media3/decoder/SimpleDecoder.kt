package androidx.media3.decoder

import androidx.annotation.CallSuper
import androidx.media3.common.C

import com.google.common.base.Preconditions
import java.util.ArrayDeque
import java.util.concurrent.Executor

abstract class SimpleDecoder<I : DecoderInputBuffer, O : DecoderOutputBuffer, E : DecoderException>(
     val numInputBuffers: Int, numOutputBuffers: Int
) :
    Decoder<I, O, E> {
    private val decodeThread: Thread

    private val lock: Any
    private val queuedInputBuffers: ArrayDeque<I>
    private val queuedOutputBuffers: ArrayDeque<O>
    private val availableOutputBuffers: Array<DecoderOutputBuffer>
    private val availableInputBuffers: Array<DecoderInputBuffer>

    private var availableInputBufferCount: Int
    private var availableOutputBufferCount: Int
    private var dequeuedInputBuffer: I? = null

    private var exception: E? = null
    private var flushed = false
    private var released = false
    private var skippedOutputBufferCount = 0
    private var outputStartTimeUs: Long

    private var executor: Executor? = null
    private var onOutputBufferAvailableRunnable: Runnable? = null
    private var onInputBufferAvailableRunnable: Runnable? = null

    init {
        lock = Any()
        outputStartTimeUs = C.TIME_UNSET
        queuedInputBuffers = ArrayDeque<I>()
        queuedOutputBuffers = ArrayDeque<O>()
        availableInputBufferCount = numInputBuffers
        availableInputBuffers = Array<DecoderInputBuffer>(availableInputBufferCount){
            createInputBuffer()
        }
        for (i in 0..<availableInputBufferCount) {
            availableInputBuffers[i] = createInputBuffer()
        }
        availableOutputBufferCount = numOutputBuffers
        availableOutputBuffers = Array<DecoderOutputBuffer>(availableOutputBufferCount){
            createOutputBuffer()
        }
        decodeThread =
            object : Thread("ExoPlayer:SimpleDecoder") {
                override fun run() {
                    this@SimpleDecoder.run()
                }
            }
        decodeThread.start()
    }

    protected fun setInitialInputBufferSize(size: Int) {
        Preconditions.checkState(availableInputBufferCount == availableInputBuffers.size)
        for (inputBuffer in availableInputBuffers) {
            inputBuffer!!.ensureSpaceForWrite(size)
        }
    }

    protected fun isAtLeastOutputStartTimeUs(timeUs: Long): Boolean {
        synchronized(lock) {
            return outputStartTimeUs == C.TIME_UNSET || timeUs >= outputStartTimeUs
        }
    }

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        synchronized(lock) {
            Preconditions.checkState(availableInputBufferCount == availableInputBuffers.size || flushed)
            this.outputStartTimeUs = outputStartTimeUs
        }
    }

    override fun setCallback(callback: Decoder.Callback?, executor: Executor?) {
        synchronized(lock) {
            this.executor = executor
            this.onOutputBufferAvailableRunnable =
                if (callback != null) Runnable { callback.onOutputBufferAvailable() } else null
            this.onInputBufferAvailableRunnable =
                if (callback != null) Runnable { callback.onInputBufferAvailable() } else null
        }
    }

    @Throws(DecoderException::class)
    override fun dequeueInputBuffer(): I? {
        synchronized(lock) {
            maybeThrowException()
            Preconditions.checkState(dequeuedInputBuffer == null)
            dequeuedInputBuffer =
                if (availableInputBufferCount == 0)
                    null
                else
                    availableInputBuffers[--availableInputBufferCount] as I
            return dequeuedInputBuffer
        }
    }

    @Throws(DecoderException::class)
    override fun queueInputBuffer(inputBuffer: I) {
        synchronized(lock) {
            maybeThrowException()
            Preconditions.checkArgument(inputBuffer === dequeuedInputBuffer)
            queuedInputBuffers.addLast(inputBuffer)
            maybeNotifyDecodeLoop()
            dequeuedInputBuffer = null
        }
    }

    @Throws(DecoderException::class)
    override fun dequeueOutputBuffer(): O? {
        synchronized(lock) {
            maybeThrowException()
            if (queuedOutputBuffers.isEmpty()) {
                return null
            }
            return queuedOutputBuffers.removeFirst()
        }
    }

    @CallSuper
    protected fun releaseOutputBuffer(outputBuffer: O?) {
        synchronized(lock) {
            releaseOutputBufferInternal(outputBuffer)
            maybeNotifyDecodeLoop()
        }
    }

    override fun flush() {
        synchronized(lock) {
            flushed = true
            skippedOutputBufferCount = 0
            if (dequeuedInputBuffer != null) {
                releaseInputBufferInternal(dequeuedInputBuffer,  false)
                dequeuedInputBuffer = null
            }
            while (!queuedInputBuffers.isEmpty()) {
                releaseInputBufferInternal(
                    queuedInputBuffers.removeFirst(),  
                    false
                )
            }
            while (!queuedOutputBuffers.isEmpty()) {
                queuedOutputBuffers.removeFirst()!!.release()
            }
        }
    }

    @CallSuper
    override fun release() {
        synchronized(lock) {
            released = true
            (lock as Object).notify()
        }
        try {
            decodeThread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Throws(DecoderException::class)
    private fun maybeThrowException() {
        val exception = this.exception
        if (exception != null) {
            throw exception
        }
    }

    private fun maybeNotifyDecodeLoop() {
        if (canDecodeBuffer()) {
            (lock as Object).notify()
        }
    }

    private fun run() {
        try {
            while (decode()) {
                // Do nothing.
            }
        } catch (e: InterruptedException) {
            // Not expected.
            throw IllegalStateException(e)
        }
    }

    @Throws(InterruptedException::class)
    private fun decode(): Boolean {
        val inputBuffer: I?
        val outputBuffer: O?
        val resetDecoder: Boolean

        // Wait until we have an input buffer to decode, and an output buffer to decode into.
        synchronized(lock) {
            while (!released && !canDecodeBuffer()) {
                (lock as Object).wait()
            }
            if (released) {
                return false
            }
            inputBuffer = queuedInputBuffers.removeFirst()
            outputBuffer = availableOutputBuffers[--availableOutputBufferCount] as O
            resetDecoder = flushed
            flushed = false
        }

        if (inputBuffer!!.isEndOfStream) {
            outputBuffer!!.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        } else {
            outputBuffer!!.timeUs = inputBuffer.timeUs
            if (inputBuffer.isFirstSample) {
                outputBuffer.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
            }
            if (!isAtLeastOutputStartTimeUs(inputBuffer.timeUs)) {
                outputBuffer.shouldBeSkipped = true
            }
            var exception: E?
            try {
                exception = decode(inputBuffer, outputBuffer, resetDecoder)
            } catch (e: RuntimeException) {
                // This can occur if a sample is malformed in a way that the decoder is not robust against.
                // We don't want the process to die in this case, but we do want to propagate the error.
                exception = createUnexpectedDecodeException(e)
            } catch (e: OutOfMemoryError) {
                // This can occur if a sample is malformed in a way that causes the decoder to think it
                // needs to allocate a large amount of memory. We don't want the process to die in this
                // case, but we do want to propagate the error.
                exception = createUnexpectedDecodeException(e)
            }
            if (exception != null) {
                synchronized(lock) {
                    this.exception = exception
                }
                return false
            }
        }

        synchronized(lock) {
            if (flushed) {
                outputBuffer.release()
            } else if (outputBuffer.shouldBeSkipped) {
                skippedOutputBufferCount++
                outputBuffer.release()
            } else {
                outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount
                skippedOutputBufferCount = 0
                queuedOutputBuffers.addLast(outputBuffer)
                val currentOnOutputBufferAvailableRunnable = this.onOutputBufferAvailableRunnable
                val currentExecutor = this.executor
                if (currentOnOutputBufferAvailableRunnable != null && currentExecutor != null) {
                    currentExecutor.execute(currentOnOutputBufferAvailableRunnable)
                }
            }
            // Make the input buffer available again.
            releaseInputBufferInternal(inputBuffer,  true)
        }

        return true
    }

    private fun canDecodeBuffer(): Boolean {
        return !queuedInputBuffers.isEmpty() && availableOutputBufferCount > 0
    }

    private fun releaseInputBufferInternal(inputBuffer: I?, notifyCallback: Boolean) {
        inputBuffer!!.clear()
        availableInputBuffers[availableInputBufferCount++] = inputBuffer
        val currentOnInputBufferAvailableRunnable = this.onInputBufferAvailableRunnable
        val currentExecutor = this.executor
        if (notifyCallback
            && currentOnInputBufferAvailableRunnable != null && currentExecutor != null
        ) {
            currentExecutor.execute(currentOnInputBufferAvailableRunnable)
        }
    }

    private fun releaseOutputBufferInternal(outputBuffer: O?) {
        outputBuffer!!.clear()
        availableOutputBuffers[availableOutputBufferCount++] = outputBuffer
    }

    protected abstract fun createInputBuffer(): I

    protected abstract fun createOutputBuffer(): O

    protected abstract fun createUnexpectedDecodeException(error: Throwable?): E

    protected abstract fun decode(inputBuffer: I, outputBuffer: O, reset: Boolean): E?
}
