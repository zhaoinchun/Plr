package androidx.media3.datasource

import android.net.Uri
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo.registerModule
import java.util.Collections

data class DataSpec(
    var uri: Uri,
    var uriPositionOffset: Long = 0,
    var httpMethod: @HttpMethod Int = HTTP_METHOD_GET,
    private val body: ByteArray? = null,
    var headers: Map<String, String> = mapOf(),
    var position: Long = 0,
    var length: Long = C.LENGTH_UNSET.toLong(),
    var key: String? = null,
    var flags: @Flags Int = 0,
    var customData: Any? = null,
) {
    val httpBody: ByteArray? = if (body != null && body.isNotEmpty()) body else null
    val httpRequestHeaders = Collections.unmodifiableMap<String?, String?>(
        HashMap<String?, String?>(headers)
    )

    constructor(uri: Uri, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) : this(
        uri,
        position,
        length = length,
        key = null
    )

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_ALLOW_GZIP, FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN, FLAG_ALLOW_CACHE_FRAGMENTATION, FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED
        ]
    )
    annotation class Flags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [HTTP_METHOD_GET, HTTP_METHOD_POST, HTTP_METHOD_HEAD])
    annotation class HttpMethod

    fun isFlagSet(flag: @Flags Int): Boolean {
        return (this.flags and flag) == flag
    }

    val httpMethodString: String
        get() = getStringForHttpMethod(httpMethod)

    fun subrange(
        offset: Long,
        length: Long = if (this.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else this.length - offset
    ): DataSpec {
        if (offset == 0L && this.length == length) {
            return this
        } else {
            return DataSpec(
                uri,
                uriPositionOffset,
                httpMethod,
                httpBody,
                httpRequestHeaders,
                position + offset,
                length,
                key,
                flags,
                customData
            )
        }
    }

    fun withUri(uri: Uri): DataSpec {
        return DataSpec(
            uri,
            uriPositionOffset,
            httpMethod,
            httpBody,
            httpRequestHeaders,
            position,
            length,
            key,
            flags,
            customData
        )
    }

    fun withRequestHeaders(httpRequestHeaders: MutableMap<String, String>): DataSpec {
        return DataSpec(
            uri,
            uriPositionOffset,
            httpMethod,
            httpBody,
            httpRequestHeaders,
            position,
            length,
            key,
            flags,
            customData
        )
    }

    fun withAdditionalHeaders(additionalHttpRequestHeaders: MutableMap<String, String>): DataSpec {
        val httpRequestHeaders: MutableMap<String, String> =
            HashMap(this.httpRequestHeaders)
        httpRequestHeaders.putAll(additionalHttpRequestHeaders)
        return DataSpec(
            uri,
            uriPositionOffset,
            httpMethod,
            httpBody,
            httpRequestHeaders,
            position,
            length,
            key,
            flags,
            customData
        )
    }

    override fun toString(): String {
        return ("DataSpec["
                + this.httpMethodString
                + " "
                + uri
                + ", "
                + position
                + ", "
                + length
                + ", "
                + key
                + ", "
                + flags
                + "]")
    }

    companion object {
        init {
            registerModule("media3.datasource")
        }

        const val FLAG_ALLOW_GZIP: Int = 1
        const val FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN: Int = 1 shl 1
        const val FLAG_ALLOW_CACHE_FRAGMENTATION: Int = 1 shl 2
        const val FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED: Int = 1 shl 3
        const val HTTP_METHOD_GET: Int = 1
        const val HTTP_METHOD_POST: Int = 2
        const val HTTP_METHOD_HEAD: Int = 3
        fun getStringForHttpMethod(httpMethod: @HttpMethod Int): String {
            when (httpMethod) {
                HTTP_METHOD_GET -> return "GET"
                HTTP_METHOD_POST -> return "POST"
                HTTP_METHOD_HEAD -> return "HEAD"
                else ->         // Never happens.
                    throw IllegalStateException()
            }
        }
    }
}
