package androidx.media3.exoplayer.mediacodec

import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import androidx.annotation.GuardedBy
import androidx.media3.common.util.ConditionVariable
import androidx.media3.decoder.CryptoInfo
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

internal class AsynchronousMediaCodecBufferEnqueuer (
    private val codec: MediaCodec,
    private val handlerThread: HandlerThread,
    private val conditionVariable: ConditionVariable,
    private val asyncCryptoSynchronizationEnabled: Boolean
) : MediaCodecBufferEnqueuer {
    private var handler: Handler? = null
    private val pendingRuntimeException: AtomicReference<RuntimeException?> = AtomicReference<RuntimeException?>()
    private var started = false

    constructor(
        codec: MediaCodec,
        queueingThread: HandlerThread,
        enableAsyncCryptoSynchronization: Boolean
    ) : this(
        codec,
        queueingThread,  
        ConditionVariable(),
        enableAsyncCryptoSynchronization
    )

    override fun start() {
        if (!started) {
            handlerThread.start()
            handler =
                object : Handler(handlerThread.getLooper()) {
                    override fun handleMessage(msg: Message) {
                        doHandleMessage(msg)
                    }
                }
            started = true
        }
    }

    override fun queueInputBuffer(
        index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int
    ) {
        maybeThrowException()
        val messageParams: MessageParams =
            messageParams
        messageParams.setQueueParams(index, offset, size, presentationTimeUs, flags)
        val message: Message =
            handler!!.obtainMessage(MSG_QUEUE_INPUT_BUFFER, messageParams)
        message.sendToTarget()
    }

    override fun queueSecureInputBuffer(
        index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int
    ) {
        maybeThrowException()
        val messageParams: MessageParams =
            messageParams
        messageParams.setQueueParams(index, offset,  0, presentationTimeUs, flags)
        copy(info, messageParams.cryptoInfo)
        val message: Message =
            handler!!.obtainMessage(MSG_QUEUE_SECURE_INPUT_BUFFER, messageParams)
        message.sendToTarget()
    }

    override fun setParameters(params: Bundle) {
        maybeThrowException()
        handler!!.obtainMessage(MSG_SET_PARAMETERS, params).sendToTarget()
    }

    override fun flush() {
        if (started) {
            try {
                flushHandlerThread()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                // The playback thread should not be interrupted. Raising this as an
                // IllegalStateException.
                throw IllegalStateException(e)
            }
        }
    }

    override fun shutdown() {
        if (started) {
            flush()
            handlerThread.quit()
        }
        started = false
    }

    @Throws(InterruptedException::class)
    override fun waitUntilQueueingComplete() {
        blockUntilHandlerThreadIsIdle()
    }

    override fun maybeThrowException() {
        val exception = pendingRuntimeException.getAndSet(null)
        if (exception != null) {
            throw exception
        }
    }

    @Throws(InterruptedException::class)
    private fun flushHandlerThread() {
        this.handler!! .removeCallbacksAndMessages(null)
        blockUntilHandlerThreadIsIdle()
    }

    @Throws(InterruptedException::class)
    private fun blockUntilHandlerThreadIsIdle() {
        conditionVariable.close()
        handler!!.obtainMessage(MSG_OPEN_CV).sendToTarget()
        conditionVariable.block()
    }

    fun setPendingRuntimeException(exception: RuntimeException?) {
        pendingRuntimeException.set(exception)
    }

    // Called from the handler thread
    private fun doHandleMessage(msg: Message) {
        var params: MessageParams? = null
        when (msg.what) {
            MSG_QUEUE_INPUT_BUFFER -> {
                params = msg.obj as MessageParams?
                doQueueInputBuffer(
                    params!!.index,
                    params.offset,
                    params.size,
                    params.presentationTimeUs,
                    params.flags
                )
            }

            MSG_QUEUE_SECURE_INPUT_BUFFER -> {
                params = msg.obj as MessageParams?
                doQueueSecureInputBuffer(
                    params!!.index,
                    params.offset,
                    params.cryptoInfo,
                    params.presentationTimeUs,
                    params.flags
                )
            }

            MSG_OPEN_CV -> conditionVariable.open()
            MSG_SET_PARAMETERS -> {
                val parameters = msg.obj as Bundle?
                doSetParameters(parameters)
            }

            else -> pendingRuntimeException.compareAndSet(
                null, IllegalStateException(msg.what.toString())
            )
        }
        if (params != null) {
            recycleMessageParams(params)
        }
    }

    private fun doQueueInputBuffer(
        index: Int, offset: Int, size: Int, presentationTimeUs: Long, flag: Int
    ) {
        try {
            codec.queueInputBuffer(index, offset, size, presentationTimeUs, flag)
        } catch (e: RuntimeException) {
            pendingRuntimeException.compareAndSet(null, e)
        }
    }

    private fun doQueueSecureInputBuffer(
        index: Int, offset: Int, info: MediaCodec.CryptoInfo, presentationTimeUs: Long, flags: Int
    ) {
        try {
            // Below 31, synchronize calls to MediaCodec.queueSecureInputBuffer() to avoid race conditions
            // inside the crypto module when audio and video are sharing the same DRM session
            // (see [Internal: b/149908061]).
            if (Build.VERSION.SDK_INT >= 31 && !asyncCryptoSynchronizationEnabled) {
                codec.queueSecureInputBuffer(index, offset, info, presentationTimeUs, flags)
            } else {
                synchronized(QUEUE_SECURE_LOCK) {
                    codec.queueSecureInputBuffer(index, offset, info, presentationTimeUs, flags)
                }
            }
        } catch (e: RuntimeException) {
            pendingRuntimeException.compareAndSet(null, e)
        }
    }

    private fun doSetParameters(parameters: Bundle?) {
        try {
            codec.setParameters(parameters)
        } catch (e: RuntimeException) {
            pendingRuntimeException.compareAndSet(null, e)
        }
    }

    private class MessageParams {
        var index: Int = 0
        var offset: Int = 0
        var size: Int = 0
        val cryptoInfo: MediaCodec.CryptoInfo = MediaCodec.CryptoInfo()
        var presentationTimeUs: Long = 0
        var flags: Int = 0

        fun setQueueParams(
            index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int
        ) {
            this.index = index
            this.offset = offset
            this.size = size
            this.presentationTimeUs = presentationTimeUs
            this.flags = flags
        }
    }

    companion object {
        private const val MSG_QUEUE_INPUT_BUFFER = 1
        private const val MSG_QUEUE_SECURE_INPUT_BUFFER = 2
        private const val MSG_OPEN_CV = 3
        private const val MSG_SET_PARAMETERS = 4

        @GuardedBy("MESSAGE_PARAMS_INSTANCE_POOL")
        private val MESSAGE_PARAMS_INSTANCE_POOL: ArrayDeque<MessageParams> =
            ArrayDeque<MessageParams>()

        private val QUEUE_SECURE_LOCK = Any()
        private val messageParams: MessageParams
            get() {
                synchronized(MESSAGE_PARAMS_INSTANCE_POOL) {
                    if (MESSAGE_PARAMS_INSTANCE_POOL.isEmpty()) {
                        return MessageParams()
                    } else {
                        return MESSAGE_PARAMS_INSTANCE_POOL.removeFirst()
                    }
                }
            }

        private fun recycleMessageParams(params: MessageParams?) {
            synchronized(MESSAGE_PARAMS_INSTANCE_POOL) {
                MESSAGE_PARAMS_INSTANCE_POOL.add(params)
            }
        }

        private fun copy(
            cryptoInfo: CryptoInfo, frameworkCryptoInfo: MediaCodec.CryptoInfo
        ) {
            // Update frameworkCryptoInfo fields directly because CryptoInfo.set performs an unnecessary
            // object allocation on Android N.
            frameworkCryptoInfo.numSubSamples = cryptoInfo.numSubSamples
            frameworkCryptoInfo.numBytesOfClearData =
                copy(cryptoInfo.numBytesOfClearData, frameworkCryptoInfo.numBytesOfClearData)
            frameworkCryptoInfo.numBytesOfEncryptedData =
                copy(
                    cryptoInfo.numBytesOfEncryptedData,
                    frameworkCryptoInfo.numBytesOfEncryptedData
                )
            frameworkCryptoInfo.key = copy(
                cryptoInfo.key,
                frameworkCryptoInfo.key
            )
            frameworkCryptoInfo.iv =copy(cryptoInfo.iv, frameworkCryptoInfo.iv)!!
            frameworkCryptoInfo.mode = cryptoInfo.mode
            if (Build.VERSION.SDK_INT >= 24) {
                val pattern =
                    MediaCodec.CryptoInfo.Pattern(
                        cryptoInfo.encryptedBlocks, cryptoInfo.clearBlocks
                    )
                frameworkCryptoInfo.setPattern(pattern)
            }
        }

        private fun copy(src: IntArray?, dst: IntArray?): IntArray? {
            if (src == null) {
                return dst
            }

            if (dst == null || dst.size < src.size) {
                return src.copyOf(src.size)
            } else {
                System.arraycopy(src, 0, dst, 0, src.size)
                return dst
            }
        }

        private fun copy(src: ByteArray?, dst: ByteArray?): ByteArray? {
            if (src == null) {
                return dst
            }
            if (dst == null || dst.size < src.size) {
                return src.copyOf(src.size)
            } else {
                System.arraycopy(src, 0, dst, 0, src.size)
                return dst
            }
        }
    }
}
