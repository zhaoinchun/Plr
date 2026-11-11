package androidx.media3.exoplayer

import android.media.AudioDeviceInfo
import android.os.Looper
import androidx.annotation.IntRange
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.isRunningOnEmulator
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.image.ImageOutput
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.TrackSelectionArray
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener

interface ExoPlayer : Player {
    interface AudioOffloadListener {
        fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {}
        fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {}
    }

    class PreloadConfiguration(val targetPreloadDurationUs: Long) {
        companion object {
            val DEFAULT: PreloadConfiguration = PreloadConfiguration(C.TIME_UNSET)
        }
    }

    override val playerError: PlaybackException?
    fun addAudioOffloadListener(listener: AudioOffloadListener?)
    fun removeAudioOffloadListener(listener: AudioOffloadListener?)
    val analyticsCollector: AnalyticsCollector?
    fun addAnalyticsListener(listener: AnalyticsListener)
    fun removeAnalyticsListener(listener: AnalyticsListener)
    val rendererCount: Int
    fun getRendererType(index: Int): Int
    fun getRenderer(index: Int): Renderer?
    fun getSecondaryRenderer(index: Int): Renderer?
    val trackSelector: TrackSelector?
    val currentTrackGroups: TrackGroupArray?
    val currentTrackSelections: TrackSelectionArray?
    val playbackLooper: Looper?
    val clock: Clock?

    fun setMediaSources(mediaSources: List<MediaSource>)
    fun setMediaSources(mediaSources: List<MediaSource>, resetPosition: Boolean)
    fun setMediaSources(
        mediaSources: List<MediaSource>, startMediaItemIndex: Int, startPositionMs: Long
    )

    fun setMediaSource(mediaSource: MediaSource)
    fun setMediaSource(mediaSource: MediaSource, startPositionMs: Long)
    fun setMediaSource(mediaSource: MediaSource, resetPosition: Boolean)
    fun addMediaSource(mediaSource: MediaSource)
    fun addMediaSource(index: Int, mediaSource: MediaSource)
    fun addMediaSources(mediaSources: List<MediaSource>)
    fun addMediaSources(index: Int, mediaSources: List<MediaSource>)
    var shuffleOrder: ShuffleOrder
    var preloadConfiguration: PreloadConfiguration?
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem)
    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    )

    fun setAudioSessionId(audioSessionId: Int)
    fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo?)
    fun clearAuxEffectInfo()
    fun setPreferredAudioDevice(audioDeviceInfo: AudioDeviceInfo?)
    fun setVirtualDeviceId(virtualDeviceId: Int)
    var skipSilenceEnabled: Boolean
    var isScrubbingModeEnabled: Boolean
    var scrubbingModeParameters: ScrubbingModeParameters?
    fun setVideoEffects(videoEffects: List<Effect>?)
    var videoScalingMode:  Int
    var videoChangeFrameRateStrategy:  Int
    fun setVideoFrameMetadataListener(listener: VideoFrameMetadataListener?)
    fun clearVideoFrameMetadataListener(listener: VideoFrameMetadataListener?)
    fun setCameraMotionListener(listener: CameraMotionListener?)
    fun clearCameraMotionListener(listener: CameraMotionListener?)
    fun createMessage(target: PlayerMessage.Target?): PlayerMessage?
    var seekParameters: SeekParameters?
    fun setSeekBackIncrementMs(@IntRange(from = 1) seekBackIncrementMs: Long)
    fun setSeekForwardIncrementMs(@IntRange(from = 1) seekForwardIncrementMs: Long)
    fun setMaxSeekToPreviousPositionMs(@IntRange(from = 0) maxSeekToPreviousPositionMs: Long)
    fun setForegroundMode(foregroundMode: Boolean)
    fun setEnforceAdPlaybackOnTimelineRefresh(enforceAdPlaybackOnTimelineRefresh: Boolean)
    var pauseAtEndOfMediaItems: Boolean
    val audioFormat: Format?
    val videoFormat: Format?
    val audioDecoderCounters: DecoderCounters?
    val videoDecoderCounters: DecoderCounters?
    fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean)
    fun setWakeMode( wakeMode:  Int)
    fun setPriority(priority: Int)
    fun setPriorityTaskManager(priorityTaskManager: PriorityTaskManager?)
    val isSleepingForOffload: Boolean
    val isTunnelingEnabled: Boolean
    override fun release()
    val isReleased: Boolean
    fun setImageOutput(imageOutput: ImageOutput?)
    fun setAudioCodecParameters(codecParameters: CodecParameters?)
    fun addAudioCodecParametersChangeListener(
        listener: CodecParametersChangeListener?, keys: List<String?>?
    )

    fun removeAudioCodecParametersChangeListener(listener: CodecParametersChangeListener?)
    fun setVideoCodecParameters(codecParameters: CodecParameters?)
    fun addVideoCodecParametersChangeListener(
        listener: CodecParametersChangeListener?, keys: List<String?>?
    )

    fun removeVideoCodecParametersChangeListener(listener: CodecParametersChangeListener?)

    companion object{
        const val DEFAULT_RELEASE_TIMEOUT_MS: Long = 500
        const val DEFAULT_DETACH_SURFACE_TIMEOUT_MS: Long = 2000
        const val DEFAULT_STUCK_BUFFERING_DETECTION_TIMEOUT_MS: Int = 600000
        val DEFAULT_STUCK_PLAYING_DETECTION_TIMEOUT_MS: Int =
            if (isRunningOnEmulator) 30000 else 10000
        const val DEFAULT_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS: Int = 60000
        const val DEFAULT_STUCK_SUPPRESSED_DETECTION_TIMEOUT_MS: Int = 600000
        var experimentalEnableStuckPlayingDetection: Boolean = true
    }
}
