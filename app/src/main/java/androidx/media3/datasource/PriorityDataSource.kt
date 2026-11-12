package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PriorityTaskManager
import com.google.common.base.Preconditions
import java.io.IOException

class PriorityDataSource(
    private val upstream: DataSource,
    priorityTaskManager: PriorityTaskManager?,
    private val priority: Int
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val priorityTaskManager: PriorityTaskManager,
        private val priority: Int
    ) : DataSource.Factory {
        override fun createDataSource(): PriorityDataSource {
            return PriorityDataSource(
                upstreamFactory.createDataSource(), priorityTaskManager, priority
            )
        }
    }

    private val priorityTaskManager: PriorityTaskManager = priorityTaskManager!!

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    
    override fun open(dataSpec: DataSpec): Long {
        priorityTaskManager.proceedOrThrow(priority)
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        priorityTaskManager.proceedOrThrow(priority)
        return upstream.read(buffer, offset, length)
    }

    override val uri: Uri?
        get() = upstream.uri

    override val responseHeaders: MutableMap<String, List<String>>
        get() = upstream.responseHeaders

    
    override fun close() {
        upstream.close()
    }
}
