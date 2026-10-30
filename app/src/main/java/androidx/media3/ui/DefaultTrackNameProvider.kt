package androidx.media3.ui

import android.content.res.Resources
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.getAudioMediaMimeType
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.MimeTypes.getVideoMediaMimeType
import androidx.media3.common.util.Util.defaultDisplayLocale
import com.example.plr.R
import java.util.Locale

class DefaultTrackNameProvider(private val resources: Resources) : TrackNameProvider {

    override fun getTrackName(format: Format): String {
        val trackName: String
        val trackType: Int = inferPrimaryTrackType(format)
        if (trackType == C.TRACK_TYPE_VIDEO) {
            trackName = joinWithSeparator(
                buildRoleString(format),
                buildResolutionString(format),
                buildBitrateString(format)!!
            )
        } else if (trackType == C.TRACK_TYPE_AUDIO) {
            trackName = joinWithSeparator(
                buildLanguageOrLabelString(format),
                buildAudioChannelString(format),
                buildBitrateString(format)!!
            )
        } else {
            trackName = buildLanguageOrLabelString(format)
        }
        if (!trackName.isEmpty()) {
            return trackName
        }
        val language = format.language
        return if (language == null || language.trim { it <= ' ' }.isEmpty())
            resources.getString(R.string.exo_track_unknown)
        else
            resources.getString(R.string.exo_track_unknown_name, language)
    }

    private fun buildResolutionString(format: Format): String {
        val width = format.width
        val height = format.height
        return if (width == Format.NO_VALUE || height == Format.NO_VALUE)
            ""
        else
            resources.getString(R.string.exo_track_resolution, width, height)
    }

    private fun buildBitrateString(format: Format): String? {
        val bitrate = format.bitrate
        return if (bitrate == Format.NO_VALUE)
            ""
        else
            resources.getString(R.string.exo_track_bitrate, bitrate / 1000000f)
    }

    private fun buildAudioChannelString(format: Format): String {
        val channelCount = format.channelCount
        if (channelCount == Format.NO_VALUE || channelCount < 1) {
            return ""
        }
        return when (channelCount) {
            1 -> resources.getString(R.string.exo_track_mono)
            2 -> resources.getString(R.string.exo_track_stereo)
            6, 7 -> resources.getString(R.string.exo_track_surround_5_point_1)
            8 -> resources.getString(R.string.exo_track_surround_7_point_1)
            else -> resources.getString(R.string.exo_track_surround)
        }
    }

    private fun buildLanguageOrLabelString(format: Format): String {
        val languageAndRole =
            joinWithSeparator(buildLanguageString(format), buildRoleString(format))
        return if (TextUtils.isEmpty(languageAndRole)) buildLabelString(format) else languageAndRole
    }

    private fun buildLabelString(format: Format): String {
        return format.label ?: ""
    }

    private fun buildLanguageString(format: Format): String {
        val language = format.language
        if (language.isNullOrEmpty() || C.LANGUAGE_UNDETERMINED == language) {
            return ""
        }
        val languageLocale = Locale.forLanguageTag(language)
        val displayLocale = defaultDisplayLocale
        val languageName = languageLocale.getDisplayName(displayLocale)
        if (languageName.isNullOrEmpty()) {
            return ""
        }
        try {
            // Capitalize the first letter. See: https://github.com/google/ExoPlayer/issues/9452.
            val firstCodePointLength = languageName.offsetByCodePoints(0, 1)
            return (languageName.substring(0, firstCodePointLength).uppercase(displayLocale)
                    + languageName.substring(firstCodePointLength))
        } catch (e: IndexOutOfBoundsException) {
            // Should never happen, but return the unmodified language name if it does.
            return languageName
        }
    }

    private fun buildRoleString(format: Format): String {
        var roles = ""
        if ((format.roleFlags and C.ROLE_FLAG_ALTERNATE) != 0) {
            roles = resources.getString(R.string.exo_track_role_alternate)
        }
        if ((format.roleFlags and C.ROLE_FLAG_SUPPLEMENTARY) != 0) {
            roles = joinWithSeparator(roles, resources.getString(R.string.exo_track_role_supplementary))
        }
        if ((format.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) {
            roles = joinWithSeparator(roles, resources.getString(R.string.exo_track_role_commentary))
        }
        if ((format.roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0) {
            roles = joinWithSeparator(roles, resources.getString(R.string.exo_track_role_closed_captions))
        }
        return roles
    }

    private fun joinWithSeparator(vararg items: String): String {
        var itemList = ""
        for (item in items) {
            if (!item.isEmpty()) {
                if (TextUtils.isEmpty(itemList)) {
                    itemList = item
                } else {
                    itemList = resources.getString(R.string.exo_item_list, itemList, item)
                }
            }
        }
        return itemList
    }

    companion object {
        private fun inferPrimaryTrackType(format: Format): Int {
            val trackType = getTrackType(format.sampleMimeType)
            if (trackType != C.TRACK_TYPE_UNKNOWN) {
                return trackType
            }
            if (getVideoMediaMimeType(format.codecs) != null) {
                return C.TRACK_TYPE_VIDEO
            }
            if (getAudioMediaMimeType(format.codecs) != null) {
                return C.TRACK_TYPE_AUDIO
            }
            if (format.width != Format.NO_VALUE || format.height != Format.NO_VALUE) {
                return C.TRACK_TYPE_VIDEO
            }
            if (format.channelCount != Format.NO_VALUE || format.sampleRate != Format.NO_VALUE) {
                return C.TRACK_TYPE_AUDIO
            }
            return C.TRACK_TYPE_UNKNOWN
        }
    }
}
