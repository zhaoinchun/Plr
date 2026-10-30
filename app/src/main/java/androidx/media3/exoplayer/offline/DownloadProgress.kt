package androidx.media3.exoplayer.offline

import kotlin.concurrent.Volatile

class DownloadProgress {

    @Volatile
    var bytesDownloaded: Long = 0

    @Volatile
    var percentDownloaded: Float = 0f
}
