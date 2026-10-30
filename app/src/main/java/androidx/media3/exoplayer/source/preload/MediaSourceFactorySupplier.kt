package androidx.media3.exoplayer.source.preload

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.base.Supplier

interface MediaSourceFactorySupplier : Supplier<MediaSource.Factory> {
    fun setCache(cache: Cache?): MediaSourceFactorySupplier
    fun setDataSourceFactory(dataSourceFactory: DataSource.Factory?): MediaSourceFactorySupplier
}
