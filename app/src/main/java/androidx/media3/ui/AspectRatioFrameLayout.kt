package androidx.media3.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.IntDef
import com.example.plr.R
import kotlin.math.abs

class AspectRatioFrameLayout(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    interface AspectRatioListener {
        fun onAspectRatioUpdated(
            targetAspectRatio: Float, naturalAspectRatio: Float, aspectRatioMismatch: Boolean
        )
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [RESIZE_MODE_FIT, RESIZE_MODE_FIXED_WIDTH, RESIZE_MODE_FIXED_HEIGHT, RESIZE_MODE_FILL, RESIZE_MODE_ZOOM]
    )
    annotation class ResizeMode

    private val aspectRatioUpdateDispatcher = AspectRatioUpdateDispatcher()
    private var aspectRatioListener: AspectRatioListener? = null
    private var videoAspectRatio = 0f
    private var resizeMode: @ResizeMode Int = RESIZE_MODE_FIT

    init {
        if (attrs != null) {
            val a = context.theme
                .obtainStyledAttributes(attrs, R.styleable.AspectRatioFrameLayout, 0, 0)
            try {
                resizeMode =
                    a.getInt(R.styleable.AspectRatioFrameLayout_resize_mode, RESIZE_MODE_FIT)
            } finally {
                a.recycle()
            }
        }
    }

    fun setAspectRatio(widthHeightRatio: Float) {
        if (videoAspectRatio != widthHeightRatio) {
            videoAspectRatio = widthHeightRatio
            requestLayout()
        }
    }

    fun setAspectRatioListener(listener: AspectRatioListener?) {
        this.aspectRatioListener = listener
    }

    fun getResizeMode(): @ResizeMode Int {
        return resizeMode
    }

    fun setResizeMode(resizeMode: @ResizeMode Int) {
        if (this.resizeMode != resizeMode) {
            this.resizeMode = resizeMode
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (videoAspectRatio <= 0) {
            // Aspect ratio not set.
            return
        }

        var width = measuredWidth
        var height = measuredHeight
        val viewAspectRatio = width.toFloat() / height
        val aspectDeformation = videoAspectRatio / viewAspectRatio - 1
        if (abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION) {
            // We're within the allowed tolerance.
            aspectRatioUpdateDispatcher.scheduleUpdate(videoAspectRatio, viewAspectRatio, false)
            return
        }

        when (resizeMode) {
            RESIZE_MODE_FIXED_WIDTH -> height = (width / videoAspectRatio).toInt()
            RESIZE_MODE_FIXED_HEIGHT -> width = (height * videoAspectRatio).toInt()
            RESIZE_MODE_ZOOM -> if (aspectDeformation > 0) {
                width = (height * videoAspectRatio).toInt()
            } else {
                height = (width / videoAspectRatio).toInt()
            }

            RESIZE_MODE_FIT -> if (aspectDeformation > 0) {
                height = (width / videoAspectRatio).toInt()
            } else {
                width = (height * videoAspectRatio).toInt()
            }

            RESIZE_MODE_FILL -> {}
            else -> {}
        }
        aspectRatioUpdateDispatcher.scheduleUpdate(videoAspectRatio, viewAspectRatio, true)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    private inner class AspectRatioUpdateDispatcher : Runnable {
        private var targetAspectRatio = 0f
        private var naturalAspectRatio = 0f
        private var aspectRatioMismatch = false
        private var isScheduled = false

        fun scheduleUpdate(
            targetAspectRatio: Float, naturalAspectRatio: Float, aspectRatioMismatch: Boolean
        ) {
            this.targetAspectRatio = targetAspectRatio
            this.naturalAspectRatio = naturalAspectRatio
            this.aspectRatioMismatch = aspectRatioMismatch
            if (!isScheduled) {
                isScheduled = true
                post(this)
            }
        }

        override fun run() {
            isScheduled = false
            val aspectRatioListener = aspectRatioListener ?: return

            aspectRatioListener.onAspectRatioUpdated(
                targetAspectRatio, naturalAspectRatio, aspectRatioMismatch
            )
        }
    }

    companion object {
        const val RESIZE_MODE_FIT: Int = 0
        const val RESIZE_MODE_FIXED_WIDTH: Int = 1
        const val RESIZE_MODE_FIXED_HEIGHT: Int = 2
        const val RESIZE_MODE_FILL: Int = 3
        const val RESIZE_MODE_ZOOM: Int = 4
        private const val MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f
    }
}
