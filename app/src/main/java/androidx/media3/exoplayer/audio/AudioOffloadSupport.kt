package androidx.media3.exoplayer.audio

data class AudioOffloadSupport(
    var isFormatSupported: Boolean = false,
    var isGaplessSupported: Boolean = false,
    var isSpeedChangeSupported: Boolean = false,
) {
    companion object {
        val DEFAULT_UNSUPPORTED: AudioOffloadSupport = AudioOffloadSupport()
    }
}
