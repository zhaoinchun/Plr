
package androidx.media3.exoplayer

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.msToUs

class StandaloneMediaClock(private val clock: Clock) : MediaClock {
    private var started = false
    private var baseUs: Long = 0
    private var baseElapsedMs: Long = 0
    override var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        set(value) {
            // Store the current position as the new base, in case the playback speed has changed.
            if (started) {
                resetPosition(positionUs)
            }
            field = value
        }

    init {
        playbackParameters = PlaybackParameters.DEFAULT
    }

    fun start() {
        if (!started) {
            baseElapsedMs = clock.elapsedRealtime()
            started = true
        }
    }

    fun stop() {
        if (started) {
            resetPosition(positionUs)
            started = false
        }
    }

    fun resetPosition(positionUs: Long) {
        baseUs = positionUs
        if (started) {
            baseElapsedMs = clock.elapsedRealtime()
        }
    }

    override val positionUs: Long
        get() {
            var positionUs = baseUs
            if (started) {
                val elapsedSinceBaseMs = clock.elapsedRealtime() - baseElapsedMs
                if (playbackParameters!!.speed == 1f) {
                    positionUs += msToUs(elapsedSinceBaseMs)
                } else {
                    // Add the media time in microseconds that will elapse in elapsedSinceBaseMs milliseconds of
                    // wallclock time
                    positionUs += playbackParameters!!.getMediaTimeUsForPlayoutTimeMs(
                        elapsedSinceBaseMs
                    )
                }
            }
            return positionUs
        }

}
