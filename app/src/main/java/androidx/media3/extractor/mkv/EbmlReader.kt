
package androidx.media3.extractor.mkv

import androidx.media3.extractor.ExtractorInput
import java.io.IOException

interface EbmlReader {

    fun init(processor: EbmlProcessor)

    fun reset()

    
    fun read(input: ExtractorInput): Boolean
}
