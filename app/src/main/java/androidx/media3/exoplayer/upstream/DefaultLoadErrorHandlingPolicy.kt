package androidx.media3.exoplayer.upstream

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException

import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource.CleartextNotPermittedException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min

class DefaultLoadErrorHandlingPolicy(private val minimumLoadableRetryCount: Int = DEFAULT_BEHAVIOR_MIN_LOADABLE_RETRY_COUNT) :
    LoadErrorHandlingPolicy {

    override fun getFallbackSelectionFor(
        fallbackOptions: FallbackOptions, loadErrorInfo: LoadErrorInfo
    ): FallbackSelection? {
        if (!isEligibleForFallback(loadErrorInfo.exception)) {
            return null
        }
        if (!fallbackOptions.locationSteeringActive) {
            if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)) {
                return FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                    DEFAULT_LOCATION_EXCLUSION_MS
                )
            } else if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) {
                return FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                    DEFAULT_TRACK_EXCLUSION_MS
                )
            }
        } else {
            if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) {
                return FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                    DEFAULT_TRACK_EXCLUSION_MS
                )
            } else if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)) {
                return FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                    DEFAULT_LOCATION_EXCLUSION_MS
                )
            }
        }
        return null
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        return if (isAnyCauseNonRetriable(loadErrorInfo.exception))
            C.TIME_UNSET
        else
            min((loadErrorInfo.errorCount - 1) * 1000, 5000).toLong()
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        if (minimumLoadableRetryCount == DEFAULT_BEHAVIOR_MIN_LOADABLE_RETRY_COUNT) {
            return if (dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE)
                DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE
            else
                DEFAULT_MIN_LOADABLE_RETRY_COUNT
        } else {
            return minimumLoadableRetryCount
        }
    }

    protected fun isEligibleForFallback(exception: IOException?): Boolean {
        if (exception !is InvalidResponseCodeException) {
            return false
        }
        return exception.responseCode == 403 // HTTP 403 Forbidden.
                || exception.responseCode == 404 // HTTP 404 Not Found.
                || exception.responseCode == 410 // HTTP 410 Gone.
                || exception.responseCode == 416 // HTTP 416 Range Not Satisfiable.
                || exception.responseCode == 500 // HTTP 500 Internal Server Error.
                || exception.responseCode == 503 // HTTP 503 Service Unavailable.
    }

    private fun isAnyCauseNonRetriable(exception: Throwable?): Boolean {
        var exception = exception
        while (exception != null) {
            if (isNonRetriableException(exception)) {
                return true
            }
            exception = exception.cause
        }
        return false
    }

    private fun isNonRetriableException(exception: Throwable?): Boolean {
        return exception is ParserException
                || exception is FileNotFoundException
                || exception is CleartextNotPermittedException
                || exception is UnexpectedLoaderException
                || (exception is DataSourceException
                && (exception.reason
                == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
    }

    companion object {
        const val DEFAULT_MIN_LOADABLE_RETRY_COUNT: Int = 3
        const val DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE: Int = 6
        const val DEFAULT_TRACK_EXCLUSION_MS: Long = 60000

        @Deprecated("Use {@link #DEFAULT_TRACK_EXCLUSION_MS} instead.")
        val DEFAULT_TRACK_BLACKLIST_MS: Long = DEFAULT_TRACK_EXCLUSION_MS
        val DEFAULT_LOCATION_EXCLUSION_MS: Long = (5 * 60000).toLong()
        private val DEFAULT_BEHAVIOR_MIN_LOADABLE_RETRY_COUNT = -1
    }
}
