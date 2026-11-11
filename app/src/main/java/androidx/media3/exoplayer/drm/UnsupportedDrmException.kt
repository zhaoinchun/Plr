
package androidx.media3.exoplayer.drm

import androidx.annotation.IntDef

class UnsupportedDrmException : Exception {
    
    val reason:  Int

    constructor( reason:  Int) {
        this.reason = reason
    }

    constructor( reason:  Int, cause: Exception?) : super(cause) {
        this.reason = reason
    }

    companion object {
        const val REASON_UNSUPPORTED_SCHEME: Int = 1
        const val REASON_INSTANTIATION_ERROR: Int = 2
    }
}
