package androidx.media3.exoplayer.source.preload

interface TargetPreloadStatusControl<T, PreloadStatusT> {
    fun getTargetPreloadStatus(rankingData: T): PreloadStatusT
}
