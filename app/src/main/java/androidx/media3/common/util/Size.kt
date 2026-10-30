package androidx.media3.common.util

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions

class Size(width: Int, height: Int) {
    val width: Int
    val height: Int

    override fun equals(obj: Any?): Boolean {
        if (obj == null) {
            return false
        }
        if (this === obj) {
            return true
        }
        if (obj is Size) {
            val other = obj
            return width == other.width && height == other.height
        }
        return false
    }

    override fun toString(): String {
        return width.toString() + "x" + height
    }

    override fun hashCode(): Int {
        // assuming most sizes are <2^16, doing a rotate will give us perfect hashing
        return height xor ((width shl (Integer.SIZE / 2)) or (width ushr (Integer.SIZE / 2)))
    }

    init {
        Preconditions.checkArgument(
            (width == C.LENGTH_UNSET || width >= 0) && (height == C.LENGTH_UNSET || height >= 0)
        )

        this.width = width
        this.height = height
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_WIDTH, width)
        bundle.putInt(FIELD_HEIGHT, height)
        return bundle
    }

    companion object {
        val UNKNOWN: Size = Size( C.LENGTH_UNSET,  C.LENGTH_UNSET)
        val ZERO: Size = Size( 0,  0)

        private val FIELD_WIDTH = intToStringMaxRadix(0)
        private val FIELD_HEIGHT = intToStringMaxRadix(1)

        fun fromBundle(bundle: Bundle): Size {
            val width = bundle.getInt(FIELD_WIDTH, C.LENGTH_UNSET)
            val height = bundle.getInt(FIELD_HEIGHT, C.LENGTH_UNSET)
            return Size(width, height)
        }
    }
}
