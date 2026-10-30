
package androidx.media3.extractor

import androidx.media3.extractor.SeekMap.SeekPoints

interface TrackAwareSeekMap : SeekMap {

    fun isSeekable(trackId: Int): Boolean

    fun getSeekPoints(timeUs: Long, trackId: Int): SeekPoints?
}
