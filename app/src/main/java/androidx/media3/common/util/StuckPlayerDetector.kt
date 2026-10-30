package androidx.media3.common.util

import android.os.Handler
import android.os.Message
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlin.math.ceil
import kotlin.math.max

class StuckPlayerDetector(
    private val player: Player,
    private val callback: Callback,
    clock: Clock,
    stuckBufferingTimeoutMs: Int,
    stuckPlayingTimeoutMs: Int,
    stuckPlayingNotEndingTimeoutMs: Int,
    stuckSuppressedTimeoutMs: Int
) {
    interface Callback {
        fun onStuckPlayerDetected(exception: StuckPlayerException)
    }

    private val playerListener: Player.Listener
    private val clock: Clock?
    private val period: Timeline.Period
    private val handler: HandlerWrapper
    private val stuckBufferingDetector: StuckBufferingDetector
    private val stuckPlayingDetector: StuckPlayingDetector
    private val stuckPlayingNotEndingDetector: StuckPlayingNotEndingDetector
    private val stuckSuppressedDetector: StuckSuppressedDetector

    // Using instance methods as listeners in the constructor
    init {
        this.clock = clock
        this.period = Timeline.Period()
        this.handler =
            clock.createHandler(
                player.applicationLooper!!,  
                Handler.Callback { message: Message? -> this.handleMessage(message!!) })
        this.stuckBufferingDetector =
            StuckBufferingDetector(stuckBufferingTimeoutMs)
        this.stuckPlayingDetector = StuckPlayingDetector(stuckPlayingTimeoutMs)
        this.stuckPlayingNotEndingDetector =
           StuckPlayingNotEndingDetector(stuckPlayingNotEndingTimeoutMs)
        this.stuckSuppressedDetector =
           StuckSuppressedDetector(stuckSuppressedTimeoutMs)
        this.playerListener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    onPlayerEvents()
                }
            }
        player.addListener(playerListener)
    }

    fun release() {
        handler.removeCallbacksAndMessages( null)
        player.removeListener(playerListener)
    }

    private fun onPlayerEvents() {
        stuckBufferingDetector.update()
        stuckPlayingDetector.update()
        stuckPlayingNotEndingDetector.update()
        stuckSuppressedDetector.update()
    }

    private fun handleMessage(message: Message): Boolean {
        when (message.what) {
            MSG_STUCK_BUFFERING_TIMEOUT -> {
                stuckBufferingDetector.update()
                return true
            }

            MSG_STUCK_PLAYING_TIMEOUT -> {
                stuckPlayingDetector.update()
                return true
            }

            MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT -> {
                stuckPlayingNotEndingDetector.update()
                return true
            }

            MSG_STUCK_SUPPRESSED_TIMEOUT -> {
                stuckSuppressedDetector.update()
                return true
            }

            else -> return false
        }
    }

    private inner class StuckBufferingDetector(private val stuckBufferingTimeoutMs: Int) {
        private var periodUid: Any? = null
        private var adGroupIndex = 0
        private var adIndexInAdGroup = 0
        private var bufferedPositionInPeriodMs: Long = 0
        private var bufferedDurationInOtherPeriodsMs: Long = 0
        private var isBuffering = false
        private var startRealtimeMs: Long = 0

        fun update() {
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                // Preconditions for stuck buffering not met. Clear any pending timeout and ignore.
                if (isBuffering) {
                    handler.removeMessages(MSG_STUCK_BUFFERING_TIMEOUT)
                }
                isBuffering = false
                return
            }
            val timeline = player.currentTimeline
            val periodUid =
                if (timeline.isEmpty) null else timeline.getUidOfPeriod(player.currentPeriodIndex)
            val adGroupIndex = player.currentAdGroupIndex
            val adIndexInAdGroup = player.currentAdIndexInAdGroup
            var bufferedPositionInPeriodMs = player.bufferedPosition
            val bufferedDurationInPeriodMs =
                max(0, bufferedPositionInPeriodMs - player.currentPosition)
            val bufferedDurationInOtherPeriodsMs =
                max(0, player.totalBufferedDuration - bufferedDurationInPeriodMs)
            if (periodUid != null && adGroupIndex == C.INDEX_UNSET) {
                bufferedPositionInPeriodMs -=
                    timeline.getPeriodByUid(periodUid, period).positionInWindowMs
            }
            val nowRealtimeMs = clock!!.elapsedRealtime()
            if (isBuffering
                && periodUid == this.periodUid
                && adGroupIndex == this.adGroupIndex && adIndexInAdGroup == this.adIndexInAdGroup && bufferedPositionInPeriodMs == this.bufferedPositionInPeriodMs && bufferedDurationInOtherPeriodsMs == this.bufferedDurationInOtherPeriodsMs
            ) {
                // Still the same state, keep current timeout.
                if (nowRealtimeMs - startRealtimeMs >= stuckBufferingTimeoutMs) {
                    callback.onStuckPlayerDetected(
                        StuckPlayerException(
                            StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
                            stuckBufferingTimeoutMs
                        )
                    )
                }
            } else {
                // Restart the timeout from the current time.
                isBuffering = true
                startRealtimeMs = nowRealtimeMs
                this.periodUid = periodUid
                this.adGroupIndex = adGroupIndex
                this.adIndexInAdGroup = adIndexInAdGroup
                this.bufferedPositionInPeriodMs = bufferedPositionInPeriodMs
                this.bufferedDurationInOtherPeriodsMs = bufferedDurationInOtherPeriodsMs
                handler.removeMessages(MSG_STUCK_BUFFERING_TIMEOUT)
                handler.sendEmptyMessageDelayed(
                    MSG_STUCK_BUFFERING_TIMEOUT,
                    stuckBufferingTimeoutMs
                )
            }
        }
    }

    private inner class StuckPlayingDetector(private val stuckPlayingTimeoutMs: Int) {
        private var periodUid: Any? = null
        private var adGroupIndex = 0
        private var adIndexInAdGroup = 0
        private var currentPositionInPeriodMs: Long = 0
        private var isPlaying = false
        private var startRealtimeMs: Long = 0

        fun update() {
            if (!player.isPlaying) {
                // Preconditions for stuck playing not met. Clear any pending timeout and ignore.
                if (isPlaying) {
                    handler.removeMessages(MSG_STUCK_PLAYING_TIMEOUT)
                }
                isPlaying = false
                return
            }
            val timeline = player.currentTimeline
            val periodUid =
                if (timeline.isEmpty) null else timeline.getUidOfPeriod(player.currentPeriodIndex)
            val adGroupIndex = player.currentAdGroupIndex
            val adIndexInAdGroup = player.currentAdIndexInAdGroup
            var currentPositionInPeriodMs = player.currentPosition
            if (periodUid != null && adGroupIndex == C.INDEX_UNSET) {
                currentPositionInPeriodMs -=
                    timeline.getPeriodByUid(periodUid, period).positionInWindowMs
            }
            val nowRealtimeMs = clock!!.elapsedRealtime()
            if (isPlaying
                && periodUid == this.periodUid
                && adGroupIndex == this.adGroupIndex && adIndexInAdGroup == this.adIndexInAdGroup && currentPositionInPeriodMs == this.currentPositionInPeriodMs
            ) {
                // Still the same state, keep current timeout.
                if (nowRealtimeMs - startRealtimeMs >= stuckPlayingTimeoutMs) {
                    callback.onStuckPlayerDetected(
                        StuckPlayerException(
                            StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, stuckPlayingTimeoutMs
                        )
                    )
                }
            } else {
                // Restart the timeout from the current time.
                isPlaying = true
                startRealtimeMs = nowRealtimeMs
                this.periodUid = periodUid
                this.adGroupIndex = adGroupIndex
                this.adIndexInAdGroup = adIndexInAdGroup
                this.currentPositionInPeriodMs = currentPositionInPeriodMs
                handler.removeMessages(MSG_STUCK_PLAYING_TIMEOUT)
                handler.sendEmptyMessageDelayed(MSG_STUCK_PLAYING_TIMEOUT, stuckPlayingTimeoutMs)
            }
        }
    }

    private inner class StuckPlayingNotEndingDetector(private val stuckPlayingNotEndingTimeoutMs: Int) {
        private var periodUid: Any? = null
        private var adGroupIndex = 0
        private var adIndexInAdGroup = 0
        private var isPlayingAndReachedDuration = false
        private var startRealtimeMs: Long = 0

        fun update() {
            val timeline = player.currentTimeline
            val periodUid =
                if (timeline.isEmpty) null else timeline.getUidOfPeriod(player.currentPeriodIndex)
            val adGroupIndex = player.currentAdGroupIndex
            val adIndexInAdGroup = player.currentAdIndexInAdGroup
            var currentPositionInPeriodOrAdMs = player.currentPosition
            var durationOfPeriodOrAdMs = C.TIME_UNSET
            if (periodUid != null && adGroupIndex == C.INDEX_UNSET) {
                timeline.getPeriodByUid(periodUid, period)
                currentPositionInPeriodOrAdMs -= period.positionInWindowMs
                durationOfPeriodOrAdMs = period.durationMs
            } else if (adGroupIndex != C.INDEX_UNSET) {
                durationOfPeriodOrAdMs = player.duration
            }
            val isPlaying = player.isPlaying
            if (!isPlaying || durationOfPeriodOrAdMs == C.TIME_UNSET || currentPositionInPeriodOrAdMs < durationOfPeriodOrAdMs) {
                // Preconditions for stuck playing not ending not met.
                // Clear any pending previous update or timeout.
                handler.removeMessages(MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT)
                if (isPlaying && durationOfPeriodOrAdMs != C.TIME_UNSET) {
                    // Reschedule update for when the duration is likely reached.
                    val realtimeUntilDurationReachedMs =
                        ((durationOfPeriodOrAdMs - currentPositionInPeriodOrAdMs)
                                / player.playbackParameters!!.speed)
                    handler.sendEmptyMessageDelayed(
                        MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT,
                        ceil(realtimeUntilDurationReachedMs.toDouble()).toInt()
                    )
                }
                this.isPlayingAndReachedDuration = false
                return
            }
            val nowRealtimeMs = clock!!.elapsedRealtime()
            if (this.isPlayingAndReachedDuration
                && periodUid == this.periodUid
                && adGroupIndex == this.adGroupIndex && adIndexInAdGroup == this.adIndexInAdGroup
            ) {
                // Still the same state, keep current timeout.
                if (nowRealtimeMs - startRealtimeMs >= stuckPlayingNotEndingTimeoutMs) {
                    callback.onStuckPlayerDetected(
                        StuckPlayerException(
                            StuckPlayerException.STUCK_PLAYING_NOT_ENDING,
                            stuckPlayingNotEndingTimeoutMs
                        )
                    )
                }
            } else {
                // Restart the timeout from the current time.
                isPlayingAndReachedDuration = true
                startRealtimeMs = nowRealtimeMs
                this.periodUid = periodUid
                this.adGroupIndex = adGroupIndex
                this.adIndexInAdGroup = adIndexInAdGroup
                handler.removeMessages(MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT)
                handler.sendEmptyMessageDelayed(
                    MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT, stuckPlayingNotEndingTimeoutMs
                )
            }
        }
    }

    private inner class StuckSuppressedDetector(private val stuckSuppressedTimeoutMs: Int) {
        @Player.PlaybackSuppressionReason
        private var suppressionReason: @Player.PlaybackSuppressionReason Int = 0
        private var isSuppressed = false
        private var startRealtimeMs: Long = 0

        fun update() {
            @Player.PlaybackSuppressionReason val suppressionReason: @Player.PlaybackSuppressionReason Int =
                player.playbackSuppressionReason
            if (!player.playWhenReady || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED || suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE || suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                // Preconditions for stuck suppressed not met. Clear any pending timeout and ignore.
                if (isSuppressed) {
                    handler.removeMessages(MSG_STUCK_SUPPRESSED_TIMEOUT)
                }
                isSuppressed = false
                return
            }
            val nowRealtimeMs = clock!!.elapsedRealtime()
            if (isSuppressed && this.suppressionReason == suppressionReason) {
                // Still the same state, keep current timeout.
                if (nowRealtimeMs - startRealtimeMs >= stuckSuppressedTimeoutMs) {
                    callback.onStuckPlayerDetected(
                        StuckPlayerException(
                            StuckPlayerException.STUCK_SUPPRESSED, stuckSuppressedTimeoutMs
                        )
                    )
                }
            } else {
                // Restart the timeout from the current time.
                isSuppressed = true
                startRealtimeMs = nowRealtimeMs
                this.suppressionReason = suppressionReason
                handler.removeMessages(MSG_STUCK_SUPPRESSED_TIMEOUT)
                handler.sendEmptyMessageDelayed(
                    MSG_STUCK_SUPPRESSED_TIMEOUT,
                    stuckSuppressedTimeoutMs
                )
            }
        }
    }

    companion object {
        private const val MSG_STUCK_BUFFERING_TIMEOUT = 1
        private const val MSG_STUCK_PLAYING_TIMEOUT = 2
        private const val MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT = 3
        private const val MSG_STUCK_SUPPRESSED_TIMEOUT = 4
    }
}
