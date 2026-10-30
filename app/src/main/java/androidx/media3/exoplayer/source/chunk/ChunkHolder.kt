package androidx.media3.exoplayer.source.chunk

class ChunkHolder {
    var chunk: Chunk? = null
    var endOfStream = false

    fun clear() {
        chunk = null
        endOfStream = false
    }
}
