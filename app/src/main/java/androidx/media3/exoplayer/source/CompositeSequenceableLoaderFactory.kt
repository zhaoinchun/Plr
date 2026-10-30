package androidx.media3.exoplayer.source

interface CompositeSequenceableLoaderFactory {
    fun empty(): SequenceableLoader
    fun create(
        loaders: List<SequenceableLoader>,
        loaderTrackTypes: List<List<Int>>
    ): SequenceableLoader
}
