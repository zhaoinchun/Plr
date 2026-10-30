package androidx.media3.exoplayer.drm

import androidx.annotation.IntDef
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.CryptoConfig
import java.io.IOException
import java.util.UUID

interface DrmSession {
    class DrmSessionException(
        cause: Throwable,
        @field:PlaybackException.ErrorCode @param:PlaybackException.ErrorCode val errorCode: @PlaybackException.ErrorCode Int
    ) : IOException(cause)

    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [STATE_RELEASED, STATE_ERROR, STATE_OPENING, STATE_OPENED, STATE_OPENED_WITH_KEYS])
    annotation class State

    @get:State
    val state: @State Int

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
