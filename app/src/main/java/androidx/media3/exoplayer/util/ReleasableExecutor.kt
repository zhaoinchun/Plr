package androidx.media3.exoplayer.util

import androidx.media3.common.util.Consumer
import java.util.concurrent.Executor

interface ReleasableExecutor : Executor {
    fun release()

    companion object {
        fun <T : Executor?> from(executor: T, releaseCallback: Consumer<T>): ReleasableExecutor {
            return object : ReleasableExecutor {
                override fun execute(command: Runnable) {
                    executor?.execute(command)
                }

                override fun release() {
                    releaseCallback.accept(executor)
                }
            }
        }
    }
}
