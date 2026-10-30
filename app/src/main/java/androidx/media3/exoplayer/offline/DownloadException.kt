package androidx.media3.exoplayer.offline

import java.io.IOException

class DownloadException : IOException {
    constructor(message: String?) : super(message)
    constructor(cause: Throwable?) : super(cause)
}
