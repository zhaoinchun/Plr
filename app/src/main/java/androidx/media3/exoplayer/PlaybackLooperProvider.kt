package androidx.media3.exoplayer

import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import androidx.annotation.GuardedBy
import androidx.annotation.RestrictTo
import com.google.common.base.Preconditions

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class PlaybackLooperProvider  constructor(
    @field:GuardedBy(
        "lock"
    ) private var playbackLooper: Looper? = null
) {
    private val lock: Any

    @GuardedBy("lock")
    private var internalPlaybackThread: HandlerThread? = null

    @GuardedBy("lock")
    private var referenceCount = 0

    init {
        lock = Any()
    }

    fun obtainLooper(): Looper {
        synchronized(lock) {
            if (playbackLooper == null) {
                Preconditions.checkState(referenceCount == 0 && internalPlaybackThread == null)
                // Note: The documentation for Process.THREAD_PRIORITY_AUDIO that states "Applications can
                // not normally change to this priority" is incorrect.
                internalPlaybackThread =
                    HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)
                internalPlaybackThread!!.start()
                playbackLooper = internalPlaybackThread!!.getLooper()
            }
            referenceCount++
            return playbackLooper!!
        }
    }

    fun releaseLooper() {
        synchronized(lock) {
            Preconditions.checkState(referenceCount > 0)
            referenceCount--
            if (referenceCount == 0 && internalPlaybackThread != null) {
                internalPlaybackThread!!.quit()
                internalPlaybackThread = null
                playbackLooper = null
            }
        }
    }
}
