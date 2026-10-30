package androidx.media3.exoplayer.video.spherical

import android.content.Context
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.AnyThread
import androidx.annotation.BinderThread
import androidx.annotation.UiThread
import androidx.media3.common.C
import androidx.media3.common.util.GlUtil
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class SphericalGLSurfaceView(
    context: Context,
    attributeSet: AttributeSet? = null
) : GLSurfaceView(context, attributeSet) {
    interface VideoSurfaceListener {
        fun onVideoSurfaceCreated(surface: Surface)
        fun onVideoSurfaceDestroyed(surface: Surface)
    }

    private val videoSurfaceListeners: CopyOnWriteArrayList<VideoSurfaceListener> =
        CopyOnWriteArrayList<VideoSurfaceListener>()

    // Configure sensors and touch.
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val orientationSensor: Sensor?
    private val orientationListener: OrientationListener
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val touchTracker: TouchTracker
    private val scene: SceneRenderer = SceneRenderer()
    private var surfaceTexture: SurfaceTexture? = null
    var videoSurface: Surface? = null
        private set
    private var useSensorRotation: Boolean = true
    private var isStarted = false
    private var isOrientationListenerRegistered = false

    init {
        // TYPE_GAME_ROTATION_VECTOR is the easiest sensor since it handles all the complex math for
        // fusion. It's used instead of TYPE_ROTATION_VECTOR since the latter uses the magnetometer on
        // devices. When used indoors, the magnetometer can take some time to settle depending on the
        // device and amount of metal in the environment.
        var orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (orientationSensor == null) {
            orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }
        this.orientationSensor = orientationSensor
        val renderer = Renderer(scene)

        touchTracker = TouchTracker(context, renderer, PX_PER_DEGREES)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
        val display = windowManager!!.defaultDisplay
        orientationListener = OrientationListener(display, arrayOf(touchTracker, renderer))

        setEGLContextClientVersion(2)
        setRenderer(renderer)
        setOnTouchListener(touchTracker)
    }

    fun addVideoSurfaceListener(listener: VideoSurfaceListener) {
        videoSurfaceListeners.add(listener)
    }

    fun removeVideoSurfaceListener(listener: VideoSurfaceListener) {
        videoSurfaceListeners.remove(listener)
    }

    val videoFrameMetadataListener: VideoFrameMetadataListener
        get() = scene

    val cameraMotionListener: CameraMotionListener
        get() = scene

    fun setDefaultStereoMode(stereoMode: @C.StereoMode Int) {
        scene.setDefaultStereoMode(stereoMode)
    }

    fun setUseSensorRotation(useSensorRotation: Boolean) {
        this.useSensorRotation = useSensorRotation
        updateOrientationListenerRegistration()
    }

    override fun onResume() {
        super.onResume()
        isStarted = true
        updateOrientationListenerRegistration()
    }

    override fun onPause() {
        isStarted = false
        updateOrientationListenerRegistration()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        // This call stops GL thread.
        super.onDetachedFromWindow()

        // Post to make sure we occur in order with any onSurfaceTextureAvailable calls.
        mainHandler.post(
            Runnable {
                val oldSurface = this.videoSurface
                if (oldSurface != null) {
                    for (videoSurfaceListener in videoSurfaceListeners) {
                        videoSurfaceListener.onVideoSurfaceDestroyed(oldSurface)
                    }
                }
                releaseSurface(surfaceTexture, oldSurface)
                surfaceTexture = null
                this.videoSurface = null
            })
    }

    private fun updateOrientationListenerRegistration() {
        val enabled = useSensorRotation && isStarted
        if (orientationSensor == null || enabled == isOrientationListenerRegistered) {
            return
        }
        if (enabled) {
            sensorManager.registerListener(
                orientationListener, orientationSensor, SensorManager.SENSOR_DELAY_FASTEST
            )
        } else {
            sensorManager.unregisterListener(orientationListener)
        }
        isOrientationListenerRegistered = enabled
    }

    // Called on GL thread.
    private fun onSurfaceTextureAvailable(newSurfaceTexture: SurfaceTexture) {
        mainHandler.post(
            Runnable {
                val oldSurfaceTexture = surfaceTexture
                val oldSurface = this.videoSurface
                val newSurface = Surface(newSurfaceTexture)
                surfaceTexture = newSurfaceTexture
                this.videoSurface = newSurface
                for (videoSurfaceListener in videoSurfaceListeners) {
                    videoSurfaceListener.onVideoSurfaceCreated(newSurface)
                }
                releaseSurface(oldSurfaceTexture, oldSurface)
            })
    }

    internal inner class Renderer
        (private val scene: SceneRenderer) : GLSurfaceView.Renderer, TouchTracker.Listener,
        OrientationListener.Listener {
        private val projectionMatrix = FloatArray(16)

        // There is no model matrix for this scene so viewProjectionMatrix is used for the mvpMatrix.
        private val viewProjectionMatrix = FloatArray(16)

        // Device orientation is derived from sensor data. This is accessed in the sensor's thread and
        // the GL thread.
        private val deviceOrientationMatrix = FloatArray(16)

        // Optional pitch and yaw rotations are applied to the sensor orientation. These are accessed on
        // the UI, sensor and GL Threads.
        private val touchPitchMatrix = FloatArray(16)
        private val touchYawMatrix = FloatArray(16)
        private var touchPitch = 0f
        private var deviceRoll: Float = UPRIGHT_ROLL

        // viewMatrix = touchPitch * deviceOrientation * touchYaw.
        private val viewMatrix = FloatArray(16)
        private val tempMatrix = FloatArray(16)

        init {
            GlUtil.setToIdentity(deviceOrientationMatrix)
            GlUtil.setToIdentity(touchPitchMatrix)
            GlUtil.setToIdentity(touchYawMatrix)
        }

        @Synchronized
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig) {
            onSurfaceTextureAvailable(scene.init())
        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val aspect = width.toFloat() / height
            val fovY = calculateFieldOfViewInYDirection(aspect)
            Matrix.perspectiveM(projectionMatrix, 0, fovY, aspect, Z_NEAR, Z_FAR)
        }

        override fun onDrawFrame(gl: GL10) {
            // Combine touch & sensor data.
            // Orientation = pitch * sensor * yaw since that is closest to what most users expect the
            // behavior to be.
            synchronized(this) {
                Matrix.multiplyMM(tempMatrix, 0, deviceOrientationMatrix, 0, touchYawMatrix, 0)
                Matrix.multiplyMM(viewMatrix, 0, touchPitchMatrix, 0, tempMatrix, 0)
            }

            Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            scene.drawFrame(viewProjectionMatrix,  false)
        }

        @BinderThread
        @Synchronized
        override fun onOrientationChange(matrix: FloatArray, deviceRoll: Float) {
            System.arraycopy(matrix, 0, deviceOrientationMatrix, 0, deviceOrientationMatrix.size)
            this.deviceRoll = -deviceRoll
            updatePitchMatrix()
        }

        @AnyThread
        private fun updatePitchMatrix() {
            // The camera's pitch needs to be rotated along an axis that is parallel to the real world's
            // horizon. This is the <1, 0, 0> axis after compensating for the device's roll.
            Matrix.setRotateM(
                touchPitchMatrix,
                0,
                -touchPitch,
                cos(deviceRoll.toDouble()).toFloat(),
                sin(deviceRoll.toDouble()).toFloat(),
                0f
            )
        }

        @UiThread
        @Synchronized
        override fun onScrollChange(scrollOffsetDegrees: PointF) {
            touchPitch = scrollOffsetDegrees.y
            updatePitchMatrix()
            Matrix.setRotateM(touchYawMatrix, 0, -scrollOffsetDegrees.x, 0f, 1f, 0f)
        }

        @UiThread
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            return performClick()
        }

        private fun calculateFieldOfViewInYDirection(aspect: Float): Float {
            val landscapeMode = aspect > 1
            if (landscapeMode) {
                val halfFovX = (FIELD_OF_VIEW_DEGREES / 2f).toDouble()
                val tanY = tan(Math.toRadians(halfFovX)) / aspect
                val halfFovY = Math.toDegrees(atan(tanY))
                return (halfFovY * 2).toFloat()
            } else {
                return FIELD_OF_VIEW_DEGREES.toFloat()
            }
        }
    }

    companion object {
        // Arbitrary vertical field of view.
        private const val FIELD_OF_VIEW_DEGREES = 90
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f

        // TODO Calculate this depending on surface size and field of view.
        private const val PX_PER_DEGREES = 25f
        const val UPRIGHT_ROLL: Float = Math.PI.toFloat()

        private fun releaseSurface(
            oldSurfaceTexture: SurfaceTexture?, oldSurface: Surface?
        ) {
            oldSurfaceTexture?.release()
            oldSurface?.release()
        }
    }
}
