package androidx.media3.ui

interface TimeBar {
    fun addListener(listener: OnScrubListener)
    fun removeListener(listener: OnScrubListener)
    fun setEnabled(enabled: Boolean)
    fun setKeyTimeIncrement(time: Long)
    fun setKeyCountIncrement(count: Int)
    fun setPosition(position: Long)
    fun setBufferedPosition(bufferedPosition: Long)
    fun setDuration(duration: Long)

    val preferredUpdateDelay: Long

    fun setAdGroupTimesMs(
        adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int
    )

    interface OnScrubListener {
        fun onScrubStart(timeBar: TimeBar, position: Long)
        fun onScrubMove(timeBar: TimeBar, position: Long)
        fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean)
    }
}
