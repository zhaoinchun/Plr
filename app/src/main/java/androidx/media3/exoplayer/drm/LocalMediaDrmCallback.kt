
package androidx.media3.exoplayer.drm

import java.util.UUID

class LocalMediaDrmCallback(keyResponse: ByteArray) : MediaDrmCallback {
    private val keyResponse: MediaDrmCallback.Response = MediaDrmCallback.Response(keyResponse)

    override fun executeProvisionRequest(
        uuid: UUID, request: ExoMediaDrm.ProvisionRequest
    ): MediaDrmCallback.Response {
        throw UnsupportedOperationException()
    }

    override fun executeKeyRequest(
        uuid: UUID, request: ExoMediaDrm.KeyRequest
    ): MediaDrmCallback.Response {
        return keyResponse
    }
}
