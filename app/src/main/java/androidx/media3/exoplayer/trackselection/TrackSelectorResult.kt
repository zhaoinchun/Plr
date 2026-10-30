package androidx.media3.exoplayer.trackselection

import androidx.media3.common.Tracks
import androidx.media3.exoplayer.RendererConfiguration
import com.google.common.base.Preconditions

class TrackSelectorResult(
    val rendererConfigurations: Array<RendererConfiguration?>,
    selections: Array<ExoTrackSelection?>,
    val tracks: Tracks,
    val info: Any?
) {
    val selections: Array<ExoTrackSelection?> = selections.copyOf()
    val length: Int = rendererConfigurations.size

    init {
        Preconditions.checkArgument(rendererConfigurations.size == selections.size)
    }

    fun isRendererEnabled(index: Int): Boolean {
        return rendererConfigurations[index] != null
    }

    fun isEquivalent(other: TrackSelectorResult?): Boolean {
        if (other == null || other.selections.size != selections.size) {
            return false
        }
        for (i in selections.indices) {
            if (!isEquivalent(other, i)) {
                return false
            }
        }
        return true
    }

    fun isEquivalent(other: TrackSelectorResult?, index: Int): Boolean {
        if (other == null) {
            return false
        }
        return rendererConfigurations[index] == other.rendererConfigurations[index]
                && selections[index] == other.selections[index]
    }
}
