
package androidx.media3.exoplayer.drm

import android.media.MediaDrmException
import android.os.PersistableBundle
import androidx.media3.common.C
import androidx.media3.common.DrmInitData

import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig

class DummyExoMediaDrm : ExoMediaDrm {
    override fun setOnEventListener(listener: ExoMediaDrm.OnEventListener?) {
        // Do nothing.
    }

    override fun setOnKeyStatusChangeListener(listener: ExoMediaDrm.OnKeyStatusChangeListener?) {
        // Do nothing.
    }

    override fun setOnExpirationUpdateListener(listener: ExoMediaDrm.OnExpirationUpdateListener?) {
        // Do nothing.
    }

    @Throws(MediaDrmException::class)
    override fun openSession(): ByteArray {
        throw MediaDrmException("Attempting to open a session using a dummy ExoMediaDrm.")
    }

    override fun closeSession(sessionId: ByteArray) {
        // Do nothing.
    }

    override fun getKeyRequest(
        scope: ByteArray,
        schemeDatas: List<DrmInitData.SchemeData>?,
        keyType: Int,
        optionalParameters: HashMap<String, String>?
    ): ExoMediaDrm.KeyRequest {
        // Should not be invoked. No session should exist.
        throw IllegalStateException()
    }

    override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray? {
        // Should not be invoked. No session should exist.
        throw IllegalStateException()
    }

    override val provisionRequest: ExoMediaDrm.ProvisionRequest
        get() =  throw IllegalStateException()

    override fun provideProvisionResponse(response: ByteArray) {
        // Should not be invoked. No provision should be required.
        throw IllegalStateException()
    }

    override fun queryKeyStatus(sessionId: ByteArray): MutableMap<String, String> {
        // Should not be invoked. No session should exist.
        throw IllegalStateException()
    }

    override fun requiresSecureDecoder(sessionId: ByteArray, mimeType: String): Boolean {
        // Should not be invoked. No session should exist.
        throw IllegalStateException()
    }

    override fun acquire() {
        // Do nothing.
    }

    override fun release() {
        // Do nothing.
    }

    override fun restoreKeys(sessionId: ByteArray, keySetId: ByteArray) {
        // Should not be invoked. No session should exist.
        throw IllegalStateException()
    }

    override val metrics: PersistableBundle?
        get() = null

    override fun getPropertyString(propertyName: String): String {
        return ""
    }

    override fun getPropertyByteArray(propertyName: String): ByteArray {
        return Util.EMPTY_BYTE_ARRAY
    }

    override fun setPropertyString(propertyName: String, value: String) {
        // Do nothing.
    }

    override fun setPropertyByteArray(propertyName: String, value: ByteArray) {
        // Do nothing.
    }

    override fun createCryptoConfig(sessionId: ByteArray): CryptoConfig {
        // Should not be invoked. No session should exist.
        throw IllegalStateException()
    }

    override val cryptoType: @C.CryptoType Int
        get() =  C.CRYPTO_TYPE_UNSUPPORTED

    companion object {
        val instance: DummyExoMediaDrm
            get() = DummyExoMediaDrm()
    }
}
