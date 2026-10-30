package androidx.media3.extractor.metadata

import androidx.media3.common.C
import androidx.media3.common.Label
import com.google.common.base.Preconditions
import com.google.common.primitives.Longs

internal class ChapterImpl(startTimeMs: Long, endTimeMs: Long, isHidden: Boolean, title: Label?) :
    Chapter {
    override val startTimeMs: Long
    override val endTimeMs: Long
    override val isHidden: Boolean
    override val title: Label?

    init {
        Preconditions.checkArgument(
            startTimeMs == C.TIME_UNSET || endTimeMs == C.TIME_UNSET || startTimeMs <= endTimeMs
        )
        this.startTimeMs = startTimeMs
        this.endTimeMs = endTimeMs
        this.isHidden = isHidden
        this.title = title
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as ChapterImpl
        return startTimeMs == other.startTimeMs && endTimeMs == other.endTimeMs && isHidden == other.isHidden && title == other.title
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + Longs.hashCode(startTimeMs)
        result = 31 * result + Longs.hashCode(endTimeMs)
        result = 31 * result + (if (isHidden) 1 else 0)
        result = 31 * result + (if (title != null) title.hashCode() else 0)
        return result
    }

    override fun toString(): String {
        return ("Chapter: startTimeMs="
                + (if (startTimeMs == C.TIME_UNSET) "UNSET" else startTimeMs)
                + (if (endTimeMs == C.TIME_UNSET) "" else ", endTimeMs=" + endTimeMs)
                + (if (isHidden) ", hidden" else "")
                + (if (title == null) "" else ", title=" + title))
    }
}
