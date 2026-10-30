
package androidx.media3.exoplayer.offline

import java.io.IOException

interface Downloader {
    interface ProgressListener {
        fun onProgress(contentLength: Long, bytesDownloaded: Long, percentDownloaded: Float)
    }

    @Throws(IOException::class, InterruptedException::class)
    fun download(progressListener: ProgressListener?)
    fun cancel()
    fun remove()
}
