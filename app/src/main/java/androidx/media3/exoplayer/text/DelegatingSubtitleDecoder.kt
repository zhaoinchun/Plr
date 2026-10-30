package androidx.media3.exoplayer.text

import androidx.media3.extractor.text.SimpleSubtitleDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleParser

// while we maintain dual architecture. Once we fully migrate to the pre-SampleQueue flow, it can be
// deprecated and later deleted.
internal class DelegatingSubtitleDecoder(
    name: String,
    private val subtitleParser: SubtitleParser
) : SimpleSubtitleDecoder(name) {
    override fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle {
        if (reset) {
            subtitleParser.reset()
        }
        return subtitleParser.parseToLegacySubtitle(data,  0, length)
    }
}
