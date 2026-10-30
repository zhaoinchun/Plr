package androidx.media3.common

import android.view.SurfaceView

fun interface DebugViewProvider {
    fun getDebugPreviewSurfaceView(width: Int, height: Int): SurfaceView?

    companion object {
        val NONE: DebugViewProvider = DebugViewProvider { width: Int, height: Int -> null }
    }
}
