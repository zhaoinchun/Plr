package androidx.media3.exoplayer.drm

import androidx.annotation.IntDef
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.CryptoConfig
import java.io.IOException
import java.util.UUID

interface DrmSession {
    class DrmSessionException(
        cause: Throwable,val errorCode:  Int
    ) : IOException(cause)

    val state:  Int

    fun playClearSamplesWithoutKeys(): Boolean {
        return false
    }

    val error: DrmSessionException?
    val schemeUuid: UUID
    val cryptoConfig: CryptoConfig?
    fun queryKeyStatus(): Map<String, String>?
    val offlineLicenseKeySetId: ByteArray?
    fun requiresSecureDecoder(mimeType: String): Boolean
    fun acquire(eventDispatcher: DrmSessionEventListener.EventDispatcher?)
    fun release(eventDispatcher: DrmSessionEventListener.EventDispatcher?)

    companion object {
        fun replaceSession(
            previousSession: DrmSession?, newSession: DrmSession?
        ) {
            if (previousSession === newSession) {
                // Do nothing.
                return
            }
            newSession?.acquire( null)
            previousSession?.release( null)
        }

        const val STATE_RELEASED: Int = 0
        const val STATE_ERROR: Int = 1
        const val STATE_OPENING: Int = 2
        const val STATE_OPENED: Int = 3
        const val STATE_OPENED_WITH_KEYS: Int = 4
    }
}
