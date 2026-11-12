package androidx.media3.datasource

import androidx.media3.common.PlaybackException
import java.io.IOException

open class DataSourceException : IOException {
    val reason:  Int

    constructor( reason:  Int) {
        this.reason = reason
    }

    constructor(
        cause: Throwable?,
         reason:  Int
    ) : super(cause) {
        this.reason = reason
    }

    constructor(
        message: String?,
         reason:  Int
    ) : super(message) {
        this.reason = reason
    }

    constructor(
        message: String?,
        cause: Throwable?,
         reason:  Int
    ) : super(message, cause) {
        this.reason = reason
    }

    companion object {
        fun isCausedByPositionOutOfRange(e: IOException?): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is DataSourceException) {
                    val reason = cause.reason
                    if (reason == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
                        return true
                    }
                }
                cause = cause.cause
            }
            return false
        }

    }
}
