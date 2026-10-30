package androidx.media3.exoplayer.source.preload

interface RankingDataComparator<T> : Comparator<T> {
    fun interface InvalidationListener {
        fun onRankingDataComparatorInvalidated()
    }

    fun setInvalidationListener(invalidationListener: InvalidationListener?)
}
