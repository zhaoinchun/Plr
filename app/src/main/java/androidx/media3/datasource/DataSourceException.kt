package androidx.media3.datasource

import androidx.media3.common.PlaybackException
import java.io.IOException

open class DataSourceException : IOException {
    @PlaybackException.ErrorCode
    val reason: @PlaybackException.ErrorCode Int

    constructor(@PlaybackException.ErrorCode reason: @PlaybackException.ErrorCode Int) {
        this.reason = reason
    }

    constructor(
        cause: Throwable?,
        @PlaybackException.ErrorCode reason: @PlaybackException.ErrorCode Int
    ) : super(cause) {
        this.reason = reason
    }

    constructor(
        message: String?,
        @PlaybackException.ErrorCode reason: @PlaybackException.ErrorCode Int
    ) : super(message) {
        this.reason = reason
    }

    constructor(
        message: String?,
        cause: Throwable?,
        @PlaybackException.ErrorCode reason: @PlaybackException.ErrorCode Int
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

        @Deprecated("Use {@link PlaybackException#ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE}.")
        val POSITION_OUT_OF_RANGE: Int = PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
    }
}
