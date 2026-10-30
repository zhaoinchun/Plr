package androidx.media3.exoplayer.upstream

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection
import androidx.media3.common.util.Util.newSingleThreadExecutor
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.concurrent.ExecutorService
import kotlin.concurrent.Volatile
import kotlin.math.min

class Loader(private val downloadExecutor: ReleasableExecutor) : LoaderErrorThrower {
    class UnexpectedLoaderException(cause: Throwable) : IOException(
        ("Unexpected "
                + cause.javaClass.getSimpleName()
                + (if (cause.message != null) ": " + cause.message else "")),
        cause
    )

    interface Loadable {
        fun cancelLoad()

        @Throws(IOException::class)
        fun load()
    }

    interface Callback<T : Loadable> {
        fun onLoadStarted(
            loadable: T, elapsedRealtimeMs: Long, loadDurationMs: Long, retryCount: Int
        ) {
        }

        fun onLoadCompleted(loadable: T, elapsedRealtimeMs: Long, loadDurationMs: Long)

        fun onLoadCanceled(
            loadable: T,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            released: Boolean
        )

        fun onLoadError(
            loadable: T,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            error: IOException,
            errorCount: Int
        ): LoadErrorAction
    }

    interface ReleaseCallback {
        fun onLoaderReleased()
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [ACTION_TYPE_RETRY, ACTION_TYPE_RETRY_AND_RESET_ERROR_COUNT, ACTION_TYPE_DONT_RETRY, ACTION_TYPE_DONT_RETRY_FATAL
        ]
    )
    private annotation class RetryActionType

    class LoadErrorAction constructor(
        val type: @RetryActionType Int,
        val retryDelayMillis: Long
    ) {
        val isRetry: Boolean
            get() = type == ACTION_TYPE_RETRY || type == ACTION_TYPE_RETRY_AND_RESET_ERROR_COUNT
    }

    private var currentTask: LoadTask<out Loadable>? = null
    private var fatalError: IOException? = null

    constructor(threadNameSuffix: String?) : this(
        ReleasableExecutor.from<ExecutorService?>(
            newSingleThreadExecutor(THREAD_NAME_PREFIX + threadNameSuffix),
            androidx.media3.common.util.Consumer { obj: ExecutorService? -> obj!!.shutdown() })
    )

    fun hasFatalError(): Boolean {
        return fatalError != null
    }

    fun clearFatalError() {
        fatalError = null
    }

    fun <T : Loadable> startLoading(
        loadable: T, callback: Callback<T>, defaultMinRetryCount: Int
    ): Long {
        val looper = Looper.myLooper()!!
        fatalError = null
        val startTimeMs = SystemClock.elapsedRealtime()
        LoadTask<T>(looper, loadable, callback, defaultMinRetryCount, startTimeMs).start(0)
        return startTimeMs
    }

    val isLoading: Boolean
        get() = currentTask != null

    fun cancelLoading() {
        currentTask!!.cancel(false)
    }

    fun release(callback: ReleaseCallback? = null) {
        if (currentTask != null) {
            currentTask!!.cancel(true)
        }
        if (callback != null) {
            downloadExecutor.execute(ReleaseTask(callback))
        }
        downloadExecutor.release()
    }

    // LoaderErrorThrower implementation.
    @Throws(IOException::class)
    override fun maybeThrowError() {
        maybeThrowError(Int.MIN_VALUE)
    }

    @Throws(IOException::class)
    override fun maybeThrowError(minRetryCount: Int) {
        if (fatalError != null) {
            throw fatalError!!
        } else if (currentTask != null) {
            currentTask!!.maybeThrowError(
                if (minRetryCount == Int.MIN_VALUE) currentTask!!.defaultMinRetryCount else minRetryCount
            )
        }
    }

