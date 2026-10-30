package androidx.media3.common

import androidx.media3.common.util.contentHashCode
import com.google.common.base.Predicate
import com.google.common.base.Predicates
import com.google.common.primitives.Longs

class Metadata(val presentationTimeUs: Long, entries: List<Entry>) {
    interface Entry {
        val wrappedMetadataFormat: Format?
            get() = null
        val wrappedMetadataBytes: ByteArray?
            get() = null

        fun populateMediaMetadata(builder: MediaMetadata.Builder) {}
    }

    private val entries: List<Entry> = entries

    constructor(entries: List<Entry>) : this(C.TIME_UNSET, entries)

    fun length(): Int {
        return entries.size
    }

    fun get(index: Int): Entry {
        return entries[index]
    }

    fun <T : Entry> getFirstEntryOfType(clazz: Class<T>): T? {
        return getFirstMatchingEntry<T>(clazz, Predicates.alwaysTrue<T>())
    }

    fun <T : Entry> getFirstMatchingEntry(clazz: Class<T>, predicate: Predicate<T>): T? {
        for (entry in entries) {
            val castEntry = entryIfMatches<T>(entry, clazz, predicate)
            if (castEntry != null) {
                return castEntry
            }
        }
        return null
    }

    fun <T : Entry?> getEntriesOfType(clazz: Class<T>): List<T> {
        return entries.filter { clazz.isAssignableFrom(it.javaClass) }
            .map { clazz.cast(it) as T }
    }

    fun <T : Entry> getMatchingEntries(
        clazz: Class<T>, predicate: Predicate<T>
    ): List<T> {
        val matchingEntries = mutableListOf<T>()
        for (entry in entries) {
            val castEntry = entryIfMatches<T>(entry, clazz, predicate)
            if (castEntry != null) {
                matchingEntries.add(castEntry)
            }
        }
        return matchingEntries.toList()
    }

    private fun <T : Entry> entryIfMatches(
        entry: Entry,
        clazz: Class<T>,
        predicate: Predicate<T>
    ): T? {
        if (clazz.isAssignableFrom(entry.javaClass)) {
            val castEntry = clazz.cast(entry)
            if (predicate.apply(castEntry)) {
                return castEntry
            }
        }
        return null
    }

    fun copyWithAppendedEntriesFrom(other: Metadata?): Metadata {
        if (other == null) {
            return this
        }
        return copyWithAppendedEntries(other.entries)
    }

    fun copyWithAppendedEntries(entriesToAppend: List<Entry>): Metadata {
        if (entriesToAppend.isEmpty()) {
            return this
        }
        return Metadata(presentationTimeUs, entries + entriesToAppend)
    }

    fun copyWithPresentationTimeUs(presentationTimeUs: Long): Metadata {
        if (this.presentationTimeUs == presentationTimeUs) {
            return this
        }
        return Metadata(presentationTimeUs, entries)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        other as Metadata
        return entries == other.entries && presentationTimeUs == other.presentationTimeUs
    }

    override fun hashCode(): Int {
        var result = entries.contentHashCode()
        result = 31 * result + Longs.hashCode(presentationTimeUs)
        return result
    }

    override fun toString(): String {
        return ("entries="
                + entries + (if (presentationTimeUs == C.TIME_UNSET) "" else ", presentationTimeUs=$presentationTimeUs"))
    }
}
