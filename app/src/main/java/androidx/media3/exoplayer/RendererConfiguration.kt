package androidx.media3.exoplayer

import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.OffloadMode

class RendererConfiguration {
    val offloadModePreferred: @OffloadMode Int
    val tunneling: Boolean

    constructor(tunneling: Boolean) {
        this.offloadModePreferred = AudioSink.OFFLOAD_MODE_DISABLED
        this.tunneling = tunneling
    }

    constructor(offloadModePreferred: @OffloadMode Int, tunneling: Boolean) {
        this.offloadModePreferred = offloadModePreferred
        this.tunneling = tunneling
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as RendererConfiguration
        return offloadModePreferred == other.offloadModePreferred && tunneling == other.tunneling
    }

    override fun hashCode(): Int {
        var hashCode = offloadModePreferred shl 1
        hashCode += (if (tunneling) 1 else 0)
        return hashCode
    }

    companion object {
        val DEFAULT: RendererConfiguration = RendererConfiguration( 
            AudioSink.OFFLOAD_MODE_DISABLED,  false
        )
    }
}
