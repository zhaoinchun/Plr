package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.util.Objects

class HeartRating : Rating {
    private val rated: Boolean
    val isHeart: Boolean

    constructor() {
        rated = false
        isHeart = false
    }

    constructor(isHeart: Boolean) {
        rated = true
        this.isHeart = isHeart
    }

    override val isRated: Boolean
        get() = rated

    override fun hashCode(): Int {
        return Objects.hash(rated, isHeart)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is HeartRating) {
            return false
        }
        return isHeart == other.isHeart && rated == other.rated
    }

    override fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_RATING_TYPE, TYPE)
        bundle.putBoolean(FIELD_RATED, rated)
        bundle.putBoolean(FIELD_IS_HEART, isHeart)
        return bundle
    }

    companion object {
        private const val TYPE: @RatingType Int = RATING_TYPE_HEART

        private val FIELD_RATED: String = Util.intToStringMaxRadix(1)
        private val FIELD_IS_HEART: String = Util.intToStringMaxRadix(2)

        fun fromBundle(bundle: Bundle): HeartRating {
            Preconditions.checkArgument(
                bundle.getInt(
                    FIELD_RATING_TYPE,
                    RATING_TYPE_UNSET
                ) == TYPE
            )
            val isRated = bundle.getBoolean(FIELD_RATED, false)
            return if (isRated) {
                HeartRating(bundle.getBoolean(FIELD_IS_HEART, false))
            } else {
                HeartRating()
            }
        }
    }
}
