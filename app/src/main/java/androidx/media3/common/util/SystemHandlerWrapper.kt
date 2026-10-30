package androidx.media3.common.util

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.GuardedBy
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue

class SystemHandlerWrapper(private val handler: Handler) : HandlerWrapper {
    override val looper: Looper
        get() = handler.getLooper()

    override fun hasMessages(what: Int): Boolean {
        // Using what == 0 when using hasMessages is dangerous as it also checks for pending Runnables.
        Preconditions.checkArgument(what != 0)
        return handler.hasMessages(what)
    }

    override fun obtainMessage(what: Int): HandlerWrapper.Message {
        return obtainSystemMessage()!!.setMessage(handler.obtainMessage(what),  this)
    }

    override fun obtainMessage(what: Int, obj: Any?): HandlerWrapper.Message {
        return obtainSystemMessage()!!.setMessage(
            handler.obtainMessage(what, obj),  
            this
        )
    }

    override fun obtainMessage(what: Int, arg1: Int, arg2: Int): HandlerWrapper.Message {
        return obtainSystemMessage()!!
            .setMessage(handler.obtainMessage(what, arg1, arg2),  this)
    }

    override fun obtainMessage(what: Int, arg1: Int, arg2: Int, obj: Any?): HandlerWrapper.Message {
        return obtainSystemMessage()!!
            .setMessage(handler.obtainMessage(what, arg1, arg2, obj),  this)
    }

    override fun sendMessageAtFrontOfQueue(message: HandlerWrapper.Message): Boolean {
        return (message as SystemMessage).sendAtFrontOfQueue(handler)
    }

    override fun sendEmptyMessage(what: Int): Boolean {
        return handler.sendEmptyMessage(what)
    }

    override fun sendEmptyMessageDelayed(what: Int, delayMs: Int): Boolean {
        return handler.sendEmptyMessageDelayed(what, delayMs.toLong())
    }

    override fun sendEmptyMessageAtTime(what: Int, uptimeMs: Long): Boolean {
        return handler.sendEmptyMessageAtTime(what, uptimeMs)
    }

    override fun removeMessages(what: Int) {
        // Using what == 0 when removing messages is dangerous as it also removes all pending Runnables.
        Preconditions.checkArgument(what != 0)
        handler.removeMessages(what)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }

    override fun removeCallbacksAndMessages(token: Any?) {
        handler.removeCallbacksAndMessages(token)
    }

    override fun post(runnable: Runnable): Boolean {
        return handler.post(runnable)
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long): Boolean {
        return handler.postDelayed(runnable, delayMs)
    }

    override fun postAtFrontOfQueue(runnable: Runnable): Boolean {
        return handler.postAtFrontOfQueue(runnable)
    }

    private class SystemMessage : HandlerWrapper.Message {
        private var message: Message? = null
        private var handler: SystemHandlerWrapper? = null

        fun setMessage(message: Message?, handler: SystemHandlerWrapper?): SystemMessage {
            this.message = message
            this.handler = handler
            return this
        }

        fun sendAtFrontOfQueue(handler: Handler): Boolean {
            val success =
                handler.sendMessageAtFrontOfQueue(message!!)
            recycle()
            return success
        }

        override fun sendToTarget() {
            message!!.sendToTarget()
            recycle()
        }

        override val target: HandlerWrapper
            get() = handler!!

        fun recycle() {
            message = null
            handler = null
            recycleMessage(this)
        }
    }

    companion object {
        private const val MAX_POOL_SIZE = 50

        @GuardedBy("messagePool")
        private val messagePool: MutableList<SystemMessage?> = ArrayList<SystemMessage?>(
            MAX_POOL_SIZE
        )

        private fun obtainSystemMessage(): SystemMessage? {
            synchronized(messagePool) {
                return if (messagePool.isEmpty())
                    SystemMessage()
                else
                    messagePool.removeAt(messagePool.size - 1)
            }
        }

        private fun recycleMessage(message: SystemMessage?) {
            synchronized(messagePool) {
                if (messagePool.size < MAX_POOL_SIZE) {
                    messagePool.add(message)
                }
            }
        }
    }
}
