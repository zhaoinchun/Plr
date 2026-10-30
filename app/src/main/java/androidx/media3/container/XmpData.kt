package androidx.media3.container

import androidx.media3.common.Metadata
import androidx.media3.common.util.Util.toHexString

class XmpData(val data: ByteArray) : Metadata.Entry {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }

        return data.contentEquals((obj as XmpData).data)
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }

    override fun toString(): String {
        return "XMP: " + toHexString(data)
    }
}
