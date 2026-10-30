package androidx.media3.common.util

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.google.common.util.concurrent.ListenableFuture

interface BitmapLoader {
    fun supportsMimeType(mimeType: String): Boolean

    fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap?>?

    fun loadBitmap(uri: Uri): ListenableFuture<Bitmap?>?

    fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap?>? {
        val future: ListenableFuture<Bitmap?>?
        if (metadata.artworkData != null) {
            future = decodeBitmap(metadata.artworkData)
        } else if (metadata.artworkUri != null) {
            future = loadBitmap(metadata.artworkUri)
        } else {
            future = null
        }
        return future
    }
}
