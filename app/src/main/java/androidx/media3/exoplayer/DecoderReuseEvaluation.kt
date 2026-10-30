package androidx.media3.exoplayer

import android.text.TextUtils
import androidx.annotation.IntDef
import androidx.media3.common.Format

import com.google.common.base.Preconditions

class DecoderReuseEvaluation(
    decoderName: String,
    oldFormat: Format,
    newFormat: Format,
    result: @DecoderReuseResult Int,
    discardReasons: @DecoderDiscardReasons Int
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [REUSE_RESULT_NO, REUSE_RESULT_YES_WITH_FLUSH, REUSE_RESULT_YES_WITH_RECONFIGURATION, REUSE_RESULT_YES_WITHOUT_RECONFIGURATION]
    )
    annotation class DecoderReuseResult

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [DISCARD_REASON_REUSE_NOT_IMPLEMENTED, DISCARD_REASON_WORKAROUND, DISCARD_REASON_APP_OVERRIDE, DISCARD_REASON_MIME_TYPE_CHANGED, DISCARD_REASON_OPERATING_RATE_CHANGED, DISCARD_REASON_INITIALIZATION_DATA_CHANGED, DISCARD_REASON_DRM_SESSION_CHANGED, DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED, DISCARD_REASON_VIDEO_MAX_RESOLUTION_EXCEEDED, DISCARD_REASON_VIDEO_RESOLUTION_CHANGED, DISCARD_REASON_VIDEO_ROTATION_CHANGED, DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED, DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED, DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED, DISCARD_REASON_AUDIO_ENCODING_CHANGED, DISCARD_REASON_AUDIO_BYPASS_POSSIBLE, DISCARD_REASON_VIDEO_FRAME_RATE_CHANGED]
    )
    annotation class DecoderDiscardReasons

    val decoderName: String
    val oldFormat: Format
    val newFormat: Format
    val result: @DecoderReuseResult Int
    val discardReasons: @DecoderDiscardReasons Int

    init {
        Preconditions.checkArgument(result == REUSE_RESULT_NO || discardReasons == 0)
        Preconditions.checkArgument(!TextUtils.isEmpty(decoderName))
        this.decoderName = decoderName
        this.oldFormat = oldFormat
        this.newFormat = newFormat
        this.result = result
        this.discardReasons = discardReasons
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as DecoderReuseEvaluation
        return result == other.result && discardReasons == other.discardReasons && decoderName == other.decoderName
                && oldFormat.equals(other.oldFormat)
                && newFormat.equals(other.newFormat)
    }

    override fun hashCode(): Int {
        var hashCode = 17
        hashCode = 31 * hashCode + result
        hashCode = 31 * hashCode + discardReasons
        hashCode = 31 * hashCode + decoderName.hashCode()
        hashCode = 31 * hashCode + oldFormat.hashCode()
        hashCode = 31 * hashCode + newFormat.hashCode()
        return hashCode
    }

    companion object {
        const val REUSE_RESULT_NO: Int = 0
        const val REUSE_RESULT_YES_WITH_FLUSH: Int = 1
        const val REUSE_RESULT_YES_WITH_RECONFIGURATION: Int = 2
        const val REUSE_RESULT_YES_WITHOUT_RECONFIGURATION: Int = 3
        const val DISCARD_REASON_REUSE_NOT_IMPLEMENTED: Int = 1 shl 0
        const val DISCARD_REASON_WORKAROUND: Int = 1 shl 1
        const val DISCARD_REASON_APP_OVERRIDE: Int = 1 shl 2
        const val DISCARD_REASON_MIME_TYPE_CHANGED: Int = 1 shl 3
        const val DISCARD_REASON_OPERATING_RATE_CHANGED: Int = 1 shl 4
        const val DISCARD_REASON_INITIALIZATION_DATA_CHANGED: Int = 1 shl 5
        const val DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED: Int = 1 shl 6
        const val DISCARD_REASON_DRM_SESSION_CHANGED: Int = 1 shl 7
        const val DISCARD_REASON_VIDEO_MAX_RESOLUTION_EXCEEDED: Int = 1 shl 8
        const val DISCARD_REASON_VIDEO_RESOLUTION_CHANGED: Int = 1 shl 9
        const val DISCARD_REASON_VIDEO_ROTATION_CHANGED: Int = 1 shl 10
        const val DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED: Int = 1 shl 11
        const val DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED: Int = 1 shl 12
        const val DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED: Int = 1 shl 13
        const val DISCARD_REASON_AUDIO_ENCODING_CHANGED: Int = 1 shl 14
        const val DISCARD_REASON_AUDIO_BYPASS_POSSIBLE: Int = 1 shl 15
        const val DISCARD_REASON_VIDEO_FRAME_RATE_CHANGED: Int = 1 shl 16
    }
}
