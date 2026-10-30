
package androidx.media3.extractor.mp4

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.Id3Util
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlin.math.min

internal object MetadataUtil {
    private const val TAG = "MetadataUtil"

    // Codes that start with the copyright character (omitted) and have equivalent ID3 frames.
    private const val SHORT_TYPE_NAME_1 = 0x006e616d
    private const val SHORT_TYPE_NAME_2 = 0x0074726b
    private const val SHORT_TYPE_COMMENT = 0x00636d74
    private const val SHORT_TYPE_YEAR = 0x00646179
    private const val SHORT_TYPE_ARTIST = 0x00415254
    private const val SHORT_TYPE_ENCODER = 0x00746f6f
    private const val SHORT_TYPE_ALBUM = 0x00616c62
    private const val SHORT_TYPE_COMPOSER_1 = 0x00636f6d
    private const val SHORT_TYPE_COMPOSER_2 = 0x00777274
    private const val SHORT_TYPE_LYRICS = 0x006c7972
    private const val SHORT_TYPE_GENRE = 0x0067656e
    private const val SHORT_TYPE_MOVEMENT_NAME = 0x006d766e // ©mvn
    private const val SHORT_TYPE_MOVEMENT_INDEX = 0x006d7669 // ©mvi

    // Codes that have equivalent ID3 frames.
    private const val TYPE_COVER_ART = 0x636f7672
    private const val TYPE_GENRE = 0x676e7265
    private const val TYPE_GROUPING = 0x00677270
    private const val TYPE_DISK_NUMBER = 0x6469736b
    private const val TYPE_TRACK_NUMBER = 0x74726b6e
    private const val TYPE_TEMPO = 0x746d706f
    private const val TYPE_COMPILATION = 0x6370696c
    private const val TYPE_ALBUM_ARTIST = 0x61415254
    private const val TYPE_SORT_TRACK_NAME = 0x736f6e6d
    private const val TYPE_SORT_ALBUM = 0x736f616c
    private const val TYPE_SORT_ARTIST = 0x736f6172
    private const val TYPE_SORT_ALBUM_ARTIST = 0x736f6161
    private const val TYPE_SORT_COMPOSER = 0x736f636f

    // Types that do not have equivalent ID3 frames.
    private const val TYPE_RATING = 0x72746e67
    private const val TYPE_GAPLESS_ALBUM = 0x70676170
    private const val TYPE_TV_SORT_SHOW = 0x736f736e
    private const val TYPE_TV_SHOW = 0x74767368

    // Type for items that are intended for internal use by the player.
    private const val TYPE_INTERNAL = 0x2d2d2d2d

    private const val PICTURE_TYPE_FRONT_COVER = 3

    private const val TYPE_TOP_BYTE_COPYRIGHT = 0xA9
    private const val TYPE_TOP_BYTE_REPLACEMENT = 0xFD // Truncated value of \uFFFD.

    fun setFormatMetadata(
        trackType: @C.TrackType Int,
        mdtaMetadata: Metadata?,
        formatBuilder: Format.Builder,
        existingMetadata: Metadata?,
        vararg additionalMetadata: Metadata?
    ) {
        var formatMetadata = if (existingMetadata != null) existingMetadata else Metadata(listOf())
        if (mdtaMetadata != null) {
            for (mdtaMetadataEntry in mdtaMetadata.getEntriesOfType<MdtaMetadataEntry>(
                MdtaMetadataEntry::class.java
            )) {
                // If KEY_ANDROID_CAPTURE_FPS key is present in the moov.meta box it should only be exposed
                // in video track metadata. All other mdta entries can be added to all tracks.
                if (mdtaMetadataEntry!!.key != MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS || trackType == C.TRACK_TYPE_VIDEO) {
                    formatMetadata = formatMetadata.copyWithAppendedEntries(listOf(mdtaMetadataEntry))
                }
            }
        }

        for (metadata in additionalMetadata) {
            formatMetadata = formatMetadata.copyWithAppendedEntriesFrom(metadata)
        }

        if (formatMetadata.length() > 0) {
            formatBuilder.setMetadata(formatMetadata)
        }
    }

