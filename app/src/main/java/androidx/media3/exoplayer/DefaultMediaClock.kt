
package androidx.media3.exoplayer

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import com.google.common.base.Preconditions

internal class DefaultMediaClock(private val listener: PlaybackParametersListener, clock: Clock) :
    MediaClock {
    interface PlaybackParametersListener {
        fun onPlaybackParametersChanged(newPlaybackParameters: PlaybackParameters?)
    }

    private val standaloneClock: StandaloneMediaClock

    private var rendererClockSource: Renderer? = null
    private var rendererClock: MediaClock? = null
    private var isUsingStandaloneClock: Boolean
    private var standaloneClockIsStarted = false

    init {
        this.standaloneClock = StandaloneMediaClock(clock)
        isUsingStandaloneClock = true
    }

    fun start() {
        standaloneClockIsStarted = true
        standaloneClock.start()
    }

    fun stop() {
        standaloneClockIsStarted = false
        standaloneClock.stop()
    }

    fun resetPosition(positionUs: Long) {
        standaloneClock.resetPosition(positionUs)
    }

    @Throws(ExoPlaybackException::class)
    fun onRendererEnabled(renderer: Renderer) {
        val rendererMediaClock = renderer.mediaClock
        if (rendererMediaClock != null && rendererMediaClock !== rendererClock) {
            if (rendererClock != null) {
                throw ExoPlaybackException.createForUnexpected(
                    IllegalStateException("Multiple renderer media clocks enabled."),
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            }
            this.rendererClock = rendererMediaClock
            this.rendererClockSource = renderer
            rendererClock!!.playbackParameters = standaloneClock.playbackParameters
        }
    }

    fun onRendererDisabled(renderer: Renderer?) {
        if (renderer === rendererClockSource) {
            this.rendererClock = null
            this.rendererClockSource = null
            isUsingStandaloneClock = true
        }
    }

    fun syncAndGetPositionUs(isReadingAhead: Boolean): Long {
        syncClocks(isReadingAhead)
        return positionUs
    }

    override val positionUs: Long
        get() {
            return if (isUsingStandaloneClock)
                standaloneClock.positionUs
            else
                rendererClock!!.positionUs
        }

    override fun hasSkippedSilenceSinceLastCall(): Boolean {
        return if (isUsingStandaloneClock)
            standaloneClock.hasSkippedSilenceSinceLastCall()
        else
            rendererClock!!.hasSkippedSilenceSinceLastCall()
    }

    override var playbackParameters: PlaybackParameters
        get() {
            return if (rendererClock != null)
                rendererClock!!.playbackParameters
            else
                standaloneClock.playbackParameters
        }
        set(value) {
            var v = value!!
            if (rendererClock != null) {
                rendererClock!!.playbackParameters = v
                v = rendererClock!!.playbackParameters!!
            }
            standaloneClock.playbackParameters=(v)
        }

    private fun syncClocks(isReadingAhead: Boolean) {
        if (shouldUseStandaloneClock(isReadingAhead)) {
            isUsingStandaloneClock = true
            if (standaloneClockIsStarted) {
                standaloneClock.start()
            }
            return
        }
        // We are either already using the renderer clock or switching from the standalone to the
        // renderer clock, so it must be non-null.
        val rendererClock = rendererClock!!
        val rendererClockPositionUs = rendererClock.positionUs
        if (isUsingStandaloneClock) {
            // Ensure enabling the renderer clock doesn't jump backwards in time.
            if (rendererClockPositionUs < standaloneClock.positionUs) {
                standaloneClock.stop()
                return
            }
            isUsingStandaloneClock = false
            if (standaloneClockIsStarted) {
                standaloneClock.start()
            }
        }
        // Continuously sync stand-alone clock to renderer clock so that it can take over if needed.
        standaloneClock.resetPosition(rendererClockPositionUs)
        val playbackParameters = rendererClock.playbackParameters!!
        if (!playbackParameters.equals(standaloneClock.playbackParameters)) {
            standaloneClock.playbackParameters=(playbackParameters)
            listener.onPlaybackParametersChanged(playbackParameters)
        }
    }

    private fun shouldUseStandaloneClock(isReadingAhead: Boolean): Boolean {
        // Use the standalone clock if the clock providing renderer is not set or has ended. Use the
        // standalone clock if reading ahead and the renderer is not in a started state. Also use
        // the standalone clock if the renderer is not ready and we have finished reading the stream or
        // are reading ahead to avoid getting stuck if tracks in the current period have uneven
        // durations. See: https://github.com/google/ExoPlayer/issues/1874.
        return rendererClockSource == null || rendererClockSource!!.isEnded
                || (isReadingAhead && rendererClockSource!!.state != Renderer.STATE_STARTED)
                || (!rendererClockSource!!.isReady
                && (isReadingAhead || rendererClockSource!!.hasReadStreamToEnd()))
    }
}
