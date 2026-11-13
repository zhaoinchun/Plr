package androidx.media3.exoplayer.offline

interface Downloader {
    fun download(onProgress: ((contentLength: Long, bytesDownloaded: Long, percentDownloaded: Float)-> Unit)?)
    fun cancel()
    fun remove()
}
