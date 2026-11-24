package androidx.media3.extractor.mp4

import androidx.media3.common.util.Util.toFourccString
import androidx.media3.extractor.SniffFailure

class AtomSizeTooSmallSniffFailure(
    val atomType: Int,
    val atomSize: Long,
    val minimumHeaderSize: Int
) : SniffFailure {
    override fun toString(): String {
        return ("AtomSizeTooSmall{type="
                + toFourccString(atomType)
                + ", size="
                + atomSize
                + ", minHeaderSize="
                + minimumHeaderSize
                + "}")
    }
}
