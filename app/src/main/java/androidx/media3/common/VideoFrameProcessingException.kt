package androidx.media3.common

class VideoFrameProcessingException : Exception {
    val presentationTimeUs: Long

    constructor(
        message: String?,
        presentationTimeUs: Long = C.TIME_UNSET
    ) : super(message + getPresentationTimeUsString(presentationTimeUs)) {
        this.presentationTimeUs = presentationTimeUs
    }

    constructor(
        message: String?,
        cause: Throwable?,
        presentationTimeUs: Long = C.TIME_UNSET
    ) : super(message + getPresentationTimeUsString(presentationTimeUs), cause) {
        this.presentationTimeUs = presentationTimeUs
    }

    constructor(cause: Throwable?, presentationTimeUs: Long = C.TIME_UNSET) : super(
        getPresentationTimeUsString(presentationTimeUs), cause
    ) {
        this.presentationTimeUs = presentationTimeUs
    }

    companion object {
        
        fun from(
            exception: Exception,
            presentationTimeUs: Long = C.TIME_UNSET
        ): VideoFrameProcessingException {
            if (exception is VideoFrameProcessingException) {
                return exception
            } else {
                return VideoFrameProcessingException(exception, presentationTimeUs)
            }
        }

        private fun getPresentationTimeUsString(presentationTimeUs: Long): String {
            return if (presentationTimeUs == C.TIME_UNSET) " @UNSET" else " @" + presentationTimeUs
        }
    }
}
