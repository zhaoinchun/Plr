package androidx.media3.exoplayer.image

import androidx.media3.decoder.DecoderException

class ImageDecoderException : DecoderException {
    constructor(message: String?) : super(message)
    constructor(cause: Throwable?) : super(cause)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
