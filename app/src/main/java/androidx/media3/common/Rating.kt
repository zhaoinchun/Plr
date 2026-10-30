package androidx.media3.common

import android.os.Bundle
import androidx.annotation.IntDef

abstract class Rating internal constructor() {
    abstract val isRated: Boolean
    abstract fun toBundle(): Bundle?

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [RATING_TYPE_UNSET, RATING_TYPE_HEART, RATING_TYPE_PERCENTAGE, RATING_TYPE_STAR, RATING_TYPE_THUMB]
    )
    internal annotation class RatingType

    companion object {
        const val RATING_UNSET: Float = -1.0f
        const val RATING_TYPE_UNSET: Int = -1
        const val RATING_TYPE_HEART: Int = 0
        const val RATING_TYPE_PERCENTAGE: Int = 1
        const val RATING_TYPE_STAR: Int = 2
        const val RATING_TYPE_THUMB: Int = 3
        const val FIELD_RATING_TYPE: String = "0"

        fun fromBundle(bundle: Bundle): Rating {
            val ratingType: @RatingType Int = bundle.getInt(
                FIELD_RATING_TYPE,
                RATING_TYPE_UNSET
            )
            return when (ratingType) {
                RATING_TYPE_HEART -> HeartRating.fromBundle(bundle)
                RATING_TYPE_PERCENTAGE -> PercentageRating.fromBundle(bundle)
                RATING_TYPE_STAR -> StarRating.fromBundle(bundle)
                RATING_TYPE_THUMB -> ThumbRating.fromBundle(bundle)
                RATING_TYPE_UNSET -> throw IllegalArgumentException("Unknown RatingType: $ratingType")
                else -> throw IllegalArgumentException("Unknown RatingType: $ratingType")
            }
        }
    }
}
