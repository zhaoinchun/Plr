package androidx.media3.extractor.metadata.icy

import androidx.media3.common.Metadata

import androidx.media3.extractor.metadata.MetadataInputBuffer
import androidx.media3.extractor.metadata.SimpleMetadataDecoder
import com.google.common.base.Ascii
import java.nio.ByteBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

class IcyDecoder : SimpleMetadataDecoder() {
    private val utf8Decoder: CharsetDecoder
    private val iso88591Decoder: CharsetDecoder

    init {
        utf8Decoder = StandardCharsets.UTF_8.newDecoder()
        iso88591Decoder = StandardCharsets.ISO_8859_1.newDecoder()
    }

    override fun decode(inputBuffer: MetadataInputBuffer, buffer: ByteBuffer): Metadata {
        val icyString = decodeToString(buffer)
        val icyBytes = ByteArray(buffer.limit())
        buffer.get(icyBytes)

        if (icyString == null) {
            return Metadata(listOf(IcyInfo(icyBytes,  null,  null)))
        }

        var name: String? = null
        var url: String? = null
        var index = 0
        val matcher: Matcher = METADATA_ELEMENT.matcher(icyString)
        while (matcher.find(index)) {
            val key = matcher.group(1)
            val value = matcher.group(2)
            if (key != null) {
                when (Ascii.toLowerCase(key)) {
                    STREAM_KEY_NAME -> name = value
                    STREAM_KEY_URL -> url = value
                    else -> {}
                }
            }
            index = matcher.end()
        }
        return Metadata(listOf(IcyInfo(icyBytes, name, url)))
    }

    // The ICY spec doesn't specify a character encoding, and there's no way to communicate one
    // either. So try decoding UTF-8 first, then fall back to ISO-8859-1.
    // https://github.com/google/ExoPlayer/issues/6753
    private fun decodeToString(data: ByteBuffer): String? {
        try {
            return utf8Decoder.decode(data).toString()
        } catch (e: CharacterCodingException) {
            // Fall through to try ISO-8859-1 decoding.
        } finally {
            utf8Decoder.reset()
            data.rewind()
        }
        try {
            return iso88591Decoder.decode(data).toString()
        } catch (e: CharacterCodingException) {
            return null
        } finally {
            iso88591Decoder.reset()
            data.rewind()
        }
    }

    companion object {
        private val METADATA_ELEMENT: Pattern = Pattern.compile("(.+?)='(.*?)';", Pattern.DOTALL)
        private const val STREAM_KEY_NAME = "streamtitle"
        private const val STREAM_KEY_URL = "streamurl"
    }
}
