package androidx.media3.exoplayer

import androidx.media3.common.Format

import androidx.media3.exoplayer.drm.DrmSession

class FormatHolder {
    var drmSession: DrmSession? = null
    var format: Format? = null

    fun clear() {
        drmSession = null
        format = null
    }
}
