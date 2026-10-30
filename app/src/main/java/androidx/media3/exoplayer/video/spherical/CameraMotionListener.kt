package androidx.media3.exoplayer.video.spherical

interface CameraMotionListener {
    fun onCameraMotion(timeUs: Long, rotation: FloatArray)
    fun onCameraMotionReset()
}
