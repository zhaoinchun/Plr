
package androidx.media3.extractor.text.ttml

import androidx.media3.common.text.Cue

internal class TtmlRegion  constructor(
    val id: String?,
    val position: Float = Cue.DIMEN_UNSET,
    val line: Float = Cue.DIMEN_UNSET,
      val lineType:  Int = Cue.TYPE_UNSET,
  val lineAnchor:  Int = Cue.TYPE_UNSET,
    val width: Float = Cue.DIMEN_UNSET,
    val height: Float = Cue.DIMEN_UNSET,
   val textSizeType:  Int = Cue.TYPE_UNSET,
    val textSize: Float = Cue.DIMEN_UNSET,
   val verticalType:  Int = Cue.TYPE_UNSET
)
