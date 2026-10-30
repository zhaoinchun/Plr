package androidx.media3.datasource

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Util.getUtf8Bytes
import androidx.media3.common.util.Util.split
import com.google.common.base.Preconditions
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.min

class DataSchemeDataSource : BaseDataSource( false) {
    private var dataSpec: DataSpec? = null
    private var data: ByteArray? = null
    private var readPosition = 0
    private var bytesRemaining = 0

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        this.dataSpec = dataSpec
        val uri = dataSpec.uri.normalizeScheme()
        val scheme = uri.getScheme()
        Preconditions.checkArgument(SCHEME_DATA == scheme, "Unsupported scheme: %s", scheme)
        val uriParts: Array<String> = split(uri.getSchemeSpecificPart(), ",")
        if (uriParts.size != 2) {
            throw ParserException.createForMalformedDataOfUnknownType(
                "Unexpected URI format: " + uri,  null
            )
        }
        val dataString = uriParts[1]
        if (uriParts[0]!!.contains(";base64")) {
            try {
                data = Base64.decode(dataString,  Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw ParserException.createForMalformedDataOfUnknownType(
                    "Error while parsing Base64 encoded string: " + dataString, e
                )
            }
        } else {
            // TODO: Add support for other charsets.
            data = getUtf8Bytes(URLDecoder.decode(dataString, StandardCharsets.US_ASCII.name()))
        }
        if (dataSpec.position > data!!.size) {
            data = null
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        readPosition = dataSpec.position.toInt()
        bytesRemaining = data!!.size - readPosition
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = min(bytesRemaining.toLong(), dataSpec.length).toInt()
        }
        transferStarted(dataSpec)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else bytesRemaining.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        var length = length
        if (length == 0) {
            return 0
        }
        if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT
        }
        length = min(length, bytesRemaining)
        System.arraycopy(data, readPosition, buffer, offset, length)
        readPosition += length
        bytesRemaining -= length
        bytesTransferred(length)
        return length
    }

    override val uri: Uri?
        get() = dataSpec?.uri

    override fun close() {
        if (data != null) {
            data = null
            transferEnded()
        }
        dataSpec = null
    }

    companion object {
        const val SCHEME_DATA: String = "data"
    }
}
