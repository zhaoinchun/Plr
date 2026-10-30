package androidx.media3.exoplayer.video

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation

interface VideoRendererEventListener {
    fun onVideoEnabled(counters: DecoderCounters) {}
    fun onVideoDecoderInitialized(
        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
    ) {
    }

    fun onVideoInputFormatChanged(
        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
    }

    fun onDroppedFrames(count: Int, elapsedMs: Long) {}
    fun onVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) {}
    fun onVideoSizeChanged(videoSize: VideoSize) {}
    fun onRenderedFirstFrame(output: Any, renderTimeMs: Long) {}
    fun onVideoDecoderReleased(decoderName: String) {}
    fun onVideoDisabled(counters: DecoderCounters) {}
    fun onVideoCodecError(videoCodecError: Exception) {}
    fun onVideoCodecParametersChanged(codecParameters: CodecParameters) {}

    class EventDispatcher(handler: Handler?, private val listener: VideoRendererEventListener?) {
        private val handler: Handler? = if (listener != null) handler!! else null

        fun enabled(decoderCounters: DecoderCounters) {
            handler?.post { listener!!.onVideoEnabled(decoderCounters) }
        }

        fun decoderInitialized(
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) {
            handler?.post {
                listener!!
                    .onVideoDecoderInitialized(
                        decoderName, initializedTimestampMs, initializationDurationMs
                    )
            }
        }

        fun inputFormatChanged(
            format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            handler?.post {
                listener!!.onVideoInputFormatChanged(
                    format,
                    decoderReuseEvaluation
                )
            }
        }

        fun droppedFrames(droppedFrameCount: Int, elapsedMs: Long) {
            handler?.post {
                listener!!.onDroppedFrames(
                    droppedFrameCount,
                    elapsedMs
                )
            }
        }

        fun reportVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) {
            handler?.post {
                listener!!
                    .onVideoFrameProcessingOffset(totalProcessingOffsetUs, frameCount)
            }
        }

        fun videoSizeChanged(videoSize: VideoSize) {
            handler?.post { listener!!.onVideoSizeChanged(videoSize) }
        }

        fun renderedFirstFrame(output: Any) {
            if (handler != null) {
                // TODO: Replace this timestamp with the actual frame release time.
                val renderTimeMs = SystemClock.elapsedRealtime()
                handler.post {
                    listener!!.onRenderedFirstFrame(
                        output,
                        renderTimeMs
                    )
                }
            }
        }

        fun decoderReleased(decoderName: String) {
            handler?.post { listener!!.onVideoDecoderReleased(decoderName) }
        }

        fun disabled(counters: DecoderCounters) {
            counters.ensureUpdated()
            handler?.post {
                counters.ensureUpdated()
                listener!!.onVideoDisabled(counters)
            }
        }

        fun videoCodecError(videoCodecError: Exception) {
            handler?.post { listener!!.onVideoCodecError(videoCodecError) }
        }

        fun videoCodecParametersChanged(codecParameters: CodecParameters) {
            handler?.post {
                listener!!.onVideoCodecParametersChanged(
                    codecParameters
                )
            }
        }
    }
}
