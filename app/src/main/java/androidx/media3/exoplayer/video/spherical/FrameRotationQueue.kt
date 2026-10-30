package androidx.media3.exoplayer.video.spherical

import android.opengl.Matrix
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.TimedValueQueue
import kotlin.math.sqrt

internal class FrameRotationQueue {
    private val recenterMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val rotations = TimedValueQueue<FloatArray>()
    private var recenterMatrixComputed = false

    fun setRotation(timestampUs: Long, angleAxis: FloatArray) {
        rotations.add(timestampUs, angleAxis)
    }

    fun reset() {
        rotations.clear()
        recenterMatrixComputed = false
    }

    fun pollRotationMatrix(matrix: FloatArray, timestampUs: Long): Boolean {
        val rotation = rotations.pollFloor(timestampUs) ?: return false

        // TODO [Internal: b/113315546]: Slerp between the floor and ceil rotation.
        getRotationMatrixFromAngleAxis(rotationMatrix, rotation)
        if (!recenterMatrixComputed) {
            computeRecenterMatrix(recenterMatrix, rotationMatrix)
            recenterMatrixComputed = true
        }
        Matrix.multiplyMM(matrix, 0, recenterMatrix, 0, rotationMatrix, 0)
        return true
    }

    companion object {
        fun computeRecenterMatrix(recenterMatrix: FloatArray, rotationMatrix: FloatArray) {
            // The re-centering matrix is computed as follows:
            // recenter.row(2) = temp.col(2).transpose();
            // recenter.row(0) = recenter.row(1).cross(recenter.row(2)).normalized();
            // recenter.row(2) = recenter.row(0).cross(recenter.row(1)).normalized();
            //             | temp[10]  0   -temp[8]    0|
            //             | 0         1    0          0|
            // recenter =  | temp[8]   0    temp[10]   0|
            //             | 0         0    0          1|
            GlUtil.setToIdentity(recenterMatrix)
            val normRowSqr =
                rotationMatrix[10] * rotationMatrix[10] + rotationMatrix[8] * rotationMatrix[8]
            val normRow = sqrt(normRowSqr.toDouble()).toFloat()
            recenterMatrix[0] = rotationMatrix[10] / normRow
            recenterMatrix[2] = rotationMatrix[8] / normRow
            recenterMatrix[8] = -rotationMatrix[8] / normRow
            recenterMatrix[10] = rotationMatrix[10] / normRow
        }

        private fun getRotationMatrixFromAngleAxis(matrix: FloatArray, angleAxis: FloatArray) {
            // Convert coordinates to OpenGL coordinates.
            // CAMM motion metadata: +x right, +y down, and +z forward.
            // OpenGL: +x right, +y up, -z forwards
            val x = angleAxis[0]
            val y = -angleAxis[1]
            val z = -angleAxis[2]
            val angleRad = Matrix.length(x, y, z)
            if (angleRad != 0f) {
                val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
                Matrix.setRotateM(matrix, 0, angleDeg, x / angleRad, y / angleRad, z / angleRad)
            } else {
                GlUtil.setToIdentity(matrix)
            }
        }
    }
}
