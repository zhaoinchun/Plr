
package androidx.media3.extractor.metadata

import androidx.media3.common.Metadata

interface MetadataDecoder {
    fun decode(inputBuffer: MetadataInputBuffer): Metadata?
}
