package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import com.google.common.primitives.Ints
import java.util.Collections

class TrackSelectionOverride(mediaTrackGroup: TrackGroup, trackIndices: List<Int>) {
    val mediaTrackGroup: TrackGroup
    val trackIndices: List<Int>

    constructor(mediaTrackGroup: TrackGroup, trackIndex: Int) : this(
        mediaTrackGroup,
        listOf(trackIndex)
    )

    init {
        if (!trackIndices.isEmpty()) {
            if (Collections.min(trackIndices) < 0 || Collections.max(trackIndices) >= mediaTrackGroup.length) {
                throw IndexOutOfBoundsException()
            }
        }
        this.mediaTrackGroup = mediaTrackGroup
        this.trackIndices = trackIndices.toList()
    }

    val type: @C.TrackType Int
        get() = mediaTrackGroup.type

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val that = other as TrackSelectionOverride
        return mediaTrackGroup == that.mediaTrackGroup && trackIndices == that.trackIndices
    }

    override fun hashCode(): Int {
        return mediaTrackGroup.hashCode() + 31 * trackIndices.hashCode()
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