    fun setFormatGaplessInfo(
        trackType: Int, gaplessInfoHolder: GaplessInfoHolder, formatBuilder: Format.Builder
    ) {
        if (trackType == C.TRACK_TYPE_AUDIO && gaplessInfoHolder.hasGaplessInfo()) {
            formatBuilder
                .setEncoderDelay(gaplessInfoHolder.encoderDelay)
                .setEncoderPadding(gaplessInfoHolder.encoderPadding)
        }
    }

    fun parseIlstElement(ilst: ParsableByteArray, ignoreArtwork: Boolean): Metadata.Entry? {
        val position: Int = ilst.getPosition()
        val size: Int = ilst.readInt()
        if (size < Mp4Box.HEADER_SIZE) {
            Log.w(TAG, "Skipped empty metadata entry")
            return null
        }
        val endPosition = position + size
        val type: Int = ilst.readInt()
        val typeTopByte = (type shr 24) and 0xFF
        try {
            val remainingPayloadSize: Int = endPosition - ilst.getPosition()
            if (remainingPayloadSize < Mp4Box.HEADER_SIZE) {
                Log.w(TAG, "Skipped empty metadata entry: " + Mp4Box.getBoxTypeString(type))
                return null
            }
            if (typeTopByte == TYPE_TOP_BYTE_COPYRIGHT || typeTopByte == TYPE_TOP_BYTE_REPLACEMENT) {
                val shortType = type and 0x00FFFFFF
                if (shortType == SHORT_TYPE_COMMENT) {
                    return parseCommentAttribute(type, ilst)
                } else if (shortType == SHORT_TYPE_NAME_1 || shortType == SHORT_TYPE_NAME_2) {
                    return parseTextAttribute(type, "TIT2", ilst)
                } else if (shortType == SHORT_TYPE_COMPOSER_1 || shortType == SHORT_TYPE_COMPOSER_2) {
                    return parseTextAttribute(type, "TCOM", ilst)
                } else if (shortType == SHORT_TYPE_YEAR) {
                    return parseTextAttribute(type, "TDRC", ilst)
                } else if (shortType == SHORT_TYPE_ARTIST) {
                    return parseTextAttribute(type, "TPE1", ilst)
                } else if (shortType == SHORT_TYPE_ENCODER) {
                    return parseTextAttribute(type, "TSSE", ilst)
                } else if (shortType == SHORT_TYPE_ALBUM) {
                    return parseTextAttribute(type, "TALB", ilst)
                } else if (shortType == SHORT_TYPE_LYRICS) {
                    return parseTextAttribute(type, "USLT", ilst)
                } else if (shortType == SHORT_TYPE_GENRE) {
                    return parseTextAttribute(type, "TCON", ilst)
                } else if (shortType == TYPE_GROUPING) {
                    return parseTextAttribute(type, "TIT1", ilst)
                } else if (shortType == SHORT_TYPE_MOVEMENT_NAME) {
                    return parseTextAttribute(type, "MVNM", ilst)
                } else if (shortType == SHORT_TYPE_MOVEMENT_INDEX) {
                    return parseIntegerAttribute(type, "MVIN", ilst, true, false)
                }
            } else if (type == TYPE_GENRE) {
                return parseStandardGenreAttribute(ilst)
            } else if (type == TYPE_DISK_NUMBER) {
                return parseIndexAndCountAttribute(type, "TPOS", ilst)
            } else if (type == TYPE_TRACK_NUMBER) {
                return parseIndexAndCountAttribute(type, "TRCK", ilst)
            } else if (type == TYPE_TEMPO) {
                return parseIntegerAttribute(type, "TBPM", ilst, true, false)
            } else if (type == TYPE_COMPILATION) {
                return parseIntegerAttribute(type, "TCMP", ilst, true, true)
            } else if (type == TYPE_COVER_ART) {
                return if (ignoreArtwork) null else parseCoverArt(ilst)
            } else if (type == TYPE_ALBUM_ARTIST) {
                return parseTextAttribute(type, "TPE2", ilst)
            } else if (type == TYPE_SORT_TRACK_NAME) {
                return parseTextAttribute(type, "TSOT", ilst)
            } else if (type == TYPE_SORT_ALBUM) {
                return parseTextAttribute(type, "TSOA", ilst)
            } else if (type == TYPE_SORT_ARTIST) {
                return parseTextAttribute(type, "TSOP", ilst)
            } else if (type == TYPE_SORT_ALBUM_ARTIST) {
                return parseTextAttribute(type, "TSO2", ilst)
            } else if (type == TYPE_SORT_COMPOSER) {
                return parseTextAttribute(type, "TSOC", ilst)
            } else if (type == TYPE_RATING) {
                return parseIntegerAttribute(type, "ITUNESADVISORY", ilst, false, false)
            } else if (type == TYPE_GAPLESS_ALBUM) {
                return parseIntegerAttribute(type, "ITUNESGAPLESS", ilst, false, true)
            } else if (type == TYPE_TV_SORT_SHOW) {
                return parseTextAttribute(type, "TVSHOWSORT", ilst)
            } else if (type == TYPE_TV_SHOW) {
                return parseTextAttribute(type, "TVSHOW", ilst)
            } else if (type == TYPE_INTERNAL) {
                return parseInternalAttribute(ilst, endPosition)
            }
            Log.d(TAG, "Skipped unknown metadata entry: " + Mp4Box.getBoxTypeString(type))
            return null
        } finally {
            ilst.setPosition(endPosition)
        }
    }

