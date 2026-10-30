package androidx.media3.extractor.metadata.id3

import androidx.media3.common.MediaMetadata

import androidx.media3.common.util.Util.split
import com.google.common.base.Preconditions
import com.google.common.primitives.Ints

class TextInformationFrame(id: String?, description: String?, values: List<String>) :
    Id3Frame(id!!) {
    val description: String?

    @Deprecated("Use the first element of {@link #values} instead.")
    val value: String?

    val values: List<String>

    init {
        Preconditions.checkArgument(!values.isEmpty())

        this.description = description
        this.values = values.toList()
        this.value = this.values[0]
    }

    @Deprecated(
        """Use {@code TextInformationFrame(String id, String description, String[] values}
        instead"""
    )
    constructor(id: String?, description: String?, value: String) : this(
        id,
        description,
        listOf(value)
    )

    override fun populateMediaMetadata(builder: MediaMetadata.Builder) {
        when (id) {
            "TT2", "TIT2" -> builder.setTitle(values[0])
            "TP1", "TPE1" -> builder.setArtist(values[0])
            "TP2", "TPE2" -> builder.setAlbumArtist(values[0])
            "TAL", "TALB" -> builder.setAlbumTitle(values[0])
            "TRK", "TRCK" -> {
                val trackNumbers: Array<String> = split(values[0], "/")
                try {
                    val trackNumber = trackNumbers[0]!!.toInt()
                    val totalTrackCount =
                        if (trackNumbers.size > 1) trackNumbers[1]!!.toInt() else null
                    builder.setTrackNumber(trackNumber).setTotalTrackCount(totalTrackCount)
                } catch (e: NumberFormatException) {
                    // Do nothing, invalid input.
                }
            }

            "TYE", "TYER" -> try {
                builder.setRecordingYear(values[0].toInt())
            } catch (e: NumberFormatException) {
                // Do nothing, invalid input.
            }

            "TDA", "TDAT" -> try {
                val date = values[0]
                val month = date.substring(2, 4).toInt()
                val day = date.substring(0, 2).toInt()
                builder.setRecordingMonth(month).setRecordingDay(day)
            } catch (e: NumberFormatException) {
                // Do nothing, invalid input.
            } catch (e: StringIndexOutOfBoundsException) {
            }

            "TDRC" -> {
                val recordingDate: MutableList<Int?> =
                    parseId3v2point4TimestampFrameForDate(values[0])
                when (recordingDate.size) {
                    3 -> {
                        builder.setRecordingDay(recordingDate.get(2)!!)
                        builder.setRecordingMonth(recordingDate.get(1)!!)
                        builder.setRecordingYear(recordingDate[0]!!)
                    }

                    2 -> {
                        builder.setRecordingMonth(recordingDate.get(1)!!)
                        builder.setRecordingYear(recordingDate[0]!!)
                    }

                    1 -> builder.setRecordingYear(recordingDate[0]!!)
                    else -> {}
                }
            }

            "TDRL" -> {
                val releaseDate: MutableList<Int?> =
                    parseId3v2point4TimestampFrameForDate(values[0])
                when (releaseDate.size) {
                    3 -> {
                        builder.setReleaseDay(releaseDate.get(2)!!)
                        builder.setReleaseMonth(releaseDate.get(1)!!)
                        builder.setReleaseYear(releaseDate[0]!!)
                    }

                    2 -> {
                        builder.setReleaseMonth(releaseDate.get(1)!!)
                        builder.setReleaseYear(releaseDate[0]!!)
                    }

                    1 -> builder.setReleaseYear(releaseDate[0]!!)
                    else -> {}
                }
            }

            "TCM", "TCOM" -> builder.setComposer(values[0])
            "TP3", "TPE3" -> builder.setConductor(values[0])
            "TXT", "TEXT" -> builder.setWriter(values[0])
            "TCON" -> {
                val genreCode = Ints.tryParse(values[0])
                if (genreCode == null) {
                    builder.setGenre(values[0])
                }else {
                    val genre = Id3Util.resolveV1Genre(genreCode)
                    if (genre != null) {
                        builder.setGenre(genre)
                    }
                }
            }

            "TSST" -> builder.setDiscSubtitle(values[0])
            "TPOS" -> {
                val discNumbers: Array<String> = split(values[0], "/")
                try {
                    val discNumber = discNumbers[0]!!.toInt()
                    val totalDiscCount =
                        if (discNumbers.size > 1) discNumbers[1]!!.toInt() else null
                    builder.setDiscNumber(discNumber).setTotalDiscCount(totalDiscCount!!)
                } catch (e: NumberFormatException) {
                    // Do nothing, invalid input.
                }
            }

            else -> {}
        }
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as TextInformationFrame
        return id == other.id
                && description == other.description
                && values == other.values
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + id.hashCode()
        result = 31 * result + (if (description != null) description.hashCode() else 0)
        result = 31 * result + values.hashCode()
        return result
    }

    override fun toString(): String {
        return id + ": description=" + description + ": values=" + values
    }

    companion object {
        // Private methods
        private fun parseId3v2point4TimestampFrameForDate(value: String): MutableList<Int?> {
            // Timestamp string format is ISO-8601, can be `yyyy-MM-ddTHH:mm:ss`, or reduced precision
            // at each point, for example `yyyy-MM` or `yyyy-MM-ddTHH:mm`.
            val dates: MutableList<Int?> = ArrayList<Int?>()
            try {
                if (value.length >= 10) {
                    dates.add(value.substring(0, 4).toInt())
                    dates.add(value.substring(5, 7).toInt())
                    dates.add(value.substring(8, 10).toInt())
                } else if (value.length >= 7) {
                    dates.add(value.substring(0, 4).toInt())
                    dates.add(value.substring(5, 7).toInt())
                } else if (value.length >= 4) {
                    dates.add(value.substring(0, 4).toInt())
                }
            } catch (e: NumberFormatException) {
                // Invalid output, return.
                return ArrayList<Int?>()
            }
            return dates
        }
    }
}
