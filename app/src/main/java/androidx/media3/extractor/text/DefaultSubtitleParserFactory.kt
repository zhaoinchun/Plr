package androidx.media3.extractor.text

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.dvb.DvbParser
import androidx.media3.extractor.text.pgs.PgsParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.ttml.TtmlParser
import androidx.media3.extractor.text.tx3g.Tx3gParser
import androidx.media3.extractor.text.vobsub.VobsubParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.extractor.text.webvtt.Mp4WebvttParser

class DefaultSubtitleParserFactory : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean {
        val mimeType = format.sampleMimeType
        return mimeType == MimeTypes.TEXT_SSA
                || mimeType == MimeTypes.TEXT_VTT
                || mimeType == MimeTypes.APPLICATION_MP4VTT
                || mimeType == MimeTypes.APPLICATION_SUBRIP
                || mimeType == MimeTypes.APPLICATION_TX3G
                || mimeType == MimeTypes.APPLICATION_PGS
                || mimeType == MimeTypes.APPLICATION_VOBSUB
                || mimeType == MimeTypes.APPLICATION_DVBSUBS
                || mimeType == MimeTypes.APPLICATION_TTML
    }

    override fun getCueReplacementBehavior(format: Format): @Format.CueReplacementBehavior Int {
        val mimeType = format.sampleMimeType
        if (mimeType != null) {
            when (mimeType) {
                MimeTypes.TEXT_SSA -> return SsaParser.cueReplacementBehavior
                MimeTypes.TEXT_VTT -> return WebvttParser.cueReplacementBehavior
                MimeTypes.APPLICATION_MP4VTT -> return Mp4WebvttParser.cueReplacementBehavior
                MimeTypes.APPLICATION_SUBRIP -> return SubripParser.cueReplacementBehavior
                MimeTypes.APPLICATION_TX3G -> return Tx3gParser.cueReplacementBehavior
                MimeTypes.APPLICATION_PGS -> return PgsParser.cueReplacementBehavior
                MimeTypes.APPLICATION_VOBSUB -> return VobsubParser.cueReplacementBehavior
                MimeTypes.APPLICATION_DVBSUBS -> return DvbParser.cueReplacementBehavior
                MimeTypes.APPLICATION_TTML -> return TtmlParser.cueReplacementBehavior
                else -> {}
            }
        }
        throw IllegalArgumentException("Unsupported MIME type: " + mimeType)
    }

    override fun create(format: Format): SubtitleParser {
        val mimeType = format.sampleMimeType
        if (mimeType != null) {
            when (mimeType) {
                MimeTypes.TEXT_SSA -> return SsaParser(format.initializationData)
                MimeTypes.TEXT_VTT -> return WebvttParser()
                MimeTypes.APPLICATION_MP4VTT -> return Mp4WebvttParser()
                MimeTypes.APPLICATION_SUBRIP -> return SubripParser()
                MimeTypes.APPLICATION_TX3G -> return Tx3gParser(format.initializationData)
                MimeTypes.APPLICATION_PGS -> return PgsParser()
                MimeTypes.APPLICATION_VOBSUB -> return VobsubParser(format.initializationData)
                MimeTypes.APPLICATION_DVBSUBS -> return DvbParser(format.initializationData)
                MimeTypes.APPLICATION_TTML -> return TtmlParser()
                else -> {}
            }
        }
        throw IllegalArgumentException("Unsupported MIME type: " + mimeType)
    }
}