    // Internal classes.
    @SuppressLint("HandlerLeak")
    private inner class LoadTask<T : Loadable>(
        looper: Looper,
        private val loadable: T,
        private var callback: Loader.Callback<T>?,
        val defaultMinRetryCount: Int,
        private val startTimeMs: Long
    ) : Handler(looper), Runnable {
        private var currentError: IOException? = null
        private var errorCount = 0

        private var executorThread: Thread? = null
        private var canceled = false

        @Volatile
        private var released = false

        @Throws(IOException::class)
        fun maybeThrowError(minRetryCount: Int) {
            if (currentError != null && errorCount > minRetryCount) {
                throw currentError!!
            }
        }

        fun start(delayMillis: Long) {
            Preconditions.checkState(currentTask == null)
            currentTask = this
            if (delayMillis > 0) {
                sendEmptyMessageDelayed(MSG_START, delayMillis)
            } else {
                execute()
            }
        }

        fun cancel(released: Boolean) {
            this.released = released
            currentError = null
            if (hasMessages(MSG_START)) {
                // The task has not been given to the executor yet.
                canceled = true
                removeMessages(MSG_START)
                if (!released) {
                    sendEmptyMessage(MSG_FINISH)
                }
            } else {
                // The task has been given to the executor.
                synchronized(this) {
                    canceled = true
                    loadable!!.cancelLoad()
                    val executorThread = this.executorThread
                    if (executorThread != null) {
                        executorThread.interrupt()
                    }
                }
            }
            if (released) {
                finish()
                val nowMs = SystemClock.elapsedRealtime()
                callback!!.onLoadCanceled(loadable, nowMs, nowMs - startTimeMs, true)
                // If loading, this task will be referenced from a GC root (the loading thread) until
                // cancellation completes. The time taken for cancellation to complete depends on the
                // implementation of the Loadable that the task is loading. We null the callback reference
                // here so that it doesn't prevent garbage collection whilst cancellation is ongoing.
                callback = null
            }
        }

        override fun run() {
            try {
                val shouldLoad: Boolean
                synchronized(this) {
                    shouldLoad = !canceled
                    executorThread = Thread.currentThread()
                }
                if (shouldLoad) {
                    beginSection("load:" + loadable!!.javaClass.getSimpleName())
                    try {
                        loadable.load()
                    } finally {
                        endSection()
                    }
                }
                synchronized(this) {
                    executorThread = null
                    // Clear the interrupted flag if set, to avoid it leaking into a subsequent task.
                    Thread.interrupted()
                }
                if (!released) {
                    sendEmptyMessage(MSG_FINISH)
                }
            } catch (e: IOException) {
                if (!released) {
                    obtainMessage(MSG_IO_EXCEPTION, e).sendToTarget()
                }
            } catch (e: Exception) {
                // This should never happen, but handle it anyway.
                if (!released) {
                    e(TAG, "Unexpected exception loading stream", e)
                    obtainMessage(MSG_IO_EXCEPTION, UnexpectedLoaderException(e)).sendToTarget()
                }
            } catch (e: OutOfMemoryError) {
                // This can occur if a stream is malformed in a way that causes an extractor to think it
                // needs to allocate a large amount of memory. We don't want the process to die in this
                // case, but we do want the playback to fail.
                if (!released) {
                    e(TAG, "OutOfMemory error loading stream", e)
                    obtainMessage(MSG_IO_EXCEPTION, UnexpectedLoaderException(e)).sendToTarget()
                }
            } catch (e: Error) {
                // We'd hope that the platform would shut down the process if an Error is thrown here, but
                // the executor may catch the error (b/20616433). Throw it here, but also pass and throw it
                // from the handler thread so the process dies even if the executor behaves in this way.
                if (!released) {
                    e(TAG, "Unexpected error loading stream", e)
                    obtainMessage(MSG_FATAL_ERROR, e).sendToTarget()
                }
                throw e
            }
        }

        override fun handleMessage(msg: Message) {
            if (released) {
                return
            }
            if (msg.what == MSG_START) {
                execute()
                return
            }
            if (msg.what == MSG_FATAL_ERROR) {
                throw msg.obj as Error
            }
            finish()
            val nowMs = SystemClock.elapsedRealtime()
            val durationMs = nowMs - startTimeMs
            val callback = this.callback!!
            if (canceled) {
                callback.onLoadCanceled(loadable, nowMs, durationMs, false)
                return
            }
            when (msg.what) {
                MSG_FINISH -> try {
                    callback.onLoadCompleted(loadable, nowMs, durationMs)
                } catch (e: RuntimeException) {
                    // This should never happen, but handle it anyway.
                    e(TAG, "Unexpected exception handling load completed", e)
                    fatalError = UnexpectedLoaderException(e)
                }

                MSG_IO_EXCEPTION -> {
                    currentError = msg.obj as IOException?
                    errorCount++
                    val action =
                        callback.onLoadError(
                            loadable,
                            nowMs,
                            durationMs,
                            currentError!!,
                            errorCount
                        )
                    if (action.type == ACTION_TYPE_DONT_RETRY_FATAL) {
                        fatalError = currentError
                    } else if (action.type != ACTION_TYPE_DONT_RETRY) {
                        if (action.type == ACTION_TYPE_RETRY_AND_RESET_ERROR_COUNT) {
                            errorCount = 1
                        }
                        start(
                            if (action.retryDelayMillis != C.TIME_UNSET)
                                action.retryDelayMillis
                            else
                                this.retryDelayMillis
                        )
                    }
                }

                else -> {}
            }
        }

        fun execute() {
            val nowMs = SystemClock.elapsedRealtime()
            val durationMs = nowMs - startTimeMs
            this.callback!!.onLoadStarted(loadable, nowMs, durationMs, errorCount)
            currentError = null
            downloadExecutor.execute(currentTask!!)
        }

        fun finish() {
            currentTask = null
        }

        val retryDelayMillis: Long
            get() = min((errorCount - 1) * 1000, 5000).toLong()

    }

    private class ReleaseTask(private val callback: ReleaseCallback) : Runnable {
        override fun run() {
            callback.onLoaderReleased()
        }
    }

    companion object {
        private const val TAG = "LoadTask"
        private const val MSG_START = 1
        private const val MSG_FINISH = 2
        private const val MSG_IO_EXCEPTION = 3
        private const val MSG_FATAL_ERROR = 4

        private const val THREAD_NAME_PREFIX = "ExoPlayer:Loader:"

        private const val ACTION_TYPE_RETRY = 0
        private const val ACTION_TYPE_RETRY_AND_RESET_ERROR_COUNT = 1
        private const val ACTION_TYPE_DONT_RETRY = 2
        private const val ACTION_TYPE_DONT_RETRY_FATAL = 3

        val RETRY: LoadErrorAction = createRetryAction(false, C.TIME_UNSET)
        val RETRY_RESET_ERROR_COUNT: LoadErrorAction =
            createRetryAction(true, C.TIME_UNSET)
        val DONT_RETRY: LoadErrorAction = LoadErrorAction(ACTION_TYPE_DONT_RETRY, C.TIME_UNSET)
        val DONT_RETRY_FATAL: LoadErrorAction =
            LoadErrorAction(ACTION_TYPE_DONT_RETRY_FATAL, C.TIME_UNSET)

        fun createRetryAction(resetErrorCount: Boolean, retryDelayMillis: Long): LoadErrorAction {
            return LoadErrorAction(
                if (resetErrorCount) ACTION_TYPE_RETRY_AND_RESET_ERROR_COUNT else ACTION_TYPE_RETRY,
                retryDelayMillis
            )
        }
    }
}
