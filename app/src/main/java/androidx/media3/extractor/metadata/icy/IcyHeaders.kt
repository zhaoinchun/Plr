package androidx.media3.extractor.metadata.icy

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.util.Log.w

import com.google.common.base.Preconditions

class IcyHeaders(
    bitrate: Int,
    genre: String?,
    name: String?,
    url: String?,
    isPublic: Boolean,
    metadataInterval: Int
) : Metadata.Entry {
    
    val bitrate: Int

    val genre: String?

    val name: String?

    val url: String?

    val isPublic: Boolean

    val metadataInterval: Int

    init {
        Preconditions.checkArgument(metadataInterval == C.LENGTH_UNSET || metadataInterval > 0)
        this.bitrate = bitrate
        this.genre = genre
        this.name = name
        this.url = url
        this.isPublic = isPublic
        this.metadataInterval = metadataInterval
    }

    override fun populateMediaMetadata(builder: MediaMetadata.Builder) {
        if (name != null) {
            builder.setStation(name)
        }
        if (genre != null) {
            builder.setGenre(genre)
        }
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as IcyHeaders
        return bitrate == other.bitrate && genre == other.genre
                && name == other.name
                && url == other.url
                && isPublic == other.isPublic && metadataInterval == other.metadataInterval
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + bitrate
        result = 31 * result + (if (genre != null) genre.hashCode() else 0)
        result = 31 * result + (if (name != null) name.hashCode() else 0)
        result = 31 * result + (if (url != null) url.hashCode() else 0)
        result = 31 * result + (if (isPublic) 1 else 0)
        result = 31 * result + metadataInterval
        return result
    }

    override fun toString(): String {
        return ("IcyHeaders: name=\""
                + name
                + "\", genre=\""
                + genre
                + "\", bitrate="
                + bitrate
                + ", metadataInterval="
                + metadataInterval)
    }

    companion object {
        const val REQUEST_HEADER_ENABLE_METADATA_NAME: String = "Icy-MetaData"
        const val REQUEST_HEADER_ENABLE_METADATA_VALUE: String = "1"

        private const val TAG = "IcyHeaders"

        private const val RESPONSE_HEADER_BITRATE = "icy-br"
        private const val RESPONSE_HEADER_GENRE = "icy-genre"
        private const val RESPONSE_HEADER_NAME = "icy-name"
        private const val RESPONSE_HEADER_URL = "icy-url"
        private const val RESPONSE_HEADER_PUB = "icy-pub"
        private const val RESPONSE_HEADER_METADATA_INTERVAL = "icy-metaint"

        fun parse(responseHeaders: MutableMap<String, List<String>>): IcyHeaders? {
            var icyHeadersPresent = false
            var bitrate = Format.NO_VALUE
            var genre: String? = null
            var name: String? = null
            var url: String? = null
            var isPublic = false
            var metadataInterval = C.LENGTH_UNSET

            var headers = responseHeaders.get(RESPONSE_HEADER_BITRATE)
            if (headers != null) {
                val bitrateHeader = headers[0]
                try {
                    bitrate = bitrateHeader.toInt() * 1000
                    if (bitrate > 0) {
                        icyHeadersPresent = true
                    } else {
                        w(TAG, "Invalid bitrate: " + bitrateHeader)
                        bitrate = Format.NO_VALUE
                    }
                } catch (e: NumberFormatException) {
                    w(TAG, "Invalid bitrate header: " + bitrateHeader)
                }
            }
            headers = responseHeaders.get(RESPONSE_HEADER_GENRE)
            if (headers != null) {
                genre = headers[0]
                icyHeadersPresent = true
            }
            headers = responseHeaders.get(RESPONSE_HEADER_NAME)
            if (headers != null) {
                name = headers[0]
                icyHeadersPresent = true
            }
            headers = responseHeaders.get(RESPONSE_HEADER_URL)
            if (headers != null) {
                url = headers[0]
                icyHeadersPresent = true
            }
            headers = responseHeaders.get(RESPONSE_HEADER_PUB)
            if (headers != null) {
                isPublic = headers[0] == "1"
                icyHeadersPresent = true
            }
            headers = responseHeaders.get(RESPONSE_HEADER_METADATA_INTERVAL)
            if (headers != null) {
                val metadataIntervalHeader = headers[0]
                try {
                    metadataInterval = metadataIntervalHeader.toInt()
                    if (metadataInterval > 0) {
                        icyHeadersPresent = true
                    } else {
                        w(TAG, "Invalid metadata interval: " + metadataIntervalHeader)
                        metadataInterval = C.LENGTH_UNSET
                    }
                } catch (e: NumberFormatException) {
                    w(TAG, "Invalid metadata interval: " + metadataIntervalHeader)
                }
            }
            return if (icyHeadersPresent)
                IcyHeaders(bitrate, genre, name, url, isPublic, metadataInterval)
            else
                null
        }
    }
}
