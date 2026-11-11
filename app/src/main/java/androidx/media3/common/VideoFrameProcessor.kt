package androidx.media3.common

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.media3.common.util.TimestampIterator
import java.util.concurrent.Executor

interface VideoFrameProcessor {
    interface Factory {
        fun create(
            context: Context,
            debugViewProvider: DebugViewProvider,
            outputColorInfo: ColorInfo,
            renderFramesAutomatically: Boolean,
            listenerExecutor: Executor,
            listener: Listener
        ): VideoFrameProcessor
    }

    interface Listener {
        fun onInputStreamRegistered(
            inputType: Int, format: Format?, effects: MutableList<Effect?>?
        ) {
        }

        fun onOutputSizeChanged(width: Int, height: Int) {}
        fun onOutputFrameRateChanged(frameRate: Float) {}
        fun onOutputFrameAvailableForRendering(
            presentationTimeUs: Long, isRedrawnFrame: Boolean
        ) {
        }

        fun onError(exception: VideoFrameProcessingException?) {}
        fun onEnded() {}
    }

    fun queueInputBitmap(inputBitmap: Bitmap?, timestampIterator: TimestampIterator?): Boolean
    fun queueInputTexture(textureId: Int, presentationTimeUs: Long): Boolean
    fun setOnInputFrameProcessedListener(listener: OnInputFrameProcessedListener?)
    fun setOnInputSurfaceReadyListener(listener: Runnable?)
    val inputSurface: Surface?
    fun redraw()
    fun registerInputStream(
        inputType: Int,
        format: Format?,
        effects: MutableList<Effect?>?,
        offsetToAddUs: Long
    )

    fun registerInputFrame(): Boolean
    val pendingInputFrameCount: Int
    fun setOutputSurfaceInfo(outputSurfaceInfo: SurfaceInfo?)
    fun renderOutputFrame(renderTimeNs: Long)
    fun signalEndOfInput()
    fun flush()
    fun release()

    companion object {
        const val INPUT_TYPE_SURFACE: Int = 1
        const val INPUT_TYPE_BITMAP: Int = 2
        const val INPUT_TYPE_TEXTURE_ID: Int = 3
        const val INPUT_TYPE_SURFACE_AUTOMATIC_FRAME_REGISTRATION: Int = 4
        val DROP_OUTPUT_FRAME: Long = -2
        val RENDER_OUTPUT_FRAME_WITH_PRESENTATION_TIME: Long = -3
        val REDRAW: List<Effect?> = listOf<Effect?>(object : Effect {})
    }
}
