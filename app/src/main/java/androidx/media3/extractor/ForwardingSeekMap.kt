package androidx.media3.extractor

import androidx.media3.extractor.SeekMap.SeekPoints

open class ForwardingSeekMap(private val seekMap: SeekMap) : SeekMap {
    override val isSeekable: Boolean
        get() = seekMap.isSeekable

    override val durationUs: Long
        get() = seekMap.durationUs

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        return seekMap.getSeekPoints(timeUs)
    }

    override val isEstimated: Boolean
        get() = seekMap.isEstimated
}
