package androidx.media3.decoder

import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C

import com.google.common.base.Preconditions

class CryptoInfo {
    var iv: ByteArray? = null
    var key: ByteArray? = null
    var mode: @C.CryptoMode Int = 0
    var numBytesOfClearData: IntArray? = null
    var numBytesOfEncryptedData: IntArray? = null
    var numSubSamples: Int = 0
    var encryptedBlocks: Int = 0
    var clearBlocks: Int = 0
    val frameworkCryptoInfo: MediaCodec.CryptoInfo
    private val patternHolder: PatternHolderV24?

    init {
        frameworkCryptoInfo = MediaCodec.CryptoInfo()
        patternHolder =
            if (Build.VERSION.SDK_INT >= 24) PatternHolderV24(frameworkCryptoInfo) else null
    }

    fun set(
        numSubSamples: Int,
        numBytesOfClearData: IntArray?,
        numBytesOfEncryptedData: IntArray?,
        key: ByteArray?,
        iv: ByteArray?,
        mode: @C.CryptoMode Int,
        encryptedBlocks: Int,
        clearBlocks: Int
    ) {
        this.numSubSamples = numSubSamples
        this.numBytesOfClearData = numBytesOfClearData
        this.numBytesOfEncryptedData = numBytesOfEncryptedData
        this.key = key
        this.iv = iv
        this.mode = mode
        this.encryptedBlocks = encryptedBlocks
        this.clearBlocks = clearBlocks
        // Update frameworkCryptoInfo fields directly because CryptoInfo.set performs an unnecessary
        // object allocation on Android N.
        frameworkCryptoInfo.numSubSamples = numSubSamples
        frameworkCryptoInfo.numBytesOfClearData = numBytesOfClearData
        frameworkCryptoInfo.numBytesOfEncryptedData = numBytesOfEncryptedData
        frameworkCryptoInfo.key = key
        frameworkCryptoInfo.iv = iv
        frameworkCryptoInfo.mode = mode
        if (Build.VERSION.SDK_INT >= 24) {
            patternHolder!!.set(encryptedBlocks, clearBlocks)
        }
    }

    fun increaseClearDataFirstSubSampleBy(count: Int) {
        if (count == 0) {
            return
        }
        if (numBytesOfClearData == null) {
            numBytesOfClearData = IntArray(1)
            frameworkCryptoInfo.numBytesOfClearData = numBytesOfClearData
        }
        numBytesOfClearData!![0] += count
    }

    @RequiresApi(24)
    private class PatternHolderV24(private val frameworkCryptoInfo: MediaCodec.CryptoInfo) {
        private val pattern: MediaCodec.CryptoInfo.Pattern

        init {
            pattern = MediaCodec.CryptoInfo.Pattern(0, 0)
        }

        fun set(encryptedBlocks: Int, clearBlocks: Int) {
            pattern.set(encryptedBlocks, clearBlocks)
            frameworkCryptoInfo.setPattern(pattern)
        }
    }
}
