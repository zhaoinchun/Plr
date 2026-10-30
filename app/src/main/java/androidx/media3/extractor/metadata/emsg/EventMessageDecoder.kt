package androidx.media3.extractor.metadata.emsg

import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.metadata.MetadataInputBuffer
import androidx.media3.extractor.metadata.SimpleMetadataDecoder
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import java.util.Arrays

class EventMessageDecoder : SimpleMetadataDecoder() {
    // Buffer validated by SimpleMetadataDecoder.decode
    override fun decode(inputBuffer: MetadataInputBuffer, buffer: ByteBuffer): Metadata {
        return Metadata(listOf(decode(ParsableByteArray(buffer.array(), buffer.limit()))))
    }

    fun decode(emsgData: ParsableByteArray): EventMessage {
        val schemeIdUri = emsgData.readNullTerminatedString()!!
        val value =emsgData.readNullTerminatedString()!!
        val durationMs = emsgData.readLong()
        val id = emsgData.readLong()
        val messageData =
            Arrays.copyOfRange(emsgData.data, emsgData.getPosition(), emsgData.limit())
        return EventMessage(schemeIdUri, value, durationMs, id, messageData)
    }
}
