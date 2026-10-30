package androidx.media3.exoplayer

import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException

import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.getFormatSupportString
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Preconditions
import java.io.IOException

class ExoPlaybackException private constructor(
    message: String?,
    cause: Throwable?,
    @ErrorCode errorCode: @ErrorCode Int,
    @Type type: @Type Int,
    rendererName: String?,
    rendererIndex: Int,
    rendererFormat: Format?,
    @C.FormatSupport rendererFormatSupport: @C.FormatSupport Int,
    mediaPeriodId: MediaPeriodId?,
    timestampMs: Long,
    isRecoverable: Boolean
) : PlaybackException(message, cause, errorCode, Bundle.EMPTY, timestampMs) {

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
    @IntDef(value = [TYPE_SOURCE, TYPE_RENDERER, TYPE_UNEXPECTED, TYPE_REMOTE])
    annotation class Type

    @Type
    val type: @Type Int
    val rendererName: String?
    val rendererIndex: Int
    val rendererFormat: Format?

    @C.FormatSupport
    val rendererFormatSupport: @C.FormatSupport Int
    val mediaPeriodId: MediaPeriodId?
    val isRecoverable: Boolean

    private constructor(
        @Type type: @Type Int,
        cause: Throwable?,
        @ErrorCode errorCode: @ErrorCode Int
    ) : this(
        type,
        cause,  
        null,
        errorCode,  
        null,  
        C.INDEX_UNSET,  
        null,  
        C.FORMAT_HANDLED,  
        null,  
        false
    )

    private constructor(
        @Type type: @Type Int,
        cause: Throwable?,
        customMessage: String?,
        @ErrorCode errorCode: @ErrorCode Int,
        rendererName: String?,
        rendererIndex: Int,
        rendererFormat: Format?,
        @C.FormatSupport rendererFormatSupport: @C.FormatSupport Int,
        mediaPeriodId: MediaPeriodId?,
        isRecoverable: Boolean
    ) : this(
        deriveMessage(
            type,
            customMessage,
            rendererName,
            rendererIndex,
            rendererFormat,
            rendererFormatSupport
        ),
        cause,
        errorCode,
        type,
        rendererName,
        rendererIndex,
        rendererFormat,
        rendererFormatSupport,
        mediaPeriodId,  
        SystemClock.elapsedRealtime(),
        isRecoverable
    )

    init {
        Preconditions.checkArgument(!isRecoverable || type == TYPE_RENDERER)
        Preconditions.checkArgument(cause != null || type == TYPE_REMOTE)
        this.type = type
        this.rendererName = rendererName
        this.rendererIndex = rendererIndex
        this.rendererFormat = rendererFormat
        this.rendererFormatSupport = rendererFormatSupport
        this.mediaPeriodId = mediaPeriodId
        this.isRecoverable = isRecoverable
    }

    val sourceException: IOException
        get() {
            Preconditions.checkState(type == TYPE_SOURCE)
            return cause!! as IOException
        }

    val rendererException: Exception
        get() {
            Preconditions.checkState(type == TYPE_RENDERER)
            return cause!! as java.lang.Exception
        }

    val unexpectedException: RuntimeException
        get() {
            Preconditions.checkState(type == TYPE_UNEXPECTED)
            return cause!! as java.lang.RuntimeException
        }

    override fun errorInfoEquals(that: PlaybackException?): Boolean {
        if (!super.errorInfoEquals(that)) {
            return false
        }
        // We know that is not null and is an ExoPlaybackException because of the super call returning
        // true.
        val other = that as ExoPlaybackException
        return type == other.type && rendererName == other.rendererName
                && rendererIndex == other.rendererIndex && rendererFormat == other.rendererFormat
                && rendererFormatSupport == other.rendererFormatSupport && mediaPeriodId == other.mediaPeriodId
                && isRecoverable == other.isRecoverable
    }

    fun copyWithMediaPeriodId(mediaPeriodId: MediaPeriodId?): ExoPlaybackException {
        return ExoPlaybackException(
            message!!,
            cause,
            errorCode,
            type,
            rendererName,
            rendererIndex,
            rendererFormat,
            rendererFormatSupport,
            mediaPeriodId,
            timestampMs,
            isRecoverable
        )
    }

    companion object {
        const val TYPE_SOURCE: Int = 0
        const val TYPE_RENDERER: Int = 1
        const val TYPE_UNEXPECTED: Int = 2
        const val TYPE_REMOTE: Int = 3

        fun createForSource(cause: IOException?, errorCode: Int): ExoPlaybackException {
            return ExoPlaybackException(TYPE_SOURCE, cause, errorCode)
        }

        @Deprecated(
            """Use {@link #createForRenderer(Throwable, String, int, Format, int, MediaPeriodId,
   *     boolean, int)} instead."""
        )
        fun createForRenderer(
            cause: Throwable?,
            rendererName: String?,
            rendererIndex: Int,
            rendererFormat: Format?,
            @C.FormatSupport rendererFormatSupport: @C.FormatSupport Int,
            isRecoverable: Boolean,
            @ErrorCode errorCode: @ErrorCode Int
        ): ExoPlaybackException {
            return createForRenderer(
                cause,
                rendererName,
                rendererIndex,
                rendererFormat,
                rendererFormatSupport,  
                null,
                isRecoverable,
                errorCode
            )
        }

        fun createForRenderer(
            cause: Throwable?,
            rendererName: String?,
            rendererIndex: Int,
            rendererFormat: Format?,
            @C.FormatSupport rendererFormatSupport: @C.FormatSupport Int,
            mediaPeriodId: MediaPeriodId?,
            isRecoverable: Boolean,
            @ErrorCode errorCode: @ErrorCode Int
        ): ExoPlaybackException {
            return ExoPlaybackException(
                TYPE_RENDERER,
                cause,  
                null,
                errorCode,
                rendererName,
                rendererIndex,
                rendererFormat,
                if (rendererFormat == null) C.FORMAT_HANDLED else rendererFormatSupport,
                mediaPeriodId,
                isRecoverable
            )
        }

        @Deprecated(
            """Use {@link #createForUnexpected(RuntimeException, int)
   *     createForUnexpected(RuntimeException, ERROR_CODE_UNSPECIFIED)} instead."""
        )
        fun createForUnexpected(cause: java.lang.RuntimeException?): ExoPlaybackException {
            return createForUnexpected(cause, ERROR_CODE_UNSPECIFIED)
        }

        fun createForUnexpected(
            cause: java.lang.RuntimeException?, @ErrorCode errorCode: @ErrorCode Int
        ): ExoPlaybackException {
            return ExoPlaybackException(TYPE_UNEXPECTED, cause, errorCode)
        }

        fun createForRemote(message: String?): ExoPlaybackException {
            return ExoPlaybackException(
                TYPE_REMOTE,  
                null,  
                message,
                ERROR_CODE_REMOTE_ERROR,  
                null,  
                C.INDEX_UNSET,  
                null,  
                C.FORMAT_HANDLED,  
                null,  
                false
            )
        }

        private fun deriveMessage(
            @Type type: @Type Int,
            customMessage: String?,
            rendererName: String?,
            rendererIndex: Int,
            rendererFormat: Format?,
            @C.FormatSupport rendererFormatSupport: @C.FormatSupport Int
        ): String {
            var message: String
            when (type) {
                TYPE_SOURCE -> message = "Source error"
                TYPE_RENDERER -> message =
                    (rendererName
                            + " error"
                            + ", index="
                            + rendererIndex
                            + ", format="
                            + rendererFormat
                            + ", format_supported="
                            + getFormatSupportString(rendererFormatSupport))

                TYPE_REMOTE -> message = "Remote error"
                TYPE_UNEXPECTED -> message = "Unexpected runtime error"
                else -> message = "Unexpected runtime error"
            }
            if (!TextUtils.isEmpty(customMessage)) {
                message += ": " + customMessage
            }
            return message
        }
    }
}
