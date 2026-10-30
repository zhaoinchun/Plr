
package androidx.media3.exoplayer.drm

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException

interface DrmSessionManager {
    fun interface DrmSessionReference {
        fun release()

        companion object {
            val EMPTY: DrmSessionReference = DrmSessionReference {}
        }
    }

    fun prepare() {
        // Do nothing.
    }

    fun release() {
        // Do nothing.
    }

    fun setPlayer(playbackLooper: Looper, playerId: PlayerId)

    fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format
    ): DrmSessionReference {
        return DrmSessionReference.EMPTY
    }

    fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format
    ): DrmSession?

    fun getCryptoType(format: Format): @C.CryptoType Int

    companion object {
        
        val DRM_UNSUPPORTED: DrmSessionManager = object : DrmSessionManager {
            override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {}

            override fun acquireSession(
                eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format
            ): DrmSession? {
                return if (format.drmInitData == null) {
                    null
                } else {
                    ErrorStateDrmSession(
                        DrmSessionException(
                            UnsupportedDrmException(UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME),
                            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED
                        )
                    )
                }
            }

            override fun getCryptoType(format: Format): @C.CryptoType Int {
                return if (format.drmInitData != null) C.CRYPTO_TYPE_UNSUPPORTED else C.CRYPTO_TYPE_NONE
            }
        }
    }
}
