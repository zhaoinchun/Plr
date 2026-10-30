
package androidx.media3.extractor.mp4

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format

import com.google.common.base.Preconditions
import com.google.common.primitives.ImmutableLongArray
import com.google.errorprone.annotations.CanIgnoreReturnValue

class Track {
    
    class Builder {
         var id = 0
         var type: @C.TrackType Int
         var timescale: Long
         var movieTimescale: Long
         var durationUs: Long
         var mediaDurationUs: Long
         var format: Format? = null
         var sampleTransformation: @Transformation Int
         var sampleDescriptionEncryptionBoxes: Array<TrackEncryptionBox?>? = null
         var nalUnitLengthFieldLength = 0
         var editListDurations: ImmutableLongArray? = null
         var editListMediaTimes: ImmutableLongArray? = null
         var shouldBeExposed: Boolean
         var chapterTrackId: Int

        constructor() {
            type = C.TRACK_TYPE_UNKNOWN
            timescale = TIMESCALE_UNSET
            movieTimescale = TIMESCALE_UNSET
            durationUs = C.TIME_UNSET
            mediaDurationUs = C.TIME_UNSET
            sampleTransformation = TRANSFORMATION_NONE
            shouldBeExposed = true
            chapterTrackId = C.INDEX_UNSET
        }

         constructor(track: Track) {
            id = track.id
            type = track.type
            timescale = track.timescale
            movieTimescale = track.movieTimescale
            durationUs = track.durationUs
            mediaDurationUs = track.mediaDurationUs
            format = track.format
            sampleTransformation = track.sampleTransformation
            sampleDescriptionEncryptionBoxes = track.sampleDescriptionEncryptionBoxes
            nalUnitLengthFieldLength = track.nalUnitLengthFieldLength
            editListDurations = track.editListDurations
            editListMediaTimes = track.editListMediaTimes
            shouldBeExposed = track.shouldBeExposed
            chapterTrackId = track.chapterTrackId
        }

        fun setId(id: Int): Builder {
            this.id = id
            return this
        }

        fun setType(type: @C.TrackType Int): Builder {
            this.type = type
            return this
        }

        fun setTimescale(timescale: Long): Builder {
            this.timescale = timescale
            return this
        }

        fun setMovieTimescale(movieTimescale: Long): Builder {
            this.movieTimescale = movieTimescale
            return this
        }

        fun setDurationUs(durationUs: Long): Builder {
            this.durationUs = durationUs
            return this
        }

        fun setMediaDurationUs(mediaDurationUs: Long): Builder {
            this.mediaDurationUs = mediaDurationUs
            return this
        }

        fun setFormat(format: Format?): Builder {
            this.format = format
            return this
        }

        fun setSampleTransformation(sampleTransformation: @Transformation Int): Builder {
            this.sampleTransformation = sampleTransformation
            return this
        }

        fun setSampleDescriptionEncryptionBoxes(
            sampleDescriptionEncryptionBoxes: Array<TrackEncryptionBox?>?
        ): Builder {
            this.sampleDescriptionEncryptionBoxes =
                if (sampleDescriptionEncryptionBoxes == null)
                    null
                else
                    sampleDescriptionEncryptionBoxes.clone()
            return this
        }

        fun setNalUnitLengthFieldLength(nalUnitLengthFieldLength: Int): Builder {
            this.nalUnitLengthFieldLength = nalUnitLengthFieldLength
            return this
        }

        fun setEditListDurations(editListDurations: ImmutableLongArray?): Builder {
            this.editListDurations = editListDurations
            return this
        }

        fun setEditListMediaTimes(editListMediaTimes: ImmutableLongArray?): Builder {
            this.editListMediaTimes = editListMediaTimes
            return this
        }

        fun setShouldBeExposed(shouldBeExposed: Boolean): Builder {
            this.shouldBeExposed = shouldBeExposed
            return this
        }

