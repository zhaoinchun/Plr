package androidx.media3.extractor.metadata.vorbis

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import com.google.common.base.Ascii
import com.google.common.primitives.Ints

class VorbisComment(key: String, val value: String) : Metadata.Entry {
    val key: String

    init {
        this.key = Ascii.toUpperCase(key)
    }

    override fun populateMediaMetadata(builder: MediaMetadata.Builder) {
        // Vorbis comments can have duplicate keys, but all these fields are singular on MediaMetadata,
        // so we naively overwrite whatever any previously set value (which results in 'keep last'
        // semantics).
        when (key) {
            "TITLE" -> builder.setTitle(value)
            "ARTIST" -> builder.setArtist(value)
            "ALBUM" -> builder.setAlbumTitle(value)
            "ALBUMARTIST" -> builder.setAlbumArtist(value)
            "TRACKNUMBER" -> {
                val trackNumber = Ints.tryParse(value)
                if (trackNumber != null) {
                    builder.setTrackNumber(trackNumber)
                }
            }

            "TOTALTRACKS" -> {
                val totalTracks = Ints.tryParse(value)
                if (totalTracks != null) {
                    builder.setTotalTrackCount(totalTracks)
                }
            }

            "DISCSUBTITLE" -> builder.setDiscSubtitle(value)
            "DISCNUMBER" -> {
                val discNumber = Ints.tryParse(value)
                if (discNumber != null) {
                    builder.setDiscNumber(discNumber)
                }
            }

            "TOTALDISCS" -> {
                val totalDiscs = Ints.tryParse(value)
                if (totalDiscs != null) {
                    builder.setTotalDiscCount(totalDiscs)
                }
            }

            "GENRE" -> builder.setGenre(value)
            "DESCRIPTION" -> builder.setDescription(value)
            else -> {}
        }
    }

    override fun toString(): String {
        return "VC: " + key + "=" + value
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as VorbisComment
        return key == other.key && value == other.value
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + key.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}
