package androidx.media3.exoplayer.drm

import androidx.annotation.GuardedBy
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
        val drmSessionManager =
            DefaultDrmSessionManager(
                uuid = drmConfiguration.scheme!!,
                exoMediaDrmProvider = FrameworkMediaDrm.DEFAULT_PROVIDER,
                callback = httpDrmCallback,
                multiSession = drmConfiguration.multiSession,
                loadErrorHandlingPolicy = drmLoadErrorHandlingPolicy
                    ?: DefaultLoadErrorHandlingPolicy(),
                playClearSamplesWithoutKeys = drmConfiguration.playClearContentWithoutKey,
                useDrmSessionsForClearContentTrackTypes = Ints.toArray(drmConfiguration.forcedSessionTrackTypes)
            )
        drmSessionManager.setMode(
            DefaultDrmSessionManager.MODE_PLAYBACK,
            drmConfiguration.keySetId
        )
        return drmSessionManager
    }
}
