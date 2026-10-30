package androidx.media3.exoplayer.drm

import androidx.annotation.GuardedBy
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.primitives.Ints

class DefaultDrmSessionManagerProvider : DrmSessionManagerProvider {
    private val lock: Any = Any()

    @GuardedBy("lock")
    private var drmConfiguration: MediaItem.DrmConfiguration? = null

    @GuardedBy("lock")
    private var manager: DrmSessionManager? = null

    private var drmHttpDataSourceFactory: DataSource.Factory? = null
    private var userAgent: String? = null
    private var drmLoadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

    fun setDrmHttpDataSourceFactory(drmDataSourceFactory: DataSource.Factory?) {
        this.drmHttpDataSourceFactory = drmDataSourceFactory
    }

    @Deprecated(
        """Pass a custom {@link DataSource.Factory} to {@link
   *     #setDrmHttpDataSourceFactory(DataSource.Factory)} which sets the desired user agent on
        outgoing requests."""
    )
    fun setDrmUserAgent(userAgent: String?) {
        this.userAgent = userAgent
    }

    fun setDrmLoadErrorHandlingPolicy(drmLoadErrorHandlingPolicy: LoadErrorHandlingPolicy?) {
        this.drmLoadErrorHandlingPolicy = drmLoadErrorHandlingPolicy
    }

    override fun get(mediaItem: MediaItem): DrmSessionManager {
        val drmConfiguration = mediaItem.localConfiguration!!.drmConfiguration
            ?: return DrmSessionManager.DRM_UNSUPPORTED

        synchronized(lock) {
            if (drmConfiguration != this.drmConfiguration) {
                this.drmConfiguration = drmConfiguration
                this.manager = createManager(drmConfiguration)
            }
            return this.manager!!
        }
    }

    private fun createManager(drmConfiguration: MediaItem.DrmConfiguration): DrmSessionManager {
        val dataSourceFactory: DataSource.Factory =
            (if (drmHttpDataSourceFactory != null)
                drmHttpDataSourceFactory
            else
                androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent))!!
        val httpDrmCallback =
            HttpMediaDrmCallback(
                drmConfiguration.licenseUri?.toString(),
                drmConfiguration.forceDefaultLicenseUri,
                dataSourceFactory
            )
        for (entry in drmConfiguration.licenseRequestHeaders.entries) {
            httpDrmCallback.setKeyRequestProperty(entry.key, entry.value)
        }
        val drmSessionManagerBuilder =
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(
                    drmConfiguration.scheme, FrameworkMediaDrm.DEFAULT_PROVIDER
                )
                .setMultiSession(drmConfiguration.multiSession)
                .setPlayClearSamplesWithoutKeys(drmConfiguration.playClearContentWithoutKey)
                .setUseDrmSessionsForClearContent(
                    *Ints.toArray(drmConfiguration.forcedSessionTrackTypes)
                )
        if (drmLoadErrorHandlingPolicy != null) {
            drmSessionManagerBuilder.setLoadErrorHandlingPolicy(drmLoadErrorHandlingPolicy!!)
        }
        val drmSessionManager = drmSessionManagerBuilder.build(httpDrmCallback)
        drmSessionManager.setMode(
            DefaultDrmSessionManager.MODE_PLAYBACK,
            drmConfiguration.getKeySetId()
        )
        return drmSessionManager
    }
}
