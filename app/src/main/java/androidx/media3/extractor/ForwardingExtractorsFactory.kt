package androidx.media3.extractor

import android.net.Uri
import androidx.media3.common.C.VideoCodecFlags

import androidx.media3.extractor.text.SubtitleParser

class ForwardingExtractorsFactory(private val factory: ExtractorsFactory) : ExtractorsFactory {
    @Suppress("deprecation") // Forwarding deprecated method.
    override fun experimentalSetTextTrackTranscodingEnabled(
        textTrackTranscodingEnabled: Boolean
    ): ExtractorsFactory {
        return factory.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        return factory.setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: @VideoCodecFlags Int
    ): ExtractorsFactory? {
        return factory.experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
    }

    override fun createExtractors(): Array<Extractor> {
        return factory.createExtractors()
    }

    override fun createExtractors(
        uri: Uri,
        responseHeaders: MutableMap<String, List<String>>
    ): Array<Extractor> {
        return factory.createExtractors(uri, responseHeaders)
    }
}
