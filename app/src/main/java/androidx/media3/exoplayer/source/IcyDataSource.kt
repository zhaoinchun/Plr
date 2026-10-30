package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.min

internal class IcyDataSource(
    private val upstream: DataSource,
    private val metadataIntervalBytes: Int,
    private val listener: Listener
) :
    DataSource {
    interface Listener {
        fun onIcyMetadata(metadata: ParsableByteArray)
    }

    private val metadataLengthByteHolder: ByteArray = ByteArray(1)
    private var bytesUntilMetadata: Int = metadataIntervalBytes

    init {
        Preconditions.checkArgument(metadataIntervalBytes > 0)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesUntilMetadata == 0) {
            if (readMetadata()) {
                bytesUntilMetadata = metadataIntervalBytes
            } else {
                return C.RESULT_END_OF_INPUT
            }
        }
        val bytesRead = upstream.read(buffer, offset, min(bytesUntilMetadata, length))
        if (bytesRead != C.RESULT_END_OF_INPUT) {
            bytesUntilMetadata -= bytesRead
        }
        return bytesRead
    }

    override val uri: Uri?
        get() = upstream.uri

    override val responseHeaders: MutableMap<String, List<String>>
        get() = upstream.responseHeaders

    override fun close() {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    private fun readMetadata(): Boolean {
        var bytesRead = upstream.read(metadataLengthByteHolder, 0, 1)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            return false
        }
        var metadataLength = (metadataLengthByteHolder[0].toInt() and 0xFF) shl 4
        if (metadataLength == 0) {
            return true
        }

        var offset = 0
        var lengthRemaining = metadataLength
        val metadata = ByteArray(metadataLength)
        while (lengthRemaining > 0) {
            bytesRead = upstream.read(metadata, offset, lengthRemaining)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                return false
            }
            offset += bytesRead
            lengthRemaining -= bytesRead
        }

        // Discard trailing zero bytes.
        while (metadataLength > 0 && metadata[metadataLength - 1].toInt() == 0) {
            metadataLength--
        }

        if (metadataLength > 0) {
            listener.onIcyMetadata(ParsableByteArray(metadata, metadataLength))
        }
        return true
    }
}
