package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.util.Util

data class Label(var language: String?, val value: String) {
    init {
        language = language?.let { Util.normalizeLanguageCode(it) }
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        if (language != null) {
            bundle.putString(FIELD_LANGUAGE_INDEX, language)
        }
        bundle.putString(FIELD_VALUE_INDEX, value)
        return bundle
    }

    companion object {
        private val FIELD_LANGUAGE_INDEX: String = Util.intToStringMaxRadix(0)
        private val FIELD_VALUE_INDEX: String = Util.intToStringMaxRadix(1)

        fun fromBundle(bundle: Bundle): Label {
            return Label(
                bundle.getString(FIELD_LANGUAGE_INDEX),
                bundle.getString(FIELD_VALUE_INDEX)!!
            )
        }
    }
}
