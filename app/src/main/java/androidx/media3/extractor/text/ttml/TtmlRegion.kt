
package androidx.media3.extractor.text.ttml

import androidx.media3.common.text.Cue
import androidx.media3.common.text.Cue.AnchorType
import androidx.media3.common.text.Cue.TextSizeType
import androidx.media3.common.text.Cue.VerticalType

internal class TtmlRegion  constructor(
    val id: String?,
    val position: Float = Cue.DIMEN_UNSET,
    val line: Float = Cue.DIMEN_UNSET,
    @field:Cue.LineType @param:Cue.LineType val lineType: @Cue.LineType Int = Cue.TYPE_UNSET,
    @field:AnchorType @param:AnchorType val lineAnchor: @AnchorType Int = Cue.TYPE_UNSET,
    val width: Float = Cue.DIMEN_UNSET,
    val height: Float = Cue.DIMEN_UNSET,
    @field:TextSizeType val textSizeType: @TextSizeType Int = Cue.TYPE_UNSET,
    val textSize: Float = Cue.DIMEN_UNSET,
    @field:VerticalType @param:VerticalType val verticalType: @VerticalType Int = Cue.TYPE_UNSET
)
