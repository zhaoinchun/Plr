package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import java.io.IOException

class StatsDataSource(private val dataSource: DataSource) : DataSource {
    var bytesRead: Long = 0
        private set
    var lastOpenedUri: Uri
        private set

    var lastResponseHeaders: MutableMap<String, List<String>>
        private set

    init {
        lastOpenedUri = Uri.EMPTY
        lastResponseHeaders = mutableMapOf()
    }

    fun resetBytesRead() {
        bytesRead = 0
    }

    override fun addTransferListener(transferListener: TransferListener) {
        dataSource.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        // Reassign defaults in case dataSource.open throws an exception.
        lastOpenedUri = dataSpec.uri
        lastResponseHeaders = mutableMapOf()
        try {
            return dataSource.open(dataSpec)
        } finally {
            // TODO: b/373321956 - Remove this null-tolerance when we've fixed all DataSource
            //  implementations to return a non-null URI after a failed open() call and before close()
            //  (and updated the DataSourceContractTest to enforce this).
            val upstreamUri = uri
            if (upstreamUri != null) {
                lastOpenedUri = upstreamUri
            }
            lastResponseHeaders = responseHeaders
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead: Int = dataSource.read(buffer, offset, length)
        if (bytesRead != C.RESULT_END_OF_INPUT) {
            this.bytesRead += bytesRead.toLong()
        }
        return bytesRead
    }

    override val uri: Uri?
        get() = dataSource.uri
    override val responseHeaders: MutableMap<String, List<String>>
        get() = dataSource.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        dataSource.close()
    }
}
