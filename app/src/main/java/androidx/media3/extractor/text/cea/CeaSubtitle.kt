
package androidx.media3.extractor.text.cea

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.Subtitle
import com.google.common.base.Preconditions

internal class CeaSubtitle(private val cues: MutableList<Cue>?) : Subtitle {
    override fun getNextEventTimeIndex(timeUs: Long): Int {
        return if (timeUs < 0) 0 else C.INDEX_UNSET
    }

    override val eventTimeCount=1

    override fun getEventTime(index: Int): Long {
        Preconditions.checkArgument(index == 0)
        return 0
    }

    override fun getCues(timeUs: Long): MutableList<Cue>? {
        return if (timeUs >= 0) cues else mutableListOf<Cue>()
    }
}
