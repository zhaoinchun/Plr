package androidx.media3.extractor.metadata.scte35

import androidx.media3.common.Metadata

abstract class SpliceCommand : Metadata.Entry {
    override fun toString(): String {
        return "SCTE-35 splice command: type=" + javaClass.getSimpleName()
    }
}
