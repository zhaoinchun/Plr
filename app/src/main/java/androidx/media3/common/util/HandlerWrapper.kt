package androidx.media3.common.util

import android.os.Looper

interface HandlerWrapper {
    interface Message {
        fun sendToTarget()

        val target: HandlerWrapper?
    }

    val looper: Looper

    fun hasMessages(what: Int): Boolean

    fun obtainMessage(what: Int): Message

    fun obtainMessage(what: Int, obj: Any?): Message

    fun obtainMessage(what: Int, arg1: Int, arg2: Int): Message

    fun obtainMessage(what: Int, arg1: Int, arg2: Int, obj: Any?): Message

    fun sendMessageAtFrontOfQueue(message: Message): Boolean

    fun sendEmptyMessage(what: Int): Boolean

    fun sendEmptyMessageDelayed(what: Int, delayMs: Int): Boolean

    fun sendEmptyMessageAtTime(what: Int, uptimeMs: Long): Boolean

    fun removeMessages(what: Int)

    fun removeCallbacks(runnable: Runnable)

    fun removeCallbacksAndMessages(token: Any?)

    fun post(runnable: Runnable): Boolean

    fun postDelayed(runnable: Runnable, delayMs: Long): Boolean

    fun postAtFrontOfQueue(runnable: Runnable): Boolean
}
