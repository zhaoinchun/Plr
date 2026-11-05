package androidx.media3.exoplayer.drm

import androidx.media3.exoplayer.source.LoadEventInfo
import java.util.UUID

interface MediaDrmCallback {
    class Response(val data: ByteArray, val loadEventInfo: LoadEventInfo? = null)

    @Throws(MediaDrmCallbackException::class)
    fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): Response

    @Throws(MediaDrmCallbackException::class)
    fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): Response
}
