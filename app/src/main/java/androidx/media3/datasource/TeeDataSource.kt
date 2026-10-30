package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import com.google.common.base.Preconditions
import java.io.IOException

class TeeDataSource(
    private val upstream: DataSource,
    private val dataSink: DataSink
) : DataSource {
    private var dataSinkNeedsClosing = false
    private var bytesRemaining: Long = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        var dataSpec = dataSpec
        bytesRemaining = upstream.open(dataSpec)
        if (bytesRemaining == 0L) {
            return 0
        }
        if (dataSpec.length == C.LENGTH_UNSET.toLong() && bytesRemaining != C.LENGTH_UNSET.toLong()) {
            // Reconstruct dataSpec in order to provide the resolved length to the sink.
            dataSpec = dataSpec.subrange(0, bytesRemaining)
        }
        dataSinkNeedsClosing = true
        dataSink.open(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        val bytesRead: Int = upstream.read(buffer, offset, length)
        if (bytesRead > 0) {
            // TODO: Consider continuing even if writes to the sink fail.
            dataSink.write(buffer, offset, bytesRead)
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead.toLong()
            }
        }
        return bytesRead
    }

    override val uri: Uri?
        get() = upstream.uri
    override val responseHeaders: MutableMap<String, List<String>>
        get() = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        try {
            upstream.close()
        } finally {
            if (dataSinkNeedsClosing) {
                dataSinkNeedsClosing = false
                dataSink.close()
            }
        }
    }
}
