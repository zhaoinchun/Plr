package androidx.media3.extractor.metadata

import androidx.media3.common.Label
import androidx.media3.common.Metadata

interface Chapter : Metadata.Entry {
    val startTimeMs: Long
    val endTimeMs: Long
    val isHidden: Boolean
    val title: Label?
}