    fun parseMdtaMetadataEntryFromIlst(
        ilst: ParsableByteArray, endPosition: Int, key: String
    ): MdtaMetadataEntry? {
        var atomPosition: Int
        while ((ilst.getPosition().also { atomPosition = it }) < endPosition) {
            val atomSize: Int = ilst.readInt()
            val atomType: Int = ilst.readInt()
            if (atomType == Mp4Box.TYPE_data) {
                val typeIndicator: Int = ilst.readInt()
                val localeIndicator: Int = ilst.readInt()
                val dataSize = atomSize - 16
                val value = ByteArray(dataSize)
                ilst.readBytes(value, 0, dataSize)
                try {
                    return MdtaMetadataEntry(key, value, localeIndicator, typeIndicator)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse metadata entry with key: " + key)
                    break
                }
            }
            ilst.setPosition(atomPosition + atomSize)
        }
        return null
    }

    @Deprecated("Use {@link Metadata#getFirstMatchingEntry(Class, Predicate)} instead.")
    fun findMdtaMetadataEntryWithKey(metadata: Metadata, key: String?): MdtaMetadataEntry? {
        for (i in 0..<metadata.length()) {
            val entry = metadata.get(i)
            if (entry is MdtaMetadataEntry) {
                val mdtaMetadataEntry: MdtaMetadataEntry = entry as MdtaMetadataEntry
                if (mdtaMetadataEntry.key == key) {
                    return mdtaMetadataEntry
                }
            }
        }
        return null
    }

    private fun parseTextAttribute(
        type: Int, id: String?, data: ParsableByteArray
    ): TextInformationFrame? {
        val atomSize: Int = data.readInt()
        val atomType: Int = data.readInt()
        if (atomType == Mp4Box.TYPE_data) {
            data.skipBytes(8) // version (1), flags (3), empty (4)
            val value: String = data.readNullTerminatedString(atomSize - 16)
            return TextInformationFrame(
                id,  
                null,
               listOf(value)
            )
        }
        Log.w(TAG, "Failed to parse text attribute: " + Mp4Box.getBoxTypeString(type))
        return null
    }

    private fun parseCommentAttribute(type: Int, data: ParsableByteArray): CommentFrame? {
        val atomSize: Int = data.readInt()
        val atomType: Int = data.readInt()
        if (atomType == Mp4Box.TYPE_data) {
            data.skipBytes(8) // version (1), flags (3), empty (4)
            val value: String = data.readNullTerminatedString(atomSize - 16)
            return CommentFrame(C.LANGUAGE_UNDETERMINED, value, value)
        }
        Log.w(TAG, "Failed to parse comment attribute: " + Mp4Box.getBoxTypeString(type))
        return null
    }

    private fun parseIntegerAttribute(
        type: Int,
        id: String?,
        data: ParsableByteArray,
        isTextInformationFrame: Boolean,
        isBoolean: Boolean
    ): Id3Frame? {
        var value = parseIntegerAttribute(data)
        if (isBoolean) {
            value = min(1, value)
        }
        if (value >= 0) {
            return if (isTextInformationFrame)
                TextInformationFrame(
                    id,  null, listOf(value.toString())
                )
            else
                CommentFrame(C.LANGUAGE_UNDETERMINED, id, value.toString())
        }
        Log.w(TAG, "Failed to parse uint8 attribute: " + Mp4Box.getBoxTypeString(type))
        return null
    }

