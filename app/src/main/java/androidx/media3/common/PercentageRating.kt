package androidx.media3.common

import android.os.Bundle
import androidx.annotation.FloatRange
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions
import java.util.Objects

data class PercentageRating(@FloatRange(from = 0.0, to = 100.0) val percent: Float = RATING_UNSET) :
    Rating() {
    override val isRated: Boolean
        get() = percent != RATING_UNSET

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_RATING_TYPE, TYPE)
        bundle.putFloat(FIELD_PERCENT, percent)
        return bundle
    }

    companion object {
        private val TYPE: Int = RATING_TYPE_PERCENTAGE
        private val FIELD_PERCENT = intToStringMaxRadix(1)

        fun fromBundle(bundle: Bundle): PercentageRating {
            Preconditions.checkArgument(
                bundle.getInt(FIELD_RATING_TYPE, RATING_TYPE_UNSET) == TYPE
            )
            val percent = bundle.getFloat(FIELD_PERCENT, RATING_UNSET)
            return if (percent == RATING_UNSET) PercentageRating() else PercentageRating(percent)
        }
    }
}
