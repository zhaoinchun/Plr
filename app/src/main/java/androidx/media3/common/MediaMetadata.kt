package androidx.media3.common

import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.util.Objects

data class MediaMetadata(
    var title: CharSequence? = null,
    var artist: CharSequence? = null,
    var albumTitle: CharSequence? = null,
    var albumArtist: CharSequence? = null,
    val displayTitle: CharSequence? = null,
    val subtitle: CharSequence? = null,
    var description: CharSequence? = null,
    val durationMs: Long? = null,
    val userRating: Rating? = null,
    val overallRating: Rating? = null,
    var artworkData: ByteArray? = null,
    var bundleableArtworkData: BundleableByteArray? = null,
    var artworkDataType: Int? = null,
    var artworkUri: Uri? = null,
    var trackNumber: Int? = null,
    var totalTrackCount: Int? = null,
    var folderType: Int? = null,
    var isBrowsable: Boolean? = null,
    val isPlayable: Boolean? = null,
    var recordingYear: Int = 0,
    var recordingMonth: Int = 0,
    var recordingDay: Int = 0,
    var releaseYear: Int = 0,
    var releaseMonth: Int = 0,
    var releaseDay: Int = 0,
    var writer: CharSequence? = null,
    val author: CharSequence? = null,
    var composer: CharSequence? = null,
    var conductor: CharSequence? = null,
    var discSubtitle: CharSequence? = null,
    var discNumber: Int = 0,
    var totalDiscCount: Int = 0,
    var genre: CharSequence? = null,
    val compilation: CharSequence? = null,
    var station: CharSequence? = null,
    var mediaType: Int = 0,
    val extras: Bundle? = null,
    var supportedCommands: List<String> = listOf(),
) {

    init {
        // Handle compatibility for deprecated fields.
        var isBrowsable = isBrowsable
        var folderType = folderType
        var mediaType = mediaType
        if (isBrowsable != null) {
            if (!isBrowsable) {
                folderType = FOLDER_TYPE_NONE
            } else if (folderType == null || folderType == FOLDER_TYPE_NONE) {
                folderType =
                    if (mediaType != null) getFolderTypeFromMediaType(mediaType) else FOLDER_TYPE_MIXED
            }
        } else if (folderType != null) {
            isBrowsable = folderType != FOLDER_TYPE_NONE
            if (isBrowsable && mediaType == null) {
                mediaType = getMediaTypeFromFolderType(folderType)
            }
        }
        this.isBrowsable = isBrowsable
        this.folderType = folderType
        this.mediaType = mediaType
    }

    @Suppress("deprecation") // Bundling deprecated fields.
    fun toBundle(interfaceVersion: Int): Bundle {
        val bundle = Bundle()
        if (title != null) {
            bundle.putCharSequence(FIELD_TITLE, title)
        }
        if (artist != null) {
            bundle.putCharSequence(FIELD_ARTIST, artist)
        }
        if (albumTitle != null) {
            bundle.putCharSequence(FIELD_ALBUM_TITLE, albumTitle)
        }
        if (albumArtist != null) {
            bundle.putCharSequence(FIELD_ALBUM_ARTIST, albumArtist)
        }
        if (displayTitle != null) {
            bundle.putCharSequence(FIELD_DISPLAY_TITLE, displayTitle)
        }
        if (subtitle != null) {
            bundle.putCharSequence(FIELD_SUBTITLE, subtitle)
        }
        if (description != null) {
            bundle.putCharSequence(FIELD_DESCRIPTION, description)
        }
        if (durationMs != null) {
            bundle.putLong(FIELD_DURATION_MS, durationMs!!)
        }
        if (artworkData != null) {
            if (interfaceVersion >= 9) {
                if (bundleableArtworkData == null) {
                    bundleableArtworkData = BundleableByteArray(artworkData!!)
                }
                bundle.putBundle(FIELD_ARTWORK_DATA, bundleableArtworkData!!.toBundle())
            } else if (artworkData!!.size <= LEGACY_ARTWORK_DATA_ARRAY_SIZE_LIMIT) {
                bundle.putByteArray(FIELD_ARTWORK_DATA, artworkData)
            }
        }
        if (artworkUri != null) {
            bundle.putParcelable(FIELD_ARTWORK_URI, artworkUri)
        }
        if (writer != null) {
            bundle.putCharSequence(FIELD_WRITER, writer)
        }
        if (composer != null) {
            bundle.putCharSequence(FIELD_COMPOSER, composer)
        }
        if (conductor != null) {
            bundle.putCharSequence(FIELD_CONDUCTOR, conductor)
        }
        if (genre != null) {
            bundle.putCharSequence(FIELD_GENRE, genre)
        }
        if (compilation != null) {
            bundle.putCharSequence(FIELD_COMPILATION, compilation)
        }
        if (station != null) {
            bundle.putCharSequence(FIELD_STATION, station)
        }
        if (userRating != null) {
            bundle.putBundle(FIELD_USER_RATING, userRating.toBundle())
        }
        if (overallRating != null) {
            bundle.putBundle(FIELD_OVERALL_RATING, overallRating.toBundle())
        }
        if (trackNumber != null) {
            bundle.putInt(FIELD_TRACK_NUMBER, trackNumber!!)
        }
        if (totalTrackCount != null) {
            bundle.putInt(FIELD_TOTAL_TRACK_COUNT, totalTrackCount!!)
        }
        if (folderType != null) {
            bundle.putInt(FIELD_FOLDER_TYPE, folderType!!)
        }
        if (isBrowsable != null) {
            bundle.putBoolean(FIELD_IS_BROWSABLE, isBrowsable!!)
        }
        if (isPlayable != null) {
            bundle.putBoolean(FIELD_IS_PLAYABLE, isPlayable)
        }
        if (recordingYear != null) {
            bundle.putInt(FIELD_RECORDING_YEAR, recordingYear)
        }
        if (recordingMonth != null) {
            bundle.putInt(FIELD_RECORDING_MONTH, recordingMonth)
        }
        if (recordingDay != null) {
            bundle.putInt(FIELD_RECORDING_DAY, recordingDay)
        }
        if (releaseYear != null) {
            bundle.putInt(FIELD_RELEASE_YEAR, releaseYear)
        }
        if (releaseMonth != null) {
            bundle.putInt(FIELD_RELEASE_MONTH, releaseMonth)
        }
        if (releaseDay != null) {
            bundle.putInt(FIELD_RELEASE_DAY, releaseDay)
        }
        if (discNumber != null) {
            bundle.putInt(FIELD_DISC_NUMBER, discNumber)
        }
        if (discSubtitle != null) {
            bundle.putCharSequence(FIELD_DISC_SUBTITLE, discSubtitle)
        }
        if (totalDiscCount != null) {
            bundle.putInt(FIELD_TOTAL_DISC_COUNT, totalDiscCount)
        }
        if (artworkDataType != null) {
            bundle.putInt(FIELD_ARTWORK_DATA_TYPE, artworkDataType!!)
        }
        if (mediaType != null) {
            bundle.putInt(FIELD_MEDIA_TYPE, mediaType)
        }
        if (!supportedCommands.isEmpty()) {
            bundle.putStringArrayList(
                FIELD_SUPPORTED_COMMANDS,
                ArrayList<String?>(supportedCommands)
            )
        }
        if (extras != null) {
            bundle.putBundle(FIELD_EXTRAS, extras)
        }
        return bundle
    }

    fun maybeSetArtworkData(
        artworkData: ByteArray,
        artworkDataType: Int
    ) {
        if (this.artworkData == null || artworkDataType == PICTURE_TYPE_FRONT_COVER || (this.artworkDataType != PICTURE_TYPE_FRONT_COVER)) {
            this.artworkData = artworkData.clone()
            this.bundleableArtworkData = null
            this.artworkDataType = artworkDataType
        }
    }

    fun setArtworkData(
        artworkData: ByteArray?, artworkDataType: Int?
    ) {
        this.artworkData = artworkData?.clone()
        this.bundleableArtworkData = null
        this.artworkDataType = artworkDataType
    }

    fun populateFromMetadata(metadata: Metadata) {
        for (i in 0..<metadata.length()) {
            val entry = metadata.get(i)
            entry.populateMediaMetadata(this)
        }
    }

    fun populateFromMetadata(metadataList: List<Metadata>) {
        for (i in metadataList.indices) {
            val metadata = metadataList[i]
            for (j in 0..<metadata.length()) {
                val entry = metadata.get(j)
                entry.populateMediaMetadata(this)
            }
        }
    }

    fun populate(mediaMetadata: MediaMetadata) {
        if (mediaMetadata.artworkUri != null || mediaMetadata.artworkData != null) {
            artworkUri = mediaMetadata.artworkUri
            setArtworkData(mediaMetadata.artworkData, mediaMetadata.artworkDataType)
            bundleableArtworkData = mediaMetadata.bundleableArtworkData
        }
        if (!mediaMetadata.supportedCommands.isEmpty()) {
            supportedCommands = mediaMetadata.supportedCommands
        }
    }

    companion object {
        const val MEDIA_TYPE_MIXED: Int = 0
        const val MEDIA_TYPE_MUSIC: Int = 1
        const val MEDIA_TYPE_AUDIO_BOOK_CHAPTER: Int = 2
        const val MEDIA_TYPE_PODCAST_EPISODE: Int = 3
        const val MEDIA_TYPE_RADIO_STATION: Int = 4
        const val MEDIA_TYPE_NEWS: Int = 5
        const val MEDIA_TYPE_VIDEO: Int = 6
        const val MEDIA_TYPE_TRAILER: Int = 7
        const val MEDIA_TYPE_MOVIE: Int = 8
        const val MEDIA_TYPE_TV_SHOW: Int = 9
        const val MEDIA_TYPE_ALBUM: Int = 10
        const val MEDIA_TYPE_ARTIST: Int = 11
        const val MEDIA_TYPE_GENRE: Int = 12
        const val MEDIA_TYPE_PLAYLIST: Int = 13
        const val MEDIA_TYPE_YEAR: Int = 14
        const val MEDIA_TYPE_AUDIO_BOOK: Int = 15
        const val MEDIA_TYPE_PODCAST: Int = 16
        const val MEDIA_TYPE_TV_CHANNEL: Int = 17
        const val MEDIA_TYPE_TV_SERIES: Int = 18
        const val MEDIA_TYPE_TV_SEASON: Int = 19
        const val MEDIA_TYPE_FOLDER_MIXED: Int = 20
        const val MEDIA_TYPE_FOLDER_ALBUMS: Int = 21
        const val MEDIA_TYPE_FOLDER_ARTISTS: Int = 22
        const val MEDIA_TYPE_FOLDER_GENRES: Int = 23
        const val MEDIA_TYPE_FOLDER_PLAYLISTS: Int = 24
        const val MEDIA_TYPE_FOLDER_YEARS: Int = 25
        const val MEDIA_TYPE_FOLDER_AUDIO_BOOKS: Int = 26
        const val MEDIA_TYPE_FOLDER_PODCASTS: Int = 27
        const val MEDIA_TYPE_FOLDER_TV_CHANNELS: Int = 28
        const val MEDIA_TYPE_FOLDER_TV_SERIES: Int = 29
        const val MEDIA_TYPE_FOLDER_TV_SHOWS: Int = 30
        const val MEDIA_TYPE_FOLDER_RADIO_STATIONS: Int = 31
        const val MEDIA_TYPE_FOLDER_NEWS: Int = 32
        const val MEDIA_TYPE_FOLDER_VIDEOS: Int = 33
        const val MEDIA_TYPE_FOLDER_TRAILERS: Int = 34
        const val MEDIA_TYPE_FOLDER_MOVIES: Int = 35

        @Deprecated("")
        const val FOLDER_TYPE_NONE: Int = -1

        @Deprecated("")
        const val FOLDER_TYPE_MIXED: Int = 0

        @Deprecated("")
        const val FOLDER_TYPE_TITLES: Int = 1

        @Deprecated("")
        const val FOLDER_TYPE_ALBUMS: Int = 2

        @Deprecated("")
        const val FOLDER_TYPE_ARTISTS: Int = 3

        @Deprecated("")
        const val FOLDER_TYPE_GENRES: Int = 4

        @Deprecated("")
        const val FOLDER_TYPE_PLAYLISTS: Int = 5

        @Deprecated("")
        const val FOLDER_TYPE_YEARS: Int = 6

        const val PICTURE_TYPE_OTHER: Int = 0x00
        const val PICTURE_TYPE_FILE_ICON: Int = 0x01
        const val PICTURE_TYPE_FILE_ICON_OTHER: Int = 0x02
        const val PICTURE_TYPE_FRONT_COVER: Int = 0x03
        const val PICTURE_TYPE_BACK_COVER: Int = 0x04
        const val PICTURE_TYPE_LEAFLET_PAGE: Int = 0x05
        const val PICTURE_TYPE_MEDIA: Int = 0x06
        const val PICTURE_TYPE_LEAD_ARTIST_PERFORMER: Int = 0x07
        const val PICTURE_TYPE_ARTIST_PERFORMER: Int = 0x08
        const val PICTURE_TYPE_CONDUCTOR: Int = 0x09
        const val PICTURE_TYPE_BAND_ORCHESTRA: Int = 0x0A
        const val PICTURE_TYPE_COMPOSER: Int = 0x0B
        const val PICTURE_TYPE_LYRICIST: Int = 0x0C
        const val PICTURE_TYPE_RECORDING_LOCATION: Int = 0x0D
        const val PICTURE_TYPE_DURING_RECORDING: Int = 0x0E
        const val PICTURE_TYPE_DURING_PERFORMANCE: Int = 0x0F
        const val PICTURE_TYPE_MOVIE_VIDEO_SCREEN_CAPTURE: Int = 0x10
        const val PICTURE_TYPE_A_BRIGHT_COLORED_FISH: Int = 0x11
        const val PICTURE_TYPE_ILLUSTRATION: Int = 0x12
        const val PICTURE_TYPE_BAND_ARTIST_LOGO: Int = 0x13
        const val PICTURE_TYPE_PUBLISHER_STUDIO_LOGO: Int = 0x14
        val EMPTY: MediaMetadata = MediaMetadata()
        val FIELD_TITLE: String = Util.intToStringMaxRadix(0)
        val FIELD_ARTIST: String = Util.intToStringMaxRadix(1)
        val FIELD_ALBUM_TITLE: String = Util.intToStringMaxRadix(2)
        val FIELD_ALBUM_ARTIST: String = Util.intToStringMaxRadix(3)
        val FIELD_DISPLAY_TITLE: String = Util.intToStringMaxRadix(4)
        val FIELD_SUBTITLE: String = Util.intToStringMaxRadix(5)
        val FIELD_DESCRIPTION: String = Util.intToStringMaxRadix(6)

        // 7 is reserved to maintain backward compatibility for a previously defined field.
        val FIELD_USER_RATING: String = Util.intToStringMaxRadix(8)
        val FIELD_OVERALL_RATING: String = Util.intToStringMaxRadix(9)
        val FIELD_ARTWORK_DATA: String = Util.intToStringMaxRadix(10)
        val FIELD_ARTWORK_URI: String = Util.intToStringMaxRadix(11)
        val FIELD_TRACK_NUMBER: String = Util.intToStringMaxRadix(12)
        val FIELD_TOTAL_TRACK_COUNT: String = Util.intToStringMaxRadix(13)
        val FIELD_FOLDER_TYPE: String = Util.intToStringMaxRadix(14)
        val FIELD_IS_PLAYABLE: String = Util.intToStringMaxRadix(15)
        val FIELD_RECORDING_YEAR: String = Util.intToStringMaxRadix(16)
        val FIELD_RECORDING_MONTH: String = Util.intToStringMaxRadix(17)
        val FIELD_RECORDING_DAY: String = Util.intToStringMaxRadix(18)
        val FIELD_RELEASE_YEAR: String = Util.intToStringMaxRadix(19)
        val FIELD_RELEASE_MONTH: String = Util.intToStringMaxRadix(20)
        val FIELD_RELEASE_DAY: String = Util.intToStringMaxRadix(21)
        val FIELD_WRITER: String = Util.intToStringMaxRadix(22)
        val FIELD_COMPOSER: String = Util.intToStringMaxRadix(23)
        val FIELD_CONDUCTOR: String = Util.intToStringMaxRadix(24)
        val FIELD_DISC_NUMBER: String = Util.intToStringMaxRadix(25)
        val FIELD_TOTAL_DISC_COUNT: String = Util.intToStringMaxRadix(26)
        val FIELD_GENRE: String = Util.intToStringMaxRadix(27)
        val FIELD_COMPILATION: String = Util.intToStringMaxRadix(28)
        val FIELD_ARTWORK_DATA_TYPE: String = Util.intToStringMaxRadix(29)
        val FIELD_STATION: String = Util.intToStringMaxRadix(30)
        val FIELD_MEDIA_TYPE: String = Util.intToStringMaxRadix(31)
        val FIELD_IS_BROWSABLE: String = Util.intToStringMaxRadix(32)
        val FIELD_DURATION_MS: String = Util.intToStringMaxRadix(33)
        val FIELD_SUPPORTED_COMMANDS: String = Util.intToStringMaxRadix(34)
        val FIELD_DISC_SUBTITLE: String = Util.intToStringMaxRadix(35)
        val FIELD_EXTRAS: String = Util.intToStringMaxRadix(1000)

        // Use a fairly lenient threshold for sending byte array to legacy processes that don't support
        // BundleableByteArray yet. This is because most practical compressed artwork data is larger
        // than C.SUGGESTED_MAX_IPC_SIZE. We still need to enforce a maximum to stay well under the global
        // hard limit of 1MB across of concurrent binder transactions to this process.
        const val LEGACY_ARTWORK_DATA_ARRAY_SIZE_LIMIT = 500000

        fun fromBundle(bundle: Bundle, interfaceVersion: Int): MediaMetadata {
            var artworkData: ByteArray? = null
            var artworkDataType: Int? = null
            var userRating: Rating? = null
            var overallRating: Rating? = null
            var durationMs: Long? = null
            var recordingMonth = 0
            var discNumber = 0
            var totalDiscCount = 0
            var recordingDay = 0
            var mediaType = 0
            var releaseYear = 0
            var releaseMonth = 0
            var releaseDay = 0
            var trackNumber = 0
            var totalTrackCount = 0
            var folderType = 0
            var isBrowsable: Boolean? = null
            var isPlayable: Boolean? = null
            var recordingYear = 0

            if (bundle.containsKey(FIELD_ARTWORK_DATA)) {
                artworkDataType =
                    if (bundle.containsKey(FIELD_ARTWORK_DATA_TYPE))
                        bundle.getInt(FIELD_ARTWORK_DATA_TYPE)
                    else
                        null
                if (interfaceVersion >= 9) {
                    val artworkDataBundle = bundle.getBundle(FIELD_ARTWORK_DATA)
                    if (artworkDataBundle != null) {
                        artworkData = BundleableByteArray.fromBundle(artworkDataBundle)
                    }
                } else {
                    artworkData = bundle.getByteArray(FIELD_ARTWORK_DATA)
                }
            }
            if (bundle.containsKey(FIELD_USER_RATING)) {
                val fieldBundle = bundle.getBundle(FIELD_USER_RATING)
                if (fieldBundle != null) {
                    userRating = Rating.fromBundle(fieldBundle)
                }
            }
            if (bundle.containsKey(FIELD_OVERALL_RATING)) {
                val fieldBundle = bundle.getBundle(FIELD_OVERALL_RATING)
                if (fieldBundle != null) {
                    overallRating = Rating.fromBundle(fieldBundle)
                }
            }
            if (bundle.containsKey(FIELD_DURATION_MS)) {
                durationMs = bundle.getLong(FIELD_DURATION_MS)
            }
            if (bundle.containsKey(FIELD_TRACK_NUMBER)) {
                trackNumber = bundle.getInt(FIELD_TRACK_NUMBER)
            }
            if (bundle.containsKey(FIELD_TOTAL_TRACK_COUNT)) {
                totalTrackCount = bundle.getInt(FIELD_TOTAL_TRACK_COUNT)
            }
            if (bundle.containsKey(FIELD_FOLDER_TYPE)) {
                folderType = bundle.getInt(FIELD_FOLDER_TYPE)
            }
            if (bundle.containsKey(FIELD_IS_BROWSABLE)) {
                isBrowsable = bundle.getBoolean(FIELD_IS_BROWSABLE)
            }
            if (bundle.containsKey(FIELD_IS_PLAYABLE)) {
                isPlayable = bundle.getBoolean(FIELD_IS_PLAYABLE)
            }
            if (bundle.containsKey(FIELD_RECORDING_YEAR)) {
                recordingYear = bundle.getInt(FIELD_RECORDING_YEAR)
            }
            if (bundle.containsKey(FIELD_RECORDING_MONTH)) {
                recordingMonth = bundle.getInt(FIELD_RECORDING_MONTH)
            }
            if (bundle.containsKey(FIELD_RECORDING_DAY)) {
                recordingDay = bundle.getInt(FIELD_RECORDING_DAY)
            }
            if (bundle.containsKey(FIELD_RELEASE_YEAR)) {
                releaseYear = bundle.getInt(FIELD_RELEASE_YEAR)
            }
            if (bundle.containsKey(FIELD_RELEASE_MONTH)) {
                releaseMonth = bundle.getInt(FIELD_RELEASE_MONTH)
            }
            if (bundle.containsKey(FIELD_RELEASE_DAY)) {
                releaseDay = bundle.getInt(FIELD_RELEASE_DAY)
            }
            if (bundle.containsKey(FIELD_DISC_NUMBER)) {
                discNumber = bundle.getInt(FIELD_DISC_NUMBER)
            }
            if (bundle.containsKey(FIELD_TOTAL_DISC_COUNT)) {
                totalDiscCount = bundle.getInt(FIELD_TOTAL_DISC_COUNT)
            }
            if (bundle.containsKey(FIELD_MEDIA_TYPE)) {
                mediaType = bundle.getInt(FIELD_MEDIA_TYPE)
            }
            val supportedCommands = bundle.getStringArrayList(FIELD_SUPPORTED_COMMANDS) ?: listOf()
            return MediaMetadata(
                title = bundle.getCharSequence(FIELD_TITLE),
                artist = bundle.getCharSequence(FIELD_ARTIST),
                albumTitle = bundle.getCharSequence(FIELD_ALBUM_TITLE),
                albumArtist = bundle.getCharSequence(FIELD_ALBUM_ARTIST),
                subtitle = bundle.getCharSequence(FIELD_SUBTITLE),
                description = bundle.getCharSequence(FIELD_DESCRIPTION),
                artworkUri = bundle.getParcelable<Uri?>(FIELD_ARTWORK_URI),
                writer = bundle.getCharSequence(FIELD_WRITER),
                composer = bundle.getCharSequence(FIELD_COMPOSER),
                conductor = bundle.getCharSequence(FIELD_CONDUCTOR),
                discSubtitle = bundle.getCharSequence(FIELD_DISC_SUBTITLE),
                genre = bundle.getCharSequence(FIELD_GENRE),
                compilation = bundle.getCharSequence(FIELD_COMPILATION),
                extras = Util.convertToNullIfInvalid(bundle.getBundle(FIELD_EXTRAS)),
                artworkData = artworkData,
                artworkDataType = artworkDataType,
                userRating = userRating,
                overallRating = overallRating,
                durationMs = durationMs,
                discNumber = discNumber,
                totalDiscCount = totalDiscCount,
                recordingYear = recordingYear,
                recordingMonth = recordingMonth,
                recordingDay = recordingDay,
                mediaType = mediaType,
                releaseYear = releaseYear,
                releaseMonth = releaseMonth,
                releaseDay = releaseDay,
                trackNumber = trackNumber,
                totalTrackCount = totalTrackCount,
                folderType = folderType,
                isBrowsable = isBrowsable,
                isPlayable = isPlayable,
                supportedCommands = supportedCommands
            )
        }

        @Suppress("deprecation") // Converting deprecated field.
        
        fun getFolderTypeFromMediaType(mediaType: Int): Int {
            when (mediaType) {
                MEDIA_TYPE_ALBUM, MEDIA_TYPE_ARTIST, MEDIA_TYPE_AUDIO_BOOK, MEDIA_TYPE_AUDIO_BOOK_CHAPTER, MEDIA_TYPE_FOLDER_MOVIES, MEDIA_TYPE_FOLDER_NEWS, MEDIA_TYPE_FOLDER_RADIO_STATIONS, MEDIA_TYPE_FOLDER_TRAILERS, MEDIA_TYPE_FOLDER_VIDEOS, MEDIA_TYPE_GENRE, MEDIA_TYPE_MOVIE, MEDIA_TYPE_MUSIC, MEDIA_TYPE_NEWS, MEDIA_TYPE_PLAYLIST, MEDIA_TYPE_PODCAST, MEDIA_TYPE_PODCAST_EPISODE, MEDIA_TYPE_RADIO_STATION, MEDIA_TYPE_TRAILER, MEDIA_TYPE_TV_CHANNEL, MEDIA_TYPE_TV_SEASON, MEDIA_TYPE_TV_SERIES, MEDIA_TYPE_TV_SHOW, MEDIA_TYPE_VIDEO, MEDIA_TYPE_YEAR -> return FOLDER_TYPE_TITLES
                MEDIA_TYPE_FOLDER_ALBUMS -> return FOLDER_TYPE_ALBUMS
                MEDIA_TYPE_FOLDER_ARTISTS -> return FOLDER_TYPE_ARTISTS
                MEDIA_TYPE_FOLDER_GENRES -> return FOLDER_TYPE_GENRES
                MEDIA_TYPE_FOLDER_PLAYLISTS -> return FOLDER_TYPE_PLAYLISTS
                MEDIA_TYPE_FOLDER_YEARS -> return FOLDER_TYPE_YEARS
                MEDIA_TYPE_FOLDER_AUDIO_BOOKS, MEDIA_TYPE_FOLDER_MIXED, MEDIA_TYPE_FOLDER_TV_CHANNELS, MEDIA_TYPE_FOLDER_TV_SERIES, MEDIA_TYPE_FOLDER_TV_SHOWS, MEDIA_TYPE_FOLDER_PODCASTS, MEDIA_TYPE_MIXED -> return FOLDER_TYPE_MIXED
                else -> return FOLDER_TYPE_MIXED
            }
        }

        @Suppress("deprecation") // Converting deprecated field.
        fun getMediaTypeFromFolderType(folderType: Int): Int {
            when (folderType) {
                FOLDER_TYPE_ALBUMS -> return MEDIA_TYPE_FOLDER_ALBUMS
                FOLDER_TYPE_ARTISTS -> return MEDIA_TYPE_FOLDER_ARTISTS
                FOLDER_TYPE_GENRES -> return MEDIA_TYPE_FOLDER_GENRES
                FOLDER_TYPE_PLAYLISTS -> return MEDIA_TYPE_FOLDER_PLAYLISTS
                FOLDER_TYPE_TITLES -> return MEDIA_TYPE_MIXED
                FOLDER_TYPE_YEARS -> return MEDIA_TYPE_FOLDER_YEARS
                FOLDER_TYPE_MIXED, FOLDER_TYPE_NONE -> return MEDIA_TYPE_FOLDER_MIXED
                else -> return MEDIA_TYPE_FOLDER_MIXED
            }
        }
    }
}