    private fun parseIntegerAttribute(data: ParsableByteArray): Int {
        val atomSize: Int = data.readInt()
        val atomType: Int = data.readInt()
        if (atomType == Mp4Box.TYPE_data) {
            data.skipBytes(8) // version (1), flags (3), empty (4)
            when (atomSize - 16) {
                1 -> return data.readUnsignedByte()
                2 -> return data.readUnsignedShort()
                3 -> return data.readUnsignedInt24()
                4 -> if ((data.peekUnsignedByte() and 0x80) == 0) {
                    return data.readUnsignedIntToInt()
                }
            }
        }
        Log.w(TAG, "Failed to parse data atom to int")
        return -1
    }

    private fun parseIndexAndCountAttribute(
        type: Int, attributeName: String?, data: ParsableByteArray
    ): TextInformationFrame? {
        val atomSize: Int = data.readInt()
        val atomType: Int = data.readInt()
        if (atomType == Mp4Box.TYPE_data && atomSize >= 22) {
            data.skipBytes(10) // version (1), flags (3), empty (4), empty (2)
            val index: Int = data.readUnsignedShort()
            if (index > 0) {
                var value = "" + index
                val count: Int = data.readUnsignedShort()
                if (count > 0) {
                    value += "/" + count
                }
                return TextInformationFrame(
                    attributeName,  null, listOf(value)
                )
            }
        }
        Log.w(TAG, "Failed to parse index/count attribute: " + Mp4Box.getBoxTypeString(type))
        return null
    }

    private fun parseStandardGenreAttribute(data: ParsableByteArray): TextInformationFrame? {
        val genreCode = parseIntegerAttribute(data)
        // ID3 tags are zero-indexed, but MP4 gnre codes are 1-indexed (the list of genres is otherwise
        // the same).
        val genreString: String? = Id3Util.resolveV1Genre(genreCode - 1)
        if (genreString != null) {
            return TextInformationFrame(
                "TCON",  null, listOf(genreString)
            )
        }
        Log.w(TAG, "Failed to parse standard genre code")
        return null
    }

    private fun parseCoverArt(data: ParsableByteArray): ApicFrame? {
        val atomSize: Int = data.readInt()
        val atomType: Int = data.readInt()
        if (atomType == Mp4Box.TYPE_data) {
            val fullVersionInt: Int = data.readInt()
            val flags = BoxParser.parseFullBoxFlags(fullVersionInt)
            val mimeType = if (flags == 13) "image/jpeg" else if (flags == 14) "image/png" else null
            if (mimeType == null) {
                Log.w(TAG, "Unrecognized cover art flags: " + flags)
                return null
            }
            data.skipBytes(4) // empty (4)
            val pictureData = ByteArray(atomSize - 16)
            data.readBytes(pictureData, 0, pictureData.size)
            return ApicFrame(
                mimeType,  
                null,  
                PICTURE_TYPE_FRONT_COVER,
                pictureData
            )
        }
        Log.w(TAG, "Failed to parse cover art attribute")
        return null
    }

    private fun parseInternalAttribute(data: ParsableByteArray, endPosition: Int): Id3Frame? {
        var domain: String? = null
        var name: String? = null
        var dataAtomPosition = -1
        var dataAtomSize = -1
        while (data.getPosition() < endPosition) {
            val atomPosition: Int = data.getPosition()
            val atomSize: Int = data.readInt()
            val atomType: Int = data.readInt()
            data.skipBytes(4) // version (1), flags (3)
            if (atomType == Mp4Box.TYPE_mean) {
                domain = data.readNullTerminatedString(atomSize - 12)
            } else if (atomType == Mp4Box.TYPE_name) {
                name = data.readNullTerminatedString(atomSize - 12)
            } else {
                if (atomType == Mp4Box.TYPE_data) {
                    dataAtomPosition = atomPosition
                    dataAtomSize = atomSize
                }
                data.skipBytes(atomSize - 12)
            }
        }
        if (domain == null || name == null || dataAtomPosition == -1) {
            return null
        }
        data.setPosition(dataAtomPosition)
        data.skipBytes(16) // size (4), type (4), version (1), flags (3), empty (4)
        val value: String = data.readNullTerminatedString(dataAtomSize - 16)
        return InternalFrame(domain, name, value)
    }
}
