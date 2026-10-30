package androidx.media3.common

import android.os.Bundle
import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions
import java.util.Objects

class StarRating : Rating {
    @get:IntRange(from = 1)
    @IntRange(from = 1)
    val maxStars: Int

    val starRating: Float

    constructor(@IntRange(from = 1) maxStars: Int) {
        Preconditions.checkArgument(maxStars > 0, "maxStars must be a positive integer")
        this.maxStars = maxStars
        starRating = RATING_UNSET
    }

    constructor(@IntRange(from = 1) maxStars: Int, @FloatRange(from = 0.0) starRating: Float) {
        Preconditions.checkArgument(maxStars > 0, "maxStars must be a positive integer")
        Preconditions.checkArgument(
            starRating >= 0.0f && starRating <= maxStars, "starRating is out of range [0, maxStars]"
        )
        this.maxStars = maxStars
        this.starRating = starRating
    }

    override val isRated: Boolean
        get() = starRating != RATING_UNSET

    override fun hashCode(): Int {
        return Objects.hash(maxStars, starRating)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is StarRating) {
            return false
        }
        return maxStars == other.maxStars && starRating == other.starRating
    }

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_RATING_TYPE, TYPE)
        bundle.putInt(FIELD_MAX_STARS, maxStars)
        bundle.putFloat(FIELD_STAR_RATING, starRating)
        return bundle
    }

    companion object {
        private val TYPE: @RatingType Int = RATING_TYPE_STAR
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
