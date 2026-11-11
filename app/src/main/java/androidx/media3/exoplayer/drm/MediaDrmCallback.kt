package androidx.media3.exoplayer.drm

import androidx.media3.exoplayer.source.LoadEventInfo
import java.util.UUID

interface MediaDrmCallback {
    class Response(val data: ByteArray, val loadEventInfo: LoadEventInfo? = null)
    fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): Response
    fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): Response
}
