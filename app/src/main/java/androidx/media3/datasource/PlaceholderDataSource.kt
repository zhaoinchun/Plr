package androidx.media3.datasource

import android.net.Uri
import java.io.IOException

class PlaceholderDataSource private constructor() : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        // Do nothing.
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        throw IOException("PlaceholderDataSource cannot be opened")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        throw UnsupportedOperationException()
    }

    override val uri: Uri? = null

    override fun close() {
        // do nothing.
    }

    companion object {
        val INSTANCE: PlaceholderDataSource = PlaceholderDataSource()

        val FACTORY: DataSource.Factory = object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                return PlaceholderDataSource()
            }
        }
    }
}
