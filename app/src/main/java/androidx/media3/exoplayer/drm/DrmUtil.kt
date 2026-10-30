
package androidx.media3.exoplayer.drm

import android.media.DeniedByServerException
import android.media.MediaDrm.MediaDrmStateException
import android.media.MediaDrmResetException
import android.media.NotProvisionedException
import android.os.Build
import android.os.SystemClock
import androidx.annotation.IntDef
import androidx.media3.common.PlaybackException

import androidx.media3.common.util.Util.closeQuietly
import androidx.media3.common.util.Util.getErrorCodeForMediaDrmErrorCode
import androidx.media3.common.util.Util.getErrorCodeFromPlatformDiagnosticsInfo
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.StatsDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager.MissingSchemeDataException
import androidx.media3.exoplayer.source.LoadEventInfo
import com.google.common.io.ByteStreams

object DrmUtil {
    private const val MAX_MANUAL_REDIRECTS = 5
    const val ERROR_SOURCE_EXO_MEDIA_DRM: Int = 1
    const val ERROR_SOURCE_LICENSE_ACQUISITION: Int = 2
    const val ERROR_SOURCE_PROVISIONING: Int = 3

    @PlaybackException.ErrorCode
    fun getErrorCodeForMediaDrmException(
        exception: Throwable?, @ErrorSource errorSource: @ErrorSource Int
    ): @PlaybackException.ErrorCode Int {
        if (exception is MediaDrmStateException) {
            val diagnosticsInfo: String = exception.diagnosticInfo
            val drmErrorCode = getErrorCodeFromPlatformDiagnosticsInfo(diagnosticsInfo)
            return getErrorCodeForMediaDrmErrorCode(drmErrorCode)
        } else if (exception is MediaDrmResetException) {
            return PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
        } else if (exception is NotProvisionedException
            || isFailureToConstructNotProvisionedException(exception)
        ) {
            return PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED
        } else if (exception is DeniedByServerException) {
            return PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED
        } else if (exception is UnsupportedDrmException) {
            return PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED
        } else if (exception is MissingSchemeDataException) {
            return PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR
        } else if (exception is KeysExpiredException) {
            return PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
        } else if (errorSource == ERROR_SOURCE_EXO_MEDIA_DRM) {
            // A MediaDrm exception was thrown but it was impossible to determine the cause. Because no
            // better diagnosis tools were provided, we treat this as a system error.
            return PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
        } else if (errorSource == ERROR_SOURCE_LICENSE_ACQUISITION) {
            return PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
        } else if (errorSource == ERROR_SOURCE_PROVISIONING) {
            return PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED
        } else {
            // Should never happen.
            throw IllegalArgumentException()
        }
    }

    fun isFailureToConstructNotProvisionedException(e: Throwable?): Boolean {
        return Build.VERSION.SDK_INT == 34 && e is NoSuchMethodError
                && e.message != null && e.message!!.contains("Landroid/media/NotProvisionedException;.<init>(")
    }

    fun isFailureToConstructResourceBusyException(e: Throwable?): Boolean {
        return Build.VERSION.SDK_INT == 34 && e is NoSuchMethodError
                && e.message != null && e.message!!.contains("Landroid/media/ResourceBusyException;.<init>(")
    }

    @Throws(MediaDrmCallbackException::class)
    fun executePost(
        dataSource: DataSource,
        url: String,
        httpBody: ByteArray?,
        requestProperties: Map<String, String>
    ): MediaDrmCallback.Response {
        val statsDataSource = StatsDataSource(dataSource)
        var manualRedirectCount = 0
        var dataSpec =
            DataSpec.Builder()
                .setUri(url)
                .setHttpRequestHeaders(requestProperties)
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(httpBody)
                .setFlags(DataSpec.FLAG_ALLOW_GZIP)
                .build()
        val originalDataSpec = dataSpec
        try {
            while (true) {
                val inputStream = DataSourceInputStream(statsDataSource, dataSpec)
                try {
                    val response = ByteStreams.toByteArray(inputStream)
                    val loadEventInfo =
                        LoadEventInfo.Builder(
                            -1,  // This will be replaced with the actual taskId from the request.
                            originalDataSpec,
                            SystemClock.elapsedRealtime()
                        )
                            .setUri(statsDataSource.lastOpenedUri)
                            .setResponseHeaders(statsDataSource.lastResponseHeaders!!)
                            .setBytesLoaded(response.size.toLong())
                            .build()
                    return MediaDrmCallback.Response.Builder(response)
                        .setLoadEventInfo(loadEventInfo)
                        .build()
                } catch (e: InvalidResponseCodeException) {
                    val redirectUrl = getRedirectUrl(e, manualRedirectCount)
                    if (redirectUrl == null) {
                        throw e
                    }
                    manualRedirectCount++
                    dataSpec = dataSpec.buildUpon().setUri(redirectUrl).build()
                } finally {
                    closeQuietly(inputStream)
                }
            }
        } catch (e: Exception) {
            throw MediaDrmCallbackException(
                originalDataSpec,
                statsDataSource.lastOpenedUri,
                statsDataSource.responseHeaders,
                statsDataSource.bytesRead,  
                e
            )
        }
    }

    private fun getRedirectUrl(
        exception: InvalidResponseCodeException, manualRedirectCount: Int
    ): String? {
        // For POST requests, the underlying network stack will not normally follow 307 or 308
        // redirects automatically. Do so manually here.
        val manuallyRedirect =
            (exception.responseCode == 307 || exception.responseCode == 308)
                    && manualRedirectCount < MAX_MANUAL_REDIRECTS
        if (!manuallyRedirect) {
            return null
        }
        val headerFields = exception.headerFields
        if (headerFields != null) {
            val locationHeaders = headerFields["Location"]
            if (!locationHeaders.isNullOrEmpty()) {
                return locationHeaders[0]
            }
        }
        return null
    }

     // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [ERROR_SOURCE_EXO_MEDIA_DRM, ERROR_SOURCE_LICENSE_ACQUISITION, ERROR_SOURCE_PROVISIONING]
    )
    annotation class ErrorSource
}
