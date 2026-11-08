package androidx.media3.extractor.mp4

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format

import com.google.common.base.Preconditions
import com.google.common.primitives.ImmutableLongArray
import com.google.errorprone.annotations.CanIgnoreReturnValue

data class Track(
    var id: Int = 0,
    var type: @C.TrackType Int = C.TRACK_TYPE_UNKNOWN,
    var timescale: Long = TIMESCALE_UNSET,
    var movieTimescale: Long = TIMESCALE_UNSET,
    var durationUs: Long = C.TIME_UNSET,
    var mediaDurationUs: Long = C.TIME_UNSET,
    val format: Format? = null,
    var sampleTransformation: @Transformation Int = TRANSFORMATION_NONE,
    var sampleDescriptionEncryptionBoxes: Array<TrackEncryptionBox?>? = null,
    var nalUnitLengthFieldLength: Int = 0,
    val editListDurations: ImmutableLongArray? = null,
    val editListMediaTimes: ImmutableLongArray? = null,
    var shouldBeExposed: Boolean = true,
    var chapterTrackId: Int = C.INDEX_UNSET,
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [TRANSFORMATION_NONE, TRANSFORMATION_CEA608_CDAT])
    annotation class Transformation

    fun getSampleDescriptionEncryptionBox(sampleDescriptionIndex: Int): TrackEncryptionBox? {
        return sampleDescriptionEncryptionBoxes?.get(sampleDescriptionIndex)
    }

    companion object {
        const val TRANSFORMATION_NONE: Int = 0
        const val TRANSFORMATION_CEA608_CDAT: Int = 1
        val TIMESCALE_UNSET: Long = -1
    }
}
