package androidx.media3.common

import java.io.IOException

open class ParserException protected constructor(
    message: String?,
    cause: Throwable?,
    val contentIsMalformed: Boolean,
    val dataType: @C.DataType Int
) : IOException(message, cause) {
    override val message: String?
        get() {
            val superMessage = super.message
            return ((if (superMessage != null) superMessage + " " else "")
                    + "{contentIsMalformed="
                    + contentIsMalformed
                    + ", dataType="
                    + dataType
                    + "}")
        }

    companion object {
        fun createForMalformedDataOfUnknownType(
            message: String?, cause: Throwable?
        ): ParserException {
            return ParserException(
                message,
                cause,  
                true,
                C.DATA_TYPE_UNKNOWN
            )
        }

        fun createForMalformedContainer(
            message: String?, cause: Throwable?
        ): ParserException {
            return ParserException(
                message,
                cause,  
                true,
                C.DATA_TYPE_MEDIA
            )
        }

        fun createForMalformedManifest(
            message: String?, cause: Throwable?
        ): ParserException {
            return ParserException(
                message, cause,  true, C.DATA_TYPE_MANIFEST
            )
        }

        fun createForMalformedSteeringManifest(
            message: String?, cause: Throwable?
        ): ParserException {
            return ParserException(
                message, cause,  true, C.DATA_TYPE_STEERING_MANIFEST
            )
        }

        fun createForManifestWithUnsupportedFeature(
            message: String?, cause: Throwable?
        ): ParserException {
            return ParserException(
                message, cause,  false, C.DATA_TYPE_MANIFEST
            )
        }

        fun createForUnsupportedContainerFeature(message: String?): ParserException {
            return ParserException(
                message,  null,  false, C.DATA_TYPE_MEDIA
            )
        }
    }
}
