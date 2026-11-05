package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.datasource.DataSpec
import java.util.concurrent.atomic.AtomicLong

data class LoadEventInfo(
    val loadTaskId: Long,
    val dataSpec: DataSpec,
    val elapsedRealtimeMs: Long,
    val uri: Uri = dataSpec.uri,
    var steeredPathwayId: String? = null,
    var responseHeaders: Map<String, List<String>> = mapOf(),
    var loadDurationMs: Long = 0,
    var bytesLoaded: Long = 0,
) {
    companion object {
        private val idSource = AtomicLong()
        val newId: Long
            get() = idSource.getAndIncrement()
    }
}
