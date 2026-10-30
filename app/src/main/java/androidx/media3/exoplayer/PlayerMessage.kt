package androidx.media3.exoplayer

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock

import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.concurrent.TimeoutException

class PlayerMessage(
    private val sender: Sender,
    val target: Target?,
    val timeline: Timeline,
    var mediaItemIndex: Int,
    private val clock: Clock,
    var looper: Looper
) {
    interface Target {
        @Throws(ExoPlaybackException::class)
        fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?)
    }

    interface Sender {
        fun sendMessage(message: PlayerMessage)
    }

    var type: Int = 0
        private set
    var payload: Any? = null
        private set
    var positionMs: Long
        private set
    var deleteAfterDelivery: Boolean = true
        private set
    private var isSent = false
    private var isDelivered = false
    private var isProcessed = false

    @get:Synchronized
    var isCanceled: Boolean = false
        private set

    init {
        this.positionMs = C.TIME_UNSET
    }

    fun setType(messageType: Int): PlayerMessage {
        Preconditions.checkState(!isSent)
        this.type = messageType
        return this
    }

    fun setPayload(payload: Any?): PlayerMessage {
        Preconditions.checkState(!isSent)
        this.payload = payload
        return this
    }

    fun setLooper(looper: Looper): PlayerMessage {
        Preconditions.checkState(!isSent)
        this.looper = looper
        return this
    }

    fun setPosition(positionMs: Long): PlayerMessage {
        Preconditions.checkState(!isSent)
        this.positionMs = positionMs
        return this
    }

    fun setPosition(mediaItemIndex: Int, positionMs: Long): PlayerMessage {
        Preconditions.checkState(!isSent)
        Preconditions.checkArgument(positionMs != C.TIME_UNSET)
        if (mediaItemIndex < 0
            || (!timeline.isEmpty && mediaItemIndex >= timeline.windowCount)
        ) {
            throw IllegalSeekPositionException(timeline, mediaItemIndex, positionMs)
        }
        this.mediaItemIndex = mediaItemIndex
        this.positionMs = positionMs
        return this
    }

    fun setDeleteAfterDelivery(deleteAfterDelivery: Boolean): PlayerMessage {
        Preconditions.checkState(!isSent)
        this.deleteAfterDelivery = deleteAfterDelivery
        return this
    }

    fun send(): PlayerMessage {
        Preconditions.checkState(!isSent)
        if (positionMs == C.TIME_UNSET) {
            Preconditions.checkArgument(deleteAfterDelivery)
        }
        isSent = true
        sender.sendMessage(this)
        return this
    }

    @Synchronized
    fun cancel(): PlayerMessage {
        Preconditions.checkState(isSent)
        isCanceled = true
        markAsProcessed( false)
        return this
    }

    @Synchronized
    fun markAsProcessed(isDelivered: Boolean) {
        this.isDelivered = this.isDelivered or isDelivered
        isProcessed = true
        (this as Object).notifyAll()
    }

    @Synchronized
    @Throws(InterruptedException::class)
    fun blockUntilDelivered(): Boolean {
        Preconditions.checkState(isSent)
        Preconditions.checkState(looper.getThread() !== Thread.currentThread())
        while (!isProcessed) {
            (this as Object).wait()
        }
        return isDelivered
    }

    @Synchronized
    @Throws(InterruptedException::class, TimeoutException::class)
    fun blockUntilDelivered(timeoutMs: Long): Boolean {
        Preconditions.checkState(isSent)
        Preconditions.checkState(looper.getThread() !== Thread.currentThread())

        val deadlineMs = clock.elapsedRealtime() + timeoutMs
        var remainingMs = timeoutMs
        while (!isProcessed && remainingMs > 0) {
            clock.onThreadBlocked()
            (this as Object).wait(remainingMs)
            remainingMs = deadlineMs - clock.elapsedRealtime()
        }
        if (!isProcessed) {
            throw TimeoutException("Message delivery timed out.")
        }
        return isDelivered
    }
}
