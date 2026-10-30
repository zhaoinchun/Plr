package androidx.media3.exoplayer.drm

import android.media.DeniedByServerException
import android.media.MediaCryptoException
import android.media.MediaDrm
import android.media.MediaDrmException
import android.media.NotProvisionedException
import android.os.PersistableBundle
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.analytics.PlayerId
import java.util.UUID

interface ExoMediaDrm {
    fun interface Provider {
        fun acquireExoMediaDrm(uuid: UUID): ExoMediaDrm
    }

    class AppManagedProvider(private val exoMediaDrm: ExoMediaDrm) :
        Provider {
        override fun acquireExoMediaDrm(uuid: UUID): ExoMediaDrm {
            exoMediaDrm.acquire()
            return exoMediaDrm
        }
    }

    interface OnEventListener {
        fun onEvent(
            mediaDrm: ExoMediaDrm,
            sessionId: ByteArray?,
            event: Int,
            extra: Int,
            data: ByteArray?
        )
    }

    interface OnKeyStatusChangeListener {
        fun onKeyStatusChange(
            mediaDrm: ExoMediaDrm,
            sessionId: ByteArray,
            exoKeyInformation: List<KeyStatus>,
            hasNewUsableKey: Boolean
        )
    }

    interface OnExpirationUpdateListener {
        fun onExpirationUpdate(
            mediaDrm: ExoMediaDrm,
            sessionId: ByteArray,
            expirationTimeMs: Long
        )
    }

    class KeyStatus(
        val statusCode: Int,
        val keyId: ByteArray
    )

    class KeyRequest  (
        val data: ByteArray,
        val licenseServerUrl: String,
        val requestType: @RequestType Int = REQUEST_TYPE_UNKNOWN
    ) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [REQUEST_TYPE_UNKNOWN, REQUEST_TYPE_INITIAL, REQUEST_TYPE_RENEWAL, REQUEST_TYPE_RELEASE, REQUEST_TYPE_NONE, REQUEST_TYPE_UPDATE]
        )
        annotation class RequestType

        companion object {
            const val REQUEST_TYPE_UNKNOWN: Int = Int.MIN_VALUE
            const val REQUEST_TYPE_INITIAL: Int = MediaDrm.KeyRequest.REQUEST_TYPE_INITIAL
            const val REQUEST_TYPE_RENEWAL: Int = MediaDrm.KeyRequest.REQUEST_TYPE_RENEWAL
            const val REQUEST_TYPE_RELEASE: Int = MediaDrm.KeyRequest.REQUEST_TYPE_RELEASE
            const val REQUEST_TYPE_NONE: Int = MediaDrm.KeyRequest.REQUEST_TYPE_NONE
            const val REQUEST_TYPE_UPDATE: Int = MediaDrm.KeyRequest.REQUEST_TYPE_UPDATE
        }
    }

    class ProvisionRequest(
        val data: ByteArray,
        val defaultUrl: String
    )

    fun setOnEventListener(listener: OnEventListener?)
    fun setOnKeyStatusChangeListener(listener: OnKeyStatusChangeListener?)
    fun setOnExpirationUpdateListener(listener: OnExpirationUpdateListener?)
    @Throws(MediaDrmException::class)
    fun openSession(): ByteArray
    fun closeSession(sessionId: ByteArray)
    fun setPlayerIdForSession(sessionId: ByteArray, playerId: PlayerId) {}

    @Throws(NotProvisionedException::class)
    fun getKeyRequest(
        scope: ByteArray,
        schemeDatas: List<DrmInitData.SchemeData>?,
        keyType: Int,
        optionalParameters: HashMap<String, String>?
    ): KeyRequest

    @Throws(NotProvisionedException::class, DeniedByServerException::class)
    fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray?

    val provisionRequest: ProvisionRequest

    @Throws(DeniedByServerException::class)
    fun provideProvisionResponse(response: ByteArray)
    fun queryKeyStatus(sessionId: ByteArray): Map<String, String>
    fun requiresSecureDecoder(sessionId: ByteArray, mimeType: String): Boolean
    fun acquire()
    fun release()
    fun restoreKeys(sessionId: ByteArray, keySetId: ByteArray)
    fun removeOfflineLicense(keySetId: ByteArray) {
        throw UnsupportedOperationException()
    }

    val offlineLicenseKeySetIds: List<ByteArray>
        get() {
            throw UnsupportedOperationException()
        }

    val metrics: PersistableBundle?

    fun getPropertyString(propertyName: String): String
    fun getPropertyByteArray(propertyName: String): ByteArray
    fun setPropertyString(propertyName: String, value: String)
    fun setPropertyByteArray(propertyName: String, value: ByteArray)

    @Throws(MediaCryptoException::class)
    fun createCryptoConfig(sessionId: ByteArray): CryptoConfig

    val cryptoType: @C.CryptoType Int

    companion object {
        const val EVENT_KEY_REQUIRED: Int = MediaDrm.EVENT_KEY_REQUIRED
        const val EVENT_KEY_EXPIRED: Int = MediaDrm.EVENT_KEY_EXPIRED
        const val EVENT_PROVISION_REQUIRED: Int = MediaDrm.EVENT_PROVISION_REQUIRED
        const val KEY_TYPE_STREAMING: Int = MediaDrm.KEY_TYPE_STREAMING
        const val KEY_TYPE_OFFLINE: Int = MediaDrm.KEY_TYPE_OFFLINE
        const val KEY_TYPE_RELEASE: Int = MediaDrm.KEY_TYPE_RELEASE
    }
}
