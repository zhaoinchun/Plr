package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.min

class ByteArrayDataSource(uriResolver: UriResolver) : BaseDataSource(false) {
    interface UriResolver {
        @Throws(IOException::class)
        fun resolve(uri: Uri): ByteArray
    }

    private val uriResolver: UriResolver = uriResolver

    override var uri: Uri? = null
    private var data: ByteArray? = null
    private var readPosition = 0
    private var bytesRemaining = 0
    private var opened = false

    constructor(data: ByteArray) : this(object :UriResolver {
        override fun resolve(uri: Uri): ByteArray {
            return data
        }
    } ) {
        Preconditions.checkArgument(data.isNotEmpty())
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        data = uriResolver.resolve(uri!!)
        if (dataSpec.position > data!!.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        readPosition = dataSpec.position.toInt()
        bytesRemaining = data!!.size - dataSpec.position.toInt()
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = min(bytesRemaining.toLong(), dataSpec.length).toInt()
        }
        opened = true
        transferStarted(dataSpec)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else bytesRemaining.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        var length = length
        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT
        }

        length = min(length, bytesRemaining)
        System.arraycopy(
            data!!,
            readPosition,
            buffer,
            offset,
            length
        )
        readPosition += length
        bytesRemaining -= length
        bytesTransferred(length)
        return length
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
        data = null
    }
}
