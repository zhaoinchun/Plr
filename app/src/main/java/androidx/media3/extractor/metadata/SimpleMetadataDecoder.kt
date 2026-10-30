package androidx.media3.extractor.metadata

import androidx.media3.common.Metadata

import com.google.common.base.Preconditions
import java.nio.ByteBuffer

abstract class SimpleMetadataDecoder : MetadataDecoder {
    override fun decode(inputBuffer: MetadataInputBuffer): Metadata? {
        val buffer = inputBuffer.data!!
        Preconditions.checkArgument(buffer.position() == 0 && buffer.hasArray() && buffer.arrayOffset() == 0)
        return decode(inputBuffer, buffer)
    }

    protected abstract fun decode(inputBuffer: MetadataInputBuffer, buffer: ByteBuffer): Metadata?
}
