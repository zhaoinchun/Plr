package androidx.media3.common

import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.util.Objects

class MediaMetadata internal constructor(builder: Builder) {

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [MEDIA_TYPE_MIXED, MEDIA_TYPE_MUSIC, MEDIA_TYPE_AUDIO_BOOK_CHAPTER, MEDIA_TYPE_PODCAST_EPISODE, MEDIA_TYPE_RADIO_STATION, MEDIA_TYPE_NEWS, MEDIA_TYPE_VIDEO, MEDIA_TYPE_TRAILER, MEDIA_TYPE_MOVIE, MEDIA_TYPE_TV_SHOW, MEDIA_TYPE_ALBUM, MEDIA_TYPE_ARTIST, MEDIA_TYPE_GENRE, MEDIA_TYPE_PLAYLIST, MEDIA_TYPE_YEAR, MEDIA_TYPE_AUDIO_BOOK, MEDIA_TYPE_PODCAST, MEDIA_TYPE_TV_CHANNEL, MEDIA_TYPE_TV_SERIES, MEDIA_TYPE_TV_SEASON, MEDIA_TYPE_FOLDER_MIXED, MEDIA_TYPE_FOLDER_ALBUMS, MEDIA_TYPE_FOLDER_ARTISTS, MEDIA_TYPE_FOLDER_GENRES, MEDIA_TYPE_FOLDER_PLAYLISTS, MEDIA_TYPE_FOLDER_YEARS, MEDIA_TYPE_FOLDER_AUDIO_BOOKS, MEDIA_TYPE_FOLDER_PODCASTS, MEDIA_TYPE_FOLDER_TV_CHANNELS, MEDIA_TYPE_FOLDER_TV_SERIES, MEDIA_TYPE_FOLDER_TV_SHOWS, MEDIA_TYPE_FOLDER_RADIO_STATIONS, MEDIA_TYPE_FOLDER_NEWS, MEDIA_TYPE_FOLDER_VIDEOS, MEDIA_TYPE_FOLDER_TRAILERS, MEDIA_TYPE_FOLDER_MOVIES]
    )
    annotation class MediaType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @Deprecated("")
    @Suppress("deprecation") // Defining deprecated constants.
    @IntDef(
        value = [FOLDER_TYPE_NONE, FOLDER_TYPE_MIXED, FOLDER_TYPE_TITLES, FOLDER_TYPE_ALBUMS, FOLDER_TYPE_ARTISTS, FOLDER_TYPE_GENRES, FOLDER_TYPE_PLAYLISTS, FOLDER_TYPE_YEARS]
    )
    annotation class FolderType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [PICTURE_TYPE_OTHER, PICTURE_TYPE_FILE_ICON, PICTURE_TYPE_FILE_ICON_OTHER, PICTURE_TYPE_FRONT_COVER, PICTURE_TYPE_BACK_COVER, PICTURE_TYPE_LEAFLET_PAGE, PICTURE_TYPE_MEDIA, PICTURE_TYPE_LEAD_ARTIST_PERFORMER, PICTURE_TYPE_ARTIST_PERFORMER, PICTURE_TYPE_CONDUCTOR, PICTURE_TYPE_BAND_ORCHESTRA, PICTURE_TYPE_COMPOSER, PICTURE_TYPE_LYRICIST, PICTURE_TYPE_RECORDING_LOCATION, PICTURE_TYPE_DURING_RECORDING, PICTURE_TYPE_DURING_PERFORMANCE, PICTURE_TYPE_MOVIE_VIDEO_SCREEN_CAPTURE, PICTURE_TYPE_A_BRIGHT_COLORED_FISH, PICTURE_TYPE_ILLUSTRATION, PICTURE_TYPE_BAND_ARTIST_LOGO, PICTURE_TYPE_PUBLISHER_STUDIO_LOGO]
    )
    annotation class PictureType

    val title: CharSequence?
    val artist: CharSequence?
    val albumTitle: CharSequence?
    val albumArtist: CharSequence?
    val displayTitle: CharSequence?
    val subtitle: CharSequence?
    val description: CharSequence?
    val durationMs: Long?
    val userRating: Rating?
    val overallRating: Rating?
    val artworkData: ByteArray?
    internal var bundleableArtworkData: BundleableByteArray?

    @PictureType
    val artworkDataType: @PictureType Int?
    val artworkUri: Uri?
    val trackNumber: Int?
    val totalTrackCount: Int?
    @Suppress("deprecation") // Defining field of deprecated type.
    @FolderType
    @Deprecated(
        """Use {@link #isBrowsable} to indicate if an item is a browsable folder and use
        {@link #mediaType} to indicate the type of the folder."""
    )
    val folderType: @FolderType Int?
    val isBrowsable: Boolean?
    val isPlayable: Boolean?

    @Deprecated("Use {@link #recordingYear} instead.")
    val year: Int
    val recordingYear: Int
    val recordingMonth: Int
    val recordingDay: Int
    val releaseYear: Int
    val releaseMonth: Int
    val releaseDay: Int
    val writer: CharSequence?
    val author: CharSequence?
    val composer: CharSequence?
    val conductor: CharSequence?
    val discSubtitle: CharSequence?
    val discNumber: Int
    val totalDiscCount: Int
    val genre: CharSequence?
    val compilation: CharSequence?
    val station: CharSequence?
    val mediaType: @MediaType Int
    val extras: Bundle?

    val supportedCommands: List<String>

    fun buildUpon(): Builder {
        return MediaMetadata.Builder( this)
    }

    @Suppress("deprecation") // Comparing deprecated fields.
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val that = obj as MediaMetadata
        return TextUtils.equals(title, that.title)
                && TextUtils.equals(artist, that.artist)
                && TextUtils.equals(albumTitle, that.albumTitle)
                && TextUtils.equals(albumArtist, that.albumArtist)
                && TextUtils.equals(displayTitle, that.displayTitle)
                && TextUtils.equals(subtitle, that.subtitle)
                && TextUtils.equals(description, that.description)
                && durationMs == that.durationMs
                && userRating == that.userRating
                && overallRating == that.overallRating
                && artworkData.contentEquals(that.artworkData) && artworkDataType == that.artworkDataType
                && artworkUri == that.artworkUri
                && trackNumber == that.trackNumber
                && totalTrackCount == that.totalTrackCount
                && folderType == that.folderType
                && isBrowsable == that.isBrowsable
                && isPlayable == that.isPlayable
                && recordingYear == that.recordingYear
                && recordingMonth == that.recordingMonth
                && recordingDay == that.recordingDay
                && releaseYear == that.releaseYear
                && releaseMonth == that.releaseMonth
                && releaseDay == that.releaseDay
                && TextUtils.equals(writer, that.writer)
                && TextUtils.equals(composer, that.composer)
                && TextUtils.equals(conductor, that.conductor)
                && TextUtils.equals(discSubtitle, that.discSubtitle)
                && discNumber == that.discNumber
                && totalDiscCount == that.totalDiscCount
                && TextUtils.equals(genre, that.genre)
                && TextUtils.equals(compilation, that.compilation)
                && TextUtils.equals(station, that.station)
                && mediaType == that.mediaType
                && supportedCommands == that.supportedCommands
                && ((extras == null) == (that.extras == null))
    }

    @Suppress("deprecation") // Hashing deprecated fields.
    override fun hashCode(): Int {
        return Objects.hash(
            title,
            artist,
            albumTitle,
            albumArtist,
            displayTitle,
            subtitle,
            description,
            durationMs,
            userRating,
            overallRating,
            artworkData.contentHashCode(),
            artworkDataType,
            artworkUri,
            trackNumber,
            totalTrackCount,
            folderType,
            isBrowsable,
            isPlayable,
            recordingYear,
            recordingMonth,
            recordingDay,
            releaseYear,
            releaseMonth,
            releaseDay,
            writer,
            composer,
            conductor,
            discSubtitle,
            discNumber,
            totalDiscCount,
            genre,
            compilation,
            station,
            mediaType,
            extras == null,
            supportedCommands
        )
    }

    init {
        // Handle compatibility for deprecated fields.
        var isBrowsable = builder.isBrowsable
        var folderType = builder.folderType
        var mediaType = builder.mediaType
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
        this.title = builder.title
        this.artist = builder.artist
        this.albumTitle = builder.albumTitle
        this.albumArtist = builder.albumArtist
        this.displayTitle = builder.displayTitle
        this.subtitle = builder.subtitle
        this.description = builder.description
        this.durationMs = builder.durationMs
        this.userRating = builder.userRating
        this.overallRating = builder.overallRating
        this.artworkData = builder.artworkData
        this.bundleableArtworkData = builder.bundleableArtworkData
        this.artworkDataType = builder.artworkDataType
        this.artworkUri = builder.artworkUri
        this.trackNumber = builder.trackNumber
        this.totalTrackCount = builder.totalTrackCount
        this.folderType = folderType
        this.isBrowsable = isBrowsable
        this.isPlayable = builder.isPlayable
        this.year = builder.recordingYear
        this.recordingYear = builder.recordingYear
        this.recordingMonth = builder.recordingMonth
        this.recordingDay = builder.recordingDay
        this.releaseYear = builder.releaseYear
        this.releaseMonth = builder.releaseMonth
        this.releaseDay = builder.releaseDay
        this.writer = builder.writer
        this.author = builder.author
        this.composer = builder.composer
        this.conductor = builder.conductor
        this.discSubtitle = builder.discSubtitle
        this.discNumber = builder.discNumber
        this.totalDiscCount = builder.totalDiscCount
        this.genre = builder.genre
        this.compilation = builder.compilation
        this.station = builder.station
        this.mediaType = mediaType
        this.supportedCommands = builder.supportedCommands
        this.extras = builder.extras
    }

    @Deprecated("Use {@link #toBundle(int)} instead.")
    fun toBundle(): Bundle {
        return toBundle(MediaLibraryInfo.INTERFACE_VERSION)
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
            bundle.putLong(FIELD_DURATION_MS, durationMs)
        }
        if (artworkData != null) {
            if (interfaceVersion >= 9) {
                if (bundleableArtworkData == null) {
                    bundleableArtworkData = BundleableByteArray(artworkData)
                }
                bundle.putBundle(FIELD_ARTWORK_DATA, bundleableArtworkData!!.toBundle())
            } else if (artworkData.size <= LEGACY_ARTWORK_DATA_ARRAY_SIZE_LIMIT) {
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
            bundle.putInt(FIELD_TRACK_NUMBER, trackNumber)
        }
        if (totalTrackCount != null) {
            bundle.putInt(FIELD_TOTAL_TRACK_COUNT, totalTrackCount)
        }
        if (folderType != null) {
            bundle.putInt(FIELD_FOLDER_TYPE, folderType)
        }
        if (isBrowsable != null) {
            bundle.putBoolean(FIELD_IS_BROWSABLE, isBrowsable)
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
            bundle.putInt(FIELD_ARTWORK_DATA_TYPE, artworkDataType)
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
    class Builder {
        internal var title: CharSequence? = null
        internal var artist: CharSequence? = null
        internal var albumTitle: CharSequence? = null
        internal var albumArtist: CharSequence? = null
        internal var displayTitle: CharSequence? = null
        internal var subtitle: CharSequence? = null
        internal var description: CharSequence? = null
        internal var durationMs: Long? = null
        internal var userRating: Rating? = null
        internal var overallRating: Rating? = null
        internal var artworkData: ByteArray?=null
        internal var bundleableArtworkData: BundleableByteArray? = null

        @PictureType
        internal var artworkDataType: @PictureType Int? = null
        internal var artworkUri: Uri? = null
        internal var trackNumber: Int? = null
        internal var totalTrackCount: Int? = null

        @FolderType
        internal var folderType: @FolderType Int? = null

        internal var isBrowsable: Boolean? = null
        internal var isPlayable: Boolean? = null
        internal var recordingYear: Int=0
        internal var recordingMonth: Int=0
        internal var recordingDay: Int=0
        internal var releaseYear: Int=0
        internal var releaseMonth: Int=0
        internal var releaseDay: Int=0
        internal var writer: CharSequence? = null
        internal var author: CharSequence? = null
        internal var composer: CharSequence? = null
        internal var conductor: CharSequence? = null
        internal var discSubtitle: CharSequence? = null
        internal var discNumber: Int=0
        internal var totalDiscCount: Int=0
        internal var genre: CharSequence? = null
        internal var compilation: CharSequence? = null
        internal var station: CharSequence? = null
        internal var mediaType: @MediaType Int=0
        internal var extras: Bundle? = null
        internal var supportedCommands: List<String>

        constructor() {
            supportedCommands = listOf()
        }

        internal constructor(mediaMetadata: MediaMetadata) {
            this.title = mediaMetadata.title
            this.artist = mediaMetadata.artist
            this.albumTitle = mediaMetadata.albumTitle
            this.albumArtist = mediaMetadata.albumArtist
            this.displayTitle = mediaMetadata.displayTitle
            this.subtitle = mediaMetadata.subtitle
            this.description = mediaMetadata.description
            this.durationMs = mediaMetadata.durationMs
            this.userRating = mediaMetadata.userRating
            this.overallRating = mediaMetadata.overallRating
            this.artworkData = mediaMetadata.artworkData
            this.bundleableArtworkData = mediaMetadata.bundleableArtworkData
            this.artworkDataType = mediaMetadata.artworkDataType
            this.artworkUri = mediaMetadata.artworkUri
            this.trackNumber = mediaMetadata.trackNumber
            this.totalTrackCount = mediaMetadata.totalTrackCount
            this.folderType = mediaMetadata.folderType
            this.isBrowsable = mediaMetadata.isBrowsable
            this.isPlayable = mediaMetadata.isPlayable
            this.recordingYear = mediaMetadata.recordingYear
            this.recordingMonth = mediaMetadata.recordingMonth
            this.recordingDay = mediaMetadata.recordingDay
            this.releaseYear = mediaMetadata.releaseYear
            this.releaseMonth = mediaMetadata.releaseMonth
            this.releaseDay = mediaMetadata.releaseDay
            this.writer = mediaMetadata.writer
            this.author = mediaMetadata.author
            this.composer = mediaMetadata.composer
            this.conductor = mediaMetadata.conductor
            this.discNumber = mediaMetadata.discNumber
            this.discSubtitle = mediaMetadata.discSubtitle
            this.totalDiscCount = mediaMetadata.totalDiscCount
            this.genre = mediaMetadata.genre
            this.compilation = mediaMetadata.compilation
            this.station = mediaMetadata.station
            this.mediaType = mediaMetadata.mediaType
            this.supportedCommands = mediaMetadata.supportedCommands
            this.extras = mediaMetadata.extras
        }

        fun setTitle(title: CharSequence?): Builder {
            this.title = title
            return this
        }

        fun setArtist(artist: CharSequence?): Builder {
            this.artist = artist
            return this
        }

        fun setAlbumTitle(albumTitle: CharSequence?): Builder {
            this.albumTitle = albumTitle
            return this
        }

        fun setAlbumArtist(albumArtist: CharSequence?): Builder {
            this.albumArtist = albumArtist
            return this
        }

        fun setDisplayTitle(displayTitle: CharSequence?): Builder {
            this.displayTitle = displayTitle
            return this
        }

        fun setSubtitle(subtitle: CharSequence?): Builder {
            this.subtitle = subtitle
            return this
        }

        fun setDescription(description: CharSequence?): Builder {
            this.description = description
            return this
        }

        fun setDurationMs(durationMs: Long?): Builder {
            Preconditions.checkArgument(durationMs == null || durationMs >= 0)
            this.durationMs = durationMs
            return this
        }

        fun setUserRating(userRating: Rating?): Builder {
            this.userRating = userRating
            return this
        }

        fun setOverallRating(overallRating: Rating?): Builder {
            this.overallRating = overallRating
            return this
        }

        fun setArtworkData(
            artworkData: ByteArray?, @PictureType artworkDataType: @PictureType Int?
        ): Builder {
            this.artworkData = if (artworkData == null) null else artworkData.clone()
            this.bundleableArtworkData = null
            this.artworkDataType = artworkDataType
            return this
        }

        fun maybeSetArtworkData(
            artworkData: ByteArray,
            @PictureType artworkDataType: @PictureType Int
        ): Builder {
            if (this.artworkData == null || artworkDataType == PICTURE_TYPE_FRONT_COVER || (this.artworkDataType != PICTURE_TYPE_FRONT_COVER)) {
                this.artworkData = artworkData.clone()
                this.bundleableArtworkData = null
                this.artworkDataType = artworkDataType
            }
            return this
        }

        fun setArtworkUri(artworkUri: Uri?): Builder {
            this.artworkUri = artworkUri
            return this
        }

        fun setTrackNumber(trackNumber: Int?): Builder {
            this.trackNumber = trackNumber
            return this
        }

        fun setTotalTrackCount(totalTrackCount: Int?): Builder {
            this.totalTrackCount = totalTrackCount
            return this
        }

        @Deprecated("")
        fun setFolderType(@FolderType folderType: @FolderType Int?): Builder {
            this.folderType = folderType
            return this
        }

        fun setIsBrowsable(isBrowsable: Boolean?): Builder {
            this.isBrowsable = isBrowsable
            return this
        }

        fun setIsPlayable(isPlayable: Boolean?): Builder {
            this.isPlayable = isPlayable
            return this
        }

        fun setRecordingYear(recordingYear: Int): Builder {
            this.recordingYear = recordingYear
            return this
        }

        fun setRecordingMonth(
            @IntRange(from = 1, to = 12) recordingMonth: Int
        ): Builder {
            this.recordingMonth = recordingMonth
            return this
        }

        fun setRecordingDay(@IntRange(from = 1, to = 31) recordingDay: Int): Builder {
            this.recordingDay = recordingDay
            return this
        }

        fun setReleaseYear(releaseYear: Int): Builder {
            this.releaseYear = releaseYear
            return this
        }

        fun setReleaseMonth(@IntRange(from = 1, to = 12) releaseMonth: Int): Builder {
            this.releaseMonth = releaseMonth
            return this
        }

        fun setReleaseDay(@IntRange(from = 1, to = 31) releaseDay: Int): Builder {
            this.releaseDay = releaseDay
            return this
        }

        fun setWriter(writer: CharSequence?): Builder {
            this.writer = writer
            return this
        }

        fun setAuthor(author: CharSequence?): Builder {
            this.author = author
            return this
        }

        fun setComposer(composer: CharSequence?): Builder {
            this.composer = composer
            return this
        }

        fun setConductor(conductor: CharSequence?): Builder {
            this.conductor = conductor
            return this
        }

        fun setDiscSubtitle(discSubtitle: CharSequence?): Builder {
            this.discSubtitle = discSubtitle
            return this
        }

        fun setDiscNumber(discNumber: Int): Builder {
            this.discNumber = discNumber
            return this
        }

        fun setTotalDiscCount(totalDiscCount: Int): Builder {
            this.totalDiscCount = totalDiscCount
            return this
        }

        fun setGenre(genre: CharSequence?): Builder {
            this.genre = genre
            return this
        }

        fun setCompilation(compilation: CharSequence?): Builder {
            this.compilation = compilation
            return this
        }

        fun setStation(station: CharSequence?): Builder {
            this.station = station
            return this
        }

        fun setMediaType(mediaType: @MediaType Int): Builder {
            this.mediaType = mediaType
            return this
        }

        fun setExtras(extras: Bundle?): Builder {
            this.extras = extras
            return this
        }

        fun setSupportedCommands(supportedCommands: List<String>): Builder {
            this.supportedCommands = supportedCommands.toList()
            return this
        }

        fun populateFromMetadata(metadata: Metadata): Builder {
            for (i in 0..<metadata.length()) {
                val entry = metadata.get(i)
                entry.populateMediaMetadata(this)
            }
            return this
        }

        fun populateFromMetadata(metadataList: List<Metadata>): Builder {
            for (i in metadataList.indices) {
                val metadata = metadataList[i]
                for (j in 0..<metadata.length()) {
                    val entry = metadata.get(j)
                    entry.populateMediaMetadata(this)
                }
            }
            return this
        }

        fun populate(mediaMetadata: MediaMetadata?): Builder {
            if (mediaMetadata == null) {
                return this
            }
            if (mediaMetadata.title != null) {
                setTitle(mediaMetadata.title)
            }
            if (mediaMetadata.artist != null) {
                setArtist(mediaMetadata.artist)
            }
            if (mediaMetadata.albumTitle != null) {
                setAlbumTitle(mediaMetadata.albumTitle)
            }
            if (mediaMetadata.albumArtist != null) {
                setAlbumArtist(mediaMetadata.albumArtist)
            }
            if (mediaMetadata.displayTitle != null) {
                setDisplayTitle(mediaMetadata.displayTitle)
            }
            if (mediaMetadata.subtitle != null) {
                setSubtitle(mediaMetadata.subtitle)
            }
            if (mediaMetadata.description != null) {
                setDescription(mediaMetadata.description)
            }
            if (mediaMetadata.durationMs != null) {
                setDurationMs(mediaMetadata.durationMs)
            }
            if (mediaMetadata.userRating != null) {
                setUserRating(mediaMetadata.userRating)
            }
            if (mediaMetadata.overallRating != null) {
                setOverallRating(mediaMetadata.overallRating)
            }
            if (mediaMetadata.artworkUri != null || mediaMetadata.artworkData != null) {
                setArtworkUri(mediaMetadata.artworkUri)
                setArtworkData(mediaMetadata.artworkData, mediaMetadata.artworkDataType)
                bundleableArtworkData = mediaMetadata.bundleableArtworkData
            }
            if (mediaMetadata.trackNumber != null) {
                setTrackNumber(mediaMetadata.trackNumber)
            }
            if (mediaMetadata.totalTrackCount != null) {
                setTotalTrackCount(mediaMetadata.totalTrackCount)
            }
            if (mediaMetadata.folderType != null) {
                setFolderType(mediaMetadata.folderType)
            }
            if (mediaMetadata.isBrowsable != null) {
                setIsBrowsable(mediaMetadata.isBrowsable)
            }
            if (mediaMetadata.isPlayable != null) {
                setIsPlayable(mediaMetadata.isPlayable)
            }
            if (mediaMetadata.year != null) {
                setRecordingYear(mediaMetadata.year)
            }
            if (mediaMetadata.recordingYear != null) {
                setRecordingYear(mediaMetadata.recordingYear)
            }
            if (mediaMetadata.recordingMonth != null) {
                setRecordingMonth(mediaMetadata.recordingMonth)
            }
            if (mediaMetadata.recordingDay != null) {
                setRecordingDay(mediaMetadata.recordingDay)
            }
            if (mediaMetadata.releaseYear != null) {
                setReleaseYear(mediaMetadata.releaseYear)
            }
            if (mediaMetadata.releaseMonth != null) {
                setReleaseMonth(mediaMetadata.releaseMonth)
            }
            if (mediaMetadata.releaseDay != null) {
                setReleaseDay(mediaMetadata.releaseDay)
            }
            if (mediaMetadata.writer != null) {
                setWriter(mediaMetadata.writer)
            }
            if (mediaMetadata.composer != null) {
                setComposer(mediaMetadata.composer)
            }
            if (mediaMetadata.conductor != null) {
                setConductor(mediaMetadata.conductor)
            }
            if (mediaMetadata.discSubtitle != null) {
                setDiscSubtitle(mediaMetadata.discSubtitle)
            }
            if (mediaMetadata.discNumber != null) {
                setDiscNumber(mediaMetadata.discNumber)
            }
            if (mediaMetadata.totalDiscCount != null) {
                setTotalDiscCount(mediaMetadata.totalDiscCount)
            }
            if (mediaMetadata.genre != null) {
                setGenre(mediaMetadata.genre)
            }
            if (mediaMetadata.compilation != null) {
                setCompilation(mediaMetadata.compilation)
            }
            if (mediaMetadata.station != null) {
                setStation(mediaMetadata.station)
            }
            if (mediaMetadata.mediaType != null) {
                setMediaType(mediaMetadata.mediaType)
            }
            if (mediaMetadata.extras != null) {
                setExtras(mediaMetadata.extras)
            }

            if (!mediaMetadata.supportedCommands.isEmpty()) {
                setSupportedCommands(mediaMetadata.supportedCommands)
            }

            return this
        }

        fun build(): MediaMetadata {
            return MediaMetadata( this)
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
        val EMPTY: MediaMetadata = Builder().build()
        internal val FIELD_TITLE: String = Util.intToStringMaxRadix(0)
        internal val FIELD_ARTIST: String = Util.intToStringMaxRadix(1)
        internal val FIELD_ALBUM_TITLE: String = Util.intToStringMaxRadix(2)
        internal val FIELD_ALBUM_ARTIST: String = Util.intToStringMaxRadix(3)
        internal val FIELD_DISPLAY_TITLE: String = Util.intToStringMaxRadix(4)
        internal val FIELD_SUBTITLE: String = Util.intToStringMaxRadix(5)
        internal val FIELD_DESCRIPTION: String = Util.intToStringMaxRadix(6)

        // 7 is reserved to maintain backward compatibility for a previously defined field.
        internal val FIELD_USER_RATING: String = Util.intToStringMaxRadix(8)
        internal val FIELD_OVERALL_RATING: String = Util.intToStringMaxRadix(9)
        internal val FIELD_ARTWORK_DATA: String = Util.intToStringMaxRadix(10)
        internal val FIELD_ARTWORK_URI: String = Util.intToStringMaxRadix(11)
        internal val FIELD_TRACK_NUMBER: String = Util.intToStringMaxRadix(12)
        internal val FIELD_TOTAL_TRACK_COUNT: String = Util.intToStringMaxRadix(13)
        internal val FIELD_FOLDER_TYPE: String = Util.intToStringMaxRadix(14)
        internal val FIELD_IS_PLAYABLE: String = Util.intToStringMaxRadix(15)
        internal val FIELD_RECORDING_YEAR: String = Util.intToStringMaxRadix(16)
        internal val FIELD_RECORDING_MONTH: String = Util.intToStringMaxRadix(17)
        internal val FIELD_RECORDING_DAY: String = Util.intToStringMaxRadix(18)
        internal val FIELD_RELEASE_YEAR: String = Util.intToStringMaxRadix(19)
        internal val FIELD_RELEASE_MONTH: String = Util.intToStringMaxRadix(20)
        internal val FIELD_RELEASE_DAY: String = Util.intToStringMaxRadix(21)
        internal val FIELD_WRITER: String = Util.intToStringMaxRadix(22)
        internal val FIELD_COMPOSER: String = Util.intToStringMaxRadix(23)
        internal val FIELD_CONDUCTOR: String = Util.intToStringMaxRadix(24)
        internal val FIELD_DISC_NUMBER: String = Util.intToStringMaxRadix(25)
        internal val FIELD_TOTAL_DISC_COUNT: String = Util.intToStringMaxRadix(26)
        internal val FIELD_GENRE: String = Util.intToStringMaxRadix(27)
        internal val FIELD_COMPILATION: String = Util.intToStringMaxRadix(28)
        internal val FIELD_ARTWORK_DATA_TYPE: String = Util.intToStringMaxRadix(29)
        internal val FIELD_STATION: String = Util.intToStringMaxRadix(30)
        internal val FIELD_MEDIA_TYPE: String = Util.intToStringMaxRadix(31)
        internal val FIELD_IS_BROWSABLE: String = Util.intToStringMaxRadix(32)
        internal val FIELD_DURATION_MS: String = Util.intToStringMaxRadix(33)
        internal val FIELD_SUPPORTED_COMMANDS: String = Util.intToStringMaxRadix(34)
        internal val FIELD_DISC_SUBTITLE: String = Util.intToStringMaxRadix(35)
        internal val FIELD_EXTRAS: String = Util.intToStringMaxRadix(1000)

        // Use a fairly lenient threshold for sending byte array to legacy processes that don't support
        // BundleableByteArray yet. This is because most practical compressed artwork data is larger
        // than C.SUGGESTED_MAX_IPC_SIZE. We still need to enforce a maximum to stay well under the global
        // hard limit of 1MB across of concurrent binder transactions to this process.
        internal const val LEGACY_ARTWORK_DATA_ARRAY_SIZE_LIMIT = 500000

        @Deprecated("Use {@link #fromBundle(Bundle, int)}.")
        fun fromBundle(bundle: Bundle): MediaMetadata {
            return fromBundle(bundle, MediaLibraryInfo.INTERFACE_VERSION)
        }

        fun fromBundle(bundle: Bundle, interfaceVersion: Int): MediaMetadata {
            val builder = Builder()
            builder
                .setTitle(bundle.getCharSequence(FIELD_TITLE))
                .setArtist(bundle.getCharSequence(FIELD_ARTIST))
                .setAlbumTitle(bundle.getCharSequence(FIELD_ALBUM_TITLE))
                .setAlbumArtist(bundle.getCharSequence(FIELD_ALBUM_ARTIST))
                .setDisplayTitle(bundle.getCharSequence(FIELD_DISPLAY_TITLE))
                .setSubtitle(bundle.getCharSequence(FIELD_SUBTITLE))
                .setDescription(bundle.getCharSequence(FIELD_DESCRIPTION))
                .setArtworkUri(bundle.getParcelable<Uri?>(FIELD_ARTWORK_URI))
                .setWriter(bundle.getCharSequence(FIELD_WRITER))
                .setComposer(bundle.getCharSequence(FIELD_COMPOSER))
                .setConductor(bundle.getCharSequence(FIELD_CONDUCTOR))
                .setDiscSubtitle(bundle.getCharSequence(FIELD_DISC_SUBTITLE))
                .setGenre(bundle.getCharSequence(FIELD_GENRE))
                .setCompilation(bundle.getCharSequence(FIELD_COMPILATION))
                .setStation(bundle.getCharSequence(FIELD_STATION))
                .setExtras(Util.convertToNullIfInvalid(bundle.getBundle(FIELD_EXTRAS)))

            if (bundle.containsKey(FIELD_ARTWORK_DATA)) {
                val artworkDataType =
                    if (bundle.containsKey(FIELD_ARTWORK_DATA_TYPE))
                        bundle.getInt(FIELD_ARTWORK_DATA_TYPE)
                    else
                        null
                if (interfaceVersion >= 9) {
                    val artworkDataBundle = bundle.getBundle(FIELD_ARTWORK_DATA)
                    if (artworkDataBundle != null) {
                        builder.setArtworkData(
                            BundleableByteArray.fromBundle(artworkDataBundle), artworkDataType
                        )
                    }
                } else {
                    builder.setArtworkData(bundle.getByteArray(FIELD_ARTWORK_DATA), artworkDataType)
                }
            }

            if (bundle.containsKey(FIELD_USER_RATING)) {
                val fieldBundle = bundle.getBundle(FIELD_USER_RATING)
                if (fieldBundle != null) {
                    builder.setUserRating(Rating.fromBundle(fieldBundle))
                }
            }
            if (bundle.containsKey(FIELD_OVERALL_RATING)) {
                val fieldBundle = bundle.getBundle(FIELD_OVERALL_RATING)
                if (fieldBundle != null) {
                    builder.setOverallRating(Rating.fromBundle(fieldBundle))
                }
            }
            if (bundle.containsKey(FIELD_DURATION_MS)) {
                builder.setDurationMs(bundle.getLong(FIELD_DURATION_MS))
            }
            if (bundle.containsKey(FIELD_TRACK_NUMBER)) {
                builder.setTrackNumber(bundle.getInt(FIELD_TRACK_NUMBER))
            }
            if (bundle.containsKey(FIELD_TOTAL_TRACK_COUNT)) {
                builder.setTotalTrackCount(bundle.getInt(FIELD_TOTAL_TRACK_COUNT))
            }
            if (bundle.containsKey(FIELD_FOLDER_TYPE)) {
                builder.setFolderType(bundle.getInt(FIELD_FOLDER_TYPE))
            }
            if (bundle.containsKey(FIELD_IS_BROWSABLE)) {
                builder.setIsBrowsable(bundle.getBoolean(FIELD_IS_BROWSABLE))
            }
            if (bundle.containsKey(FIELD_IS_PLAYABLE)) {
                builder.setIsPlayable(bundle.getBoolean(FIELD_IS_PLAYABLE))
            }
            if (bundle.containsKey(FIELD_RECORDING_YEAR)) {
                builder.setRecordingYear(bundle.getInt(FIELD_RECORDING_YEAR))
            }
            if (bundle.containsKey(FIELD_RECORDING_MONTH)) {
                builder.setRecordingMonth(bundle.getInt(FIELD_RECORDING_MONTH))
            }
            if (bundle.containsKey(FIELD_RECORDING_DAY)) {
                builder.setRecordingDay(bundle.getInt(FIELD_RECORDING_DAY))
            }
            if (bundle.containsKey(FIELD_RELEASE_YEAR)) {
                builder.setReleaseYear(bundle.getInt(FIELD_RELEASE_YEAR))
            }
            if (bundle.containsKey(FIELD_RELEASE_MONTH)) {
                builder.setReleaseMonth(bundle.getInt(FIELD_RELEASE_MONTH))
            }
            if (bundle.containsKey(FIELD_RELEASE_DAY)) {
                builder.setReleaseDay(bundle.getInt(FIELD_RELEASE_DAY))
            }
            if (bundle.containsKey(FIELD_DISC_NUMBER)) {
                builder.setDiscNumber(bundle.getInt(FIELD_DISC_NUMBER))
            }
            if (bundle.containsKey(FIELD_TOTAL_DISC_COUNT)) {
                builder.setTotalDiscCount(bundle.getInt(FIELD_TOTAL_DISC_COUNT))
            }
            if (bundle.containsKey(FIELD_MEDIA_TYPE)) {
                builder.setMediaType(bundle.getInt(FIELD_MEDIA_TYPE))
            }
            val supportedCommands = bundle.getStringArrayList(FIELD_SUPPORTED_COMMANDS)
            if (supportedCommands != null) {
                builder.setSupportedCommands(supportedCommands)
            }

            return builder.build()
        }

        @Suppress("deprecation") // Converting deprecated field.
        @FolderType
        internal fun getFolderTypeFromMediaType(mediaType: @MediaType Int): @FolderType Int {
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
        internal fun getMediaTypeFromFolderType(@FolderType folderType: @FolderType Int): @MediaType Int {
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
