package androidx.media3.container

import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.fromUtf8Bytes
import androidx.media3.common.util.Util.toHexString
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.primitives.Ints
import java.lang.Byte
import java.lang.Float
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Int
import kotlin.String

class MdtaMetadataEntry(key: String, value: ByteArray, localeIndicator: Int, typeIndicator: Int) :
    Metadata.Entry {
    val key: String
    val value: ByteArray
    val localeIndicator: Int
    val typeIndicator: Int

    constructor(key: String, value: ByteArray, typeIndicator: Int) : this(
        key,
        value,
        DEFAULT_LOCALE_INDICATOR,
        typeIndicator
    )

    init {
        validateData(key, value, typeIndicator)
        this.key = key
        this.value = value
        this.localeIndicator = localeIndicator
        this.typeIndicator = typeIndicator
    }

    val auxiliaryTrackTypesFromMap: List<Int>
        get() {
            Preconditions.checkState(
                key == KEY_AUXILIARY_TRACKS_MAP,
                "Metadata is not an auxiliary tracks map"
            )
            // Value has 1 byte version, 1 byte track count, n bytes track types.
            val numberOfTracks = value[1].toInt()
            val trackTypes = mutableListOf<Int>()
            for (i in 0..<numberOfTracks) {
                trackTypes.add(value[i + 2].toInt() and 0xFF)
            }
            return trackTypes
        }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as MdtaMetadataEntry
        return key == other.key
                && value.contentEquals(other.value) && localeIndicator == other.localeIndicator && typeIndicator == other.typeIndicator
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + key.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + localeIndicator
        result = 31 * result + typeIndicator
        return result
    }

    override fun toString(): String {
        var formattedValue: String?
        when (typeIndicator) {
            TYPE_INDICATOR_STRING -> formattedValue = fromUtf8Bytes(value)
            TYPE_INDICATOR_FLOAT32 -> formattedValue =
                Float.intBitsToFloat(Ints.fromByteArray(value)).toString()

            TYPE_INDICATOR_INT32 -> formattedValue = Ints.fromByteArray(value).toString()
            TYPE_INDICATOR_8_BIT_UNSIGNED_INT -> formattedValue =
                Byte.toUnsignedInt(value[0]).toString()

            TYPE_INDICATOR_UNSIGNED_INT64 -> formattedValue =
                ParsableByteArray(value).readUnsignedLongToLong().toString()

            TYPE_INDICATOR_RESERVED -> {
                if (key == KEY_AUXILIARY_TRACKS_MAP) {
                    formattedValue = getFormattedValueForAuxiliaryTracksMap(
                        this.auxiliaryTrackTypesFromMap
                    )
                } else {
                    formattedValue = toHexString(value)
                }
            }

            else -> formattedValue = toHexString(value)
        }

        return "mdta: key=" + key + ", value=" + formattedValue
    }

    companion object {
        const val KEY_ANDROID_CAPTURE_FPS: String = "com.android.capture.fps"
        const val KEY_AUXILIARY_TRACKS_OFFSET: String = "auxiliary.tracks.offset"
        const val KEY_AUXILIARY_TRACKS_LENGTH: String = "auxiliary.tracks.length"
        const val KEY_AUXILIARY_TRACKS_MAP: String = "auxiliary.tracks.map"
        const val KEY_AUXILIARY_TRACKS_INTERLEAVED: String = "auxiliary.tracks.interleaved"
        const val AUXILIARY_TRACKS_SAMPLES_NOT_INTERLEAVED: kotlin.Byte = 0
        const val AUXILIARY_TRACKS_SAMPLES_INTERLEAVED: kotlin.Byte = 1
        const val DEFAULT_LOCALE_INDICATOR: Int = 0
        const val TYPE_INDICATOR_RESERVED: Int = 0
        const val TYPE_INDICATOR_STRING: Int = 1
        const val TYPE_INDICATOR_FLOAT32: Int = 23
        const val TYPE_INDICATOR_INT32: Int = 67
        const val TYPE_INDICATOR_8_BIT_UNSIGNED_INT: Int = 75
        const val TYPE_INDICATOR_UNSIGNED_INT64: Int = 78
        private fun validateData(key: String, value: ByteArray, typeIndicator: Int) {
            when (key) {
                KEY_ANDROID_CAPTURE_FPS -> Preconditions.checkArgument(typeIndicator == TYPE_INDICATOR_FLOAT32 && value.size == 4)
                KEY_AUXILIARY_TRACKS_OFFSET, KEY_AUXILIARY_TRACKS_LENGTH -> Preconditions.checkArgument(
                    typeIndicator == TYPE_INDICATOR_UNSIGNED_INT64 && value.size == 8
                )

                KEY_AUXILIARY_TRACKS_MAP -> Preconditions.checkArgument(typeIndicator == TYPE_INDICATOR_RESERVED)
                KEY_AUXILIARY_TRACKS_INTERLEAVED -> Preconditions.checkArgument(
                    typeIndicator == TYPE_INDICATOR_8_BIT_UNSIGNED_INT && value.size == 1 && (value[0] == AUXILIARY_TRACKS_SAMPLES_NOT_INTERLEAVED
                            || value[0] == AUXILIARY_TRACKS_SAMPLES_INTERLEAVED)
                )

                else -> {}
            }
        }

        private fun getFormattedValueForAuxiliaryTracksMap(trackTypes: List<Int>): String {
            val sb = StringBuilder()
            sb.append("track types = ")
            Joiner.on(',').appendTo(sb, trackTypes)
            return sb.toString()
        }
    }
}
