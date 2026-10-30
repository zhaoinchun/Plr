package androidx.media3.datasource.cache

interface CacheEvictor : Cache.Listener {

    fun requiresCacheSpanTouches(): Boolean

    fun onCacheInitialized()

    fun onStartFile(cache: Cache, key: String, position: Long, length: Long)
}
