
package androidx.media3.extractor.text

import androidx.media3.common.Format
import androidx.media3.common.text.Cue

import androidx.media3.decoder.DecoderOutputBuffer
import com.google.common.base.Preconditions

abstract class SubtitleOutputBuffer : DecoderOutputBuffer(), Subtitle {
    private var subtitle: Subtitle? = null
    private var subsampleOffsetUs: Long = 0

    fun setContent(timeUs: Long, subtitle: Subtitle?, subsampleOffsetUs: Long) {
        this.timeUs = timeUs
        this.subtitle = subtitle
        this.subsampleOffsetUs =
            if (subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) this.timeUs else subsampleOffsetUs
    }

    override val eventTimeCount: Int
        get() = subtitle!!.eventTimeCount

    override fun getEventTime(index: Int): Long {
        return subtitle!!.getEventTime(index) + subsampleOffsetUs
    }

    override fun getNextEventTimeIndex(timeUs: Long): Int {
        return subtitle!!.getNextEventTimeIndex(timeUs - subsampleOffsetUs)
    }

    override fun getCues(timeUs: Long): MutableList<Cue>? {
        return subtitle!!.getCues(timeUs - subsampleOffsetUs)!!.toMutableList()
    }

    override fun clear() {
        super.clear()
        subtitle = null
    }
}
