package androidx.media3.common

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.annotation.IntRange
import androidx.media3.common.util.TimestampIterator
import java.util.concurrent.Executor

interface VideoGraph {
    interface Factory {
        fun create(
            context: Context,
            outputColorInfo: ColorInfo,
            debugViewProvider: DebugViewProvider,
            listener: Listener,
            listenerExecutor: Executor,
            initialTimestampOffsetUs: Long,
            renderFramesAutomatically: Boolean
        ): VideoGraph

        fun supportsMultipleInputs(): Boolean
    }

    interface Listener {
        fun onOutputSizeChanged(width: Int, height: Int) {}

        fun onOutputFrameRateChanged(frameRate: Float) {}

        fun onOutputFrameAvailableForRendering(
            framePresentationTimeUs: Long, isRedrawnFrame: Boolean
        ) {
        }

        fun onEnded(finalFramePresentationTimeUs: Long) {}

        fun onError(exception: VideoFrameProcessingException) {}
    }

    @Throws(VideoFrameProcessingException::class)
    fun initialize()

    @Throws(VideoFrameProcessingException::class)
    fun registerInput(@IntRange(from = 0) inputIndex: Int)

    fun setOutputSurfaceInfo(outputSurfaceInfo: SurfaceInfo?)

    fun setOnInputSurfaceReadyListener(inputIndex: Int, listener: Runnable?)

    fun getInputSurface(inputIndex: Int): Surface

    fun setOnInputFrameProcessedListener(inputIndex: Int, listener: OnInputFrameProcessedListener?)

    fun setCompositionEffects(compositionEffects: List<Effect>)

    fun setCompositorSettings(videoCompositorSettings: VideoCompositorSettings)

    fun registerInputStream(
        inputIndex: Int,
        inputType: @VideoFrameProcessor.InputType Int,
        format: Format?,
        effects: List<Effect?>?,
        offsetToAddUs: Long
    )

    fun getPendingInputFrameCount(inputIndex: Int): Int

    fun registerInputFrame(inputIndex: Int): Boolean

    fun queueInputBitmap(
        inputIndex: Int,
        inputBitmap: Bitmap?,
        timestampIterator: TimestampIterator?
    ): Boolean

    fun queueInputTexture(inputIndex: Int, textureId: Int, presentationTimeUs: Long): Boolean

    fun renderOutputFrame(renderTimeNs: Long)

    fun redraw()

    fun hasProducedFrameWithTimestampZero(): Boolean

    fun flush()

    fun signalEndOfInput(inputIndex: Int)

    fun release()
}
