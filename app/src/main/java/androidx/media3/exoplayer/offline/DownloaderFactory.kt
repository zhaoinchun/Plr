
package androidx.media3.exoplayer.offline

interface DownloaderFactory {
    fun createDownloader(request: DownloadRequest): Downloader
}
