package androidx.media3.common.util

import android.os.Handler
import android.os.Looper
import org.checkerframework.checker.initialization.qual.UnknownInitialization

interface Clock {
    fun currentTimeMillis(): Long

    fun elapsedRealtime(): Long

    fun uptimeMillis(): Long

    fun nanoTime(): Long

    fun createHandler(
        looper: Looper, callback: @UnknownInitialization Handler.Callback?
    ): HandlerWrapper

    fun onThreadBlocked()

    companion object {
        
        val DEFAULT: Clock = SystemClock()
    }
}
