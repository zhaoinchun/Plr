package androidx.media3.exoplayer.trackselection

import androidx.annotation.IntDef
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup

interface TrackSelection {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(open = true, value = [TYPE_UNSET])
    annotation class Type

    val type: @Type Int
    val trackGroup: TrackGroup
    fun length(): Int
    fun getFormat(index: Int): Format
    fun getIndexInTrackGroup(index: Int): Int
    fun indexOf(format: Format): Int
    fun indexOf(indexInTrackGroup: Int): Int

    companion object {
        const val TYPE_UNSET: Int = 0
        const val TYPE_CUSTOM_BASE: Int = 10000
    }
}
