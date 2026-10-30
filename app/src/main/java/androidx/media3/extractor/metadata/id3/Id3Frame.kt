package androidx.media3.extractor.metadata.id3

import androidx.media3.common.Metadata

abstract class Id3Frame(
    val id: String
) : Metadata.Entry {
    override fun toString(): String {
        return id
    }
}
