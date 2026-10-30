package androidx.media3.exoplayer.analytics

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.BandwidthMeter

interface AnalyticsCollector : Player.Listener, MediaSourceEventListener,
    BandwidthMeter.EventListener, DrmSessionEventListener {

    fun addListener(listener: AnalyticsListener)
    fun removeListener(listener: AnalyticsListener)
    fun setPlayer(player: Player, looper: Looper)
    fun release()
    fun updateMediaPeriodQueueInfo(
        queue: List<MediaPeriodId>,
        readingPeriod: MediaPeriodId?
    )

    fun notifySeekStarted()
    fun onRendererReadyChanged(
        rendererIndex: Int, rendererTrackType: @C.TrackType Int, isRendererReady: Boolean
    )

    fun onAudioEnabled(counters: DecoderCounters)
    fun onAudioDecoderInitialized(
        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
    )

    fun onAudioInputFormatChanged(
        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    )

    fun onAudioPositionAdvancing(playoutStartSystemTimeMs: Long)
    fun onAudioUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long)
    fun onAudioDecoderReleased(decoderName: String)
    fun onAudioDisabled(counters: DecoderCounters)
    fun onAudioSinkError(audioSinkError: Exception)
    fun onAudioCodecError(audioCodecError: Exception)
    fun onAudioTrackInitialized(audioTrackConfig: AudioTrackConfig)
    fun onAudioTrackReleased(audioTrackConfig: AudioTrackConfig)
    fun onVideoEnabled(counters: DecoderCounters)
    fun onVideoDecoderInitialized(
        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
    )

    fun onVideoInputFormatChanged(
        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    )

    fun onDroppedFrames(count: Int, elapsedMs: Long)
    fun onVideoDecoderReleased(decoderName: String)
    fun onVideoDisabled(counters: DecoderCounters)
    fun onRenderedFirstFrame(output: Any, renderTimeMs: Long)
    fun onVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int)
    fun onVideoCodecError(videoCodecError: Exception)
    fun onDroppedSeeksWhileScrubbing(droppedSeekCount: Int)
}
