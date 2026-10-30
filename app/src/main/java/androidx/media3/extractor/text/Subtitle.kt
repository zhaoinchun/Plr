
package androidx.media3.extractor.text

import androidx.media3.common.text.Cue

interface Subtitle {
    
    fun getNextEventTimeIndex(timeUs: Long): Int

    val eventTimeCount: Int

    fun getEventTime(index: Int): Long

    fun getCues(timeUs: Long): List<Cue>?
}
