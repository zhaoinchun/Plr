package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.common.util.contentHashCode
import com.google.common.base.Preconditions

class TrackGroup(id: String, formats: List<Format>) {
    val length: Int
    val id: String
    val type: @C.TrackType Int
    private val formats: List<Format>
    private var hashCode = 0

    constructor(formats: List<Format>) : this("", formats)

    fun copyWithId(id: String): TrackGroup {
        return TrackGroup(id, formats)
    }

    fun getFormat(index: Int): Format {
        return formats[index]
    }

    fun indexOf(format: Format): Int {
        for (i in formats.indices) {
            if (format === formats[i]) {
                return i
            }
        }
        return C.INDEX_UNSET
    }

    override fun hashCode(): Int {
        if (hashCode == 0) {
            var result = 17
            result = 31 * result + id.hashCode()
            result = 31 * result + formats.contentHashCode()
            hashCode = result
        }
        return hashCode
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as TrackGroup
        return id == other.id && formats == other.formats
    }

    override fun toString(): String {
        return "$id: $formats"
    }

    init {
        Preconditions.checkArgument(formats.isNotEmpty())
        this.id = id
        this.formats = formats
        this.length = formats.size
        val sampleMimeType = formats[0].sampleMimeType
        this.type =
            if (sampleMimeType.isNullOrEmpty())
                MimeTypes.getTrackType(formats[0].containerMimeType)
            else
                MimeTypes.getTrackType(sampleMimeType)
        verifyCorrectness()
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        val arrayList = ArrayList<Bundle>(formats.size)
        for (format in formats) {
            arrayList.add(format.toBundle())
        }
        bundle.putParcelableArrayList(FIELD_FORMATS, arrayList)
        bundle.putString(FIELD_ID, id)
        return bundle
    }

    private fun verifyCorrectness() {
        // TrackGroups should only contain tracks with exactly the same content (but in different
        // qualities). We only log an error instead of throwing to not break backwards-compatibility for
        // cases where malformed TrackGroups happen to work by chance (e.g. because adaptive selections
        // are always disabled).
        val language = normalizeLanguage(formats[0].language)
        @RoleFlags val roleFlags: @RoleFlags Int = normalizeRoleFlags(formats[0].roleFlags)
        for (i in 1..<formats.size) {
            if (language != normalizeLanguage(formats[i].language)) {
                logErrorMessage(
                    "languages",
                    formats[0].language,
                    formats[i].language,
                    i
                )
                return
            }
            if (roleFlags != normalizeRoleFlags(formats[i].roleFlags)) {
                logErrorMessage(
                    "role flags",
                    Integer.toBinaryString(formats[0].roleFlags),
                    Integer.toBinaryString(formats[i].roleFlags),
                    i
                )
                return
            }
        }
    }

    companion object {
        private const val TAG = "TrackGroup"

        private val FIELD_FORMATS: String = Util.intToStringMaxRadix(0)
        private val FIELD_ID: String = Util.intToStringMaxRadix(1)

        fun fromBundle(bundle: Bundle): TrackGroup {
            val formatBundles: MutableList<Bundle>? =
                bundle.getParcelableArrayList(FIELD_FORMATS)
            val formats = formatBundles?.map { Format.fromBundle(it) } ?: listOf()
            val id = bundle.getString(FIELD_ID, "")
            return TrackGroup(id, formats)
        }

        private fun normalizeLanguage(language: String?): String {
            // Treat all variants of undetermined or unknown languages as compatible.
            return if (language == null || language == C.LANGUAGE_UNDETERMINED) "" else language
        }

        @RoleFlags
        private fun normalizeRoleFlags(@RoleFlags roleFlags: @RoleFlags Int): @RoleFlags Int {
            // Treat trick-play and non-trick-play formats as compatible.
            return roleFlags or C.ROLE_FLAG_TRICK_PLAY
        }

        private fun logErrorMessage(
            mismatchField: String?,
            valueIndex0: String?,
            otherValue: String?,
            otherIndex: Int
        ) {
            Log.e(
                TAG,
                "",
                IllegalStateException(
                    ("Different "
                            + mismatchField
                            + " combined in one TrackGroup: '"
                            + valueIndex0
                            + "' (track 0) and '"
                            + otherValue
                            + "' (track "
                            + otherIndex
                            + ")")
                )
            )
        }
    }
}