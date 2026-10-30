
package androidx.media3.extractor.text.ttml

import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Util.binarySearchCeil
import androidx.media3.extractor.text.Subtitle
import java.util.Collections

internal class TtmlSubtitle(
    @get:VisibleForTesting val root: TtmlNode,
    globalStyles: MutableMap<String, TtmlStyle>?,
    private val regionMap: MutableMap<String, TtmlRegion>?,
    private val imageMap: MutableMap<String, String>?
) : Subtitle {
    private val eventTimesUs: LongArray

    @get:VisibleForTesting
    val globalStyles: MutableMap<String, TtmlStyle>

    init {
        this.globalStyles =
            if (globalStyles != null) Collections.unmodifiableMap<String?, TtmlStyle?>(globalStyles) else mutableMapOf<String?, TtmlStyle?>()
        this.eventTimesUs = root.eventTimesUs
    }

    override fun getNextEventTimeIndex(timeUs: Long): Int {
        val index = binarySearchCeil(eventTimesUs, timeUs, false, false)
        return if (index < eventTimesUs.size) index else C.INDEX_UNSET
    }

    override val eventTimeCount: Int
        get() = eventTimesUs.size

    override fun getEventTime(index: Int): Long {
        return eventTimesUs[index]
    }

    override fun getCues(timeUs: Long): MutableList<Cue> {
        return root.getCues(timeUs, globalStyles, regionMap!!, imageMap!!)
    }
}
