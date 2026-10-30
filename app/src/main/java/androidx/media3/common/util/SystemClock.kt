package androidx.media3.common.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.checkerframework.checker.initialization.qual.UnknownInitialization

class SystemClock constructor() : Clock {
    override fun currentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    override fun elapsedRealtime(): Long {
        return SystemClock.elapsedRealtime()
    }

    override fun uptimeMillis(): Long {
        return SystemClock.uptimeMillis()
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }

    override fun createHandler(
        looper: Looper, callback: @UnknownInitialization Handler.Callback?
    ): HandlerWrapper {
        return SystemHandlerWrapper(Handler(looper, callback))
    }

    override fun onThreadBlocked() {
        // Do nothing.
    }
}
