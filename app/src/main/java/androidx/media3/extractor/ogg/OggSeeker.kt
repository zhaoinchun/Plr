
package androidx.media3.extractor.ogg

import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.SeekMap
import java.io.IOException

internal interface OggSeeker {
    
    fun createSeekMap(): SeekMap?

    fun startSeek(targetGranule: Long)

    @Throws(IOException::class)
    fun read(input: ExtractorInput): Long
}
