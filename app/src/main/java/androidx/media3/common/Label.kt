package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.util.Util

class Label(language: String?, val value: String) {
    val language: String? = language?.let { Util.normalizeLanguageCode(it) }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val label = o as Label
        return language == label.language && value == label.value
    }

    override fun hashCode(): Int {
        var result = value.hashCode()
        result = 31 * result + (language?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "{ lang=$language, '$value' }"
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
