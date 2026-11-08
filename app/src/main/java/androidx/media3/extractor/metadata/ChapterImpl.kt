package androidx.media3.extractor.metadata

import androidx.media3.common.C
import androidx.media3.common.Label

data class ChapterImpl(
    override val startTimeMs: Long = C.TIME_UNSET,
    override val endTimeMs: Long = C.TIME_UNSET,
    override val isHidden: Boolean = false,
    override val title: Label? = null,
) : Chapter {
    override fun toString(): String {
        return ("Chapter: startTimeMs="
                + (if (startTimeMs == C.TIME_UNSET) "UNSET" else startTimeMs)
                + (if (endTimeMs == C.TIME_UNSET) "" else ", endTimeMs=" + endTimeMs)
                + (if (isHidden) ", hidden" else "")
                + (if (title == null) "" else ", title=" + title))
    }
}
