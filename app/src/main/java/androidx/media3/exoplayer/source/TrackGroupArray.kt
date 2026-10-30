package androidx.media3.exoplayer.source

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.Log.e

import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Function

class TrackGroupArray(trackGroups: Array<TrackGroup>) {
    val length = trackGroups.size
    private val trackGroups: List<TrackGroup> = trackGroups.toList()

    // Lazily initialized hashcode.
    private var hashCode = 0

    constructor() : this(arrayOf())

    fun get(index: Int): TrackGroup {
        return trackGroups[index]
    }

    fun indexOf(group: TrackGroup?): Int {
        val index = trackGroups.indexOf(group)
        return if (index >= 0) index else C.INDEX_UNSET
    }

    val isEmpty: Boolean
        get() = length == 0

    val trackTypes: List<Int>
        get() = trackGroups.map { it.type }

    override fun hashCode(): Int {
        if (hashCode == 0) {
            hashCode = trackGroups.hashCode()
        }
        return hashCode
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as TrackGroupArray
        return length == other.length && trackGroups == other.trackGroups
    }

    override fun toString(): String {
        return trackGroups.toString()
    }

    init {
        verifyCorrectness()
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putParcelableArrayList(
            FIELD_TRACK_GROUPS,
            BundleCollectionUtil.toBundleArrayList<TrackGroup>(
                trackGroups,
                Function { obj: TrackGroup -> obj.toBundle() })
        )
        return bundle
    }

    private fun verifyCorrectness() {
        for (i in trackGroups.indices) {
            for (j in i + 1..<trackGroups.size) {
                if (trackGroups[i] == trackGroups[j]) {
                    e(
                        TAG,
                        "",
                        IllegalArgumentException(
                            "Multiple identical TrackGroups added to one TrackGroupArray."
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "TrackGroupArray"
        val EMPTY: TrackGroupArray = TrackGroupArray()
        private val FIELD_TRACK_GROUPS = intToStringMaxRadix(0)

        fun fromBundle(bundle: Bundle): TrackGroupArray {
            val trackGroupBundles = bundle.getParcelableArrayList<Bundle>(
                FIELD_TRACK_GROUPS
            )
            if (trackGroupBundles == null) {
                return TrackGroupArray()
            }
            return TrackGroupArray(
                BundleCollectionUtil.fromBundleList(TrackGroup::fromBundle, trackGroupBundles)
                    .toTypedArray()
            )
        }
    }
}
