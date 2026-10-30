
package androidx.media3.exoplayer.drm

import android.os.Build

import androidx.media3.decoder.CryptoConfig
import java.util.UUID

class FrameworkCryptoConfig @Suppress("deprecation") // Setting deprecated field
@Deprecated(
    """Use {@link FrameworkCryptoConfig#FrameworkCryptoConfig(UUID, byte[])} instead, and
        {@link ExoMediaDrm#requiresSecureDecoder} for the secure decoder handling logic."""
) constructor(
  val uuid: UUID?,
  val sessionId: ByteArray?,
  val forceAllowInsecureDecoderComponents: Boolean
) : CryptoConfig {
    
    @Suppress("deprecation") // Delegating to deprecated constructor
    constructor(uuid: UUID?, sessionId: ByteArray?) : this(
        uuid,
        sessionId,  
        false
    )

    companion object {
        val WORKAROUND_DEVICE_NEEDS_KEYS_TO_CONFIGURE_CODEC: Boolean =
            "Amazon" == Build.MANUFACTURER
                    && ("AFTM" == Build.MODEL // Fire TV Stick Gen 1
                    || "AFTB" == Build.MODEL) // Fire TV Gen 1
    }
}
