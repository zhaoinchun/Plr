
package androidx.media3.exoplayer.offline

import androidx.annotation.WorkerThread
import java.io.IOException

@WorkerThread
interface DownloadIndex {
    
    @Throws(IOException::class)
    fun getDownload(id: String?): Download?

    @Throws(IOException::class)
    fun getDownloads(@Download.State vararg states: Int): DownloadCursor
}
