package main

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil.buildCronetEngine
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.example.plr.BuildConfig
import org.checkerframework.checker.nullness.qual.MonotonicNonNull
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.Executors

/** Utility methods for the demo app.  */
object DemoUtil {
    const val DOWNLOAD_NOTIFICATION_CHANNEL_ID: String = "download_channel"

    private const val TAG = "DemoUtil"
    private const val DOWNLOAD_CONTENT_DIRECTORY = "downloads"

    private var dataSourceFactory: @MonotonicNonNull DataSource.Factory? = null
    private var httpDataSourceFactory: @MonotonicNonNull DataSource.Factory? = null

    private var databaseProvider: @MonotonicNonNull DatabaseProvider? = null

    private var downloadDirectory: @MonotonicNonNull File? = null


    private var downloadCache: @MonotonicNonNull Cache? = null


    private var downloadManager: @MonotonicNonNull DownloadManager? = null

    private var downloadTracker: @MonotonicNonNull DownloadTracker? = null

    private var downloadNotificationHelper: @MonotonicNonNull DownloadNotificationHelper? = null

    /** Returns whether extension renderers should be used.  */
    fun useExtensionRenderers(): Boolean {
        return BuildConfig.USE_DECODER_EXTENSIONS
    }


    fun buildRenderersFactory(
        context: Context, preferExtensionRenderer: Boolean
    ): RenderersFactory {
        val extensionRendererMode =
            if (useExtensionRenderers())
                (if (preferExtensionRenderer)
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            else
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        return DefaultRenderersFactory(context.getApplicationContext()).also {
            it.extensionRendererMode = extensionRendererMode
        }
    }


    @Synchronized
    fun getHttpDataSourceFactory(context: Context): DataSource.Factory {
        var context = context
        if (httpDataSourceFactory != null) {
            return httpDataSourceFactory!!
        }
        context = context.getApplicationContext()
        if (Build.VERSION.SDK_INT >= 30
            && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
        ) {
            val httpEngine = HttpEngine.Builder(context).build()
            httpDataSourceFactory =
                HttpEngineDataSource.Factory(httpEngine, Executors.newSingleThreadExecutor())
            return httpDataSourceFactory!!
        }
        val cronetEngine = buildCronetEngine(context)
        if (cronetEngine != null) {
            httpDataSourceFactory =
                CronetDataSource.Factory(cronetEngine, Executors.newSingleThreadExecutor())
            return httpDataSourceFactory!!
        }
        // The device doesn't support HttpEngine and we failed to instantiate a CronetEngine.
        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        CookieHandler.setDefault(cookieManager)
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
        return httpDataSourceFactory!!
    }

    /** Returns a [DataSource.Factory].  */
    @Synchronized
    fun getDataSourceFactory(context: Context): DataSource.Factory {
        var context = context
        if (dataSourceFactory == null) {
            context = context.getApplicationContext()
            val upstreamFactory =
                DefaultDataSource.Factory(context, getHttpDataSourceFactory(context))
            dataSourceFactory =
                buildReadOnlyCacheDataSource(upstreamFactory, getDownloadCache(context))
        }
        return dataSourceFactory!!
    }


    @Synchronized
    fun getDownloadNotificationHelper(
        context: Context
    ): DownloadNotificationHelper {
        if (downloadNotificationHelper == null) {
            downloadNotificationHelper =
                DownloadNotificationHelper(context, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        }
        return downloadNotificationHelper!!
    }


    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager? {
        ensureDownloadManagerInitialized(context)
        return downloadManager
    }

    @Synchronized
    fun getDownloadTracker(context: Context): DownloadTracker? {
        ensureDownloadManagerInitialized(context)
        return downloadTracker
    }


    @Synchronized
    private fun getDownloadCache(context: Context): Cache {
        if (downloadCache == null) {
            val downloadContentDirectory =
                File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY)
            downloadCache =
                SimpleCache(
                    downloadContentDirectory, NoOpCacheEvictor(), getDatabaseProvider(context)
                )
        }
        return downloadCache!!
    }


    @Synchronized
    private fun ensureDownloadManagerInitialized(context: Context) {
        if (downloadManager == null) {
            downloadManager =
                DownloadManager(
                    context,
                    getDatabaseProvider(context)!!,
                    getDownloadCache(context),
                    getHttpDataSourceFactory(context),
                    Executors.newFixedThreadPool( /* nThreads= */6)
                )
            downloadTracker =
                DownloadTracker(context, getHttpDataSourceFactory(context), downloadManager!!)
        }
    }

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider? {
        if (databaseProvider == null) {
            databaseProvider = StandaloneDatabaseProvider(context)
        }
        return databaseProvider
    }

    @Synchronized
    private fun getDownloadDirectory(context: Context): File? {
        if (downloadDirectory == null) {
            downloadDirectory = context.getExternalFilesDir( /* type= */null)
            if (downloadDirectory == null) {
                downloadDirectory = context.getFilesDir()
            }
        }
        return downloadDirectory
    }


    private fun buildReadOnlyCacheDataSource(
        upstreamFactory: DataSource.Factory?, cache: Cache
    ): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
