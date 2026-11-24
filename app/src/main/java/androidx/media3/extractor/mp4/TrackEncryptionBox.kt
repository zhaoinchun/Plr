package androidx.media3.extractor.mp4

import androidx.media3.common.C
import androidx.media3.common.util.Log.w
import androidx.media3.extractor.TrackOutput.CryptoData

class TrackEncryptionBox(
    val isEncrypted: Boolean,
    val schemeType: String?,
    val perSampleIvSize: Int,
    keyId: ByteArray?,
    defaultEncryptedBlocks: Int,
    defaultClearBlocks: Int,
    val defaultInitializationVector: ByteArray?
) {
    val cryptoData = CryptoData(
        schemeToCryptoMode(schemeType), keyId, defaultEncryptedBlocks, defaultClearBlocks
    )

    companion object {
        private const val TAG = "TrackEncryptionBox"

        private fun schemeToCryptoMode(schemeType: String?): Int {
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
