package androidx.media3.common.text

import android.os.Bundle
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Function
import com.google.common.collect.Ordering

class CueGroup constructor(
    cues: List<Cue>, val presentationTimeUs: Long
) {
    val cues: List<Cue> = cues.sortedBy { it.zIndex }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putParcelableArrayList(
            FIELD_CUES,
            BundleCollectionUtil.toBundleArrayList<Cue>(
                filterOutBitmapCues(cues), Function { obj: Cue? -> obj!!.toBinderBasedBundle() })
        )
        bundle.putLong(FIELD_PRESENTATION_TIME_US, presentationTimeUs)
        return bundle
    }

    companion object {
        private val CUES_PRIORITY_COMPARATOR: Ordering<Cue?> =
            Ordering.natural<Int?>().onResultOf<Cue?>(
                Function { c: Cue? -> c!!.zIndex })

        val EMPTY_TIME_ZERO: CueGroup = CueGroup(listOf(), 0)

        private val FIELD_CUES = intToStringMaxRadix(0)
        private val FIELD_PRESENTATION_TIME_US = intToStringMaxRadix(1)

        fun fromBundle(bundle: Bundle): CueGroup {
            val cueBundles = bundle.getParcelableArrayList<Bundle?>(FIELD_CUES)
            val cues: List<Cue> =
                if (cueBundles == null)
                    listOf<Cue>()
                else
                    cueBundles.map { Cue.fromBundle(it) }
            val presentationTimeUs = bundle.getLong(FIELD_PRESENTATION_TIME_US)
            return CueGroup(cues, presentationTimeUs)
        }

        private fun filterOutBitmapCues(cues: List<Cue>): List<Cue> {
            return cues.filter { it.bitmap != null }
        }
    }
}
