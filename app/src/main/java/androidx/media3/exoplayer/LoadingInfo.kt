package androidx.media3.exoplayer

import androidx.media3.common.C

data class LoadingInfo(
    val playbackPositionUs: Long = C.TIME_UNSET,
    val playbackSpeed: Float = C.RATE_UNSET,
    val lastRebufferRealtimeMs: Long = C.TIME_UNSET,
) {

    fun rebufferedSince(realtimeMs: Long): Boolean {
        return lastRebufferRealtimeMs != C.TIME_UNSET && realtimeMs != C.TIME_UNSET && lastRebufferRealtimeMs >= realtimeMs
    }

}
