package androidx.media3.common

import android.os.Bundle
import androidx.annotation.IntRange
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions

class StarRating(
    @IntRange(from = 1)
    val maxStars: Int,
    val starRating: Float = RATING_UNSET,
) : Rating() {
    override val isRated: Boolean
        get() = starRating != RATING_UNSET

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_RATING_TYPE, TYPE)
        bundle.putInt(FIELD_MAX_STARS, maxStars)
        bundle.putFloat(FIELD_STAR_RATING, starRating)
        return bundle
    }

    companion object {
        private val TYPE: Int = RATING_TYPE_STAR
        private const val MAX_STARS_DEFAULT = 5
        private val FIELD_MAX_STARS = intToStringMaxRadix(1)
        private val FIELD_STAR_RATING = intToStringMaxRadix(2)

        fun fromBundle(bundle: Bundle): StarRating {
            Preconditions.checkArgument(
                bundle.getInt(FIELD_RATING_TYPE, RATING_TYPE_UNSET) == TYPE
            )
            val maxStars = bundle.getInt(FIELD_MAX_STARS, MAX_STARS_DEFAULT)
            val starRating = bundle.getFloat(FIELD_STAR_RATING, RATING_UNSET)
            return if (starRating == RATING_UNSET) {
                StarRating(maxStars)
            } else {
                StarRating(maxStars, starRating)
            }
        }
    }
}
