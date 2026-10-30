package androidx.media3.exoplayer.source

import android.net.Uri

import androidx.media3.datasource.DataSpec
import java.util.concurrent.atomic.AtomicLong

class LoadEventInfo {
    class Builder {
        var loadTaskId: Long
        var dataSpec: DataSpec
        var elapsedRealtimeMs: Long
        var uri: Uri
        var steeredPathwayId: String? = null
        var responseHeaders: Map<String, List<String>>
        var loadDurationMs: Long = 0
        var bytesLoaded: Long = 0

        constructor(loadTaskId: Long, dataSpec: DataSpec, elapsedRealtimeMs: Long) {
            this.loadTaskId = loadTaskId
            this.dataSpec = dataSpec
            this.uri = dataSpec.uri
            this.elapsedRealtimeMs = elapsedRealtimeMs
            this.responseHeaders = mapOf()
        }

        constructor(loadEventInfo: LoadEventInfo) {
            this.loadTaskId = loadEventInfo.loadTaskId
            this.dataSpec = loadEventInfo.dataSpec
            this.uri = loadEventInfo.uri
            this.responseHeaders = loadEventInfo.responseHeaders
            this.elapsedRealtimeMs = loadEventInfo.elapsedRealtimeMs
            this.loadDurationMs = loadEventInfo.loadDurationMs
            this.bytesLoaded = loadEventInfo.bytesLoaded
        }

        fun setLoadTaskId(loadTaskId: Long): Builder {
            this.loadTaskId = loadTaskId
            return this
        }

        fun setDataSpec(dataSpec: DataSpec): Builder {
            this.dataSpec = dataSpec
            this.uri = dataSpec.uri
            return this
        }

        fun setElapsedRealtimeMs(elapsedRealtimeMs: Long): Builder {
            this.elapsedRealtimeMs = elapsedRealtimeMs
            return this
        }

        fun setUri(uri: Uri): Builder {
            this.uri = uri
            return this
        }

        fun setSteeredPathwayId(steeredPathwayId: String?): Builder {
            this.steeredPathwayId = steeredPathwayId
            return this
        }

        fun setResponseHeaders(responseHeaders: MutableMap<String, List<String>>): Builder {
            this.responseHeaders = responseHeaders
            return this
        }

        fun setLoadDurationMs(loadDurationMs: Long): Builder {
            this.loadDurationMs = loadDurationMs
            return this
        }

        fun setBytesLoaded(bytesLoaded: Long): Builder {
            this.bytesLoaded = bytesLoaded
            return this
        }

        fun build(): LoadEventInfo {
            return LoadEventInfo(this)
        }
    }

    val loadTaskId: Long
    val dataSpec: DataSpec
    val uri: Uri
    val steeredPathwayId: String?
    val responseHeaders: Map<String, List<String>>
    val elapsedRealtimeMs: Long
    val loadDurationMs: Long
    val bytesLoaded: Long

    private constructor(builder: Builder) {
        this.loadTaskId = builder.loadTaskId
        this.dataSpec = builder.dataSpec
        this.uri = builder.uri
        this.steeredPathwayId = builder.steeredPathwayId
        this.responseHeaders = builder.responseHeaders
        this.elapsedRealtimeMs = builder.elapsedRealtimeMs
        this.loadDurationMs = builder.loadDurationMs
        this.bytesLoaded = builder.bytesLoaded
    }

    fun buildUpon(): Builder {
        return Builder(this)
    }

    companion object {
        private val idSource = AtomicLong()
        val newId: Long
            get() = idSource.getAndIncrement()
    }
}
