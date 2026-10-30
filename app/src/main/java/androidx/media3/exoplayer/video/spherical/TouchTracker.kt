package androidx.media3.exoplayer.video.spherical

import android.content.Context
import android.graphics.PointF
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import androidx.annotation.BinderThread
import kotlin.concurrent.Volatile
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal class TouchTracker(
    context: Context?,
    private val listener: Listener,
    private val pxPerDegrees: Float
) : SimpleOnGestureListener(), OnTouchListener, OrientationListener.Listener {
    interface Listener {
        fun onScrollChange(scrollOffsetDegrees: PointF)
        fun onSingleTapUp(event: MotionEvent): Boolean {
            return false
        }
    }

    // With every touch event, update the accumulated degrees offset by the new pixel amount.
    private val previousTouchPointPx = PointF()
    private val accumulatedTouchOffsetDegrees = PointF()
    private val gestureDetector: GestureDetector = GestureDetector(context, this)

    // The conversion from touch to yaw & pitch requires compensating for device roll. This is set
    // on the sensor thread and read on the UI thread.
    @Volatile
    private var roll: Float=SphericalGLSurfaceView.UPRIGHT_ROLL

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onDown(e: MotionEvent): Boolean {
        // Initialize drag gesture.
        previousTouchPointPx.set(e.x, e.y)
        return true
    }

    // Incompatible parameter type for e1.
    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        // Calculate the touch delta in screen space.
        val touchX = (e2.x - previousTouchPointPx.x) / pxPerDegrees
        val touchY = (e2.y - previousTouchPointPx.y) / pxPerDegrees
        previousTouchPointPx.set(e2.x, e2.y)

        val r = roll // Copy volatile state.
        val cr = cos(r.toDouble()).toFloat()
        val sr = sin(r.toDouble()).toFloat()
        // To convert from screen space to the 3D space, we need to adjust the drag vector based
        // on the roll of the phone. This is standard rotationMatrix(roll) * vector math but has
        // an inverted y-axis due to the screen-space coordinates vs GL coordinates.
        // Handle yaw.
        accumulatedTouchOffsetDegrees.x -= cr * touchX - sr * touchY
        // Handle pitch and limit it to 45 degrees.
        accumulatedTouchOffsetDegrees.y += sr * touchX + cr * touchY
        accumulatedTouchOffsetDegrees.y =
            max(-MAX_PITCH_DEGREES, min(MAX_PITCH_DEGREES, accumulatedTouchOffsetDegrees.y))

        listener.onScrollChange(accumulatedTouchOffsetDegrees)
        return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return listener.onSingleTapUp(e)
    }

    @BinderThread
    override fun onOrientationChange(deviceOrientationMatrix: FloatArray, roll: Float) {
        // We compensate for roll by rotating in the opposite direction.
        this.roll = -roll
    }

    companion object {
        // Touch input won't change the pitch beyond +/- 45 degrees. This reduces awkward situations
        // where the touch-based pitch and gyro-based pitch interact badly near the poles.
        
        const val MAX_PITCH_DEGREES: Float = 45f
    }
}
