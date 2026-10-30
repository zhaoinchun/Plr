
package androidx.media3.exoplayer.drm

import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Preconditions
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.ExecutionException

class OfflineLicenseHelper(
    private val drmSessionManager: DefaultDrmSessionManager,
    private val eventDispatcher: DrmSessionEventListener.EventDispatcher
) {
    private val drmListenerConditionVariable= ConditionVariable()
    private val handlerThread: HandlerThread = HandlerThread("ExoPlayer:OfflineLicenseHelper")
    private val handler: Handler

    init {
        handlerThread.start()
        handler = Handler(handlerThread.getLooper())
        val eventListener: DrmSessionEventListener =
            object : DrmSessionEventListener {
                override fun onDrmKeysLoaded(
                    windowIndex: Int, mediaPeriodId: MediaPeriodId?, keyLoadInfo: KeyRequestInfo
                ) {
                    drmListenerConditionVariable.open()
                }

                override fun onDrmSessionManagerError(
                    windowIndex: Int, mediaPeriodId: MediaPeriodId?, e: Exception
                ) {
                    drmListenerConditionVariable.open()
                }

                override fun onDrmKeysRestored(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
                    drmListenerConditionVariable.open()
                }

                override fun onDrmKeysRemoved(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
                    drmListenerConditionVariable.open()
                }
            }
        eventDispatcher.addEventListener(Handler(handlerThread.getLooper()), eventListener)
    }

    @Synchronized
    @Throws(DrmSessionException::class)
    fun downloadLicense(format: Format): ByteArray? {
        Preconditions.checkArgument(format.drmInitData != null)
        return acquireSessionAndGetOfflineLicenseKeySetIdOnHandlerThread(
            DefaultDrmSessionManager.MODE_DOWNLOAD, null, format
        )
    }

    @Synchronized
    @Throws(DrmSessionException::class)
    fun renewLicense(offlineLicenseKeySetId: ByteArray?): ByteArray? {
        return acquireSessionAndGetOfflineLicenseKeySetIdOnHandlerThread(
            DefaultDrmSessionManager.MODE_DOWNLOAD,
            offlineLicenseKeySetId!!,
            FORMAT_WITH_EMPTY_DRM_INIT_DATA
        )
    }

    @Synchronized
    @Throws(DrmSessionException::class)
    fun releaseLicense(offlineLicenseKeySetId: ByteArray?) {
        acquireSessionAndGetOfflineLicenseKeySetIdOnHandlerThread(
            DefaultDrmSessionManager.MODE_RELEASE,
            offlineLicenseKeySetId!!,
            FORMAT_WITH_EMPTY_DRM_INIT_DATA
        )
    }

    @Synchronized
    @Throws(DrmSessionException::class)
    fun getLicenseDurationRemainingSec(offlineLicenseKeySetId: ByteArray?): Pair<Long, Long>? {
        val drmSession: DrmSession
        try {
            drmSession =
                acquireFirstSessionOnHandlerThread(
                    DefaultDrmSessionManager.MODE_QUERY,
                    offlineLicenseKeySetId!!,
                    FORMAT_WITH_EMPTY_DRM_INIT_DATA
                )
        } catch (e: DrmSessionException) {
            if (e.cause is KeysExpiredException) {
                return Pair.create(0L, 0L)
            }
            throw e
        }

        val licenseDurationRemainingSec = SettableFuture.create<Pair<Long, Long>?>()
        handler.post(
            Runnable {
                try {
                    licenseDurationRemainingSec.set(
                        WidevineUtil.getLicenseDurationRemainingSec(drmSession)!!
                    )
                } catch (e: Throwable) {
                    licenseDurationRemainingSec.setException(e)
                } finally {
                    drmSession.release(eventDispatcher)
                }
            })
        try {
            return licenseDurationRemainingSec.get()
        } catch (e: ExecutionException) {
            throw IllegalStateException(e)
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        } finally {
            releaseManagerOnHandlerThread()
        }
    }

    fun release() {
        handlerThread.quit()
    }

    @Throws(DrmSessionException::class)
    private fun acquireSessionAndGetOfflineLicenseKeySetIdOnHandlerThread(
        licenseMode: @DefaultDrmSessionManager.Mode Int,
        offlineLicenseKeySetId: ByteArray?,
        format: Format
    ): ByteArray {
        val drmSession =
            acquireFirstSessionOnHandlerThread(licenseMode, offlineLicenseKeySetId, format)
        val keySetId = SettableFuture.create<ByteArray?>()
        handler.post {
            try {
                keySetId.set(drmSession.offlineLicenseKeySetId)
            } catch (e: Throwable) {
                keySetId.setException(e)
            } finally {
                drmSession.release(eventDispatcher)
            }
        }
        try {
            return keySetId.get()!!
        } catch (e: ExecutionException) {
            throw IllegalStateException(e)
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        } finally {
            releaseManagerOnHandlerThread()
        }
    }

    @Throws(DrmSessionException::class)
    private fun acquireFirstSessionOnHandlerThread(
        licenseMode: @DefaultDrmSessionManager.Mode Int,
        offlineLicenseKeySetId: ByteArray?,
        format: Format
    ): DrmSession {
        format.drmInitData!!
        val drmSessionFuture: SettableFuture<DrmSession> = SettableFuture.create<DrmSession>()
        drmListenerConditionVariable.close()
        handler.post {
            try {
                drmSessionManager.setPlayer(
                    Looper.myLooper()!!,
                    PlayerId.UNSET
                )
                drmSessionManager.prepare()
                try {
                    drmSessionManager.setMode(licenseMode, offlineLicenseKeySetId)
                    drmSessionFuture.set(
                        drmSessionManager.acquireSession(eventDispatcher, format)!!
                    )
                } catch (e: Throwable) {
                    drmSessionManager.release()
                    throw e
                }
            } catch (e: Throwable) {
                drmSessionFuture.setException(e)
            }
        }

        val drmSession: DrmSession
        try {
            drmSession = drmSessionFuture.get()
        } catch (e: ExecutionException) {
            throw IllegalStateException(e)
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        }

        // drmListenerConditionVariable will be opened by a callback to this.eventDispatcher when key
        // loading is complete (drmSession.state == STATE_OPENED_WITH_KEYS) or has failed
        // (drmSession.state == STATE_ERROR).
        drmListenerConditionVariable.block()

        val drmSessionError = SettableFuture.create<DrmSessionException?>()
        handler.post {
            try {
                val error = drmSession.error
                if (drmSession.state == DrmSession.STATE_ERROR) {
                    drmSession.release(eventDispatcher)
                    drmSessionManager.release()
                }
                drmSessionError.set(error)
            } catch (e: Throwable) {
                drmSessionError.setException(e)
                drmSession.release(eventDispatcher)
                drmSessionManager.release()
            }
        }
        try {
            if (drmSessionError.get() != null) {
                throw drmSessionError.get()!!
            } else {
                return drmSession
            }
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        } catch (e: ExecutionException) {
            throw IllegalStateException(e)
        }
    }

    private fun releaseManagerOnHandlerThread() {
        val result = SettableFuture.create<Void?>()
        handler.post {
            try {
                drmSessionManager.release()
                result.set(null)
            } catch (e: Throwable) {
                result.setException(e)
            }
        }
        try {
            result.get()
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        } catch (e: ExecutionException) {
            throw IllegalStateException(e)
        }
    }

    companion object {
        private val FORMAT_WITH_EMPTY_DRM_INIT_DATA =
            Format.Builder().setDrmInitData(DrmInitData(listOf())).build()

        fun newWidevineInstance(
            drmConfiguration: MediaItem.DrmConfiguration,
            dataSourceFactory: DataSource.Factory,
            eventDispatcher: DrmSessionEventListener.EventDispatcher
        ): OfflineLicenseHelper {
            Preconditions.checkArgument(drmConfiguration.scheme == C.WIDEVINE_UUID)
            return newWidevineInstance(
                drmConfiguration.licenseUri!!.toString(),
                drmConfiguration.forceDefaultLicenseUri,
                drmConfiguration.licenseRequestHeaders,
                dataSourceFactory, 
                null,
                eventDispatcher
            )
        }

        fun newWidevineInstance(
            defaultLicenseUrl: String?,
            dataSourceFactory: DataSource.Factory,
            eventDispatcher: DrmSessionEventListener.EventDispatcher
        ): OfflineLicenseHelper {
            return newWidevineInstance(
                defaultLicenseUrl, 
                false,
                dataSourceFactory,
                eventDispatcher
            )
        }

        fun newWidevineInstance(
            defaultLicenseUrl: String?,
            forceDefaultLicenseUrl: Boolean,
            dataSourceFactory: DataSource.Factory,
            eventDispatcher: DrmSessionEventListener.EventDispatcher
        ): OfflineLicenseHelper {
            return newWidevineInstance(
                defaultLicenseUrl,
                forceDefaultLicenseUrl,
                dataSourceFactory, 
                null,
                eventDispatcher
            )
        }

        fun newWidevineInstance(
            defaultLicenseUrl: String?,
            forceDefaultLicenseUrl: Boolean,
            dataSourceFactory: DataSource.Factory,
            optionalKeyRequestParameters: MutableMap<String, String>?,
            eventDispatcher: DrmSessionEventListener.EventDispatcher
        ): OfflineLicenseHelper {
            return newWidevineInstance(
                defaultLicenseUrl,
                forceDefaultLicenseUrl, 
                null,
                dataSourceFactory,
                optionalKeyRequestParameters,
                eventDispatcher
            )
        }

        private fun newWidevineInstance(
            defaultLicenseUrl: String?,
            forceDefaultLicenseUrl: Boolean,
            optionalKeyRequestHeaders: Map<String, String>?,
            dataSourceFactory: DataSource.Factory,
            optionalKeyRequestParameters: MutableMap<String, String>?,
            eventDispatcher: DrmSessionEventListener.EventDispatcher
        ): OfflineLicenseHelper {
            val httpDrmCallback =
                HttpMediaDrmCallback(defaultLicenseUrl, forceDefaultLicenseUrl, dataSourceFactory)
            if (optionalKeyRequestHeaders != null) {
                for (entry in optionalKeyRequestHeaders.entries) {
                    httpDrmCallback.setKeyRequestProperty(entry.key, entry.value)
                }
            }
            return OfflineLicenseHelper(
                DefaultDrmSessionManager.Builder()
                    .setKeyRequestParameters(optionalKeyRequestParameters)
                    .build(httpDrmCallback),
                eventDispatcher
            )
        }
    }
}
