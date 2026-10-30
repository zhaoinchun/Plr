package androidx.media3.common.text

import android.os.Bundle
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions

class VoiceSpan(
    
    val name: String?
) {
    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putString(FIELD_NAME, name)
        return bundle
    }

    companion object {
        private val FIELD_NAME = intToStringMaxRadix(0)

            fun fromBundle(bundle: Bundle): VoiceSpan {
            return VoiceSpan(bundle.getString(FIELD_NAME))
        }
    }
}
