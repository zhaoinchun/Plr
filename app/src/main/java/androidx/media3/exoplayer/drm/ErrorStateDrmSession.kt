
package androidx.media3.exoplayer.drm

import androidx.media3.common.C

import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import java.util.UUID

class ErrorStateDrmSession(override val error: DrmSessionException) : DrmSession {

    override val state: @DrmSession.State Int
        get() = DrmSession.STATE_ERROR

    override fun playClearSamplesWithoutKeys(): Boolean {
        return false
    }

    override val schemeUuid: UUID
        get() = C.UUID_NIL

    override val cryptoConfig: CryptoConfig?
        get() = null

    override fun queryKeyStatus(): MutableMap<String, String>? {
        return null
    }

    override val offlineLicenseKeySetId: ByteArray?
        get() = null

    override fun requiresSecureDecoder(mimeType: String): Boolean {
        return false
    }

    override fun acquire(eventDispatcher: DrmSessionEventListener.EventDispatcher?) {
        // Do nothing.
    }

    override fun release(eventDispatcher: DrmSessionEventListener.EventDispatcher?) {
        // Do nothing.
    }
}
