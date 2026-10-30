
package androidx.media3.extractor.text

import androidx.media3.decoder.DecoderInputBuffer

open class SubtitleInputBuffer : DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_NORMAL) {
    
    var subsampleOffsetUs: Long = 0
}
