package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Preconditions
import com.google.common.primitives.ImmutableIntArray
import java.nio.ByteBuffer

interface AudioSink {
    interface Listener {
        fun onPositionDiscontinuity()
        fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {}
        fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long)
        fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean)
        fun onOffloadBufferEmptying() {}
        fun onOffloadBufferFull() {}
        fun onAudioSinkError(audioSinkError: Exception) {}
        fun onAudioCapabilitiesChanged() {}
        fun onAudioTrackInitialized(audioTrackConfig: AudioTrackConfig) {}
        fun onAudioTrackReleased(audioTrackConfig: AudioTrackConfig) {}
        fun onSilenceSkipped() {}
        fun onAudioSessionIdChanged(audioSessionId: Int) {}
    }

    data class AudioSinkConfig private constructor(
        val format: Format,
        @Deprecated(
            """Use similar config values on lower-level components like {@link
     *     AudioOutputProvider.FormatConfig#preferredBufferSize} instead."""
        )
        val preferredBufferSizeOverride: Int,
        val outputChannelMapping: ImmutableIntArray?,
        val timeline: Timeline,
        val mediaPeriodId: MediaPeriodId?,
    ) {
        private constructor(builder: Builder) : this(
            builder.format,
            builder.preferredBufferSizeOverride,
            builder.outputChannelMapping,
            builder.timeline,
            builder.mediaPeriodId
        )

        class Builder {
            val format: Format
            var preferredBufferSizeOverride: Int
            var outputChannelMapping: ImmutableIntArray?
            var timeline: Timeline
            var mediaPeriodId: MediaPeriodId?

            constructor(format: Format) {
                this.format = format
                this.preferredBufferSizeOverride = 0
                this.outputChannelMapping = null
                this.timeline = Timeline.EMPTY
                this.mediaPeriodId = null
            }

            @Deprecated(
                """Use similar setters on lower-level components like {@link AudioOutput} instead,
            e.g. by modifying {@link AudioOutputProvider.FormatConfig#preferredBufferSize}."""
            )
            fun setPreferredBufferSizeOverride(preferredBufferSizeOverride: Int): Builder {
                this.preferredBufferSizeOverride = preferredBufferSizeOverride
                return this
            }

            fun setOutputChannelMapping(outputChannelMapping: ImmutableIntArray?): Builder {
                this.outputChannelMapping = outputChannelMapping
                return this
            }

            fun setTimeline(timeline: Timeline): Builder {
                this.timeline = timeline
                return this
            }

            fun setMediaPeriodId(mediaPeriodId: MediaPeriodId?): Builder {
                this.mediaPeriodId = mediaPeriodId
                return this
            }

            fun build(): AudioSinkConfig {
                if (!timeline.isEmpty && mediaPeriodId != null) {
                    Preconditions.checkArgument(timeline.getIndexOfPeriod(mediaPeriodId!!.periodUid) != C.INDEX_UNSET)
                }
                return AudioSinkConfig(this)
            }
        }
    }

    class AudioTrackConfig(
        val encoding: @C.Encoding Int,
        val sampleRate: Int,
        val channelConfig: Int,
        val tunneling: Boolean,
        val offload: Boolean,
        val bufferSize: Int
    )

    class ConfigurationException : Exception {
        val format: Format?

        constructor(cause: Throwable?, format: Format?) : super(cause) {
            this.format = format
        }

        constructor(message: String?, format: Format?) : super(message) {
            this.format = format
        }
    }

    class InitializationException(
        message: String,
        val audioTrackState: Int,
        val format: Format,
        val isRecoverable: Boolean,
        cause: Throwable?
    ) : Exception(message, cause) {
        constructor(
            audioTrackState: Int,
            sampleRate: Int,
            channelConfig: Int,
            encoding: Int,
            bufferSize: Int,
            format: Format,
            isRecoverable: Boolean,
            audioTrackException: Exception?
        ) : this(
            "AudioTrack init failed $audioTrackState Config($sampleRate, $channelConfig, $encoding, " +
                    "$bufferSize) $format ${if (isRecoverable) " (recoverable)" else ""}",
            audioTrackState,
            format,
            isRecoverable,
            audioTrackException
        )
    }

    class WriteException(
        val errorCode: Int,
        val format: Format,
        val isRecoverable: Boolean
    ) : Exception("AudioTrack write failed: $errorCode")

    class UnexpectedDiscontinuityException(
        val actualPresentationTimeUs: Long,
        val expectedPresentationTimeUs: Long
    ) : Exception(
        "Unexpected audio track timestamp discontinuity: expected $expectedPresentationTimeUs, got $actualPresentationTimeUs"
    )

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [SINK_FORMAT_SUPPORTED_DIRECTLY, SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, SINK_FORMAT_UNSUPPORTED]
    )
    annotation class SinkFormatSupport

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [OFFLOAD_MODE_DISABLED, OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED, OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED]
    )
    annotation class OffloadMode

    fun setListener(listener: Listener)
    fun setPlayerId(playerId: PlayerId?) {}
    fun setClock(clock: Clock) {}
    fun supportsFormat(format: Format): Boolean
    fun getFormatSupport(format: Format): @SinkFormatSupport Int
    fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        return AudioOffloadSupport.DEFAULT_UNSUPPORTED
    }

    fun getCurrentPositionUs(sourceEnded: Boolean): Long

    @Deprecated("Call and implement {@link #configure(AudioSinkConfig)} instead.")
    @Throws(
        ConfigurationException::class
    )
    fun configure(
        inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?
    ) {
        configure(
            AudioSinkConfig.Builder(inputFormat)
                .setPreferredBufferSizeOverride(specifiedBufferSize)
                .setOutputChannelMapping(
                    if (outputChannels == null) null else ImmutableIntArray.copyOf(outputChannels)
                )
                .build()
        )
    }

    @Throws(ConfigurationException::class)
    fun configure(audioSinkConfig: AudioSinkConfig) {
        configure(
            audioSinkConfig.format,
            audioSinkConfig.preferredBufferSizeOverride,
            audioSinkConfig.outputChannelMapping?.toArray()
        )
    }

    fun play()
    fun handleDiscontinuity()

    @Throws(InitializationException::class, WriteException::class)
    fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean

    @Throws(WriteException::class)
    fun playToEndOfStream()
    val isEnded: Boolean
    fun hasPendingData(): Boolean
    var playbackParameters: PlaybackParameters
    var skipSilenceEnabled: Boolean
    var audioAttributes: AudioAttributes?
    val audioCapabilities: AudioCapabilities?
        get() = null

    fun setAudioSessionId(audioSessionId: Int)
    fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo)
    fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {}
    fun setVirtualDeviceId(virtualDeviceId: Int) {}
    fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {}
    val audioTrackBufferSizeUs: Long
    fun enableTunnelingV21()
    fun disableTunneling()

    @RequiresApi(29)
    fun setOffloadMode(offloadMode: @OffloadMode Int) {
    }

    @RequiresApi(29)
    fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
    }

    fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) {
        throw UnsupportedOperationException("AudioSink doesn't support setAudioOutputProvider")
    }

    fun setVolume(volume: Float)
    fun pause()
    fun flush()
    fun reset()
    fun release() {}

    companion object {
        const val SINK_FORMAT_SUPPORTED_DIRECTLY: Int =
            AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
        const val SINK_FORMAT_SUPPORTED_WITH_TRANSCODING: Int =
            AudioOutputProvider.FORMAT_SUPPORTED_WITH_TRANSCODING
        const val SINK_FORMAT_UNSUPPORTED: Int = AudioOutputProvider.FORMAT_UNSUPPORTED
        const val CURRENT_POSITION_NOT_SET: Long = Long.MIN_VALUE
        const val OFFLOAD_MODE_DISABLED: Int = 0
        const val OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED: Int = 1
        const val OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED: Int = 2
    }
}
