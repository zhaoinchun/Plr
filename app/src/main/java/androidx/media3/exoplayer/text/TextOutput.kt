package androidx.media3.exoplayer.text

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup

fun interface TextOutput {
    @Deprecated("Use {@link #onCues(CueGroup)} instead.")
    fun onCues(cues: List<Cue>) {}
    fun onCues(cueGroup: CueGroup)
}
