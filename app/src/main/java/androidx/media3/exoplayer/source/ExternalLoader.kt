package androidx.media3.exoplayer.source

import android.net.Uri

import com.google.common.util.concurrent.ListenableFuture

interface ExternalLoader {
    class LoadRequest(val uri: Uri)

    fun load(loadRequest: LoadRequest): ListenableFuture<*>
}
