package androidx.media3.exoplayer

import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream

interface Renderer : PlayerMessage.Target {
    interface WakeupListener {
        fun onSleep()
        fun onWakeup()
    }

    val name: String?
    val trackType: Int
    val capabilities: RendererCapabilities?
    fun init(index: Int, playerId: PlayerId?, clock: Clock?)
    val mediaClock: MediaClock?
    val state: Int

    fun enable(
        configuration: RendererConfiguration,
        formats: Array<Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    )

    fun start()
    fun replaceStream(
        formats: Array<Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    )

    val stream: SampleStream?
    fun hasReadStreamToEnd(): Boolean
    val readingPositionUs: Long
    fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long {
        return if (this.state == STATE_ENABLED && (this.isReady || this.isEnded))
            DEFAULT_IDLE_DURATION_TO_PROGRESS_US
        else
            DEFAULT_DURATION_TO_PROGRESS_US
    }

    fun setCurrentStreamFinal()
    val isCurrentStreamFinal: Boolean
    fun maybeThrowStreamError()
    fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean)
    fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean {
        return false
    }

    fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {}
    fun enableMayRenderStartOfStream() {}
    fun setTimeline(timeline: Timeline)
    fun render(positionUs: Long, elapsedRealtimeUs: Long)
    val isReady: Boolean
    val isEnded: Boolean
    fun stop()
    fun disable()
    fun reset()
    fun release() {}

    companion object {
        const val DEFAULT_DURATION_TO_PROGRESS_US: Long = 10000L
        const val DEFAULT_IDLE_DURATION_TO_PROGRESS_US: Long = 1000000L
        const val MSG_SET_VIDEO_OUTPUT: Int = 1
        const val MSG_SET_VOLUME: Int = 2
        const val MSG_SET_AUDIO_ATTRIBUTES: Int = 3
        const val MSG_SET_SCALING_MODE: Int = 4
        const val MSG_SET_CHANGE_FRAME_RATE_STRATEGY: Int = 5
        const val MSG_SET_AUX_EFFECT_INFO: Int = 6
        const val MSG_SET_VIDEO_FRAME_METADATA_LISTENER: Int = 7
        const val MSG_SET_CAMERA_MOTION_LISTENER: Int = 8
        const val MSG_SET_SKIP_SILENCE_ENABLED: Int = 9
        const val MSG_SET_AUDIO_SESSION_ID: Int = 10
        const val MSG_SET_WAKEUP_LISTENER: Int = 11
        const val MSG_SET_PREFERRED_AUDIO_DEVICE: Int = 12
        const val MSG_SET_VIDEO_EFFECTS: Int = 13
        const val MSG_SET_VIDEO_OUTPUT_RESOLUTION: Int = 14
        const val MSG_SET_IMAGE_OUTPUT: Int = 15
        const val MSG_SET_PRIORITY: Int = 16
        const val MSG_TRANSFER_RESOURCES: Int = 17
        const val MSG_SET_SCRUBBING_MODE: Int = 18
        const val MSG_SET_VIRTUAL_DEVICE_ID: Int = 19
        const val MSG_SET_AUDIO_OUTPUT_PROVIDER: Int = 20
        const val MSG_SET_CODEC_PARAMETERS: Int = 21
        const val MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS: Int = 22
        const val MSG_SET_IMAGE_METADATA_LISTENER: Int = 23
        const val MSG_CUSTOM_BASE: Int = 10000
        const val STATE_DISABLED: Int = 0
        const val STATE_ENABLED: Int = 1
        const val STATE_STARTED: Int = 2
    }
}
