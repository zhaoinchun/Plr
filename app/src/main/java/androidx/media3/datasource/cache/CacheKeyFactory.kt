package androidx.media3.datasource.cache

import androidx.media3.datasource.DataSpec

interface CacheKeyFactory {
    fun buildCacheKey(dataSpec: DataSpec): String

    companion object {
        val DEFAULT: CacheKeyFactory =
            object : CacheKeyFactory {
                override fun buildCacheKey(dataSpec: DataSpec): String {
                    return dataSpec.key ?: dataSpec.uri.toString()
                }
            }
    }
}
