
package androidx.media3.extractor.text

import androidx.media3.decoder.DecoderException

class SubtitleDecoderException : DecoderException {
    
    constructor(message: String?) : super(message)

    constructor(cause: Throwable?) : super(cause)

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
