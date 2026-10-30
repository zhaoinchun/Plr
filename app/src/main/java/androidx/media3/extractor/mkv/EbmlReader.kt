
package androidx.media3.extractor.mkv

import androidx.media3.extractor.ExtractorInput
import java.io.IOException

internal interface EbmlReader {

    fun init(processor: EbmlProcessor)

    fun reset()

    @Throws(IOException::class)
    fun read(input: ExtractorInput): Boolean
}
