package androidx.media3.extractor

import androidx.media3.extractor.SeekMap.SeekPoints

class SingleSampleSeekMap(override val durationUs: Long, private val startPosition: Long = 0) :
    SeekMap {
    override val isSeekable: Boolean
        get() = true

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        return SeekPoints(SeekPoint(timeUs, startPosition))
    }
}
