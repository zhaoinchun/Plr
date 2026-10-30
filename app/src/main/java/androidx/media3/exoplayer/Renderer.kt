package androidx.media3.exoplayer

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import java.io.IOException

interface Renderer : PlayerMessage.Target {
    interface WakeupListener {
        fun onSleep()
        fun onWakeup()
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        open = true,
        value = [MSG_SET_VIDEO_OUTPUT, MSG_SET_VOLUME, MSG_SET_AUDIO_ATTRIBUTES, MSG_SET_SCALING_MODE,
            MSG_SET_CHANGE_FRAME_RATE_STRATEGY, MSG_SET_AUX_EFFECT_INFO, MSG_SET_VIDEO_FRAME_METADATA_LISTENER,
            MSG_SET_CAMERA_MOTION_LISTENER, MSG_SET_SKIP_SILENCE_ENABLED, MSG_SET_AUDIO_SESSION_ID,
            MSG_SET_WAKEUP_LISTENER, MSG_SET_PREFERRED_AUDIO_DEVICE, MSG_SET_VIDEO_EFFECTS,
            MSG_SET_VIDEO_OUTPUT_RESOLUTION, MSG_SET_IMAGE_OUTPUT, MSG_SET_PRIORITY, MSG_TRANSFER_RESOURCES,
            MSG_SET_SCRUBBING_MODE, MSG_SET_VIRTUAL_DEVICE_ID, MSG_SET_AUDIO_OUTPUT_PROVIDER,
            MSG_SET_CODEC_PARAMETERS, MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, MSG_SET_IMAGE_METADATA_LISTENER]
    )
    annotation class MessageType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [STATE_DISABLED, STATE_ENABLED, STATE_STARTED])
    annotation class State

    val name: String?
    val trackType: @C.TrackType Int
    val capabilities: RendererCapabilities?
    fun init(index: Int, playerId: PlayerId?, clock: Clock?)
    val mediaClock: MediaClock?
    val state: @State Int

    @Throws(ExoPlaybackException::class)
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

    @Throws(ExoPlaybackException::class)
    fun start()

    @Throws(ExoPlaybackException::class)
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

    @Throws(IOException::class)
    fun maybeThrowStreamError()

    @Throws(ExoPlaybackException::class)
    fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean)

    fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean {
        return false
    }

    @Throws(ExoPlaybackException::class)
    fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
    }

    fun enableMayRenderStartOfStream() {}
    fun setTimeline(timeline: Timeline)

    @Throws(ExoPlaybackException::class)
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
