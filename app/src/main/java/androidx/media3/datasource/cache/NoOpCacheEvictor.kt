package androidx.media3.datasource.cache

class NoOpCacheEvictor : CacheEvictor {
    override fun requiresCacheSpanTouches(): Boolean {
        return false
    }

    override fun onCacheInitialized() {
        // Do nothing.
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        // Do nothing.
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        // Do nothing.
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        // Do nothing.
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        // Do nothing.
    }
}
