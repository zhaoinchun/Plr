package androidx.media3.datasource.cache

import android.net.Uri
import com.google.common.base.Preconditions
import java.util.Collections

class ContentMetadataMutations {
    private val editedValues: MutableMap<String?, Any?>
    private val removedValues: MutableList<String?>

    init {
        editedValues = HashMap<String?, Any?>()
        removedValues = ArrayList<String?>()
    }

    fun set(name: String?, value: String?): ContentMetadataMutations {
        return checkAndSet(name, value)
    }

    fun set(name: String?, value: Long): ContentMetadataMutations {
        return checkAndSet(name, value)
    }

    fun set(name: String?, value: ByteArray): ContentMetadataMutations {
        return checkAndSet(name, value.copyOf(value.size))
    }

    fun remove(name: String?): ContentMetadataMutations {
        removedValues.add(name)
        editedValues.remove(name)
        return this
    }

    fun getRemovedValues(): MutableList<String?> {
        return Collections.unmodifiableList<String?>(ArrayList<String?>(removedValues))
    }

    fun getEditedValues(): MutableMap<String?, Any?> {
        val hashMap = HashMap<String?, Any?>(editedValues)
        for (entry in hashMap.entries) {
            val value = entry.value
            if (value is ByteArray) {
                val bytes = value
                entry.setValue(bytes.copyOf(bytes.size))
            }
        }
        return Collections.unmodifiableMap<String?, Any?>(hashMap)
    }

    private fun checkAndSet(name: String?, value: Any?): ContentMetadataMutations {
        editedValues[name!!] = value!!
        removedValues.remove(name)
        return this
    }

    companion object {
        fun setContentLength(
            mutations: ContentMetadataMutations, length: Long
        ): ContentMetadataMutations {
            return mutations.set(ContentMetadata.KEY_CONTENT_LENGTH, length)
        }

        fun setRedirectedUri(
            mutations: ContentMetadataMutations, uri: Uri?
        ): ContentMetadataMutations {
            if (uri == null) {
                return mutations.remove(ContentMetadata.KEY_REDIRECTED_URI)
            } else {
                return mutations.set(ContentMetadata.KEY_REDIRECTED_URI, uri.toString())
            }
        }
    }
}
