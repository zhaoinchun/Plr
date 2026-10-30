package androidx.media3.common

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.annotation.IntDef
import androidx.media3.common.util.TimestampIterator
import java.util.concurrent.Executor

interface VideoFrameProcessor {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [INPUT_TYPE_SURFACE, INPUT_TYPE_BITMAP, INPUT_TYPE_TEXTURE_ID, INPUT_TYPE_SURFACE_AUTOMATIC_FRAME_REGISTRATION
        ]
    )
    annotation class InputType

    interface Factory {
        @Throws(VideoFrameProcessingException::class)
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
            inputType: @InputType Int, format: Format?, effects: MutableList<Effect?>?
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

    // TODO - b/294369303: Remove polling API.
    fun queueInputTexture(textureId: Int, presentationTimeUs: Long): Boolean

    fun setOnInputFrameProcessedListener(listener: OnInputFrameProcessedListener?)

    fun setOnInputSurfaceReadyListener(listener: Runnable?)

    // TODO: b/351776002 - Call setDefaultBufferSize on the INPUT_TYPE_SURFACE path too and remove
    //  mentions of the method (which leak an implementation detail) throughout this file.
    
    val inputSurface: Surface?

    fun redraw()

    fun registerInputStream(
        inputType: @InputType Int,
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

        @Deprecated(
            """Pass {@link SystemClock#nanoTime()} to {@link #renderOutputFrame} to render an
        output frame immediately."""
        )
        val RENDER_OUTPUT_FRAME_IMMEDIATELY: Long = -1

        val DROP_OUTPUT_FRAME: Long = -2

        val RENDER_OUTPUT_FRAME_WITH_PRESENTATION_TIME:  // This is a named constant, not a time unit.
                Long = -3

        val REDRAW: List<Effect?> = listOf<Effect?>(object : Effect {})
    }
}
