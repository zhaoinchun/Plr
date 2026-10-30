package androidx.media3.datasource

import android.net.Uri
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.NetworkException
import android.net.http.UrlRequest
import android.net.http.UrlRequest.StatusListener
import android.net.http.UrlResponseInfo
import android.os.Build
import android.text.TextUtils
import androidx.annotation.RequiresExtension
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ConditionVariable
import androidx.media3.common.util.Util
import androidx.media3.datasource.HttpDataSource.CleartextNotPermittedException
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidContentTypeException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.HttpDataSource.RequestProperties
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.net.HttpHeaders
import com.google.common.primitives.Longs
import java.io.IOException
import java.io.InterruptedIOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.concurrent.Volatile
import kotlin.math.min

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
class HttpEngineDataSource internal constructor(
    private val httpEngine: HttpEngine,
    private val executor: Executor,
    private val requestPriority: Int,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val resetTimeoutOnRedirects: Boolean,
    private val handleSetCookieRequests: Boolean,
    private val userAgent: String?,
    private val defaultRequestProperties: RequestProperties?,
    private val contentTypePredicate: Predicate<String>?,
    private val keepPostFor302Redirects: Boolean
) : BaseDataSource( true), HttpDataSource {

    class Factory(private val httpEngine: HttpEngine, private val executor: Executor) :
        HttpDataSource.Factory {
        private val defaultRequestProperties: RequestProperties = RequestProperties()
        private var contentTypePredicate: Predicate<String>? = null
        private var transferListener: TransferListener? = null
        private var userAgent: String? = null
        private var requestPriority: Int = UrlRequest.REQUEST_PRIORITY_MEDIUM
        private var connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS
        private var readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MILLIS
        private var resetTimeoutOnRedirects = false
        private var handleSetCookieRequests = false
        private var keepPostFor302Redirects = false

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): Factory {
            this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
            return this
        }

        fun setUserAgent(userAgent: String?): Factory {
            this.userAgent = userAgent
            return this
        }

        fun setRequestPriority(requestPriority: Int): Factory {
            this.requestPriority = requestPriority
            return this
        }

        fun setConnectionTimeoutMs(connectTimeoutMs: Int): Factory {
            this.connectTimeoutMs = connectTimeoutMs
            return this
        }

        fun setResetTimeoutOnRedirects(resetTimeoutOnRedirects: Boolean): Factory {
            this.resetTimeoutOnRedirects = resetTimeoutOnRedirects
            return this
        }

        fun setHandleSetCookieRequests(handleSetCookieRequests: Boolean): Factory {
            this.handleSetCookieRequests = handleSetCookieRequests
            return this
        }

        fun setReadTimeoutMs(readTimeoutMs: Int): Factory {
            this.readTimeoutMs = readTimeoutMs
            return this
        }

        fun setContentTypePredicate(contentTypePredicate: Predicate<String>?): Factory {
            this.contentTypePredicate = contentTypePredicate
            return this
        }

        fun setKeepPostFor302Redirects(keepPostFor302Redirects: Boolean): Factory {
            this.keepPostFor302Redirects = keepPostFor302Redirects
            return this
        }

        fun setTransferListener(transferListener: TransferListener?): Factory {
            this.transferListener = transferListener
            return this
        }

        override fun createDataSource(): HttpDataSource {
            val dataSource =
                HttpEngineDataSource(
                    httpEngine,
                    executor,
                    requestPriority,
                    connectTimeoutMs,
                    readTimeoutMs,
                    resetTimeoutOnRedirects,
                    handleSetCookieRequests,
                    userAgent,
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

    class OpenException : HttpDataSourceException {
        val httpEngineConnectionStatus: Int

        constructor(
            cause: IOException?,
            dataSpec: DataSpec?,
            @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int,
            httpEngineConnectionStatus: Int
        ) : super(cause, dataSpec, errorCode, HttpDataSourceException.TYPE_OPEN) {
            this.httpEngineConnectionStatus = httpEngineConnectionStatus
        }

        constructor(
            errorMessage: String?,
            dataSpec: DataSpec?,
            @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int,
            httpEngineConnectionStatus: Int
        ) : super(errorMessage, dataSpec, errorCode, HttpDataSourceException.TYPE_OPEN) {
            this.httpEngineConnectionStatus = httpEngineConnectionStatus
        }

        constructor(
            dataSpec: DataSpec?,
            @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int,
            httpEngineConnectionStatus: Int
        ) : super(dataSpec, errorCode, HttpDataSourceException.TYPE_OPEN) {
            this.httpEngineConnectionStatus = httpEngineConnectionStatus
        }
    }

    private val requestProperties: RequestProperties
    private val operation: ConditionVariable
    private val clock: Clock

    // Accessed by the calling thread only.
    private var transferStarted = false
    private var bytesRemaining: Long = 0

    private var currentDataSpec: DataSpec? = null
    private var currentUrlRequestWrapper: UrlRequestWrapper? = null

    // Reference written and read by calling thread only. Passed to HttpEngine thread as a local
    // variable.
    // operation.open() calls ensure writes into the buffer are visible to reads made by the calling
    // thread.
    private var readBuffer: ByteBuffer? = null

    // Written from the HttpEngine thread only. operation.open() calls ensure writes are visible to
    // reads
    // made by the calling thread.
    private var responseInfo: UrlResponseInfo? = null
    private var exception: IOException? = null
    private var finished = false

    @Volatile
    private var currentConnectTimeoutMs: Long = 0

    init {
        clock = Clock.DEFAULT
        requestProperties = RequestProperties()
        operation = ConditionVariable()
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

    override val responseCode: Int
        get() {
            return if (responseInfo == null || responseInfo!!.getHttpStatusCode() <= 0)
                -1
            else
                responseInfo!!.httpStatusCode
        }

    override val responseHeaders: MutableMap<String, List<String>>
        get() {
            return if (responseInfo == null) mutableMapOf() else responseInfo!!.headers.asMap
        }

    override val uri: Uri?
        get() {
            if (responseInfo != null) {
                return Uri.parse(responseInfo!!.getUrl())
            } else if (currentDataSpec != null) {
                return currentDataSpec!!.uri
            } else {
                return null
            }
        }

    @Throws(HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        Preconditions.checkState(!transferStarted)

        operation.close()
        resetConnectTimeout()
        currentDataSpec = dataSpec
        val urlRequestWrapper: UrlRequestWrapper?
        try {
            urlRequestWrapper = buildRequestWrapper(dataSpec!!)
            currentUrlRequestWrapper = urlRequestWrapper
        } catch (e: IOException) {
            if (e is HttpDataSourceException) {
                throw e
            } else {
                throw OpenException(
                    e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, UrlRequest.Status.IDLE
                )
            }
        }
        urlRequestWrapper.start()

        transferInitializing(dataSpec)
        try {
            val connectionOpened = blockUntilConnectTimeout()
            val connectionOpenException = exception
            if (connectionOpenException != null) {
                val message = connectionOpenException.message
                if (message != null && Ascii.toLowerCase(message)
                        .contains("err_cleartext_not_permitted")
                ) {
                    throw CleartextNotPermittedException(connectionOpenException, dataSpec)
                }
                throw OpenException(
                    connectionOpenException,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    urlRequestWrapper.status
                )
            } else if (!connectionOpened) {
                // The timeout was reached before the connection was opened.
                throw OpenException(
                    SocketTimeoutException(),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    urlRequestWrapper.status
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            // An interruption means the operation is being cancelled, in which case this exception should
            // not cause the player to fail. If it does, it likely means that the owner of the operation
            // is failing to swallow the interruption, which makes us enter an invalid state.
            throw OpenException(
                InterruptedIOException(),
                dataSpec,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                UrlRequest.Status.INVALID
            )
        }

        // Check for a valid response code.
        val responseInfo = this.responseInfo!!
        val responseCode = responseInfo.getHttpStatusCode()
        val responseHeaders = responseInfo.getHeaders().getAsMap()
        if (responseCode < 200 || responseCode > 299) {
            if (responseCode == 416) {
                val documentSize =
                    HttpUtil.getDocumentSize(
                        getFirstHeader(
                            responseHeaders,
                            HttpHeaders.CONTENT_RANGE
                        )
                    )
                if (dataSpec.position == documentSize) {
                    transferStarted = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
                }
            }

            var responseBody: ByteArray?
            try {
                responseBody = readResponseBody()
            } catch (e: IOException) {
                responseBody = Util.EMPTY_BYTE_ARRAY
            }

            val cause: IOException? =
                if (responseCode == 416)
                    DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                else
                    null
            throw InvalidResponseCodeException(
                responseCode,
                responseInfo.getHttpStatusText(),
                cause,
                responseHeaders,
                dataSpec,
                responseBody
            )
        }

        // Check for a valid content type.
        val contentTypePredicate = this.contentTypePredicate
        if (contentTypePredicate != null) {
            val contentType: String? = getFirstHeader(responseHeaders, HttpHeaders.CONTENT_TYPE)
            if (contentType != null && !contentTypePredicate.apply(contentType)) {
                throw InvalidContentTypeException(contentType, dataSpec)
            }
        }

        // If we requested a range starting from a non-zero position and received a 200 rather than a
        // 206, then the server does not support partial requests. We'll need to manually skip to the
        // requested position.
        val bytesToSkip =
            if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0

        // Calculate the content length.
        if (!isCompressed(responseInfo)) {
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                bytesRemaining = dataSpec.length
            } else {
                val contentLength =
                    HttpUtil.getContentLength(
                        getFirstHeader(responseHeaders, HttpHeaders.CONTENT_LENGTH),
                        getFirstHeader(responseHeaders, HttpHeaders.CONTENT_RANGE)
                    )
                bytesRemaining =
                    if (contentLength != C.LENGTH_UNSET.toLong()) (contentLength - bytesToSkip) else C.LENGTH_UNSET.toLong()
            }
        } else {
            // If the response is compressed then the content length will be that of the compressed data
            // which isn't what we want. Always use the dataSpec length in this case.
            bytesRemaining = dataSpec.length
        }

        transferStarted = true
        transferStarted(dataSpec)

        skipFully(bytesToSkip, dataSpec)
        return bytesRemaining
    }

    @Throws(HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        Preconditions.checkState(transferStarted)

        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        val readBuffer = this.orCreateReadBuffer
        if (!readBuffer.hasRemaining()) {
            // Fill readBuffer with more data from HttpEngine.
            operation.close()
            readBuffer.clear()

            readInternal(readBuffer, currentDataSpec)

            if (finished) {
                bytesRemaining = 0
                return C.RESULT_END_OF_INPUT
            }

            // The operation didn't time out, fail or finish, and therefore data must have been read.
            readBuffer.flip()
            Preconditions.checkState(readBuffer.hasRemaining())
        }

        // Ensure we read up to bytesRemaining, in case this was a Range request with finite end, but
        // the server does not support Range requests and transmitted the entire resource.
        val bytesRead = Longs.min(
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining else Long.MAX_VALUE,
            readBuffer.remaining().toLong(),
            length.toLong()
        ).toInt()

        readBuffer.get(buffer, offset, bytesRead)

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead.toLong()
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    @Throws(HttpDataSourceException::class)
    fun read(buffer: ByteBuffer): Int {
        Preconditions.checkState(transferStarted)

        require(buffer.isDirect()) { "Passed buffer is not a direct ByteBuffer" }
        if (!buffer.hasRemaining()) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        val readLength = buffer.remaining()

        if (readBuffer != null) {
            // If there is existing data in the readBuffer, read as much as possible. Return if any read.
            val copyBytes: Int =
                Companion.copyByteBuffer( readBuffer!!,  buffer)
            if (copyBytes != 0) {
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= copyBytes.toLong()
                }
                bytesTransferred(copyBytes)
                return copyBytes
            }
        }

        // Fill buffer with more data from HttpEngine.
        operation.close()
        readInternal(buffer, currentDataSpec)

        if (finished) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }

        // The operation didn't time out, fail or finish, and therefore data must have been read.
        Preconditions.checkState(readLength > buffer.remaining())
        val bytesRead = readLength - buffer.remaining()
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead.toLong()
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    @Synchronized
    override fun close() {
        if (currentUrlRequestWrapper != null) {
            currentUrlRequestWrapper!!.close()
            currentUrlRequestWrapper = null
        }
        if (readBuffer != null) {
            readBuffer!!.limit(0)
        }
        currentDataSpec = null
        responseInfo = null
        exception = null
        finished = false
        if (transferStarted) {
            transferStarted = false
            transferEnded()
        }
    }

    val currentUrlRequestCallback: UrlRequestCallback?
        get() = if (currentUrlRequestWrapper == null)
            null
        else
            currentUrlRequestWrapper!!.urlRequestCallback

    @Throws(IOException::class)
    private fun buildRequestWrapper(dataSpec: DataSpec): UrlRequestWrapper {
        val callback = UrlRequestCallback()
        return UrlRequestWrapper(buildRequestBuilder(dataSpec, callback).build(), callback)
    }

    @Throws(IOException::class)
    private fun buildRequestBuilder(
        dataSpec: DataSpec, urlRequestCallback: UrlRequest.Callback
    ): UrlRequest.Builder {
        val requestBuilder =
            httpEngine
                .newUrlRequestBuilder(dataSpec.uri.toString(), executor, urlRequestCallback)
                .setPriority(requestPriority)
                .setDirectExecutorAllowed(true)

        // Set the headers.
        val requestHeaders: MutableMap<String?, String?> = HashMap<String?, String?>()
        if (defaultRequestProperties != null) {
            requestHeaders.putAll(defaultRequestProperties.snapshot)
        }
        requestHeaders.putAll(requestProperties.snapshot)
        requestHeaders.putAll(dataSpec.httpRequestHeaders)

        for (headerEntry in requestHeaders.entries) {
            val key: String = headerEntry.key!!
            val value: String = headerEntry.value!!
            requestBuilder.addHeader(key, value)
        }

        if (dataSpec.httpBody != null && !requestHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
            throw OpenException(
                "HTTP request with non-empty body must set Content-Type",
                dataSpec,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                UrlRequest.Status.IDLE
            )
        }

        val rangeHeader = HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)
        if (rangeHeader != null) {
            requestBuilder.addHeader(HttpHeaders.RANGE, rangeHeader)
        }
        if (userAgent != null) {
            requestBuilder.addHeader(HttpHeaders.USER_AGENT, userAgent)
        }
        // TODO: crbug.com/41313195 - Uncomment when bug is fixed (adjusting the code as necessary).
        // Force identity encoding unless gzip is allowed.
        // if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
        //   requestBuilder.addHeader("Accept-Encoding", "identity");
        // }
        // Set the method and (if non-empty) the body.
        requestBuilder.setHttpMethod(dataSpec.httpMethodString)
        if (dataSpec.httpBody != null) {
            requestBuilder.setUploadDataProvider(
                ByteArrayUploadDataProvider(dataSpec.httpBody), executor
            )
        }
        return requestBuilder
    }

    // Internal methods.
    @Throws(InterruptedException::class)
    private fun blockUntilConnectTimeout(): Boolean {
        var now = clock.elapsedRealtime()
        var opened = false
        while (!opened && now < currentConnectTimeoutMs) {
            opened = operation.block(currentConnectTimeoutMs - now + 5 )
            now = clock.elapsedRealtime()
        }
        return opened
    }

    private fun resetConnectTimeout() {
        currentConnectTimeoutMs = clock.elapsedRealtime() + connectTimeoutMs
    }

    @Throws(HttpDataSourceException::class)
    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec?) {
        var bytesToSkip = bytesToSkip
        if (bytesToSkip == 0L) {
            return
        }
        val readBuffer = this.orCreateReadBuffer

        try {
            while (bytesToSkip > 0) {
                // Fill readBuffer with more data from HttpEngine.
                operation.close()
                readBuffer.clear()
                readInternal(readBuffer, dataSpec)
                if (Thread.currentThread().isInterrupted()) {
                    throw InterruptedIOException()
                }
                if (finished) {
                    throw OpenException(
                        dataSpec,
                        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                        UrlRequest.Status.READING_RESPONSE
                    )
                } else {
                    // The operation didn't time out, fail or finish, and therefore data must have been read.
                    readBuffer.flip()
                    Preconditions.checkState(readBuffer.hasRemaining())
                    val bytesSkipped = min(readBuffer.remaining().toLong(), bytesToSkip).toInt()
                    readBuffer.position(readBuffer.position() + bytesSkipped)
                    bytesToSkip -= bytesSkipped.toLong()
                }
            }
        } catch (e: IOException) {
            if (e is HttpDataSourceException) {
                throw e
            } else {
                throw OpenException(
                    e,
                    dataSpec,
                    if (e is SocketTimeoutException)
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    else
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    UrlRequest.Status.READING_RESPONSE
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun readResponseBody(): ByteArray {
        var responseBody = Util.EMPTY_BYTE_ARRAY
        val readBuffer = this.orCreateReadBuffer
        while (!finished) {
            operation.close()
            readBuffer.clear()
            readInternal(readBuffer, currentDataSpec)
            readBuffer.flip()
            if (readBuffer.remaining() > 0) {
                val existingResponseBodyEnd = responseBody.size
                responseBody = responseBody.copyOf(responseBody.size + readBuffer.remaining())
                readBuffer.get(responseBody, existingResponseBodyEnd, readBuffer.remaining())
            }
        }
        return responseBody
    }

    @Throws(HttpDataSourceException::class)
    private fun readInternal(buffer: ByteBuffer, dataSpec: DataSpec?) {
        currentUrlRequestWrapper!!.read(buffer)
        try {
            if (!operation.block(readTimeoutMs.toLong())) {
                throw SocketTimeoutException()
            }
        } catch (e: InterruptedException) {
            // The operation is ongoing so replace buffer to avoid it being written to by this
            // operation during a subsequent request.
            if (buffer === readBuffer) {
                readBuffer = null
            }
            Thread.currentThread().interrupt()
            exception = InterruptedIOException()
        } catch (e: SocketTimeoutException) {
            // The operation is ongoing so replace buffer to avoid it being written to by this
            // operation during a subsequent request.
            if (buffer === readBuffer) {
                readBuffer = null
            }
            exception =
                HttpDataSourceException(
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    HttpDataSourceException.TYPE_READ
                )
        }

        exception?.also {
            if (it is HttpDataSourceException) {
                throw it
            } else {
                throw HttpDataSourceException.createForIOException(
                    it, dataSpec, HttpDataSourceException.TYPE_READ
                )
            }
        }
    }

    private val orCreateReadBuffer: ByteBuffer
        get() {
            if (readBuffer == null) {
                readBuffer =
                    ByteBuffer.allocateDirect(READ_BUFFER_SIZE_BYTES)
                readBuffer!!.limit(0)
            }
            return readBuffer!!
        }

    private class UrlRequestWrapper(
        private val urlRequest: UrlRequest,
        val urlRequestCallback: UrlRequestCallback
    ) {
        fun start() {
            urlRequest.start()
        }

        fun read(buffer: ByteBuffer) {
            urlRequest.read(buffer)
        }

        fun close() {
            urlRequestCallback.close()
            urlRequest.cancel()
        }

        @get:Throws(InterruptedException::class)
        val status: Int
            get() {
                val conditionVariable =
                    ConditionVariable()
                val statusHolder = IntArray(1)
                urlRequest.getStatus(
                    object : StatusListener {
                        override fun onStatus(status: Int) {
                            statusHolder[0] = status
                            conditionVariable.open()
                        }
                    })
                conditionVariable.block()
                return statusHolder[0]
            }
    }

    inner class UrlRequestCallback : UrlRequest.Callback {
        @Volatile
        private var isClosed = false

        fun close() {
            this.isClosed = true
        }

        @Synchronized
        override fun onRedirectReceived(
            request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String
        ) {
            if (isClosed) {
                return
            }
            val dataSpec = currentDataSpec!!
            val responseCode = info.getHttpStatusCode()
            if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
                // The industry standard is to disregard POST redirects when the status code is 307 or
                // 308.
                if (responseCode == 307 || responseCode == 308) {
                    exception =
                        InvalidResponseCodeException(
                            responseCode,
                            info.getHttpStatusText(),  
                            null,
                            info.getHeaders().getAsMap(),
                            dataSpec,  
                            Util.EMPTY_BYTE_ARRAY
                        )
                    operation.open()
                    return
                }
            }
            if (resetTimeoutOnRedirects) {
                resetConnectTimeout()
            }

            var cookieHandler = CookieHandler.getDefault()

            if (cookieHandler == null && handleSetCookieRequests) {
                // a temporary CookieManager is created for the duration of this request - this guarantees
                // redirects preserve the cookies correctly.
                cookieHandler = CookieManager()
            }

            val url = info.getUrl()
            val headers = info.getHeaders().getAsMap()
            HttpUtil.storeCookiesFromHeaders(url, headers, cookieHandler)
            val cookieHeaders = HttpUtil.getCookieHeader(url, headers, cookieHandler)

            val shouldKeepPost =
                keepPostFor302Redirects
                        && dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST && responseCode == 302

            // request.followRedirect() transforms a POST request into a GET request, so if we want to
            // keep it as a POST we need to fall through to the manual redirect logic below.
            if (!shouldKeepPost) {
                // No cookies, or we're not handling them - so just follow the redirect.
                if (!handleSetCookieRequests || TextUtils.isEmpty(cookieHeaders)) {
                    request.followRedirect()
                    return
                }
            }

            request.cancel()
            var redirectUrlDataSpec: DataSpec?
            if (!shouldKeepPost && dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
                // For POST redirects that aren't 307 or 308, the redirect is followed but request is
                // transformed into a GET unless shouldKeepPost is true.
                redirectUrlDataSpec =
                    dataSpec
                        .buildUpon()
                        .setUri(newLocationUrl)
                        .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                        .setHttpBody(null)
                        .build()
            } else {
                redirectUrlDataSpec = dataSpec.withUri(Uri.parse(newLocationUrl))
            }

            if (!TextUtils.isEmpty(cookieHeaders)) {
                val requestHeaders: MutableMap<String, String> = mutableMapOf()
                requestHeaders.putAll(dataSpec.httpRequestHeaders)
                requestHeaders[HttpHeaders.COOKIE] = cookieHeaders
                redirectUrlDataSpec =
                    redirectUrlDataSpec.buildUpon().setHttpRequestHeaders(requestHeaders).build()
            }

            val redirectUrlRequestWrapper: UrlRequestWrapper?
            try {
                redirectUrlRequestWrapper = buildRequestWrapper(redirectUrlDataSpec)
            } catch (e: IOException) {
                exception = e
                return
            }
            if (currentUrlRequestWrapper != null) {
                currentUrlRequestWrapper!!.close()
            }
            currentUrlRequestWrapper = redirectUrlRequestWrapper
            currentUrlRequestWrapper!!.start()
        }

        @Synchronized
        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            if (isClosed) {
                return
            }
            HttpUtil.storeCookiesFromHeaders(
                info.getUrl(), info.getHeaders().getAsMap(), CookieHandler.getDefault()
            )
            responseInfo = info
            operation.open()
        }

        @Synchronized
        override fun onReadCompleted(
            request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer
        ) {
            if (isClosed) {
                return
            }
            operation.open()
        }

        @Synchronized
        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            if (isClosed) {
                return
            }
            finished = true
            operation.open()
        }

        @Synchronized
        override fun onFailed(
            request: UrlRequest, info: UrlResponseInfo?, error: HttpException
        ) {
            if (isClosed) {
                return
            }
            if (error is NetworkException
                && (error.getErrorCode()
                        == NetworkException.ERROR_HOSTNAME_NOT_RESOLVED)
            ) {
                exception = UnknownHostException()
            } else {
                exception = error
            }
            operation.open()
        }

        @Synchronized
        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            // Do nothing
        }
    }

    companion object {
        val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 8 * 1000
        val DEFAULT_READ_TIMEOUT_MILLIS: Int = 8 * 1000

        // The size of read buffer passed to cronet UrlRequest.read().
        private val READ_BUFFER_SIZE_BYTES = 32 * 1024

        private fun isCompressed(info: UrlResponseInfo): Boolean {
            for (entry in info.getHeaders().getAsList()) {
                if (entry.key.equals("Content-Encoding", ignoreCase = true)) {
                    return !entry.value.equals("identity", ignoreCase = true)
                }
            }
            return false
        }

        private fun getFirstHeader(
            allHeaders: MutableMap<String?, MutableList<String?>?>,
            headerName: String?
        ): String? {
            val headers = allHeaders.get(headerName)
            return if (headers != null && !headers.isEmpty()) headers[0] else null
        }

        // Copy as much as possible from the src buffer into dst buffer.
        // Returns the number of bytes copied.
        private fun copyByteBuffer(src: ByteBuffer, dst: ByteBuffer): Int {
            val remaining = min(src.remaining(), dst.remaining())
            val limit = src.limit()
            src.limit(src.position() + remaining)
            dst.put(src)
            src.limit(limit)
            return remaining
        }
    }
}
