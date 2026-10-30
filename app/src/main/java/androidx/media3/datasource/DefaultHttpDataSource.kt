package androidx.media3.datasource

import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Util
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidContentTypeException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.HttpDataSource.RequestProperties
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.collect.ForwardingMap
import com.google.common.collect.Sets
import com.google.common.io.ByteStreams
import com.google.common.net.HttpHeaders
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.URL
import java.util.NavigableSet
import java.util.zip.GZIPInputStream
import kotlin.math.min

class DefaultHttpDataSource private constructor(
    private val userAgent: String?,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val allowCrossProtocolRedirects: Boolean,
    private val crossProtocolRedirectsForceOriginal: Boolean,
    defaultRequestProperties: RequestProperties?,
    contentTypePredicate: Predicate<String>?,
    keepPostFor302Redirects: Boolean
) : BaseDataSource( true), HttpDataSource {
    
    class Factory : HttpDataSource.Factory {
        private val defaultRequestProperties: RequestProperties = RequestProperties()
        private var transferListener: TransferListener? = null
        private var contentTypePredicate: Predicate<String>? = null
        private var userAgent: String? = null
        private var connectTimeoutMs: Int= DEFAULT_CONNECT_TIMEOUT_MILLIS
        private var readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MILLIS
        private var allowCrossProtocolRedirects = false
        private var crossProtocolRedirectsForceOriginal = false
        private var keepPostFor302Redirects = false

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        fun setUserAgent(userAgent: String?): Factory {
            this.userAgent = userAgent
            return this
        }

        fun setConnectTimeoutMs(connectTimeoutMs: Int): Factory {
            this.connectTimeoutMs = connectTimeoutMs
            return this
        }

        fun setReadTimeoutMs(readTimeoutMs: Int): Factory {
            this.readTimeoutMs = readTimeoutMs
            return this
        }

        fun setAllowCrossProtocolRedirects(allowCrossProtocolRedirects: Boolean): Factory {
            this.allowCrossProtocolRedirects = allowCrossProtocolRedirects
            return this
        }

        fun setCrossProtocolRedirectsForceOriginal(
            crossProtocolRedirectsForceOriginal: Boolean
        ): Factory {
            this.crossProtocolRedirectsForceOriginal = crossProtocolRedirectsForceOriginal
            return this
        }

        fun setContentTypePredicate(contentTypePredicate: Predicate<String>?): Factory {
            this.contentTypePredicate = contentTypePredicate
            return this
        }

        fun setTransferListener(transferListener: TransferListener?): Factory {
            this.transferListener = transferListener
            return this
        }

        fun setKeepPostFor302Redirects(keepPostFor302Redirects: Boolean): Factory {
            this.keepPostFor302Redirects = keepPostFor302Redirects
            return this
        }

        override fun createDataSource(): DefaultHttpDataSource {
            val dataSource =
                DefaultHttpDataSource(
                    userAgent,
                    connectTimeoutMs,
                    readTimeoutMs,
                    allowCrossProtocolRedirects,
                    crossProtocolRedirectsForceOriginal,
                    defaultRequestProperties,
                    contentTypePredicate,
                    keepPostFor302Redirects
                )
            transferListener?.also {
                dataSource.addTransferListener(it)
            }
            return dataSource
        }
    }

    private val defaultRequestProperties: RequestProperties?
    private val requestProperties: RequestProperties
    private val contentTypePredicate: Predicate<String>?
    private val keepPostFor302Redirects: Boolean

    private var dataSpec: DataSpec? = null
    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var transferStarted = false
    private var _responseCode = 0
    private var bytesToRead: Long = 0
    private var bytesRead: Long = 0

    init {
        require(!(allowCrossProtocolRedirects && crossProtocolRedirectsForceOriginal)) {
            ("crossProtocolRedirectsForceOriginal should not be set if allowCrossProtocolRedirects is"
                    + " true")
        }
        this.defaultRequestProperties = defaultRequestProperties
        this.contentTypePredicate = contentTypePredicate
        this.requestProperties = RequestProperties()
        this.keepPostFor302Redirects = keepPostFor302Redirects
    }

    override val uri: Uri?
        get() {
            if (connection != null) {
                return Uri.parse(connection!!.getURL().toString())
            } else if (dataSpec != null) {
                return dataSpec!!.uri
            } else {
                return null
            }
        }

    override val responseCode: Int
        get() = if (connection == null || _responseCode <= 0) -1 else _responseCode

    override val responseHeaders: MutableMap<String, List<String>>
        get() {
            if (connection == null) {
                return mutableMapOf()
            }
            // connection.getHeaderFields() always contains a null key with a value like
            // ["HTTP/1.1 200 OK"]. The response code is available from HttpURLConnection#getResponseCode()
            // and the HTTP version is fixed when establishing the connection.
            // DataSource#getResponseHeaders() doesn't allow null keys in the returned map, so we need to
            // remove it.
            // connection.getHeaderFields() returns a special unmodifiable case-insensitive Map
            // so we can't just remove the null key or make a copy without the null key. Instead we wrap it
            // in a ForwardingMap subclass that ignores and filters out null keys in the read methods.
            return NullFilteringHeadersMap(connection!!.headerFields)
        }

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    @Throws(HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        val responseMessage: String?
        val connection: HttpURLConnection?
        try {
            TrafficStats.setThreadStatsTag(currentThreadId.toInt())
            this.connection = makeConnection(dataSpec)
            connection = this.connection
            _responseCode = connection!!.getResponseCode()
            responseMessage = connection.getResponseMessage()
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSourceException.TYPE_OPEN
            )
        }

        // Check for a valid response code.
        if (_responseCode < 200 || _responseCode > 299) {
            val headers = connection.getHeaderFields()
            if (_responseCode == 416) {
                val documentSize =
                    HttpUtil.getDocumentSize(connection.getHeaderField(HttpHeaders.CONTENT_RANGE))
                if (dataSpec.position == documentSize) {
                    transferStarted = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
                }
            }

            val errorStream = connection.getErrorStream()
            var errorResponseBody: ByteArray?
            try {
                errorResponseBody =
                    if (errorStream != null) ByteStreams.toByteArray(errorStream) else Util.EMPTY_BYTE_ARRAY
            } catch (e: IOException) {
                errorResponseBody = Util.EMPTY_BYTE_ARRAY
            }
            closeConnectionQuietly()
            val cause: IOException? =
                if (_responseCode == 416)
                    DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                else
                    null
            throw InvalidResponseCodeException(
                _responseCode, responseMessage, cause, headers, dataSpec, errorResponseBody
            )
        }

        // Check for a valid content type.
        val contentType = connection.getContentType()
        if (contentTypePredicate != null && !contentTypePredicate.apply(contentType)) {
            closeConnectionQuietly()
            throw InvalidContentTypeException(contentType, dataSpec)
        }

        // If we requested a range starting from a non-zero position and received a 200 rather than a
        // 206, then the server does not support partial requests. We'll need to manually skip to the
        // requested position.
        val bytesToSkip =
            if (_responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0

        // Determine the length of the data to be read, after skipping.
        val isCompressed: Boolean = Companion.isCompressed(connection)
        if (!isCompressed) {
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                bytesToRead = dataSpec.length
            } else {
                val contentLength =
                    HttpUtil.getContentLength(
                        connection.getHeaderField(HttpHeaders.CONTENT_LENGTH),
                        connection.getHeaderField(HttpHeaders.CONTENT_RANGE)
                    )
                bytesToRead =
                    if (contentLength != C.LENGTH_UNSET.toLong()) (contentLength - bytesToSkip) else C.LENGTH_UNSET.toLong()
            }
        } else {
            // Gzip is enabled. If the server opts to use gzip then the content length in the response
            // will be that of the compressed data, which isn't what we want. Always use the dataSpec
            // length in this case.
            bytesToRead = dataSpec.length
        }

        try {
            inputStream = connection.getInputStream()
            if (isCompressed) {
                inputStream = GZIPInputStream(inputStream)
            }
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw HttpDataSourceException(
                e,
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSourceException.TYPE_OPEN
            )
        }

        transferStarted = true
        transferStarted(dataSpec)

        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: IOException) {
            closeConnectionQuietly()

            if (e is HttpDataSourceException) {
                throw e
            }
            throw HttpDataSourceException(
                e,
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSourceException.TYPE_OPEN
            )
        }

        return bytesToRead
    }

    @Throws(HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            return readInternal(buffer, offset, length)
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSourceException.TYPE_READ
            )
        }
    }

    @Throws(HttpDataSourceException::class)
    override fun close() {
        try {
            val inputStream = this.inputStream
            if (inputStream != null) {
                try {
                    inputStream.close()
                } catch (e: IOException) {
                    throw HttpDataSourceException(
                        e,
                        dataSpec,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        HttpDataSourceException.TYPE_CLOSE
                    )
                }
            }
        } finally {
            inputStream = null
            closeConnectionQuietly()
            if (transferStarted) {
                transferStarted = false
                transferEnded()
            }
            connection = null
            dataSpec = null
            TrafficStats.clearThreadStatsTag()
        }
    }

    @Throws(IOException::class)
    private fun makeConnection(dataSpec: DataSpec): HttpURLConnection {
        var url = URL(dataSpec.uri.toString())
        var httpMethod: @DataSpec.HttpMethod Int = dataSpec.httpMethod
        var httpBody = dataSpec.httpBody
        val position = dataSpec.position
        val length = dataSpec.length
        val allowGzip = dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)

        if (!allowCrossProtocolRedirects && !crossProtocolRedirectsForceOriginal && !keepPostFor302Redirects) {
            // HttpURLConnection disallows cross-protocol redirects, but otherwise performs redirection
            // automatically. This is the behavior we want, so use it.
            return makeConnection(
                url,
                httpMethod,
                httpBody,
                position,
                length,
                allowGzip,  
                true,
                dataSpec.httpRequestHeaders
            )
        }

        // We need to handle redirects ourselves to allow cross-protocol redirects or to keep the POST
        // request method for 302.
        var redirectCount = 0
        while (redirectCount++ <= MAX_REDIRECTS) {
            val connection =
                makeConnection(
                    url,
                    httpMethod,
                    httpBody,
                    position,
                    length,
                    allowGzip,  
                    false,
                    dataSpec.httpRequestHeaders
                )
            val responseCode = connection.getResponseCode()
            val location = connection.getHeaderField("Location")
            if ((httpMethod == DataSpec.HTTP_METHOD_GET || httpMethod == DataSpec.HTTP_METHOD_HEAD)
                && (responseCode == HttpURLConnection.HTTP_MULT_CHOICE || responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER || responseCode == HTTP_STATUS_TEMPORARY_REDIRECT || responseCode == HTTP_STATUS_PERMANENT_REDIRECT)
            ) {
                connection.disconnect()
                url = handleRedirect(url, location, dataSpec)
            } else if (httpMethod == DataSpec.HTTP_METHOD_POST
                && (responseCode == HttpURLConnection.HTTP_MULT_CHOICE || responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER)
            ) {
                connection.disconnect()
                val shouldKeepPost =
                    keepPostFor302Redirects && responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                if (!shouldKeepPost) {
                    // POST request follows the redirect and is transformed into a GET request.
                    httpMethod = DataSpec.HTTP_METHOD_GET
                    httpBody = null
                }
                url = handleRedirect(url, location, dataSpec)
            } else {
                return connection
            }
        }

        // If we get here we've been redirected more times than are permitted.
        throw HttpDataSourceException(
            NoRouteToHostException("Too many redirects: " + redirectCount),
            dataSpec,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            HttpDataSourceException.TYPE_OPEN
        )
    }

    @Throws(IOException::class)
    private fun makeConnection(
        url: URL,
        httpMethod: @DataSpec.HttpMethod Int,
        httpBody: ByteArray?,
        position: Long,
        length: Long,
        allowGzip: Boolean,
        followRedirects: Boolean,
        requestParameters: MutableMap<String, String>
    ): HttpURLConnection {
        val connection = openConnection(url)
        connection.setConnectTimeout(connectTimeoutMillis)
        connection.setReadTimeout(readTimeoutMillis)

        val requestHeaders: MutableMap<String, String> = mutableMapOf()
        if (defaultRequestProperties != null) {
            requestHeaders.putAll(defaultRequestProperties.snapshot)
        }
        requestHeaders.putAll(requestProperties.snapshot)
        requestHeaders.putAll(requestParameters)

        for (property in requestHeaders.entries) {
            connection.setRequestProperty(property.key, property.value)
        }

        val rangeHeader = HttpUtil.buildRangeRequestHeader(position, length)
        if (rangeHeader != null) {
            connection.setRequestProperty(HttpHeaders.RANGE, rangeHeader)
        }
        if (userAgent != null) {
            connection.setRequestProperty(HttpHeaders.USER_AGENT, userAgent)
        }
        connection.setRequestProperty(
            HttpHeaders.ACCEPT_ENCODING,
            if (allowGzip) "gzip" else "identity"
        )
        connection.setInstanceFollowRedirects(followRedirects)
        connection.setDoOutput(httpBody != null)
        connection.setRequestMethod(DataSpec.getStringForHttpMethod(httpMethod))

        if (httpBody != null) {
            connection.setFixedLengthStreamingMode(httpBody.size)
            connection.connect()
            val os = connection.getOutputStream()
            os.write(httpBody)
            os.close()
        } else {
            connection.connect()
        }
        return connection
    }

    @Throws(IOException::class)
    fun openConnection(url: URL): HttpURLConnection {
        return url.openConnection() as HttpURLConnection
    }

    @Throws(HttpDataSourceException::class)
    private fun handleRedirect(originalUrl: URL, location: String?, dataSpec: DataSpec?): URL {
        if (location == null) {
            throw HttpDataSourceException(
                "Null location redirect",
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSourceException.TYPE_OPEN
            )
        }
        // Form the new url.
        var url: URL
        try {
            url = URL(originalUrl, location)
        } catch (e: MalformedURLException) {
            throw HttpDataSourceException(
                e,
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSourceException.TYPE_OPEN
            )
        }

        // Check that the protocol of the new url is supported.
        val protocol = url.getProtocol()
        if ("https" != protocol && "http" != protocol) {
            throw HttpDataSourceException(
                "Unsupported protocol redirect: " + protocol,
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSourceException.TYPE_OPEN
            )
        }
        if (!allowCrossProtocolRedirects && protocol != originalUrl.getProtocol()) {
            if (!crossProtocolRedirectsForceOriginal) {
                throw HttpDataSourceException(
                    ("Disallowed cross-protocol redirect ("
                            + originalUrl.getProtocol()
                            + " to "
                            + protocol
                            + ")"),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSourceException.TYPE_OPEN
                )
            } else {
                try {
                    url = URL(
                        url.toString().replaceFirst(protocol.toRegex(), originalUrl.getProtocol())
                    )
                } catch (e: MalformedURLException) {
                    throw HttpDataSourceException(
                        e,
                        dataSpec,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        HttpDataSourceException.TYPE_OPEN
                    )
                }
            }
        }
        return url
    }

    @Throws(IOException::class)
    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec?) {
        var bytesToSkip = bytesToSkip
        if (bytesToSkip == 0L) {
            return
        }
        val skipBuffer = ByteArray(4096)
        while (bytesToSkip > 0) {
            val readLength = min(bytesToSkip, skipBuffer.size.toLong()).toInt()
            val read: Int = inputStream!!.read(skipBuffer, 0, readLength)
            if (Thread.currentThread().isInterrupted) {
                throw HttpDataSourceException(
                    InterruptedIOException(),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSourceException.TYPE_OPEN
                )
            }
            if (read == -1) {
                throw HttpDataSourceException(
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSourceException.TYPE_OPEN
                )
            }
            bytesToSkip -= read.toLong()
            bytesTransferred(read)
        }
    }

    @Throws(IOException::class)
    private fun readInternal(buffer: ByteArray?, offset: Int, readLength: Int): Int {
        var readLength = readLength
        if (readLength == 0) {
            return 0
        }
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) {
                return C.RESULT_END_OF_INPUT
            }
            readLength = min(readLength.toLong(), bytesRemaining).toInt()
        }

        val read: Int = inputStream!!.read(buffer, offset, readLength)
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += read.toLong()
        bytesTransferred(read)
        return read
    }

    private fun closeConnectionQuietly() {
        if (connection != null) {
            try {
                connection!!.disconnect()
            } catch (e: Exception) {
                e(TAG, "Unexpected error while disconnecting", e)
            }
        }
    }

    private class NullFilteringHeadersMap(private val headers: MutableMap<String, List<String>>) :
        ForwardingMap<String, List<String>>() {
        override fun delegate(): MutableMap<String, List<String>> {
            return headers
        }

        override fun containsKey(key: String?): Boolean {
            return key != null && super.containsKey(key)
        }

        override fun get(key: String?): List<String>? {
            return if (key == null) null else super.get(key)
        }

        override val keys: MutableSet<String?>
            get() = Sets.filter(super.keys, Predicate { key: String? -> key != null })

        override val entries: MutableSet<MutableMap.MutableEntry<String, List<String>?>>
            get() {
                return Sets.filter<MutableMap.MutableEntry<String, List<String>?>>(
                    super.entries,
                    Predicate { entry: MutableMap.MutableEntry<String, List<String>?>? -> entry!!.key != null })
            }

        override val size: Int
            get() = super.size - (if (super.containsKey(null)) 1 else 0)

        override fun isEmpty(): Boolean {
            return super.isEmpty() || (super.size == 1 && super.containsKey(null))
        }

        override fun containsValue(value: List<String>?): Boolean {
            return super.containsValue(value)
        }

        override fun equals(`object`: Any?): Boolean {
            return `object` != null && super.standardEquals(`object`)
        }

        override fun hashCode(): Int {
            return super.standardHashCode()
        }
    }

    companion object {
        val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 8 * 1000
        val DEFAULT_READ_TIMEOUT_MILLIS: Int = 8 * 1000

        private const val TAG = "DefaultHttpDataSource"
        private const val MAX_REDIRECTS = 20 // Same limit as okhttp.
        private const val HTTP_STATUS_TEMPORARY_REDIRECT = 307
        private const val HTTP_STATUS_PERMANENT_REDIRECT = 308

        private fun isCompressed(connection: HttpURLConnection): Boolean {
            val contentEncoding = connection.getHeaderField("Content-Encoding")
            return "gzip".equals(contentEncoding, ignoreCase = true)
        }

        @get:Suppress("deprecation")
        private val currentThreadId: Long
            // Using deprecated method on API<36
            get() {
                val thread = Thread.currentThread()
                return if (Build.VERSION.SDK_INT < 36) thread.getId() else thread.threadId()
            }
    }
}
