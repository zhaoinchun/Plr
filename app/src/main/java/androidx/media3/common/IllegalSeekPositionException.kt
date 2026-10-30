package androidx.media3.common

class IllegalSeekPositionException(
    val timeline: Timeline?,
    val windowIndex: Int,
    val positionMs: Long
) : IllegalStateException()
