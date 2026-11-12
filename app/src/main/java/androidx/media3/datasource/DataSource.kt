package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.DataReader

interface DataSource : DataReader {
    interface Factory {
        fun createDataSource(): DataSource
    }

    fun addTransferListener(transferListener: TransferListener)
    fun open(dataSpec: DataSpec): Long

    val uri: Uri?
    val responseHeaders: MutableMap<String, List<String>>
        get() = mutableMapOf()

    fun close()
}
