package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.util.Util
import com.google.common.primitives.Ints
import java.util.Collections

data class TrackSelectionOverride(val mediaTrackGroup: TrackGroup, val trackIndices: List<Int>) {
    val type: Int
        get() = mediaTrackGroup.type

    init {
        if (!trackIndices.isEmpty()) {
            if (Collections.min(trackIndices) < 0 || Collections.max(trackIndices) >= mediaTrackGroup.length) {
                throw IndexOutOfBoundsException()
            }
        }
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putBundle(FIELD_TRACK_GROUP, mediaTrackGroup.toBundle())
        bundle.putIntArray(FIELD_TRACKS, Ints.toArray(trackIndices))
        return bundle
    }

    companion object {
        private val FIELD_TRACK_GROUP: String = Util.intToStringMaxRadix(0)
        private val FIELD_TRACKS: String = Util.intToStringMaxRadix(1)

        fun fromBundle(bundle: Bundle): TrackSelectionOverride {
            val trackGroupBundle = bundle.getBundle(FIELD_TRACK_GROUP)!!
            val mediaTrackGroup = TrackGroup.fromBundle(trackGroupBundle)
            val tracks = bundle.getIntArray(FIELD_TRACKS)!!
            return TrackSelectionOverride(mediaTrackGroup, Ints.asList(*tracks))
        }
    }
}
