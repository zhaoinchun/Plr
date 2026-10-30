
package androidx.media3.extractor.mp3

import androidx.annotation.IntDef
import androidx.media3.common.Metadata
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.hashCode
import kotlin.intArrayOf

class Mp3InfoReplayGain private constructor(
    val peak: Float,
    var field1: GainField?,
    var field2: GainField?
) : Metadata.Entry {
    class GainField // Lint incorrectly thinks we're doing bitwise flag manipulation.
    private constructor(
        val name: @Name Int,
        val originator: @Originator Int,
        val gain: Float
    ) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(value = [NAME_RADIO, NAME_AUDIOPHILE], open = true)
        annotation class Name

        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [ORIGINATOR_UNSET, ORIGINATOR_ARTIST, ORIGINATOR_USER, ORIGINATOR_REPLAYGAIN, ORIGINATOR_SIMPLE_RMS
            ], open = true
        )
        annotation class Originator

        override fun toString(): String {
            return "GainField{" + "name=" + name + ", originator=" + originator + ", gain=" + gain + '}'
        }

        override fun equals(o: Any?): Boolean {
            if (o !is GainField) {
                return false
            }
            val gainField = o
            return name == gainField.name && originator == gainField.originator && gain.compareTo(gainField.gain) == 0
        }

        override fun hashCode(): Int {
            var result = name
            result = 31 * result + originator
            result = 31 * result + gain.hashCode()
            return result
        }

        companion object {
            const val NAME_RADIO: Int = 1
            const val NAME_AUDIOPHILE: Int = 2
            const val ORIGINATOR_UNSET: Int = 0
            const val ORIGINATOR_ARTIST: Int = 1
            const val ORIGINATOR_USER: Int = 2
            const val ORIGINATOR_REPLAYGAIN: Int = 3
            const val ORIGINATOR_SIMPLE_RMS: Int = 4

            // Lint incorrectly thinks we're doing bitwise flag manipulation.
             fun parse(field: Int): GainField? {
                val name = (field shr 13) and 7
                if (name == 0) {
                    return null
                }
                val originator = (field shr 10) and 7
                val gain = ((field and 0x1ff) * (if ((field and 0x200) != 0) -1 else 1)) / 10f
                return GainField(name, originator, gain)
            }
        }
    }

    override fun toString(): String {
        return ("ReplayGain Xing/Info: "
                + "peak="
                + peak
                + ", field 1="
                + field1
                + ", field 2="
                + field2)
    }

    override fun equals(o: Any?): Boolean {
        if (o !is Mp3InfoReplayGain) {
            return false
        }
        val that = o
        return peak.compareTo(that.peak)==0&& field1 == that.field1
                && field2 == that.field2
    }

    override fun hashCode(): Int {
        var result =peak.hashCode()
        result = 31 * result + (if (field1 != null) field1.hashCode() else 0)
        result = 31 * result + (if (field2 != null) field2.hashCode() else 0)
        return result
    }

    companion object {
        fun parse(peak: kotlin.Float, field1: Int, field2: Int): Mp3InfoReplayGain? {
            val parsedField1 = GainField.parse(field1)
            val parsedField2 = GainField.parse(field2)
            if (peak <= 0 && parsedField1 == null && parsedField2 == null) {
                return null
            }
            return Mp3InfoReplayGain(peak, parsedField1, parsedField2)
        }
    }
}
