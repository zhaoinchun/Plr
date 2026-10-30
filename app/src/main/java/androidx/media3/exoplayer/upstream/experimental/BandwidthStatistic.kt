package androidx.media3.exoplayer.upstream.experimental

interface BandwidthStatistic {
    fun addSample(bytes: Long, durationUs: Long)
    val bandwidthEstimate: Long
    fun reset()
}
