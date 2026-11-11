package androidx.media3.exoplayer.offline

import androidx.annotation.WorkerThread
import java.io.IOException

@WorkerThread
interface WritableDownloadIndex : DownloadIndex {
    
    fun putDownload(download: Download)

    
    fun removeDownload(id: String)

    
    fun setDownloadingStatesToQueued()

    
    fun setStatesToRemoving()

    
    fun setStopReason(stopReason: Int)

    
    fun setStopReason(id: String, stopReason: Int)
}
