
package androidx.media3.exoplayer.metadata

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.metadata.MetadataDecoder
import androidx.media3.extractor.metadata.dvbsi.AppInfoTableDecoder
import androidx.media3.extractor.metadata.emsg.EventMessageDecoder
import androidx.media3.extractor.metadata.icy.IcyDecoder
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.scte35.SpliceInfoDecoder

interface MetadataDecoderFactory {
    fun supportsFormat(format: Format): Boolean
    fun createDecoder(format: Format): MetadataDecoder

    companion object {
        val DEFAULT: MetadataDecoderFactory = object : MetadataDecoderFactory {
            override fun supportsFormat(format: Format): Boolean {
                val mimeType = format.sampleMimeType
                return MimeTypes.APPLICATION_ID3 == mimeType
                        || MimeTypes.APPLICATION_EMSG == mimeType
                        || MimeTypes.APPLICATION_SCTE35 == mimeType
                        || MimeTypes.APPLICATION_ICY == mimeType
                        || MimeTypes.APPLICATION_AIT == mimeType
            }

            override fun createDecoder(format: Format): MetadataDecoder {
                val mimeType = format.sampleMimeType
                if (mimeType != null) {
                    when (mimeType) {
                        MimeTypes.APPLICATION_ID3 -> return Id3Decoder()
                        MimeTypes.APPLICATION_EMSG -> return EventMessageDecoder()
                        MimeTypes.APPLICATION_SCTE35 -> return SpliceInfoDecoder()
                        MimeTypes.APPLICATION_ICY -> return IcyDecoder()
                        MimeTypes.APPLICATION_AIT -> return AppInfoTableDecoder()
                        else -> {}
                    }
                }
                throw IllegalArgumentException(
                    "Attempted to create decoder for unsupported MIME type: $mimeType"
                )
            }
        }
    }
}
