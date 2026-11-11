package androidx.media3.common

import android.view.Surface
import com.google.common.base.Preconditions

data class SurfaceInfo(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int = 0,
    val isEncoderInputSurface: Boolean = false
) {
    init {
        Preconditions.checkArgument(
            orientationDegrees == 0 || orientationDegrees == 90 || orientationDegrees == 180 || orientationDegrees == 270,
            "orientationDegrees must be 0, 90, 180, or 270"
        )
    }

}
