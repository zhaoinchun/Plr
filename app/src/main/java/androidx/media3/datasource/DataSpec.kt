package androidx.media3.datasource

import android.net.Uri
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo.registerModule
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Collections

class DataSpec private constructor(
    uri: Uri?,
    uriPositionOffset: Long,
    httpMethod: @HttpMethod Int,
    httpBody: ByteArray?,
    httpRequestHeaders: Map<String, String>,
    position: Long,
    length: Long,
    key: String?,
    flags: @Flags Int,
    customData: Any?
) {
    class Builder {
        private var uri: Uri? = null
        private var uriPositionOffset: Long = 0
        private var httpMethod: @HttpMethod Int
        private var httpBody: ByteArray? = null
        private var httpRequestHeaders: Map<String, String>
        private var position: Long = 0
        private var length: Long
        private var key: String? = null
        private var flags: @Flags Int = 0
        private var customData: Any? = null

        constructor() {
            httpMethod = HTTP_METHOD_GET
            httpRequestHeaders = mapOf()
            length = C.LENGTH_UNSET.toLong()
        }

        constructor(dataSpec: DataSpec) {
            uri = dataSpec.uri
            uriPositionOffset = dataSpec.uriPositionOffset
            httpMethod = dataSpec.httpMethod
            httpBody = dataSpec.httpBody
            httpRequestHeaders = dataSpec.httpRequestHeaders
            position = dataSpec.position
            length = dataSpec.length
            key = dataSpec.key
            flags = dataSpec.flags
            customData = dataSpec.customData
        }

        fun setUri(uriString: String?): Builder {
            this.uri = Uri.parse(uriString)
            return this
        }

        fun setUri(uri: Uri?): Builder {
            this.uri = uri
            return this
        }

        fun setUriPositionOffset(uriPositionOffset: Long): Builder {
            this.uriPositionOffset = uriPositionOffset
            return this
        }

        fun setHttpMethod(httpMethod: @HttpMethod Int): Builder {
            this.httpMethod = httpMethod
            return this
        }

        fun setHttpBody(httpBody: ByteArray?): Builder {
            this.httpBody = httpBody
            return this
        }

        fun setHttpRequestHeaders(httpRequestHeaders: Map<String, String>): Builder {
            this.httpRequestHeaders = httpRequestHeaders
            return this
        }

        fun setPosition(position: Long): Builder {
            this.position = position
            return this
        }

        fun setLength(length: Long): Builder {
            this.length = length
            return this
        }

        fun setKey(key: String?): Builder {
            this.key = key
            return this
        }

        fun setFlags(flags: @Flags Int): Builder {
            this.flags = flags
            return this
        }

        fun setCustomData(customData: Any?): Builder {
            this.customData = customData
            return this
        }

        fun build(): DataSpec {
            return DataSpec(
                uri!!,
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
    }

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

    val uri: Uri
    val uriPositionOffset: Long
    val httpMethod: @HttpMethod Int
    val httpBody: ByteArray?
    val httpRequestHeaders: MutableMap<String, String>

    @Deprecated(
        """Use {@link #position} except for specific use cases where the absolute position
        within the resource is required within a {@link DataSource} chain. Where the absolute
        position is required, use {@code uriPositionOffset + position}."""
    )
    val absoluteStreamPosition: Long
    val position: Long
    val length: Long
    val key: String?
    val flags: @Flags Int
    val customData: Any?

    constructor(uri: Uri?, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) : this(
        uri,
        position,
        length,  
        null
    )

    @Deprecated(
        """Use {@link Builder}.
    """
    )
    constructor(uri: Uri?, position: Long, length: Long, key: String?) : this(
        uri,  
        0,
        HTTP_METHOD_GET,
        null,
        mutableMapOf<String, String>(),
        position,
        length,
        key,
        0,  
        null
    )

    init {
        // TODO: Replace this assertion with a stricter one checking "uriPositionOffset >= 0", after
        // validating there are no violations in ExoPlayer and 1P apps.
        Preconditions.checkArgument(uriPositionOffset + position >= 0)
        Preconditions.checkArgument(position >= 0)
        Preconditions.checkArgument(length > 0 || length == C.LENGTH_UNSET.toLong())
        this.uri = uri!!
        this.uriPositionOffset = uriPositionOffset
        this.httpMethod = httpMethod
        this.httpBody = if (httpBody != null && httpBody.size != 0) httpBody else null
        this.httpRequestHeaders = Collections.unmodifiableMap<String?, String?>(
            HashMap<String?, String?>(httpRequestHeaders)
        )
        this.position = position
        this.absoluteStreamPosition = uriPositionOffset + position
        this.length = length
        this.key = key
        this.flags = flags
        this.customData = customData
    }

    fun isFlagSet(flag: @Flags Int): Boolean {
        return (this.flags and flag) == flag
    }

    val httpMethodString: String
        get() = getStringForHttpMethod(httpMethod)

    fun buildUpon(): Builder {
        return Builder(this)
    }

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

    fun withUri(uri: Uri?): DataSpec {
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
        const  val FLAG_ALLOW_CACHE_FRAGMENTATION: Int = 1 shl 2
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
