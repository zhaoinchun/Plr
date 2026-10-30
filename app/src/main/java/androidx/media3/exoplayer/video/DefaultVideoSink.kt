package androidx.media3.exoplayer.video

import android.graphics.Bitmap
import android.media.MediaFormat
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Size
import androidx.media3.common.util.TimestampIterator
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.video.VideoFrameRenderControl.FrameRenderer
import androidx.media3.exoplayer.video.VideoSink.FirstFrameReleaseInstruction
import androidx.media3.exoplayer.video.VideoSink.VideoFrameHandler
import androidx.media3.exoplayer.video.VideoSink.VideoSinkException
import com.google.common.base.Preconditions
import java.util.ArrayDeque
import java.util.Queue
import java.util.concurrent.Executor

internal class DefaultVideoSink(
    private val videoFrameReleaseControl: VideoFrameReleaseControl,
    private val videoFrameReleaseEarlyTimeForecaster: VideoFrameReleaseEarlyTimeForecaster,
    clock: Clock
) : VideoSink {
    private val videoFrameRenderControl: VideoFrameRenderControl
    private val videoFrameHandlers: Queue<VideoFrameHandler>
    private val frameRateEstimator: FixedFrameRateEstimator

    private var outputSurface: Surface? = null
    private var inputFormat: Format
    private var streamStartPositionUs: Long
    private var listener: VideoSink.Listener
    private var listenerExecutor: Executor
    private var videoFrameMetadataListener: VideoFrameMetadataListener

    init {
        videoFrameReleaseControl.setClock(clock)
        frameRateEstimator =
            FixedFrameRateEstimator { frameRate: Float ->
                videoFrameReleaseControl.setSurfaceMediaFrameRate(
                    frameRate
                )
            }
        videoFrameRenderControl =
            VideoFrameRenderControl(
                FrameRendererImpl(),
                videoFrameReleaseControl,
                videoFrameReleaseEarlyTimeForecaster,
                frameRateEstimator
            )
        videoFrameHandlers = ArrayDeque<VideoFrameHandler>()
        inputFormat = Format.Builder().build()
        streamStartPositionUs = C.TIME_UNSET
        listener = VideoSink.Listener.NO_OP
        listenerExecutor = Executor { runnable: Runnable? -> }
        videoFrameMetadataListener =
            VideoFrameMetadataListener { presentationTimeUs: Long, releaseTimeNs: Long, format: Format?, mediaFormat: MediaFormat? -> }
    }

    override fun startRendering() {
        videoFrameReleaseEarlyTimeForecaster.reset()
        videoFrameReleaseControl.onStarted()
    }

    override fun stopRendering() {
        videoFrameReleaseEarlyTimeForecaster.reset()
        videoFrameReleaseControl.onStopped()
    }

    override fun setListener(listener: VideoSink.Listener, executor: Executor) {
        this.listener = listener
        this.listenerExecutor = executor
    }

    override fun initialize(sourceFormat: Format): Boolean {
        // Do nothing as there is no initialization needed.
        return true
    }

    override val isInitialized: Boolean=true

    override fun redraw() {
        throw UnsupportedOperationException()
    }

    override fun flush(resetPosition: Boolean) {
        if (resetPosition) {
            videoFrameReleaseControl.reset()
        }
        videoFrameReleaseEarlyTimeForecaster.reset()
        videoFrameRenderControl.flush()
        videoFrameHandlers.clear()
    }

    override fun isReady(otherwiseReady: Boolean): Boolean {
        return videoFrameReleaseControl.isReady(otherwiseReady)
    }

    override fun signalEndOfCurrentInputStream() {
        videoFrameRenderControl.signalEndOfInput()
    }

    override fun signalEndOfInput() {
        // Ignored.
    }

    override val isEnded: Boolean
        get() = videoFrameRenderControl.isEnded

    override val inputSurface: Surface
        get() = outputSurface!!

    override fun setVideoFrameMetadataListener(videoFrameMetadataListener: VideoFrameMetadataListener?) {
        this.videoFrameMetadataListener = videoFrameMetadataListener!!
    }

    override fun setPlaybackSpeed(speed: Float) {
        videoFrameReleaseControl.setPlaybackSpeed(speed)
    }

    override fun setVideoEffects(videoEffects: List<Effect>) {
        throw UnsupportedOperationException()
    }

    override fun setBufferTimestampAdjustmentUs(bufferTimestampAdjustmentUs: Long) {
        throw UnsupportedOperationException()
    }

    override fun setOutputSurfaceInfo(outputSurface: Surface, outputResolution: Size) {
        this.outputSurface = outputSurface
        videoFrameReleaseControl.setOutputSurface(outputSurface)
    }

    override fun clearOutputSurfaceInfo() {
        outputSurface = null
        videoFrameReleaseControl.setOutputSurface( null)
    }

    override fun setChangeFrameRateStrategy(changeFrameRateStrategy: Int) {
        videoFrameReleaseControl.setChangeFrameRateStrategy(changeFrameRateStrategy)
    }

    override fun onInputStreamChanged(
        inputType: @VideoSink.InputType Int,
        format: Format,
        startPositionUs: Long,
        firstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int,
        videoEffects: List<Effect>
    ) {
        Preconditions.checkState(videoEffects.isEmpty())
        if (format.width != inputFormat.width || format.height != inputFormat.height) {
            videoFrameRenderControl.onVideoSizeChanged(format.width, format.height)
        }
        if (format.frameRate != inputFormat.frameRate) {
            frameRateEstimator.onFormatChanged(format.frameRate)
        }
        inputFormat = format
        if (startPositionUs != this.streamStartPositionUs) {
            videoFrameRenderControl.onStreamChanged(firstFrameReleaseInstruction, startPositionUs)
            this.streamStartPositionUs = startPositionUs
        }
    }

    override fun allowReleaseFirstFrameBeforeStarted() {
        videoFrameReleaseControl.allowReleaseFirstFrameBeforeStarted()
    }

    override fun handleInputFrame(
        framePresentationTimeUs: Long, videoFrameHandler: VideoFrameHandler
    ): Boolean {
        videoFrameHandlers.add(videoFrameHandler)
        videoFrameRenderControl.onFrameAvailableForRendering(framePresentationTimeUs)
        listenerExecutor.execute(Runnable { listener.onFrameAvailableForRendering() })
        return true
    }

    override fun handleInputBitmap(
        inputBitmap: Bitmap,
        bufferTimestampIterator: TimestampIterator
    ): Boolean {
        throw UnsupportedOperationException()
    }

    @Throws(VideoSinkException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        try {
            videoFrameRenderControl.render(positionUs, elapsedRealtimeUs)
        } catch (e: ExoPlaybackException) {
            throw VideoSinkException(e, inputFormat)
        }
    }

    override fun join(renderNextFrameImmediately: Boolean) {
        videoFrameReleaseControl.join(renderNextFrameImmediately)
    }

    override fun release() {}

    private inner class FrameRendererImpl : FrameRenderer {
        private var outputFormat:  Format? = null

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            outputFormat =
                Format.Builder()
                    .setWidth(videoSize.width)
                    .setHeight(videoSize.height)
                    .setSampleMimeType(MimeTypes.VIDEO_RAW)
                    .build()
            listenerExecutor.execute { listener.onVideoSizeChanged(videoSize) }
        }

        override fun renderFrame(
            renderTimeNs: Long,
            framePresentationTimeUs: Long,
            isFirstFrame: Boolean
        ) {
            if (isFirstFrame && outputSurface != null) {
                listenerExecutor.execute(Runnable { listener.onFirstFrameRendered() })
            }
            // TODO - b/292111083: outputFormat is initialized after the first frame is rendered because
            //  onVideoSizeChanged is announced after the first frame is available for rendering.
            val format = (if (outputFormat == null) androidx.media3.common.Format.Builder()
                .build() else outputFormat)!!
            videoFrameMetadataListener.onVideoFrameAboutToBeRendered( 
                framePresentationTimeUs,  
                renderTimeNs,
                format,  
                null
            )
            videoFrameHandlers.remove()!!.render(renderTimeNs)
        }

        override fun dropFrame() {
            listenerExecutor.execute { listener.onFrameDropped() }
            videoFrameHandlers.remove()!!.skip()
        }
    }
}
