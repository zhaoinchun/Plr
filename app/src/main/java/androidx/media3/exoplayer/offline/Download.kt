package androidx.media3.exoplayer.offline

import androidx.annotation.IntDef
import com.google.common.base.Preconditions

class Download  constructor(
    val request: DownloadRequest,
    val state: @State Int,
    val startTimeMs: Long,
    val updateTimeMs: Long,
    val contentLength: Long,
    val stopReason: Int,
    val failureReason: @FailureReason Int,
    val progress: DownloadProgress = DownloadProgress()
) {
    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [STATE_QUEUED, STATE_STOPPED, STATE_DOWNLOADING, STATE_COMPLETED, STATE_FAILED, STATE_REMOVING, STATE_RESTARTING
        ]
    )
    annotation class State

    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [FAILURE_REASON_NONE, FAILURE_REASON_UNKNOWN])
    annotation class FailureReason

    init {
        Preconditions.checkArgument((failureReason == FAILURE_REASON_NONE) == (state != STATE_FAILED))
        if (stopReason != 0) {
            Preconditions.checkArgument(state != STATE_DOWNLOADING && state != STATE_QUEUED)
        }
    }

    val isTerminalState: Boolean
        get() = state == STATE_COMPLETED || state == STATE_FAILED
    val bytesDownloaded: Long
        get() = progress.bytesDownloaded
    val percentDownloaded: Float
        get() = progress.percentDownloaded

    companion object {
        // Important: These constants are persisted into DownloadIndex. Do not change them.
        const val STATE_QUEUED: Int = 0
        const val STATE_STOPPED: Int = 1
        const val STATE_DOWNLOADING: Int = 2
        const val STATE_COMPLETED: Int = 3
        const val STATE_FAILED: Int = 4
        const val STATE_REMOVING: Int = 5
        const val STATE_RESTARTING: Int = 7
        const val FAILURE_REASON_NONE: Int = 0
        const val FAILURE_REASON_UNKNOWN: Int = 1
        const val STOP_REASON_NONE: Int = 0
    }
}
