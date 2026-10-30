package androidx.media3.common

import android.view.Surface
import com.google.common.base.Preconditions

class SurfaceInfo  constructor(
    surface: Surface,
    width: Int,
    height: Int,
    orientationDegrees: Int = 0,
    isEncoderInputSurface: Boolean = false
) {
    val surface: Surface

    val width: Int

    val height: Int

    val orientationDegrees: Int

    val isEncoderInputSurface: Boolean

    init {
        Preconditions.checkArgument(
            orientationDegrees == 0 || orientationDegrees == 90 || orientationDegrees == 180 || orientationDegrees == 270,
            "orientationDegrees must be 0, 90, 180, or 270"
        )
        this.surface = surface
        this.width = width
        this.height = height
        this.orientationDegrees = orientationDegrees
        this.isEncoderInputSurface = isEncoderInputSurface
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is SurfaceInfo) {
            return false
        }
        val that = o
        return width == that.width && height == that.height && orientationDegrees == that.orientationDegrees && isEncoderInputSurface == that.isEncoderInputSurface && surface == that.surface
    }

    override fun hashCode(): Int {
        var result = surface.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + orientationDegrees
        result = 31 * result + (if (isEncoderInputSurface) 1 else 0)
        return result
    }
}
