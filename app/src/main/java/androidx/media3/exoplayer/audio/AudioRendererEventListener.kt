package androidx.media3.exoplayer.audio

import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig

interface AudioRendererEventListener {
    fun onAudioEnabled(counters: DecoderCounters) {}

    fun onAudioDecoderInitialized(
        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
    ) {
    }

    fun onAudioInputFormatChanged(
        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
    }

    fun onAudioPositionAdvancing(playoutStartSystemTimeMs: Long) {}
    fun onAudioUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
    fun onAudioDecoderReleased(decoderName: String) {}
    fun onAudioDisabled(counters: DecoderCounters) {}
    fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
    fun onAudioCodecError(audioCodecError: Exception) {}
    fun onAudioSinkError(audioSinkError: Exception) {}
    fun onAudioTrackInitialized(audioTrackConfig: AudioTrackConfig) {}
    fun onAudioTrackReleased(audioTrackConfig: AudioTrackConfig) {}
    fun onAudioSessionIdChanged(audioSessionId: Int) {}
    fun onAudioCodecParametersChanged(codecParameters: CodecParameters) {}

    class EventDispatcher(handler: Handler?, private val listener: AudioRendererEventListener?) {
        private val handler: Handler? = listener?.let { handler!! }

        fun enabled(decoderCounters: DecoderCounters) {
            handler?.post { listener!!.onAudioEnabled(decoderCounters) }
        }

        fun decoderInitialized(
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) {
            handler?.post {
                listener!!.onAudioDecoderInitialized(
                    decoderName, initializedTimestampMs, initializationDurationMs
                )
            }
        }

        fun inputFormatChanged(
            format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            handler?.post {
                listener!!.onAudioInputFormatChanged(
                    format,
                    decoderReuseEvaluation
                )
            }
        }

        fun positionAdvancing(playoutStartSystemTimeMs: Long) {
            handler?.post {
                listener!!.onAudioPositionAdvancing(
                    playoutStartSystemTimeMs
                )
            }
        }

        fun underrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            handler?.post {
                listener!!.onAudioUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            }
        }

        fun decoderReleased(decoderName: String) {
            handler?.post { listener!!.onAudioDecoderReleased(decoderName) }
        }

        fun disabled(counters: DecoderCounters) {
            counters.ensureUpdated()
            handler?.post {
                counters.ensureUpdated()
                listener!!.onAudioDisabled(counters)
            }
        }

        fun skipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
            handler?.post {
                listener!!.onSkipSilenceEnabledChanged(
                    skipSilenceEnabled
                )
            }
        }

        fun audioSinkError(audioSinkError: Exception) {
            handler?.post { listener!!.onAudioSinkError(audioSinkError) }
        }

        fun audioCodecError(audioCodecError: Exception) {
            handler?.post { listener!!.onAudioCodecError(audioCodecError) }
        }

        fun audioTrackInitialized(audioTrackConfig: AudioTrackConfig) {
            handler?.post {
                listener!!.onAudioTrackInitialized(audioTrackConfig)
            }
        }

        fun audioTrackReleased(audioTrackConfig: AudioTrackConfig) {
            handler?.post { listener!!.onAudioTrackReleased(audioTrackConfig) }
        }

        fun audioSessionIdChanged(audioSessionId: Int) {
            handler?.post { listener!!.onAudioSessionIdChanged(audioSessionId) }
        }

        fun audioCodecParametersChanged(codecParameters: CodecParameters) {
            handler?.post {
                listener!!.onAudioCodecParametersChanged(codecParameters)
            }
        }
    }
}
