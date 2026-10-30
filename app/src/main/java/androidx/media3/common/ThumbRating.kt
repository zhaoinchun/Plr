package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions
import java.util.Objects

class ThumbRating : Rating {
    override val isRated: Boolean
    val isThumbsUp: Boolean

    constructor() {
        isRated = false
        isThumbsUp = false
    }

    constructor(isThumbsUp: Boolean) {
        isRated = true
        this.isThumbsUp = isThumbsUp
    }

    override fun hashCode(): Int {
        return Objects.hash(isRated, isThumbsUp)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ThumbRating) {
            return false
        }
        return isThumbsUp == other.isThumbsUp && this.isRated == other.isRated
    }

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_RATING_TYPE, TYPE)
        bundle.putBoolean(FIELD_RATED, this.isRated)
        bundle.putBoolean(FIELD_IS_THUMBS_UP, isThumbsUp)
        return bundle
    }

    companion object {
        private val TYPE: @RatingType Int = RATING_TYPE_THUMB

        private val FIELD_RATED = intToStringMaxRadix(1)
        private val FIELD_IS_THUMBS_UP = intToStringMaxRadix(2)

        fun fromBundle(bundle: Bundle): ThumbRating {
            Preconditions.checkArgument(
                bundle.getInt(FIELD_RATING_TYPE, RATING_TYPE_UNSET) == TYPE
            )
            val rated = bundle.getBoolean(FIELD_RATED, false)
            return if (rated) {
                ThumbRating(bundle.getBoolean(FIELD_IS_THUMBS_UP, false))
            } else {
                ThumbRating()
            }
        }
    }
}
