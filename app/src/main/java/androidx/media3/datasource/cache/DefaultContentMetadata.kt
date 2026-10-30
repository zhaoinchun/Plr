package androidx.media3.datasource.cache

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Collections

class DefaultContentMetadata  constructor(metadata: MutableMap<String?, ByteArray?> = mutableMapOf<String?, ByteArray?>()) :
    ContentMetadata {
    private var hashCode = 0

    private val metadata: MutableMap<String?, ByteArray?>

    init {
        this.metadata = Collections.unmodifiableMap<String?, ByteArray?>(metadata)
    }

    fun copyWithMutationsApplied(mutations: ContentMetadataMutations): DefaultContentMetadata {
        val mutatedMetadata: MutableMap<String?, ByteArray?> = applyMutations(metadata, mutations)
        if (isMetadataEqual(metadata, mutatedMetadata)) {
            return this
        }
        return DefaultContentMetadata(mutatedMetadata)
    }

    fun entrySet(): MutableSet<MutableMap.MutableEntry<String?, ByteArray?>> {
        return metadata.entries
    }

    override fun get(key: String?, defaultValue: ByteArray?): ByteArray? {
        val bytes = metadata.get(key)
        if (bytes != null) {
            return bytes.copyOf(bytes.size)
        } else {
            return defaultValue
        }
    }

    override fun get(key: String?, defaultValue: String?): String? {
        val bytes = metadata.get(key)
        if (bytes != null) {
            return String(bytes, StandardCharsets.UTF_8)
        } else {
            return defaultValue
        }
    }

    override fun get(key: String?, defaultValue: Long): Long {
        val bytes = metadata.get(key)
        if (bytes != null) {
            return ByteBuffer.wrap(bytes).getLong()
        } else {
            return defaultValue
        }
    }

    override fun contains(key: String?): Boolean {
        return metadata.containsKey(key)
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        return isMetadataEqual(metadata, (o as DefaultContentMetadata).metadata)
    }

    override fun hashCode(): Int {
        if (hashCode == 0) {
            var result = 0
            for (entry in metadata.entries) {
                result += entry.key.hashCode() xor entry.value.contentHashCode()
            }
            hashCode = result
        }
        return hashCode
    }

    companion object {
        val EMPTY: DefaultContentMetadata =
            DefaultContentMetadata(mutableMapOf<String?, ByteArray?>())

        private fun isMetadataEqual(
            first: MutableMap<String?, ByteArray?>,
            second: MutableMap<String?, ByteArray?>
        ): Boolean {
            if (first.size != second.size) {
                return false
            }
            for (entry in first.entries) {
                val value = entry.value
                val otherValue = second.get(entry.key)
                if (!value.contentEquals(otherValue)) {
                    return false
                }
            }
            return true
        }

        private fun applyMutations(
            otherMetadata: MutableMap<String?, ByteArray?>, mutations: ContentMetadataMutations
        ): MutableMap<String?, ByteArray?> {
            val metadata = HashMap<String?, ByteArray?>(otherMetadata)
            removeValues(metadata, mutations.getRemovedValues())
            addValues(metadata, mutations.getEditedValues())
            return metadata
        }

        private fun removeValues(
            metadata: HashMap<String?, ByteArray?>,
            names: MutableList<String?>
        ) {
            for (i in names.indices) {
                metadata.remove(names[i])
            }
        }

        private fun addValues(
            metadata: HashMap<String?, ByteArray?>,
            values: MutableMap<String?, Any?>
        ) {
            for (entry in values.entries) {
                metadata.put(entry.key, getBytes(entry.value))
            }
        }

        private fun getBytes(value: Any?): ByteArray? {
            if (value is Long) {
                return ByteBuffer.allocate(8).putLong(value).array()
            } else if (value is String) {
                return value.toByteArray(StandardCharsets.UTF_8)
            } else if (value is ByteArray) {
                return value
            } else {
                throw IllegalArgumentException()
            }
        }
    }
}
