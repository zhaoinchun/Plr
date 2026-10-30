package androidx.media3.exoplayer.video

import androidx.media3.container.ObuParser
import androidx.media3.container.ObuParser.split
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

object Av1ObuUtil {
    // Expected magic bytes for HDR10+ metadata in AV1 OBU. Spec:
    // https://aomediacodec.github.io/av1-hdr10plus/#hdr10plus-metadata
    private val expectedAv1Hdr10PlusPayloadPrefix = byteArrayOf(
        // itu_t_t35_country_code == 0xB5
        0xB5.toByte(),  // itu_t_t35_terminal_provider_code == 0x003C
        0x00.toByte(),
        0x3C.toByte(),  // itu_t_t35_terminal_provider_oriented_code == 0x0001
        0x00.toByte(),
        0x01.toByte(),  // application_identifier == 0x04
        0x04.toByte(),
    )

    fun stripAllT35Metadata(buffer: ByteBuffer) {
        stripT35Metadata(buffer,  false)
    }

    fun stripNonHdr10PlusT35Metadata(buffer: ByteBuffer) {
        stripT35Metadata(buffer,  true)
    }

    private fun stripT35Metadata(buffer: ByteBuffer, keepHdr10Plus: Boolean) {
        val obus: MutableList<ObuParser.Obu> = split(buffer.asReadOnlyBuffer())
        for (obu in obus) {
            if (obu.type != ObuParser.OBU_METADATA) {
                continue
            }
            val metadata: ObuParser.Metadata
            try {
                metadata = ObuParser.Metadata.parse(obu)
            } catch (e: BufferUnderflowException) {
                // Malformed metadata OBU, do not attempt to rewrite it and let the underlying decoder deal
                // with any potential errors.
                continue
            }
            if (metadata.type != ObuParser.Metadata.METADATA_TYPE_ITUT_T35) {
                continue
            }
            if (!keepHdr10Plus || !Av1ObuUtil.isHdr10PlusMetadata(metadata.payload!!)) {
                // This is a metadata OBU with metadata type ITUT-T35, that we want to rewrite. Set the
                // first byte of the metadata type leb128 in the OBU payload to 0x1F to mark it as an
                // unknown metadata OBU. 0x1F is the leb128() encoding of the value 31 (which according to
                // the AV1 spec is "Unregistered user private":
                // https://aomediacodec.github.io/av1-spec/#general-metadata-obu-semantics)
                buffer.put(obu.payload.position(), 0x1F.toByte())
            }
        }
    }

    private fun isHdr10PlusMetadata(payload: ByteBuffer): Boolean {
        if (payload.remaining() < expectedAv1Hdr10PlusPayloadPrefix.size) {
            return false
        }
        val position = payload.position()
        for (i in expectedAv1Hdr10PlusPayloadPrefix.indices) {
            if (payload.get(position + i) != expectedAv1Hdr10PlusPayloadPrefix[i]) {
                return false
            }
        }
        return true
    }
}
