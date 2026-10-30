package androidx.media3.extractor.metadata.emsg

import androidx.annotation.VisibleForTesting
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes

class EventMessage
(
    
    val schemeIdUri: String?,
    
    val value: String?,
    
    val durationMs: Long,
    
    val id: Long,
    
    val messageData: ByteArray?
) : Metadata.Entry {
    // Lazily initialized hashcode.
    private var hashCode = 0

    override val wrappedMetadataFormat: Format?
        get() {
            when (schemeIdUri) {
                ID3_SCHEME_ID_AOM, ID3_SCHEME_ID_APPLE -> return ID3_FORMAT
                SCTE35_SCHEME_ID -> return SCTE35_FORMAT
                else -> return null
            }
        }

    override val wrappedMetadataBytes: ByteArray?
        get() = if (wrappedMetadataFormat != null) messageData else null

    override fun hashCode(): Int {
        if (hashCode == 0) {
            var result = 17
            result = 31 * result + (if (schemeIdUri != null) schemeIdUri.hashCode() else 0)
            result = 31 * result + (if (value != null) value.hashCode() else 0)
            result = 31 * result + (durationMs xor (durationMs ushr 32)).toInt()
            result = 31 * result + (id xor (id ushr 32)).toInt()
            result = 31 * result + messageData.contentHashCode()
            hashCode = result
        }
        return hashCode
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as EventMessage
        return durationMs == other.durationMs && id == other.id && schemeIdUri == other.schemeIdUri
                && value == other.value
                && messageData.contentEquals(other.messageData)
    }

    override fun toString(): String {
        return ("EMSG: scheme="
                + schemeIdUri
                + ", id="
                + id
                + ", durationMs="
                + durationMs
                + ", value="
                + value)
    }

    companion object {
        
        @VisibleForTesting
        const val ID3_SCHEME_ID_AOM: String = "https://aomedia.org/emsg/ID3"

        private const val ID3_SCHEME_ID_APPLE = "https://developer.apple.com/streaming/emsg-id3"

        @VisibleForTesting
        const val SCTE35_SCHEME_ID: String = "urn:scte:scte35:2014:bin"

        private val ID3_FORMAT =
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build()
        private val SCTE35_FORMAT =
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SCTE35).build()
    }
}
