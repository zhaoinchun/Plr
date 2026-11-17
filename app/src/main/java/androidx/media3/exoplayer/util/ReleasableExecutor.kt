package androidx.media3.exoplayer.util

import java.util.concurrent.Executor

interface ReleasableExecutor : Executor {
    fun release()

    companion object {
        fun <T : Executor?> from(executor: T, release: (T)-> Unit): ReleasableExecutor {
            return object : ReleasableExecutor {
                override fun execute(command: Runnable) {
                    executor?.execute(command)
                }

                override fun release() {
                    release(executor)
                }
            }
        }
    }
}
