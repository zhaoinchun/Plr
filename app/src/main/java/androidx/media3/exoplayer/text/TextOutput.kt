package androidx.media3.exoplayer.text

import androidx.media3.common.text.CueGroup

fun interface TextOutput {
    fun onCues(cueGroup: CueGroup)
}
