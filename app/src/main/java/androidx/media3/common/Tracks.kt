package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.C.FormatSupport
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.Util
import com.google.common.base.Function
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
class Tracks(groups: List<Group>) {
    val groups: MutableList<Group> = groups.toMutableList()
    val isEmpty: Boolean
        get() = groups.isEmpty()

    fun containsType(trackType: @C.TrackType Int): Boolean {
        return groups.find { it.type == trackType } != null
    }

    fun isTypeSupported(trackType: @C.TrackType Int): Boolean {
        return isTypeSupported(trackType, false)
    }

    fun isTypeSupported(trackType: @C.TrackType Int, allowExceedsCapabilities: Boolean): Boolean {
        return groups.find { it.type == trackType && it.isSupported(allowExceedsCapabilities) } != null
    }

    fun isTypeSelected(trackType: @C.TrackType Int): Boolean {
        return groups.find { it.isSelected && it.type == trackType } != null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val that = other as Tracks
        return groups == that.groups
    }

    override fun hashCode(): Int {
        return groups.hashCode()
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putParcelableArrayList(
            FIELD_TRACK_GROUPS,
            BundleCollectionUtil.toBundleArrayList<Group>(
                groups,
                Function { obj: Group? -> obj!!.toBundle() })
        )
        return bundle
    }

    class Group(
        mediaTrackGroup: TrackGroup,
        adaptiveSupported: Boolean,
        trackSupport: IntArray,
        trackSelected: BooleanArray
    ) {
        val length = mediaTrackGroup.length
        val mediaTrackGroup: TrackGroup
        val isAdaptiveSupported: Boolean

        @FormatSupport
        private val trackSupport: IntArray
        private val trackSelected: BooleanArray

        fun getTrackFormat(trackIndex: Int): Format {
            return mediaTrackGroup.getFormat(trackIndex)
        }

        @FormatSupport
        fun getTrackSupport(trackIndex: Int): @FormatSupport Int {
            return trackSupport[trackIndex]
        }

        fun isTrackSupported(trackIndex: Int): Boolean {
            return isTrackSupported(trackIndex, false)
        }

        fun isTrackSupported(trackIndex: Int, allowExceedsCapabilities: Boolean): Boolean {
            return trackSupport[trackIndex] == C.FORMAT_HANDLED
                    || (allowExceedsCapabilities && trackSupport[trackIndex] == C.FORMAT_EXCEEDS_CAPABILITIES)
        }

        val isSelected: Boolean
            get() = trackSelected.contains(true)

        val isSupported: Boolean
            get() = isSupported(false)

        fun isSupported(allowExceedsCapabilities: Boolean): Boolean {
            for (i in trackSupport.indices) {
                if (isTrackSupported(i, allowExceedsCapabilities)) {
                    return true
                }
            }
            return false
        }

        fun isTrackSelected(trackIndex: Int): Boolean {
            return trackSelected[trackIndex]
        }

        val type: @C.TrackType Int
            get() = mediaTrackGroup.type

        fun copyWithMediaTrackGroup(trackGroup: TrackGroup): Group {
            return Group(
                trackGroup,
                isAdaptiveSupported, trackSupport, trackSelected
            )
        }

        fun copyWithId(groupId: String): Group {
            return Group(
                mediaTrackGroup.copyWithId(groupId),
                isAdaptiveSupported, trackSupport, trackSelected
            )
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null || javaClass != other.javaClass) {
                return false
            }
            val that = other as Group
            return this.isAdaptiveSupported == that.isAdaptiveSupported && mediaTrackGroup.equals(
                that.mediaTrackGroup
            )
                    && trackSupport.contentEquals(that.trackSupport) && trackSelected.contentEquals(
                that.trackSelected
            )
        }

        override fun hashCode(): Int {
            var result = mediaTrackGroup.hashCode()
            result = 31 * result + (if (this.isAdaptiveSupported) 1 else 0)
            result = 31 * result + trackSupport.contentHashCode()
            result = 31 * result + trackSelected.contentHashCode()
            return result
        }

        init {
            Preconditions.checkArgument(length == trackSupport.size && length == trackSelected.size)
            this.mediaTrackGroup = mediaTrackGroup
            this.isAdaptiveSupported = adaptiveSupported && length > 1
            this.trackSupport = trackSupport.clone()
            this.trackSelected = trackSelected.clone()
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putBundle(FIELD_TRACK_GROUP, mediaTrackGroup.toBundle())
            bundle.putIntArray(FIELD_TRACK_SUPPORT, trackSupport)
            bundle.putBooleanArray(FIELD_TRACK_SELECTED, trackSelected)
            bundle.putBoolean(FIELD_ADAPTIVE_SUPPORTED, isAdaptiveSupported)
            return bundle
        }

        companion object {
            private val FIELD_TRACK_GROUP: String = Util.intToStringMaxRadix(0)
            private val FIELD_TRACK_SUPPORT: String = Util.intToStringMaxRadix(1)
            private val FIELD_TRACK_SELECTED: String = Util.intToStringMaxRadix(3)
            private val FIELD_ADAPTIVE_SUPPORTED: String = Util.intToStringMaxRadix(4)

            fun fromBundle(bundle: Bundle): Group {
                val trackGroup =
                    TrackGroup.fromBundle(bundle.getBundle(FIELD_TRACK_GROUP)!!)
                val trackSupport =
                    MoreObjects.firstNonNull<IntArray>(
                        bundle.getIntArray(FIELD_TRACK_SUPPORT), IntArray(trackGroup.length)
                    )
                val selected =
                    MoreObjects.firstNonNull<BooleanArray>(
                        bundle.getBooleanArray(FIELD_TRACK_SELECTED),
                        BooleanArray(trackGroup.length)
                    )
                val adaptiveSupported = bundle.getBoolean(FIELD_ADAPTIVE_SUPPORTED, false)
                return Group(trackGroup, adaptiveSupported, trackSupport, selected)
            }
        }
    }

    companion object {
        val EMPTY: Tracks = Tracks(listOf())

        private const val FIELD_TRACK_GROUPS: String = "0"

        fun fromBundle(bundle: Bundle): Tracks {
            val groupBundles: List<Bundle>? =
                bundle.getParcelableArrayList(FIELD_TRACK_GROUPS)
            val groups = groupBundles?.map {
                Group.fromBundle(it)
            } ?: listOf()
            return Tracks(groups)
        }
    }
}
