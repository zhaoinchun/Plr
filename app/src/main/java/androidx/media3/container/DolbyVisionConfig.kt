package androidx.media3.container

import androidx.media3.common.util.ParsableByteArray

class DolbyVisionConfig private constructor(val profile: Int, val level: Int, val codecs: String?) {
    companion object {
        fun parse(data: ParsableByteArray): DolbyVisionConfig? {
            data.skipBytes(2) // dv_version_major, dv_version_minor
            val profileData = data.readUnsignedByte()
            val dvProfile = (profileData shr 1)
            val dvLevel =
                ((profileData and 0x1) shl 5) or ((data.readUnsignedByte() shr 3) and 0x1F)
            val codecsPrefix: String?
            if (dvProfile == 4 || dvProfile == 5 || dvProfile == 7 || dvProfile == 8) {
                codecsPrefix = "dvhe"
            } else if (dvProfile == 9) {
                codecsPrefix = "dvav"
            } else if (dvProfile == 10) {
                codecsPrefix = "dav1"
            } else {
                return null
            }
            val codecs =
                (codecsPrefix
                        + (if (dvProfile < 10) ".0" else ".")
                        + dvProfile
                        + (if (dvLevel < 10) ".0" else ".")
                        + dvLevel)
            return DolbyVisionConfig(dvProfile, dvLevel, codecs)
        }
    }
}
