package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.extractor.SniffFailure

class UnrecognizedInputFormatException(
    message: String?,
    val uri: Uri?, sniffFailures: List<out SniffFailure?>
) : ParserException(
    message,  
    null,  
    false,
    C.DATA_TYPE_MEDIA
) {
    val sniffFailures: List<SniffFailure?> = sniffFailures.toList()

    override val message: String?
        get() {
            val superMessage = super.message
            return if (sniffFailures.isEmpty())
                superMessage
            else
                "$superMessage\nsniff failures: $sniffFailures"
        }
}
