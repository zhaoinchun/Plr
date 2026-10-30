package androidx.media3.exoplayer.offline

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType
import androidx.media3.datasource.cache.CacheDataSource
import java.util.concurrent.Executor

class DefaultDownloaderFactory(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val executor: Executor
) : DownloaderFactory {
    private val segmentDownloaderFactories = SparseArray<SegmentDownloaderFactory>()

    @Deprecated("Use {@link #DefaultDownloaderFactory(CacheDataSource.Factory, Executor)}.")
    constructor(cacheDataSourceFactory: CacheDataSource.Factory) : this(
        cacheDataSourceFactory,
        Executor { obj: Runnable? -> obj!!.run() })

    override fun createDownloader(request: DownloadRequest): Downloader {
        @C.ContentType val contentType: @C.ContentType Int =
            inferContentTypeForUriAndMimeType(request.uri, request.mimeType)
        when (contentType) {
            C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_SS -> return createSegmentDownloader(
                request,
                contentType
            )

            C.CONTENT_TYPE_OTHER -> {
                val byteRange = request.byteRange
                return ProgressiveDownloader(
                    MediaItem.Builder()
                        .setUri(request.uri)
                        .setCustomCacheKey(request.customCacheKey)
                        .build(),
                    cacheDataSourceFactory,
                    executor,
                    byteRange?.offset ?: 0,
                    byteRange?.length ?: C.LENGTH_UNSET.toLong()
                )
            }

            else -> throw IllegalArgumentException("Unsupported type: $contentType")
        }
    }

    private fun createSegmentDownloader(
        request: DownloadRequest, @C.ContentType contentType: @C.ContentType Int
    ): Downloader {
        val downloaderFactory =
            getSegmentDownloaderFactory(contentType, cacheDataSourceFactory)
        val mediaItem =
            MediaItem.Builder()
                .setUri(request.uri)
                .setStreamKeys(request.streamKeys)
                .setCustomCacheKey(request.customCacheKey)
                .build()
        if (request.timeRange != null) {
            downloaderFactory
                .setStartPositionUs(request.timeRange.startPositionUs)!!
                .setDurationUs(request.timeRange.durationUs)
        }
        return downloaderFactory.setExecutor(executor).create(mediaItem)
    }

    // LINT.IfChange
    private fun getSegmentDownloaderFactory(
        @C.ContentType contentType: @C.ContentType Int,
        cacheDataSourceFactory: CacheDataSource.Factory?
    ): SegmentDownloaderFactory {
        if (segmentDownloaderFactories.contains(contentType)) {
            return segmentDownloaderFactories.get(contentType)
        }

        val downloaderFactory: SegmentDownloaderFactory
        try {
            downloaderFactory = loadSegmentDownloaderFactory(contentType, cacheDataSourceFactory)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("Module missing for content type $contentType", e)
        }
        return downloaderFactory
    }

    @Throws(ClassNotFoundException::class)
    private fun loadSegmentDownloaderFactory(
        @C.ContentType contentType: @C.ContentType Int,
        cacheDataSourceFactory: CacheDataSource.Factory?
    ): SegmentDownloaderFactory {
        val factory: SegmentDownloaderFactory
        when (contentType) {
            C.CONTENT_TYPE_DASH -> factory =
                createSegmentDownloaderFactory(
                    Class.forName("androidx.media3.exoplayer.dash.offline.DashDownloader\$Factory")
                        .asSubclass(SegmentDownloaderFactory::class.java),
                    cacheDataSourceFactory
                )

            C.CONTENT_TYPE_HLS -> factory =
                createSegmentDownloaderFactory(
                    Class.forName("androidx.media3.exoplayer.hls.offline.HlsDownloader\$Factory")
                        .asSubclass(SegmentDownloaderFactory::class.java),
                    cacheDataSourceFactory
                )

            C.CONTENT_TYPE_SS -> factory =
                createSegmentDownloaderFactory(
                    Class.forName(
                        "androidx.media3.exoplayer.smoothstreaming.offline.SsDownloader\$Factory"
                    )
                        .asSubclass(SegmentDownloaderFactory::class.java),
                    cacheDataSourceFactory
                )

            else -> throw IllegalArgumentException("Unsupported type: $contentType")
        }
        segmentDownloaderFactories.put(contentType, factory)
        return factory
    }

    companion object {
        private fun createSegmentDownloaderFactory(
            clazz: Class<out SegmentDownloaderFactory>,
            cacheDataSourceFactory: CacheDataSource.Factory?
        ): SegmentDownloaderFactory {
            try {
                return clazz
                    .getConstructor(CacheDataSource.Factory::class.java)
                    .newInstance(cacheDataSourceFactory)
            } catch (e: Exception) {
                throw IllegalStateException("Downloader factory missing", e)
            }
        }
    }
}
