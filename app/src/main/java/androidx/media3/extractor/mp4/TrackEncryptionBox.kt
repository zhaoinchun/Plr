
package androidx.media3.extractor.mp4

import androidx.media3.common.C
import androidx.media3.common.util.Log.w

import androidx.media3.extractor.TrackOutput.CryptoData
import com.google.common.base.Preconditions

class TrackEncryptionBox(
    isEncrypted: Boolean,
    schemeType: String?,
    perSampleIvSize: Int,
    keyId: ByteArray?,
    defaultEncryptedBlocks: Int,
    defaultClearBlocks: Int,
    defaultInitializationVector: ByteArray?
) {
    
    val isEncrypted: Boolean

    val schemeType: String?

    val cryptoData: CryptoData

    val perSampleIvSize: Int

    val defaultInitializationVector: ByteArray?

    init {
        Preconditions.checkArgument((perSampleIvSize == 0) xor (defaultInitializationVector == null))
        this.isEncrypted = isEncrypted
        this.schemeType = schemeType
        this.perSampleIvSize = perSampleIvSize
        this.defaultInitializationVector = defaultInitializationVector
        cryptoData =
            CryptoData(
                schemeToCryptoMode(schemeType), keyId, defaultEncryptedBlocks, defaultClearBlocks
            )
    }

    companion object {
        private const val TAG = "TrackEncryptionBox"

        private fun schemeToCryptoMode(schemeType: String?): @C.CryptoMode Int {
            if (schemeType == null) {
                // If unknown, assume cenc.
                return C.CRYPTO_MODE_AES_CTR
            }
            when (schemeType) {
                C.CENC_TYPE_cenc, C.CENC_TYPE_cens -> return C.CRYPTO_MODE_AES_CTR
                C.CENC_TYPE_cbc1, C.CENC_TYPE_cbcs -> return C.CRYPTO_MODE_AES_CBC
                else -> {
                    w(
                        TAG,
                        ("Unsupported protection scheme type '"
                                + schemeType
                                + "'. Assuming AES-CTR crypto mode.")
                    )
                    return C.CRYPTO_MODE_AES_CTR
                }
            }
        }
    }
}
