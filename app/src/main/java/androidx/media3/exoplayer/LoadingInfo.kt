package androidx.media3.exoplayer

import androidx.media3.common.C

import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Objects

class LoadingInfo private constructor(builder: Builder) {
    class Builder {
         var playbackPositionUs: Long
         var playbackSpeed: Float
         var lastRebufferRealtimeMs: Long

        constructor() {
            this.playbackPositionUs = C.TIME_UNSET
            this.playbackSpeed = C.RATE_UNSET
            this.lastRebufferRealtimeMs = C.TIME_UNSET
        }

         constructor(loadingInfo: LoadingInfo) {
            this.playbackPositionUs = loadingInfo.playbackPositionUs
            this.playbackSpeed = loadingInfo.playbackSpeed
            this.lastRebufferRealtimeMs = loadingInfo.lastRebufferRealtimeMs
        }

        fun setPlaybackPositionUs(playbackPositionUs: Long): Builder {
            this.playbackPositionUs = playbackPositionUs
            return this
        }

        fun setPlaybackSpeed(playbackSpeed: Float): Builder {
            Preconditions.checkArgument(playbackSpeed > 0 || playbackSpeed == C.RATE_UNSET)
            this.playbackSpeed = playbackSpeed
            return this
        }

        fun setLastRebufferRealtimeMs(lastRebufferRealtimeMs: Long): Builder {
            Preconditions.checkArgument(lastRebufferRealtimeMs >= 0 || lastRebufferRealtimeMs == C.TIME_UNSET)
            this.lastRebufferRealtimeMs = lastRebufferRealtimeMs
            return this
        }

        fun build(): LoadingInfo {
            return LoadingInfo(this)
        }
    }

    val playbackPositionUs: Long
    val playbackSpeed: Float
    val lastRebufferRealtimeMs: Long

    init {
        this.playbackPositionUs = builder.playbackPositionUs
        this.playbackSpeed = builder.playbackSpeed
        this.lastRebufferRealtimeMs = builder.lastRebufferRealtimeMs
    }

    fun buildUpon(): Builder {
        return Builder(this)
    }

    fun rebufferedSince(realtimeMs: Long): Boolean {
        return lastRebufferRealtimeMs != C.TIME_UNSET && realtimeMs != C.TIME_UNSET && lastRebufferRealtimeMs >= realtimeMs
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is LoadingInfo) {
            return false
        }
        val that = o
        return playbackPositionUs == that.playbackPositionUs && playbackSpeed == that.playbackSpeed && lastRebufferRealtimeMs == that.lastRebufferRealtimeMs
    }

    override fun hashCode(): Int {
        return Objects.hash(playbackPositionUs, playbackSpeed, lastRebufferRealtimeMs)
    }
}
