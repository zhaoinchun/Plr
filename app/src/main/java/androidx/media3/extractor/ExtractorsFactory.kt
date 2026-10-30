package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.extractor.text.SubtitleParser

fun interface ExtractorsFactory {
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.Deprecated("")
    fun experimentalSetTextTrackTranscodingEnabled(
        textTrackTranscodingEnabled: kotlin.Boolean
    ): ExtractorsFactory {
        return this
    }

    fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        return this
    }

    fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
    ): ExtractorsFactory? {
        return this
    }

    fun createExtractors(): kotlin.Array<Extractor>

    fun createExtractors(
        uri: android.net.Uri,
        responseHeaders: MutableMap<String, List<String>>
    ): kotlin.Array<Extractor> {
        return createExtractors()
    }

    companion object {
        val EMPTY: ExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf()
            }
        }
    }
}
