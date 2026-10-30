package androidx.media3.common.util

import androidx.media3.common.util.Util.newSingleThreadExecutor
import java.util.concurrent.Executor

object BackgroundExecutor {
    private var staticInstance: Executor? = null

    @Synchronized
    fun get(): Executor {
        if (staticInstance == null) {
            staticInstance = newSingleThreadExecutor("ExoPlayer:BackgroundExecutor")
        }
        return staticInstance!!
    }

    @Synchronized
    fun set(executor: Executor) {
        staticInstance = executor
    }
}
