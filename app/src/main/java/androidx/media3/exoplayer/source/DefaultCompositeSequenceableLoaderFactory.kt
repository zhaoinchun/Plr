package androidx.media3.exoplayer.source

class DefaultCompositeSequenceableLoaderFactory : CompositeSequenceableLoaderFactory {
    override fun empty(): SequenceableLoader {
        return CompositeSequenceableLoader(listOf(), listOf())
    }

    override fun create(
        loaders: List<SequenceableLoader>,
        loaderTrackTypes: List<List<Int>>
    ): SequenceableLoader {
        return CompositeSequenceableLoader(loaders, loaderTrackTypes)
    }
}
