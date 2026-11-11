
package androidx.media3.extractor.ogg

import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.SeekMap
import java.io.IOException

interface OggSeeker {
    
    fun createSeekMap(): SeekMap?

    fun startSeek(targetGranule: Long)

    
    fun read(input: ExtractorInput): Long
}
