package androidx.media3.exoplayer.video.spherical

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.view.Display
import android.view.Surface
import androidx.annotation.BinderThread

internal class OrientationListener(private val display: Display, listeners: Array<Listener>) :
    SensorEventListener {
    interface Listener {
        fun onOrientationChange(deviceOrientationMatrix: FloatArray, deviceRoll: Float)
    }

    private val deviceOrientationMatrix4x4 = FloatArray(16)
    private val tempMatrix4x4 = FloatArray(16)
    private val recenterMatrix4x4 = FloatArray(16)
    private val angles = FloatArray(3)
    private val listeners = listeners
    private var recenterMatrixComputed = false

    @BinderThread
    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(deviceOrientationMatrix4x4, event.values)
        rotateAroundZ(deviceOrientationMatrix4x4, display.rotation)
        val roll = extractRoll(deviceOrientationMatrix4x4)
        // Rotation vector sensor assumes Y is parallel to the ground.
        rotateYtoSky(deviceOrientationMatrix4x4)
        recenter(deviceOrientationMatrix4x4)
        notifyListeners(deviceOrientationMatrix4x4, roll)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Do nothing.
    }

    private fun notifyListeners(deviceOrientationMatrix: FloatArray, roll: Float) {
        for (listener in listeners) {
            listener.onOrientationChange(deviceOrientationMatrix, roll)
        }
    }

    private fun recenter(matrix: FloatArray) {
        if (!recenterMatrixComputed) {
            FrameRotationQueue.computeRecenterMatrix(recenterMatrix4x4, matrix)
            recenterMatrixComputed = true
        }
        System.arraycopy(matrix, 0, tempMatrix4x4, 0, tempMatrix4x4.size)
        Matrix.multiplyMM(matrix, 0, tempMatrix4x4, 0, recenterMatrix4x4, 0)
    }

    private fun extractRoll(matrix: FloatArray): Float {
        // Remapping is required since we need the calculated roll of the phone to be independent of the
        // phone's pitch & yaw.
        SensorManager.remapCoordinateSystem(
            matrix, SensorManager.AXIS_X, SensorManager.AXIS_MINUS_Z, tempMatrix4x4
        )
        SensorManager.getOrientation(tempMatrix4x4, angles)
        return angles[2]
    }

    private fun rotateAroundZ(matrix: FloatArray, rotation: Int) {
        val xAxis: Int
        val yAxis: Int
        when (rotation) {
            Surface.ROTATION_270 -> {
                xAxis = SensorManager.AXIS_MINUS_Y
                yAxis = SensorManager.AXIS_X
            }

            Surface.ROTATION_180 -> {
                xAxis = SensorManager.AXIS_MINUS_X
                yAxis = SensorManager.AXIS_MINUS_Y
            }

            Surface.ROTATION_90 -> {
                xAxis = SensorManager.AXIS_Y
                yAxis = SensorManager.AXIS_MINUS_X
            }

            Surface.ROTATION_0 -> return
            else -> throw IllegalStateException()
        }
        System.arraycopy(matrix, 0, tempMatrix4x4, 0, tempMatrix4x4.size)
        SensorManager.remapCoordinateSystem(tempMatrix4x4, xAxis, yAxis, matrix)
    }

    companion object {
        private fun rotateYtoSky(matrix: FloatArray) {
            Matrix.rotateM(matrix, 0, 90f, 1f, 0f, 0f)
        }
    }
}
