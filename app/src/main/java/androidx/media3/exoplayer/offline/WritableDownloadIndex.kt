
package androidx.media3.exoplayer.offline

import androidx.annotation.WorkerThread
import java.io.IOException

@WorkerThread
interface WritableDownloadIndex : DownloadIndex {
    @Throws(IOException::class)
    fun putDownload(download: Download)

    @Throws(IOException::class)
    fun removeDownload(id: String)

    @Throws(IOException::class)
    fun setDownloadingStatesToQueued()

    @Throws(IOException::class)
    fun setStatesToRemoving()

    @Throws(IOException::class)
    fun setStopReason(stopReason: Int)

    @Throws(IOException::class)
    fun setStopReason(id: String, stopReason: Int)
}
