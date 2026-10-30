package androidx.media3.exoplayer.metadata

import androidx.media3.common.Metadata

fun interface MetadataOutput {
    fun onMetadata(metadata: Metadata)
}
