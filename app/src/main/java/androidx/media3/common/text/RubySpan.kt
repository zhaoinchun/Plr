package androidx.media3.common.text

import android.os.Bundle
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions

class RubySpan(
    
    val rubyText: String?, val position: @TextAnnotation.Position Int
) : LanguageFeatureSpan {
    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putString(FIELD_TEXT, rubyText)
        bundle.putInt(FIELD_POSITION, position)
        return bundle
    }

    companion object {
        private val FIELD_TEXT = intToStringMaxRadix(0)
        private val FIELD_POSITION = intToStringMaxRadix(1)

            fun fromBundle(bundle: Bundle): RubySpan {
            return RubySpan(
                bundle.getString(FIELD_TEXT)!!,
                bundle.getInt(FIELD_POSITION)
            )
        }
    }
}
