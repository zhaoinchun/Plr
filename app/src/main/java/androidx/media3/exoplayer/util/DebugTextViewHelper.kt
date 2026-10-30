package androidx.media3.exoplayer.util

import android.annotation.SuppressLint
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Player

import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.base.Preconditions
import java.util.Locale

class DebugTextViewHelper(private val player: ExoPlayer, private val textView: TextView) {
    private val updater = Updater()
    private var started = false

    init {
        Preconditions.checkArgument(player.applicationLooper == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= 35) {
            // Do not let text updates bump up the refresh rate higher than the playing video.
            // See https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate .
            textView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
    }

    fun start() {
        if (started) {
            return
        }
        started = true
        player.addListener(updater)
        updateAndPost()
    }

    fun stop() {
        if (!started) {
            return
        }
        started = false
        player.removeListener(updater)
        textView.removeCallbacks(updater)
    }

    // Protected methods.

    @SuppressLint("SetTextI18n")
    protected fun updateAndPost() {
        textView.text = this.debugString
        textView.removeCallbacks(updater)
        textView.postDelayed(updater, REFRESH_INTERVAL_MS.toLong())
    }

    protected val debugString: String
        get() = this.playerStateString + this.videoString + this.audioString

    protected val playerStateString: String
        get() {
            val playbackStateString = when (player.playbackState) {
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_ENDED -> "ended"
                Player.STATE_IDLE -> "idle"
                Player.STATE_READY -> "ready"
                else -> "unknown"
            }
            return String.format(
                "playWhenReady:%s playbackState:%s item:%s",
                player.playWhenReady, playbackStateString, player.currentMediaItemIndex
            )
        }

    protected val videoString: String
        get() {
            val format = player.videoFormat
            val videoSize = player.videoSize!!
            val decoderCounters = player.videoDecoderCounters
            if (format == null || decoderCounters == null) {
                return ""
            }
            return ("\n"
                    + format.sampleMimeType
                    + "(id:"
                    + format.id
                    + " r:"
                    + videoSize.width
                    + "x"
                    + videoSize.height
                    + getColorInfoString(format.colorInfo)
                    + getPixelAspectRatioString(videoSize.pixelWidthHeightRatio)
                    + getDecoderCountersBufferCountString(
                decoderCounters
            )
                    + " vfpo: "
                    + getVideoFrameProcessingOffsetAverageString(
                decoderCounters.totalVideoFrameProcessingOffsetUs,
                decoderCounters.videoFrameProcessingOffsetCount
            )
                    + ")")
        }

    protected val audioString: String
        get() {
            val format = player.audioFormat
            val decoderCounters = player.audioDecoderCounters
            if (format == null || decoderCounters == null) {
                return ""
            }
            return ("\n"
                    + format.sampleMimeType
                    + "(id:"
                    + format.id
                    + " hz:"
                    + format.sampleRate
                    + " ch:"
                    + format.channelCount
                    + getDecoderCountersBufferCountString(
                decoderCounters
            )
                    + ")")
        }

    private inner class Updater : Player.Listener, Runnable {
        // Player.Listener implementation.
        override fun onPlaybackStateChanged(@Player.State playbackState: @Player.State Int) {
            updateAndPost()
        }

        override fun onPlayWhenReadyChanged(
            playWhenReady: Boolean,
            @Player.PlayWhenReadyChangeReason reason: @Player.PlayWhenReadyChangeReason Int
        ) {
            updateAndPost()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
        ) {
            updateAndPost()
        }

        // Runnable implementation.
        override fun run() {
            updateAndPost()
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000

        private fun getDecoderCountersBufferCountString(counters: DecoderCounters?): String {
            if (counters == null) {
                return ""
            }
            counters.ensureUpdated()
            return (" sib:"
                    + counters.skippedInputBufferCount
                    + " sb:"
                    + counters.skippedOutputBufferCount
                    + " rb:"
                    + counters.renderedOutputBufferCount
                    + " dib:"
                    + counters.droppedInputBufferCount
                    + " db:"
                    + counters.droppedBufferCount
                    + " mcdb:"
                    + counters.maxConsecutiveDroppedBufferCount
                    + " dk:"
                    + counters.droppedToKeyframeCount)
        }

        private fun getColorInfoString(colorInfo: ColorInfo?): String {
            return if (colorInfo != null && colorInfo.isValid) " colr:" + colorInfo.toLogString() else ""
        }

        private fun getPixelAspectRatioString(pixelAspectRatio: Float): String {
            return if (pixelAspectRatio == Format.NO_VALUE.toFloat() || pixelAspectRatio == 1f)
                ""
            else
                (" par:" + String.format(Locale.US, "%.02f", pixelAspectRatio))
        }

        private fun getVideoFrameProcessingOffsetAverageString(
            totalOffsetUs: Long, frameCount: Int
        ): String {
            if (frameCount == 0) {
                return "N/A"
            } else {
                val averageUs = (totalOffsetUs.toDouble() / frameCount).toLong()
                return averageUs.toString()
            }
        }
    }
}
