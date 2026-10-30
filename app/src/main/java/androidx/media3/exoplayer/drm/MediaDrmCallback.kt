package androidx.media3.exoplayer.drm

import androidx.media3.exoplayer.source.LoadEventInfo
import java.util.UUID

interface MediaDrmCallback {
    class Response {
        class Builder(val data: ByteArray) {
            var loadEventInfo: LoadEventInfo? = null

            fun setLoadEventInfo(loadEventInfo: LoadEventInfo): Builder {
                this.loadEventInfo = loadEventInfo
                return this
            }

            fun build(): Response {
                return Response(this)
            }
        }

        val data: ByteArray
        val loadEventInfo: LoadEventInfo?

        constructor(data: ByteArray) {
            this.data = data
            loadEventInfo = null
        }

        private constructor(builder: Builder) {
            data = builder.data
            loadEventInfo = builder.loadEventInfo
        }
    }

    @Throws(MediaDrmCallbackException::class)
    fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): Response

    @Throws(MediaDrmCallbackException::class)
    fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): Response
}
