
package androidx.media3.extractor.metadata

import androidx.media3.decoder.DecoderInputBuffer

class MetadataInputBuffer : DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_NORMAL) {
    var subsampleOffsetUs: Long = 0
}
