package androidx.media3.exoplayer.video

import android.graphics.Bitmap
import android.view.Surface
import androidx.annotation.FloatRange
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.TimestampIterator
import java.util.concurrent.Executor

interface VideoSink {
    class VideoSinkException(cause: Throwable, val format: Format) : Exception(cause)

    interface Listener {
        fun onFrameAvailableForRendering() {}
        fun onFirstFrameRendered() {}
        fun onFrameDropped() {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onError(videoSinkException: VideoSinkException) {}

        companion object {
            val NO_OP: Listener = object : Listener {}
        }
    }

    interface VideoFrameHandler {
        fun render(renderTimestampNs: Long)
        fun skip()
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [INPUT_TYPE_SURFACE, INPUT_TYPE_BITMAP])
    annotation class InputType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    
    @IntDef(
        value = [RELEASE_FIRST_FRAME_IMMEDIATELY, RELEASE_FIRST_FRAME_WHEN_STARTED, RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED]
    )
    annotation class FirstFrameReleaseInstruction

    fun startRendering()
    fun stopRendering()
    fun setListener(listener: Listener, executor: Executor)

    @Throws(VideoSinkException::class)
    fun initialize(sourceFormat: Format): Boolean

    val isInitialized: Boolean

    fun redraw()
    fun flush(resetPosition: Boolean)
    fun isReady(otherwiseReady: Boolean): Boolean
    fun signalEndOfCurrentInputStream()
    fun signalEndOfInput()

    val isEnded: Boolean
    val inputSurface: Surface
    fun setVideoFrameMetadataListener(videoFrameMetadataListener: VideoFrameMetadataListener?)

    fun setPlaybackSpeed(@FloatRange(from = 0.0, fromInclusive = false) speed: Float)
    fun setVideoEffects(videoEffects: List<Effect>)
    fun setBufferTimestampAdjustmentUs(bufferTimestampAdjustmentUs: Long)
    fun setOutputSurfaceInfo(outputSurface: Surface, outputResolution: Size)
    fun clearOutputSurfaceInfo()
    fun setChangeFrameRateStrategy(@C.VideoChangeFrameRateStrategy changeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int)

    fun onInputStreamChanged(
        inputType: @InputType Int,
        format: Format,
        startPositionUs: Long,
        firstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int,
        videoEffects: List<Effect>
    )

    fun allowReleaseFirstFrameBeforeStarted()

    fun handleInputFrame(
        bufferPresentationTimeUs: Long,
        videoFrameHandler: VideoFrameHandler
    ): Boolean

    fun handleInputBitmap(
        inputBitmap: Bitmap,
        bufferTimestampIterator: TimestampIterator
    ): Boolean

    @Throws(VideoSinkException::class)
    fun render(positionUs: Long, elapsedRealtimeUs: Long)
    fun join(renderNextFrameImmediately: Boolean)
    fun release()

    companion object {
        const val INPUT_TYPE_SURFACE: Int = 1
        const val INPUT_TYPE_BITMAP: Int = 2
        const val RELEASE_FIRST_FRAME_IMMEDIATELY: Int = 0
        const val RELEASE_FIRST_FRAME_WHEN_STARTED: Int = 1
        const val RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED: Int = 2
    }
}
