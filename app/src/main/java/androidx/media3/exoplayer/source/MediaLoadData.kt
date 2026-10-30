package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.Format

class MediaLoadData(
    val dataType: @C.DataType Int,
    val trackType: @C.TrackType Int = C.TRACK_TYPE_UNKNOWN,
    val trackFormat: Format? = null,
    val trackSelectionReason: @C.SelectionReason Int = C.SELECTION_REASON_UNKNOWN,
    val trackSelectionData: Any? = null,
    val mediaStartTimeMs: Long = C.TIME_UNSET,
    val mediaEndTimeMs: Long = C.TIME_UNSET
)
