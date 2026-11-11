package androidx.media3.exoplayer.offline

import androidx.annotation.WorkerThread
import java.io.IOException

@WorkerThread
interface DownloadIndex {

    
    fun getDownload(id: String?): Download?

    
    fun getDownloads( vararg states: Int): DownloadCursor
}
