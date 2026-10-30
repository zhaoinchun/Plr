package androidx.media3.exoplayer.drm

import android.annotation.SuppressLint
import android.media.NotProvisionedException
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Pair
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.CopyOnWriteMultiset
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import com.google.common.base.Preconditions
import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlin.math.min

internal class DefaultDrmSession(
    private val uuid: UUID,
    private val mediaDrm: ExoMediaDrm,
    private val provisioningManager: ProvisioningManager,
    private val referenceCountListener: ReferenceCountListener,
    schemeDatas: List<DrmInitData.SchemeData>?,
    private val mode: @DefaultDrmSessionManager.Mode Int,
    private val playClearSamplesWithoutKeys: Boolean,
    private val isPlaceholderSession: Boolean,
    offlineLicenseKeySetId: ByteArray?,
    private val keyRequestParameters: HashMap<String, String>,
    private val callback: MediaDrmCallback,
    private val playbackLooper: Looper,
    private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    private val playerId: PlayerId
) : DrmSession {
    class UnexpectedDrmSessionException(cause: Throwable?) : IOException(cause)

    interface ProvisioningManager {
        fun provisionRequired(session: DefaultDrmSession)
        fun onProvisionError(error: Exception, thrownByExoMediaDrm: Boolean)
        fun onProvisionCompleted()
    }

    interface ReferenceCountListener {
        fun onReferenceCountIncremented(session: DefaultDrmSession, newReferenceCount: Int)
        fun onReferenceCountDecremented(session: DefaultDrmSession, newReferenceCount: Int)
    }

    val schemeDatas: List<DrmInitData.SchemeData>?
    private val eventDispatchers = CopyOnWriteMultiset<DrmSessionEventListener.EventDispatcher>()
    private val responseHandler = ResponseHandler(playbackLooper)
    private val keyRequestInfoLock = Any()

    @DrmSession.State
    override var state: @DrmSession.State Int = DrmSession.STATE_OPENING
        get() {
            verifyPlaybackThread()
            return field
        }
    private var referenceCount = 0
    private var requestHandlerThread: HandlerThread? = null
    private var requestHandler: RequestHandler? = null
    override var cryptoConfig: CryptoConfig? = null
        get() {
            verifyPlaybackThread()
            return field
        }
    private var lastException: DrmSessionException? = null
    private var sessionId: ByteArray? = null
    override var offlineLicenseKeySetId:ByteArray? = null
        get() {
            verifyPlaybackThread()
            return field
        }
    private var currentKeyRequest: ExoMediaDrm.KeyRequest? = null

    @GuardedBy("keyRequestInfoLock")
    private var currentKeyRequestInfo: KeyRequestInfo.Builder? = null
    private var currentProvisionRequest: ExoMediaDrm.ProvisionRequest? = null

    init {
        if (mode == DefaultDrmSessionManager.MODE_QUERY
            || mode == DefaultDrmSessionManager.MODE_RELEASE
        ) {
            offlineLicenseKeySetId!!
        }
        if (offlineLicenseKeySetId != null) {
            this.offlineLicenseKeySetId = offlineLicenseKeySetId
            this.schemeDatas = null
        } else {
            this.schemeDatas = schemeDatas?.toList()
        }
    }

    fun hasSessionId(sessionId: ByteArray?): Boolean {
        verifyPlaybackThread()
        return this.sessionId.contentEquals(sessionId)
    }

    fun onMediaDrmEvent(what: Int) {
        when (what) {
            ExoMediaDrm.EVENT_KEY_REQUIRED -> onKeysRequired()
            else -> {}
        }
    }

    // Provisioning implementation.
    fun provision() {
        currentProvisionRequest = mediaDrm.provisionRequest
        requestHandler!!.post(
            MSG_PROVISION, currentProvisionRequest!!, true
        )
    }

    fun onProvisionCompleted() {
        if (openInternal()) {
            doLicense(true)
        }
    }

    fun onProvisionError(error: Exception, thrownByExoMediaDrm: Boolean) {
        onError(
            error,
            if (thrownByExoMediaDrm)
                DrmUtil.ERROR_SOURCE_EXO_MEDIA_DRM
            else
                DrmUtil.ERROR_SOURCE_PROVISIONING
        )
    }

    override fun playClearSamplesWithoutKeys(): Boolean {
        verifyPlaybackThread()
        return playClearSamplesWithoutKeys
    }

    override val error: DrmSessionException?
        get() {
            verifyPlaybackThread()
            return if (state == DrmSession.STATE_ERROR) lastException else null
        }

    override val schemeUuid: UUID
        get() {
            verifyPlaybackThread()
            return uuid
        }

    override fun queryKeyStatus(): Map<String, String>? {
        verifyPlaybackThread()
        return sessionId?.let { mediaDrm.queryKeyStatus(it) }
    }

    override fun requiresSecureDecoder(mimeType: String): Boolean {
        verifyPlaybackThread()
        return mediaDrm.requiresSecureDecoder(
            sessionId!!,
            mimeType
        )
    }

    override fun acquire(eventDispatcher: DrmSessionEventListener.EventDispatcher?) {
        verifyPlaybackThread()
        if (referenceCount < 0) {
            e(TAG, "Session reference count less than zero: $referenceCount")
            referenceCount = 0
        }
        if (eventDispatcher != null) {
            eventDispatchers.add(eventDispatcher)
        }
        if (++referenceCount == 1) {
            Preconditions.checkState(state == DrmSession.STATE_OPENING)
            requestHandlerThread = HandlerThread("ExoPlayer:DrmRequestHandler")
            requestHandlerThread!!.start()
            requestHandler = RequestHandler(requestHandlerThread!!.getLooper())
            if (openInternal()) {
                doLicense(true)
            }
        } else if (eventDispatcher != null && this.isOpen
            && eventDispatchers.count(eventDispatcher) == 1
        ) {
            // If the session is already open and this is the first instance of eventDispatcher we've
            // seen, then send the acquire event only to the provided dispatcher.
            eventDispatcher.drmSessionAcquired(state)
        }
        referenceCountListener.onReferenceCountIncremented(this, referenceCount)
    }

    override fun release(eventDispatcher: DrmSessionEventListener.EventDispatcher?) {
        verifyPlaybackThread()
        if (referenceCount <= 0) {
            e(TAG, "release() called on a session that's already fully released.")
            return
        }
        if (--referenceCount == 0) {
            // Assigning null to various non-null variables for clean-up.
            state = DrmSession.STATE_RELEASED
            responseHandler.removeCallbacksAndMessages(null)
            requestHandler!!.release()
            requestHandler = null
            requestHandlerThread!!.quit()
            requestHandlerThread = null
            cryptoConfig = null
            lastException = null
            currentKeyRequest = null
            synchronized(keyRequestInfoLock) {
                currentKeyRequestInfo = null
            }
            currentProvisionRequest = null
            sessionId?.also {
                mediaDrm.closeSession(it)
                sessionId = null
            }
        }
        if (eventDispatcher != null) {
            eventDispatchers.remove(eventDispatcher)
            if (eventDispatchers.count(eventDispatcher) == 0) {
                // Release events are only sent to the last-attached instance of each EventDispatcher.
                eventDispatcher.drmSessionReleased()
            }
        }
        referenceCountListener.onReferenceCountDecremented(this, referenceCount)
    }

    private fun openInternal(): Boolean {
        if (this.isOpen) {
            // Already opened
            return true
        }
        try {
            val sessionId = mediaDrm.openSession()
            this.sessionId = sessionId
            mediaDrm.setPlayerIdForSession(sessionId, playerId)
            cryptoConfig = mediaDrm.createCryptoConfig(sessionId)
            state = DrmSession.STATE_OPENED
            // Capture state into a local so a consistent value is seen by the lambda.
            val localState = state
            dispatchEvent { eventDispatcher: DrmSessionEventListener.EventDispatcher? ->
                eventDispatcher!!.drmSessionAcquired(
                    localState
                )
            }
            return true
        } catch (e: NotProvisionedException) {
            provisioningManager.provisionRequired(this)
        } catch (e: Exception) {
            // Work around b/291440132.
            if (DrmUtil.isFailureToConstructNotProvisionedException(e)) {
                provisioningManager.provisionRequired(this)
            } else {
                onError(e, DrmUtil.ERROR_SOURCE_EXO_MEDIA_DRM)
            }
        } catch (e: NoSuchMethodError) {
            if (DrmUtil.isFailureToConstructNotProvisionedException(e)) {
                provisioningManager.provisionRequired(this)
            } else {
                onError(e, DrmUtil.ERROR_SOURCE_EXO_MEDIA_DRM)
            }
        }

        return false
    }

    private fun onProvisionResponse(request: Any?, response: Any) {
        if (request !== currentProvisionRequest || (state != DrmSession.STATE_OPENING && !this.isOpen)) {
            // This event is stale.
            return
        }
        currentProvisionRequest = null

        if (response is Exception) {
            provisioningManager.onProvisionError(response, false)
            return
        }

        try {
            mediaDrm.provideProvisionResponse((response as MediaDrmCallback.Response).data)
        } catch (e: Exception) {
            provisioningManager.onProvisionError(e, true)
            return
        }

        provisioningManager.onProvisionCompleted()
    }

    @RequiresNonNull("sessionId")
    private fun doLicense(allowRetry: Boolean) {
        if (isPlaceholderSession) {
            return
        }
        val sessionId = this.sessionId!!
        when (mode) {
            DefaultDrmSessionManager.MODE_PLAYBACK, DefaultDrmSessionManager.MODE_QUERY -> if (offlineLicenseKeySetId == null) {
                postKeyRequest(sessionId, ExoMediaDrm.KEY_TYPE_STREAMING, allowRetry)
            } else if (state == DrmSession.STATE_OPENED_WITH_KEYS || restoreKeys()) {
                val licenseDurationRemainingSec = this.licenseDurationRemainingSec
                if (mode == DefaultDrmSessionManager.MODE_PLAYBACK
                    && licenseDurationRemainingSec <= MAX_LICENSE_DURATION_TO_RENEW_SECONDS
                ) {
                    d(
                        TAG,
                        ("Offline license has expired or will expire soon. "
                                + "Remaining seconds: "
                                + licenseDurationRemainingSec)
                    )
                    postKeyRequest(sessionId, ExoMediaDrm.KEY_TYPE_OFFLINE, allowRetry)
                } else if (licenseDurationRemainingSec <= 0) {
                    onError(KeysExpiredException(), DrmUtil.ERROR_SOURCE_LICENSE_ACQUISITION)
                } else {
                    state = DrmSession.STATE_OPENED_WITH_KEYS
                    dispatchEvent { it?.drmKeysRestored() }
                }
            }

            DefaultDrmSessionManager.MODE_DOWNLOAD -> if (offlineLicenseKeySetId == null || restoreKeys()) {
                postKeyRequest(sessionId, ExoMediaDrm.KEY_TYPE_OFFLINE, allowRetry)
            }

            DefaultDrmSessionManager.MODE_RELEASE -> {
                this.sessionId!!
                postKeyRequest(
                    offlineLicenseKeySetId!!,
                    ExoMediaDrm.KEY_TYPE_RELEASE,
                    allowRetry
                )
            }

            else -> {}
        }
    }

    @RequiresNonNull("sessionId", "offlineLicenseKeySetId")
    private fun restoreKeys(): Boolean {
        try {
            mediaDrm.restoreKeys(sessionId!!, offlineLicenseKeySetId!!)
            return true
        } catch (e: Exception) {
            onError(e, DrmUtil.ERROR_SOURCE_EXO_MEDIA_DRM)
        } catch (e: NoSuchMethodError) {
            onError(e, DrmUtil.ERROR_SOURCE_EXO_MEDIA_DRM)
        }
        return false
    }

    private val licenseDurationRemainingSec: Long
        get() {
            if (C.WIDEVINE_UUID != uuid) {
                return Long.MAX_VALUE
            }
            val pair = WidevineUtil.getLicenseDurationRemainingSec(this)!!
            return min(pair.first!!, pair.second!!)
        }

    private fun postKeyRequest(scope: ByteArray, type: Int, allowRetry: Boolean) {
        try {
            synchronized(keyRequestInfoLock) {
                currentKeyRequestInfo = KeyRequestInfo.Builder()
                schemeDatas?.also { currentKeyRequestInfo!!.setSchemeDatas(it) }
            }
            currentKeyRequest =
                mediaDrm.getKeyRequest(scope, schemeDatas, type, keyRequestParameters)
            requestHandler!!.post(MSG_KEYS, currentKeyRequest!!, allowRetry)
        } catch (e: Exception) {
            onKeysError(e, true)
        } catch (e: NoSuchMethodError) {
            onKeysError(e, true)
        }
    }

    private fun onKeyResponse(request: Any?, response: Any) {
        if (request !== currentKeyRequest || !this.isOpen) {
            // This event is stale.
            return
        }
        currentKeyRequest = null
        val keyRequestInfo: KeyRequestInfo?
        synchronized(keyRequestInfoLock) {
            // currentKeyRequest and currentKeyRequestInfo are assigned together, and nulled-out together,
            // so it must be non-null here.
            keyRequestInfo = currentKeyRequestInfo!!.build()
            currentKeyRequestInfo = null
        }

        if (response is Exception || response is NoSuchMethodError) {
            onKeysError(response, false)
            return
        }

        try {
            val responseData = (response as MediaDrmCallback.Response).data

            if (mode == DefaultDrmSessionManager.MODE_RELEASE) {
                mediaDrm.provideKeyResponse(offlineLicenseKeySetId!!, responseData)
                dispatchEvent { it?.drmKeysRemoved() }
            } else {
                val keySetId = mediaDrm.provideKeyResponse(sessionId!!, responseData)
                if ((mode == DefaultDrmSessionManager.MODE_DOWNLOAD
                            || (mode == DefaultDrmSessionManager.MODE_PLAYBACK
                            && offlineLicenseKeySetId != null))
                    && keySetId != null && keySetId.isNotEmpty()
                ) {
                    offlineLicenseKeySetId = keySetId
                }
                state = DrmSession.STATE_OPENED_WITH_KEYS
                dispatchEvent { eventDispatcher: DrmSessionEventListener.EventDispatcher? ->
                    eventDispatcher!!.drmKeysLoaded(keyRequestInfo!!)
                }
            }
        } catch (e: Exception) {
            onKeysError(e, true)
        } catch (e: NoSuchMethodError) {
            onKeysError(e, true)
        }
    }

    private fun onKeysRequired() {
        if (mode == DefaultDrmSessionManager.MODE_PLAYBACK && state == DrmSession.STATE_OPENED_WITH_KEYS) {
            sessionId
            doLicense(false)
        }
    }

    private fun onKeysError(e: Throwable, thrownByExoMediaDrm: Boolean) {
        if (e is NotProvisionedException
            || DrmUtil.isFailureToConstructNotProvisionedException(e)
        ) {
            provisioningManager.provisionRequired(this)
        } else {
            onError(
                e,
                if (thrownByExoMediaDrm)
                    DrmUtil.ERROR_SOURCE_EXO_MEDIA_DRM
                else
                    DrmUtil.ERROR_SOURCE_LICENSE_ACQUISITION
            )
        }
    }

    private fun onError(e: Throwable, @DrmUtil.ErrorSource errorSource: @DrmUtil.ErrorSource Int) {
        lastException =
            DrmSessionException(e, DrmUtil.getErrorCodeForMediaDrmException(e, errorSource))
        e(TAG, "DRM session error", e)
        if (e is Exception) {
            dispatchEvent { eventDispatcher: DrmSessionEventListener.EventDispatcher? ->
                eventDispatcher!!.drmSessionManagerError(e)
            }
        } else if (e is Error) {
            // Re-throw all Error types except a NoSuchMethodError caused by b/291440132.
            if (!DrmUtil.isFailureToConstructResourceBusyException(e)
                && !DrmUtil.isFailureToConstructNotProvisionedException(e)
            ) {
                throw e
            }
        } else {
            throw IllegalStateException("Unexpected Throwable subclass", e)
        }
        if (state != DrmSession.STATE_OPENED_WITH_KEYS) {
            state = DrmSession.STATE_ERROR
        }
    }

    private val isOpen: Boolean
        get() = state == DrmSession.STATE_OPENED || state == DrmSession.STATE_OPENED_WITH_KEYS

    private fun dispatchEvent(event: Consumer<DrmSessionEventListener.EventDispatcher?>) {
        for (eventDispatcher in eventDispatchers.elementSet()) {
            event.accept(eventDispatcher)
        }
    }

    private fun verifyPlaybackThread() {
        if (Thread.currentThread() !== playbackLooper.thread) {
            w(
                TAG,
                ("DefaultDrmSession accessed on the wrong thread.\nCurrent thread: "
                        + Thread.currentThread().name
                        + "\nExpected thread: "
                        + playbackLooper.thread.name),
                IllegalStateException()
            )
        }
    }

    // Internal classes.
    @SuppressLint("HandlerLeak")
    private inner class ResponseHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            val requestAndResponse = msg.obj as Pair<Any?, Any>
            val request = requestAndResponse.first
            val response = requestAndResponse.second
            when (msg.what) {
                MSG_PROVISION -> onProvisionResponse(request, response)
                MSG_KEYS -> onKeyResponse(request, response)
                else -> {}
            }
        }
    }

    @SuppressLint("HandlerLeak")
    private inner class RequestHandler(backgroundLooper: Looper) : Handler(backgroundLooper) {
        @GuardedBy("this")
        private var isReleased = false

        fun post(what: Int, request: Any, allowRetry: Boolean) {
            val requestTask =
                RequestTask(
                    LoadEventInfo.newId,
                    allowRetry,
                    SystemClock.elapsedRealtime(),
                    request
                )
            obtainMessage(what, requestTask).sendToTarget()
        }

        override fun handleMessage(msg: Message) {
            val requestTask: RequestTask = msg.obj as RequestTask
            var response: Any?
            try {
                when (msg.what) {
                    MSG_PROVISION -> response =
                        callback.executeProvisionRequest(
                            uuid,
                            requestTask.request as ExoMediaDrm.ProvisionRequest
                        )

                    MSG_KEYS -> {
                        val keyResponse =
                            callback.executeKeyRequest(
                                uuid,
                                requestTask.request as ExoMediaDrm.KeyRequest
                            )
                        response = keyResponse
                        synchronized(keyRequestInfoLock) {
                            if (currentKeyRequestInfo != null && keyResponse.loadEventInfo != null) {
                                currentKeyRequestInfo!!.addLoadInfo(
                                    keyResponse
                                        .loadEventInfo
                                        .buildUpon()
                                        .setLoadTaskId(requestTask.taskId)
                                        .setLoadDurationMs(SystemClock.elapsedRealtime() - requestTask.startTimeMs)
                                        .build()
                                )
                            }
                        }
                    }

                    else -> throw RuntimeException()
                }
            } catch (e: MediaDrmCallbackException) {
                if (maybeRetryRequest(msg, e)) {
                    return
                }
                response = e
            } catch (e: Exception) {
                w(
                    TAG,
                    "Key/provisioning request produced an unexpected exception. Not retrying.",
                    e
                )
                response = e
            }
            loadErrorHandlingPolicy.onLoadTaskConcluded(requestTask.taskId)
            synchronized(this) {
                if (!isReleased) {
                    responseHandler
                        .obtainMessage(
                            msg.what,
                            Pair.create<Any?, Any?>(requestTask.request, response)
                        )
                        .sendToTarget()
                }
            }
        }

        fun maybeRetryRequest(originalMsg: Message, exception: MediaDrmCallbackException): Boolean {
            val requestTask: RequestTask = originalMsg.obj as RequestTask
            if (!requestTask.allowRetry) {
                return false
            }
            requestTask.errorCount++
            if (requestTask.errorCount
                > loadErrorHandlingPolicy.getMinimumLoadableRetryCount(C.DATA_TYPE_DRM)
            ) {
                return false
            }
            val loadEventInfo =
                LoadEventInfo.Builder(
                    requestTask.taskId, exception.dataSpec, SystemClock.elapsedRealtime()
                )
                    .setUri(exception.uriAfterRedirects)
                    .setResponseHeaders(exception.responseHeaders)
                    .setLoadDurationMs(SystemClock.elapsedRealtime() - requestTask.startTimeMs)
                    .setBytesLoaded(exception.bytesLoaded)
                    .build()
            val mediaLoadData = MediaLoadData(C.DATA_TYPE_DRM)
            val loadErrorCause =
                if (exception.cause is IOException)
                    exception.cause as IOException
                else
                    UnexpectedDrmSessionException(exception.cause)
            val retryDelayMs =
                loadErrorHandlingPolicy.getRetryDelayMsFor(
                    LoadErrorInfo(
                        loadEventInfo, mediaLoadData, loadErrorCause, requestTask.errorCount
                    )
                )
            if (retryDelayMs == C.TIME_UNSET) {
                // The error is fatal.
                return false
            }
            synchronized(keyRequestInfoLock) {
                if (currentKeyRequestInfo != null) {
                    currentKeyRequestInfo!!.addLoadInfo(loadEventInfo)
                }
            }
            synchronized(this) {
                if (!isReleased) {
                    sendMessageDelayed(Message.obtain(originalMsg), retryDelayMs)
                    return true
                }
            }
            return false
        }

        @Synchronized
        fun release() {
            removeCallbacksAndMessages(null)
            isReleased = true
        }
    }

    private class RequestTask(
        val taskId: Long,
        val allowRetry: Boolean,
        val startTimeMs: Long,
        val request: Any
    ) {
        var errorCount: Int = 0
    }

    companion object {
        private const val TAG = "DefaultDrmSession"
        private const val MSG_PROVISION = 1
        private const val MSG_KEYS = 2
        private const val MAX_LICENSE_DURATION_TO_RENEW_SECONDS = 60
    }
}
