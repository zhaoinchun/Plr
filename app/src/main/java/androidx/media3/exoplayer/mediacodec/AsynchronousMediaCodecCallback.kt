package androidx.media3.exoplayer.mediacodec

import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.GuardedBy
import androidx.media3.common.util.CircularIntArray
import com.google.common.base.Preconditions
import java.util.ArrayDeque

internal class AsynchronousMediaCodecCallback(private val callbackThread: HandlerThread) :
    MediaCodec.Callback() {
    private val lock: Any = Any()
    private var handler: Handler? = null

    @GuardedBy("lock")
    private val availableInputBuffers = CircularIntArray()

    @GuardedBy("lock")
    private val availableOutputBuffers = CircularIntArray()

    @GuardedBy("lock")
    private val bufferInfos = ArrayDeque<MediaCodec.BufferInfo>()

    @GuardedBy("lock")
    private val formats = ArrayDeque<MediaFormat?>()

    @GuardedBy("lock")
    private var currentFormat: MediaFormat? = null

    @GuardedBy("lock")
    private var pendingOutputFormat: MediaFormat? = null

    @GuardedBy("lock")
    private var mediaCodecException: CodecException? = null

    @GuardedBy("lock")
    private var mediaCodecCryptoException: MediaCodec.CryptoException? = null

    @GuardedBy("lock")
    private var pendingFlushCount: Long = 0

    @GuardedBy("lock")
    private var shutDown = false

    @GuardedBy("lock")
    private var internalException: IllegalStateException? = null

    @GuardedBy("lock")
    private var onBufferAvailableListener: MediaCodecAdapter.OnBufferAvailableListener? = null

    fun initialize(codec: MediaCodec) {
        Preconditions.checkState(handler == null)
        callbackThread.start()
        val handler = Handler(callbackThread.getLooper())
        codec.setCallback(this, handler)
        // Initialize this.handler at the very end ensuring the callback in not considered configured
        // if MediaCodec raises an exception.
        this.handler = handler
    }

    fun shutdown() {
        synchronized(lock) {
            shutDown = true
            callbackThread.quit()
            flushInternal()
        }
    }

    fun useInputBuffer(runnable: Runnable) {
        synchronized(lock) {
            // Run the Runnable under the buffer lock to ensure no new error callback can be handled
            // while the buffer is used (under the assumption that error callbacks invalidate the buffer).
            maybeThrowException()
            runnable.run()
        }
    }

    fun dequeueInputBufferIndex(): Int {
        synchronized(lock) {
            maybeThrowException()
            if (this.isFlushingOrShutdown) {
                return MediaCodec.INFO_TRY_AGAIN_LATER
            } else {
                return if (availableInputBuffers.isEmpty)
                    MediaCodec.INFO_TRY_AGAIN_LATER
                else
                    availableInputBuffers.popFirst()
            }
        }
    }

    fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int {
        synchronized(lock) {
            maybeThrowException()
            if (this.isFlushingOrShutdown) {
                return MediaCodec.INFO_TRY_AGAIN_LATER
            } else {
                if (availableOutputBuffers.isEmpty) {
                    return MediaCodec.INFO_TRY_AGAIN_LATER
                } else {
                    val bufferIndex = availableOutputBuffers.popFirst()
                    if (bufferIndex >= 0) {
                        currentFormat!!
                        val nextBufferInfo = bufferInfos.remove()
                        bufferInfo.set(
                            nextBufferInfo.offset,
                            nextBufferInfo.size,
                            nextBufferInfo.presentationTimeUs,
                            nextBufferInfo.flags
                        )
                    } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        currentFormat = formats.remove()
                    }
                    return bufferIndex
                }
            }
        }
    }

    val outputFormat: MediaFormat
        get() {
            synchronized(lock) {
                checkNotNull(currentFormat)
                return currentFormat!!
            }
        }

    fun flush() {
        synchronized(lock) {
            ++pendingFlushCount
            handler!!.post { this.onFlushCompleted() }
        }
    }

    // Called from the callback thread.
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        synchronized(lock) {
            availableInputBuffers.addLast(index)
            onBufferAvailableListener?.onInputBufferAvailable()
        }
    }

    override fun onOutputBufferAvailable(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo
    ) {
        synchronized(lock) {
            if (pendingOutputFormat != null) {
                addOutputFormat(pendingOutputFormat)
                pendingOutputFormat = null
            }
            availableOutputBuffers.addLast(index)
            bufferInfos.add(info)
            onBufferAvailableListener?.onOutputBufferAvailable()
        }
    }

    override fun onError(codec: MediaCodec, e: CodecException) {
        synchronized(lock) {
            mediaCodecException = e
        }
    }

    override fun onCryptoError(codec: MediaCodec, e: MediaCodec.CryptoException) {
        synchronized(lock) {
            mediaCodecCryptoException = e
        }
    }

    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        synchronized(lock) {
            addOutputFormat(format)
            pendingOutputFormat = null
        }
    }

    fun setOnBufferAvailableListener(
        onBufferAvailableListener: MediaCodecAdapter.OnBufferAvailableListener?
    ) {
        synchronized(lock) {
            this.onBufferAvailableListener = onBufferAvailableListener
        }
    }

    private fun onFlushCompleted() {
        synchronized(lock) {
            if (shutDown) {
                return
            }
            --pendingFlushCount
            if (pendingFlushCount > 0) {
                // Another flush() has been called.
                return
            } else if (pendingFlushCount < 0) {
                // This should never happen.
                setInternalException(IllegalStateException())
                return
            }
            flushInternal()
        }
    }

    @GuardedBy("lock")
    private fun flushInternal() {
        if (!formats.isEmpty()) {
            pendingOutputFormat = formats.getLast()
        }

        // else, pendingOutputFormat may already be non-null following a previous flush, and remains
        // set in this case.

        // mediaCodecException is not reset to null. If the codec has raised an error, then it remains
        // in FAILED_STATE even after flushing.
        availableInputBuffers.clear()
        availableOutputBuffers.clear()
        bufferInfos.clear()
        formats.clear()
    }

    @get:GuardedBy("lock")
    private val isFlushingOrShutdown: Boolean
        get() = pendingFlushCount > 0 || shutDown

    @GuardedBy("lock")
    private fun addOutputFormat(mediaFormat: MediaFormat?) {
        availableOutputBuffers.addLast(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
        formats.add(mediaFormat)
    }

    @GuardedBy("lock")
    private fun maybeThrowException() {
        maybeThrowInternalException()
        maybeThrowMediaCodecException()
        maybeThrowMediaCodecCryptoException()
    }

    @GuardedBy("lock")
    private fun maybeThrowInternalException() {
        if (internalException != null) {
            val e = internalException
            internalException = null
            throw e!!
        }
    }

    @GuardedBy("lock")
    private fun maybeThrowMediaCodecException() {
        if (mediaCodecException != null) {
            val codecException = mediaCodecException
            mediaCodecException = null
            throw codecException!!
        }
    }

    @GuardedBy("lock")
    private fun maybeThrowMediaCodecCryptoException() {
        if (mediaCodecCryptoException != null) {
            val cryptoException = mediaCodecCryptoException
            mediaCodecCryptoException = null
            throw cryptoException!!
        }
    }

    private fun setInternalException(e: IllegalStateException?) {
        synchronized(lock) {
            internalException = e
        }
    }
}
