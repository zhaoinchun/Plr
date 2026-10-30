package androidx.media3.exoplayer.drm

import android.net.Uri

import androidx.media3.datasource.DataSpec
import java.io.IOException

class MediaDrmCallbackException(
    val dataSpec: DataSpec,
    val uriAfterRedirects: Uri,
    val responseHeaders: MutableMap<String, List<String>>,
    val bytesLoaded: Long,
    cause: Throwable
) : IOException(cause)
