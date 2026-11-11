package androidx.media3.datasource

import android.text.TextUtils
import androidx.annotation.IntDef
import androidx.media3.common.PlaybackException
import com.google.common.base.Ascii
import com.google.common.base.Predicate
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Collections

interface HttpDataSource : DataSource {
    interface Factory : DataSource.Factory {
        override fun createDataSource(): HttpDataSource

        fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory
    }

    class RequestProperties {
        private val requestProperties: MutableMap<String, String> = mutableMapOf()
        private var requestPropertiesSnapshot: MutableMap<String, String>? = null

        @Synchronized
        fun set(name: String, value: String) {
            requestPropertiesSnapshot = null
            requestProperties[name] = value
        }

        @Synchronized
        fun set(properties: MutableMap<String, String>) {
            requestPropertiesSnapshot = null
            requestProperties.putAll(properties)
        }

        @Synchronized
        fun clearAndSet(properties: Map<String, String>) {
            requestPropertiesSnapshot = null
            requestProperties.clear()
            requestProperties.putAll(properties)
        }

        @Synchronized
        fun remove(name: String?) {
            requestPropertiesSnapshot = null
            requestProperties.remove(name)
        }

        @Synchronized
        fun clear() {
            requestPropertiesSnapshot = null
            requestProperties.clear()
        }

        @get:Synchronized
        val snapshot: Map<String, String>
            get() {
                if (requestPropertiesSnapshot == null) {
                    requestPropertiesSnapshot =
                        Collections.unmodifiableMap<String?, String?>(
                            HashMap<String?, String?>(requestProperties)
                        )
                }
                return requestPropertiesSnapshot!!
            }
    }

    abstract class BaseFactory : Factory {
        private val defaultRequestProperties: RequestProperties

        init {
            defaultRequestProperties = RequestProperties()
        }

        override fun createDataSource(): HttpDataSource {
            return createDataSourceInternal(defaultRequestProperties)
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        protected abstract fun createDataSourceInternal(
            defaultRequestProperties: RequestProperties?
        ): HttpDataSource
    }

    open class HttpDataSourceException : DataSourceException {
        val dataSpec: DataSpec?
        val type: Int

        constructor(
            dataSpec: DataSpec?,
             errorCode:  Int,
            type: Int
        ) : super(
            assignErrorCode(errorCode, type)
        ) {
            this.dataSpec = dataSpec
            this.type = type
        }

        constructor(
            message: String?,
            dataSpec: DataSpec?,
             errorCode:  Int,
            type: Int
        ) : super(message, assignErrorCode(errorCode, type)) {
            this.dataSpec = dataSpec
            this.type = type
        }

        constructor(
            cause: IOException?,
            dataSpec: DataSpec?,
             errorCode:  Int,
            type: Int
        ) : super(cause, assignErrorCode(errorCode, type)) {
            this.dataSpec = dataSpec
            this.type = type
        }

        constructor(
            message: String?,
            cause: IOException?,
            dataSpec: DataSpec?,
             errorCode:  Int,
            type: Int
        ) : super(message, cause, assignErrorCode(errorCode, type)) {
            this.dataSpec = dataSpec
            this.type = type
        }

        companion object {
            const val TYPE_OPEN: Int = 1
            const val TYPE_READ: Int = 2
            const val TYPE_CLOSE: Int = 3

            fun createForIOException(
                cause: IOException, dataSpec: DataSpec?, type: Int
            ): HttpDataSourceException {
                 val errorCode:  Int
                val message = cause.message
                if (cause is SocketTimeoutException) {
                    errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                } else if (cause is InterruptedIOException) {
                    // An interruption means the operation is being cancelled, in which case this exception
                    // should not cause the player to fail. If it does, it likely means that the owner of the
                    // operation is failing to swallow the interruption, which makes us enter an invalid state.
                    errorCode = PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                } else if (message != null
                    && Ascii.toLowerCase(message).matches("cleartext.*not permitted.*".toRegex())
                ) {
                    errorCode = PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
                } else {
                    errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                }
                return if (errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED)
                    CleartextNotPermittedException(cause, dataSpec)
                else
                    HttpDataSourceException(cause, dataSpec, errorCode, type)
            }

            
            private fun assignErrorCode(
                 errorCode:  Int,
                type: Int
            ):  Int {
                return if (errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED && type == TYPE_OPEN)
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                else
                    errorCode
            }
        }
    }

    class CleartextNotPermittedException  constructor(
        cause: IOException?,
        dataSpec: DataSpec?
    ) : HttpDataSourceException(
        "Cleartext HTTP traffic not permitted. See"
                + " https://developer.android.com/guide/topics/media/issues/cleartext-not-permitted",
        cause,
        dataSpec,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        TYPE_OPEN
    )

    class InvalidContentTypeException  constructor(
        val contentType: String?,
        dataSpec: DataSpec?
    ) : HttpDataSourceException(
        "Invalid content type: " + contentType,
        dataSpec,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        TYPE_OPEN
    )

    class InvalidResponseCodeException  constructor(
        val responseCode: Int,
        val responseMessage: String?,
        cause: IOException?,
        val headerFields: MutableMap<String?, MutableList<String?>?>?,
        dataSpec: DataSpec?,
        val responseBody: ByteArray?
    ) : HttpDataSourceException(
        "Response code: " + responseCode,
        cause,
        dataSpec,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        TYPE_OPEN
    )

    @Throws(HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long

    @Throws(HttpDataSourceException::class)
    override fun close()

    @Throws(HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun setRequestProperty(name: String, value: String)

    fun clearRequestProperty(name: String)

    fun clearAllRequestProperties()

    val responseCode: Int

    override val responseHeaders: MutableMap<String, List<String>>

    companion object {
        val REJECT_PAYWALL_TYPES: Predicate<String?> = Predicate { contentType: String? ->
            var contentType = contentType
            if (contentType == null) {
                return@Predicate false
            }
            contentType = Ascii.toLowerCase(contentType)
            !TextUtils.isEmpty(contentType) && (!contentType.contains("text") || contentType.contains(
                "text/vtt"
            ))
                    && !contentType.contains("html") && !contentType.contains("xml")
        }
    }
}