        fun setChapterTrackId(chapterTrackId: Int): Builder {
            this.chapterTrackId = chapterTrackId
            return this
        }

        fun build(): Track {
            format!!
            return Track(this)
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [TRANSFORMATION_NONE, TRANSFORMATION_CEA608_CDAT])
    annotation class Transformation

    val id: Int

    val type: @C.TrackType Int

    val timescale: Long

    val movieTimescale: Long

    val durationUs: Long

    val mediaDurationUs: Long

    val format: Format?

    val sampleTransformation: @Transformation Int

    val editListDurations: ImmutableLongArray?

    val editListMediaTimes: ImmutableLongArray?

    val nalUnitLengthFieldLength: Int

    val chapterTrackId: Int

    val shouldBeExposed: Boolean

    private val sampleDescriptionEncryptionBoxes: Array<TrackEncryptionBox?>?

    private constructor(builder: Builder) {
        this.id = builder.id
        this.type = builder.type
        this.timescale = builder.timescale
        this.movieTimescale = builder.movieTimescale
        this.durationUs = builder.durationUs
        this.mediaDurationUs = builder.mediaDurationUs
        this.format =builder.format!!
        this.sampleTransformation = builder.sampleTransformation
        this.sampleDescriptionEncryptionBoxes = builder.sampleDescriptionEncryptionBoxes
        this.nalUnitLengthFieldLength = builder.nalUnitLengthFieldLength
        this.editListDurations = builder.editListDurations
        this.editListMediaTimes = builder.editListMediaTimes
        this.shouldBeExposed = builder.shouldBeExposed
        this.chapterTrackId = builder.chapterTrackId
    }

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        id: Int,
        type: @C.TrackType Int,
        timescale: Long,
        movieTimescale: Long,
        durationUs: Long,
        mediaDurationUs: Long,
        format: Format?,
        sampleTransformation: @Transformation Int,
        sampleDescriptionEncryptionBoxes: Array<TrackEncryptionBox?>?,
        nalUnitLengthFieldLength: Int,
        editListDurations: LongArray?,
        editListMediaTimes: LongArray?,
        shouldBeExposed: Boolean,
        chapterTrackId: Int
    ) {
        this.id = id
        this.type = type
        this.timescale = timescale
        this.movieTimescale = movieTimescale
        this.durationUs = durationUs
        this.mediaDurationUs = mediaDurationUs
        this.format = format
        this.sampleTransformation = sampleTransformation
        this.sampleDescriptionEncryptionBoxes =
            if (sampleDescriptionEncryptionBoxes == null) null else sampleDescriptionEncryptionBoxes.clone()
        this.nalUnitLengthFieldLength = nalUnitLengthFieldLength
        this.editListDurations =
            if (editListDurations == null) null else ImmutableLongArray.copyOf(editListDurations)
        this.editListMediaTimes =
            if (editListMediaTimes == null) null else ImmutableLongArray.copyOf(editListMediaTimes)
        this.shouldBeExposed = shouldBeExposed
        this.chapterTrackId = chapterTrackId
    }

    fun buildUpon(): Builder {
        return Track.Builder(this)
    }

    fun getSampleDescriptionEncryptionBox(sampleDescriptionIndex: Int): TrackEncryptionBox? {
        return if (sampleDescriptionEncryptionBoxes == null)
            null
        else
            sampleDescriptionEncryptionBoxes[sampleDescriptionIndex]
    }

    @Deprecated("Use {@link #buildUpon()} instead.")
    fun copyWithFormat(format: Format?): Track {
        return buildUpon().setFormat(format).build()
    }

    @Deprecated("Use {@link #buildUpon()} instead.")
    fun copyWithoutEditLists(): Track {
        return buildUpon().setEditListDurations(null).setEditListMediaTimes(null).build()
    }

    companion object {
        
        const val TRANSFORMATION_NONE: Int = 0

        const val TRANSFORMATION_CEA608_CDAT: Int = 1

        val TIMESCALE_UNSET: Long = -1
    }
}
