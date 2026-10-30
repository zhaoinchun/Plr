package androidx.media3.common.util

import android.text.TextUtils
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.IntDef
import androidx.annotation.Size
import org.checkerframework.dataflow.qual.Pure
import java.net.UnknownHostException

object Log {
    const val LOG_LEVEL_ALL: Int = 0
    const val LOG_LEVEL_INFO: Int = 1
    const val LOG_LEVEL_WARNING: Int = 2
    const val LOG_LEVEL_ERROR: Int = 3
    const val LOG_LEVEL_OFF: Int = Int.MAX_VALUE
    private val lock = Any()

    @GuardedBy("lock")
    private var logLevel = LOG_LEVEL_ALL

    @GuardedBy("lock")
    private var logStackTraces = true

    @GuardedBy("lock")
    private var logger: Logger = Logger.DEFAULT

    @Pure
    fun getLogLevel(): @LogLevel Int {
        synchronized(lock) {
            return logLevel
        }
    }

    fun setLogLevel(logLevel: @LogLevel Int) {
        synchronized(lock) {
            this.logLevel = logLevel
        }
    }

    fun setLogStackTraces(logStackTraces: Boolean) {
        synchronized(lock) {
            this.logStackTraces = logStackTraces
        }
    }

    fun setLogger(logger: Logger) {
        synchronized(lock) {
            this.logger = logger
        }
    }

    @Pure
    fun d(@Size(max = 23) tag: String?, message: String?) {
        synchronized(lock) {
            if (logLevel == LOG_LEVEL_ALL) {
                logger.d(tag, message,  null)
            }
        }
    }

    @Pure
    fun d(@Size(max = 23) tag: String?, message: String?, throwable: Throwable?) {
        synchronized(lock) {
            if (logLevel == LOG_LEVEL_ALL) {
                logger.d(tag, message, throwable)
            }
        }
    }

    @Pure
    fun i(@Size(max = 23) tag: String?, message: String?) {
        synchronized(lock) {
            if (logLevel <= LOG_LEVEL_INFO) {
                logger.i(tag, message,  null)
            }
        }
    }

    @Pure
    fun i(@Size(max = 23) tag: String?, message: String?, throwable: Throwable?) {
        synchronized(lock) {
            if (logLevel <= LOG_LEVEL_INFO) {
                logger.i(tag, message, throwable)
            }
        }
    }

    @Pure
    fun w(@Size(max = 23) tag: String?, message: String?) {
        synchronized(lock) {
            if (logLevel <= LOG_LEVEL_WARNING) {
                logger.w(tag, message,  null)
            }
        }
    }

    @Pure
    fun w(@Size(max = 23) tag: String?, message: String?, throwable: Throwable?) {
        synchronized(lock) {
            if (logLevel <= LOG_LEVEL_WARNING) {
                logger.w(tag, message, throwable)
            }
        }
    }

    @Pure
    fun e(@Size(max = 23) tag: String?, message: String?) {
        synchronized(lock) {
            if (logLevel <= LOG_LEVEL_ERROR) {
                logger.e(tag, message,  null)
            }
        }
    }

    @Pure
    fun e(@Size(max = 23) tag: String?, message: String?, throwable: Throwable?) {
        synchronized(lock) {
            if (logLevel <= LOG_LEVEL_ERROR) {
                logger.e(tag, message, throwable)
            }
        }
    }

    @Pure
    fun getThrowableString(throwable: Throwable?): String? {
        if (throwable == null) {
            return null
        }
        synchronized(lock) {
            if (isCausedByUnknownHostException(throwable)) {
                // UnknownHostException implies the device doesn't have network connectivity.
                // UnknownHostException.getMessage() may return a string that's more verbose than desired
                // for
                // logging an expected failure mode. Conversely, android.util.Log.getStackTraceString has
                // special handling to return the empty string, which can result in logging that doesn't
                // indicate the failure mode at all. Hence we special case this exception to always return a
                // concise but useful message.
                return "UnknownHostException (no network)"
            } else if (!logStackTraces) {
                return throwable.message
            } else {
                return Log.getStackTraceString(throwable).trim { it <= ' ' }.replace("\t", "    ")
            }
        }
    }

    @Pure
    fun appendThrowableString(message: String?, throwable: Throwable?): String? {
        var message = message
        val throwableString = getThrowableString(throwable)
        if (!TextUtils.isEmpty(throwableString)) {
            message += "\n  " + throwableString!!.replace("\n", "\n  ") + '\n'
        }
        return message
    }

    @Pure
    private fun isCausedByUnknownHostException(throwable: Throwable?): Boolean {
        var throwable = throwable
        while (throwable != null) {
            if (throwable is UnknownHostException) {
                return true
            }
            throwable = throwable.cause
        }
        return false
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [LOG_LEVEL_ALL, LOG_LEVEL_INFO, LOG_LEVEL_WARNING, LOG_LEVEL_ERROR, LOG_LEVEL_OFF])
    annotation class LogLevel

    interface Logger {
        
        fun d(tag: String?, message: String?, throwable: Throwable?)

        fun i(tag: String?, message: String?, throwable: Throwable?)

        fun w(tag: String?, message: String?, throwable: Throwable?)

        fun e(tag: String?, message: String?, throwable: Throwable?)

        companion object {
            
            val DEFAULT: Logger = object : Logger {
                override fun d(tag: String?, message: String?, throwable: Throwable?) {
                    Log.d(tag, appendThrowableString(message, throwable)!!)
                }

                override fun i(tag: String?, message: String?, throwable: Throwable?) {
                    Log.i(tag, appendThrowableString(message, throwable)!!)
                }

                override fun w(tag: String?, message: String?, throwable: Throwable?) {
                    Log.w(tag, appendThrowableString(message, throwable)!!)
                }

                override fun e(tag: String?, message: String?, throwable: Throwable?) {
                    Log.e(tag, appendThrowableString(message, throwable)!!)
                }
            }
        }
    }
}
