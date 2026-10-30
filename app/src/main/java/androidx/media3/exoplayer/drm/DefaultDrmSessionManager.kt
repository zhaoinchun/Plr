package androidx.media3.exoplayer.drm

import android.annotation.SuppressLint
import android.media.ResourceBusyException
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w

import androidx.media3.common.util.Util.linearSearch
import androidx.media3.common.util.Util.postOrRun
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSession.ReferenceCountListener
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.drm.DrmSessionManager.DrmSessionReference
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.base.Preconditions
import com.google.common.collect.Sets

import java.util.UUID
import kotlin.concurrent.Volatile

class DefaultDrmSessionManager private constructor(
    uuid: UUID,
    exoMediaDrmProvider: ExoMediaDrm.Provider,
    callback: MediaDrmCallback,
    keyRequestParameters: HashMap<String, String>,
    multiSession: Boolean,
    useDrmSessionsForClearContentTrackTypes: IntArray,
    playClearSamplesWithoutKeys: Boolean,
    loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    sessionKeepaliveMs: Long
) : DrmSessionManager {
    class Builder {
        private val keyRequestParameters: HashMap<String, String> = HashMap<String, String>()
        private var uuid: UUID = C.WIDEVINE_UUID
        private var exoMediaDrmProvider = FrameworkMediaDrm.DEFAULT_PROVIDER
        private var multiSession = false
        private var useDrmSessionsForClearContentTrackTypes = IntArray(0)
        private var playClearSamplesWithoutKeys = true
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy =
            DefaultLoadErrorHandlingPolicy()
        private var sessionKeepaliveMs = DEFAULT_SESSION_KEEPALIVE_MS

        fun setKeyRequestParameters(keyRequestParameters: MutableMap<String, String>?): Builder {
            this.keyRequestParameters.clear()
            if (keyRequestParameters != null) {
                this.keyRequestParameters.putAll(keyRequestParameters)
            }
            return this
        }

        fun setUuidAndExoMediaDrmProvider(
            uuid: UUID, exoMediaDrmProvider: ExoMediaDrm.Provider
        ): Builder {
            this.uuid = uuid
            this.exoMediaDrmProvider = exoMediaDrmProvider
            return this
        }

        fun setMultiSession(multiSession: Boolean): Builder {
            this.multiSession = multiSession
            return this
        }

        fun setUseDrmSessionsForClearContent(
            vararg useDrmSessionsForClearContentTrackTypes: Int
        ): Builder {
            for (trackType: @C.TrackType Int in useDrmSessionsForClearContentTrackTypes) {
                Preconditions.checkArgument(trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO)
            }
            this.useDrmSessionsForClearContentTrackTypes =
                useDrmSessionsForClearContentTrackTypes.clone()
            return this
        }

        fun setPlayClearSamplesWithoutKeys(playClearSamplesWithoutKeys: Boolean): Builder {
            this.playClearSamplesWithoutKeys = playClearSamplesWithoutKeys
            return this
        }

        fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): Builder {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
            return this
        }

        fun setSessionKeepaliveMs(sessionKeepaliveMs: Long): Builder {
            Preconditions.checkArgument(sessionKeepaliveMs > 0 || sessionKeepaliveMs == C.TIME_UNSET)
            this.sessionKeepaliveMs = sessionKeepaliveMs
            return this
        }

        fun build(mediaDrmCallback: MediaDrmCallback): DefaultDrmSessionManager {
            return DefaultDrmSessionManager(
                uuid,
                exoMediaDrmProvider,
                mediaDrmCallback,
                keyRequestParameters,
                multiSession,
                useDrmSessionsForClearContentTrackTypes,
                playClearSamplesWithoutKeys,
                loadErrorHandlingPolicy,
                sessionKeepaliveMs
            )
        }
    }

    class MissingSchemeDataException(uuid: UUID) : Exception("Media does not support uuid: $uuid")

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [MODE_PLAYBACK, MODE_QUERY, MODE_DOWNLOAD, MODE_RELEASE])
    annotation class Mode

    private val uuid: UUID
    private val exoMediaDrmProvider: ExoMediaDrm.Provider
    private val callback: MediaDrmCallback
    private val keyRequestParameters: HashMap<String, String>
    private val multiSession: Boolean
    private val useDrmSessionsForClearContentTrackTypes: IntArray
    private val playClearSamplesWithoutKeys: Boolean
    private val provisioningManagerImpl: ProvisioningManagerImpl
    private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    private val referenceCountListener: ReferenceCountListenerImpl
    private val sessionKeepaliveMs: Long
    private val sessions: MutableList<DefaultDrmSession>
    private val preacquiredSessionReferences: MutableSet<PreacquiredSessionReference?>
    private val keepaliveSessions: MutableSet<DefaultDrmSession?>
    private var prepareCallsCount = 0
    private var exoMediaDrm: ExoMediaDrm? = null
    private var placeholderDrmSession: DefaultDrmSession? = null
    private var noMultiSessionDrmSession: DefaultDrmSession? = null
    private var playbackLooper: Looper? = null
    private var playbackHandler: Handler? = null
    private var mode: Int
    private var offlineLicenseKeySetId: ByteArray? = null
    private var playerId: PlayerId? = null

    @Volatile
    var mediaDrmHandler: MediaDrmHandler? = null

    init {
        Preconditions.checkArgument(C.COMMON_PSSH_UUID != uuid, "Use C.CLEARKEY_UUID instead")
        this.uuid = uuid
        this.exoMediaDrmProvider = exoMediaDrmProvider
        this.callback = callback
        this.keyRequestParameters = keyRequestParameters
        this.multiSession = multiSession
        this.useDrmSessionsForClearContentTrackTypes = useDrmSessionsForClearContentTrackTypes
        this.playClearSamplesWithoutKeys = playClearSamplesWithoutKeys
        this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
        provisioningManagerImpl = ProvisioningManagerImpl()
        referenceCountListener = ReferenceCountListenerImpl()
        mode = MODE_PLAYBACK
        sessions = ArrayList<DefaultDrmSession>()
        preacquiredSessionReferences = Sets.newIdentityHashSet<PreacquiredSessionReference?>()
        keepaliveSessions = Sets.newIdentityHashSet<DefaultDrmSession?>()
        this.sessionKeepaliveMs = sessionKeepaliveMs
    }

    fun setMode(mode: @Mode Int, offlineLicenseKeySetId: ByteArray?) {
        Preconditions.checkState(sessions.isEmpty())
        if (mode == MODE_QUERY || mode == MODE_RELEASE) {
            offlineLicenseKeySetId!!
        }
        this.mode = mode
        this.offlineLicenseKeySetId = offlineLicenseKeySetId
    }

    // DrmSessionManager implementation.
    override fun prepare() {
        verifyPlaybackThread(true)
        if (prepareCallsCount++ != 0) {
            return
        }
        if (exoMediaDrm == null) {
            exoMediaDrm = exoMediaDrmProvider.acquireExoMediaDrm(uuid)
            exoMediaDrm!!.setOnEventListener(MediaDrmEventListener())
        } else if (sessionKeepaliveMs != C.TIME_UNSET) {
            // Re-acquire the keepalive references for any sessions that are still active.
            for (i in sessions.indices) {
                sessions[i].acquire(null)
            }
        }
    }

    override fun release() {
        verifyPlaybackThread(true)
        if (--prepareCallsCount != 0) {
            return
        }
        // Release all keepalive acquisitions if keepalive is enabled.
        if (sessionKeepaliveMs != C.TIME_UNSET) {
            // Make a local copy, because sessions are removed from this.sessions during release (via
            // callback).
            val sessions = sessions.toList()
            for (i in sessions.indices) {
                sessions[i].release(null)
            }
        }
        releaseAllPreacquiredSessions()
        maybeReleaseMediaDrm()
    }

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {
        initPlaybackLooper(playbackLooper)
        this.playerId = playerId
    }

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format
    ): DrmSessionReference {
        // Don't verify the playback thread, preacquireSession can be called from any thread.
        Preconditions.checkState(prepareCallsCount > 0)
        playbackLooper!!
        val preacquiredSessionReference =
            PreacquiredSessionReference(eventDispatcher)
        preacquiredSessionReference.acquire(format)
        return preacquiredSessionReference
    }

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format
    ): DrmSession? {
        verifyPlaybackThread(false)
        Preconditions.checkState(prepareCallsCount > 0)
        return acquireSession(
            playbackLooper!!,
            eventDispatcher,
            format,
            true
        )
    }

    // Must be called on the playback thread.
    private fun acquireSession(
        playbackLooper: Looper,
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
        shouldReleasePreacquiredSessionsBeforeRetrying: Boolean
    ): DrmSession? {
        maybeCreateMediaDrmHandler(playbackLooper)

        if (format.drmInitData == null) {
            // Content is not encrypted.
            return maybeAcquirePlaceholderSession(
                getTrackType(format.sampleMimeType),
                shouldReleasePreacquiredSessionsBeforeRetrying
            )
        }

        var schemeDatas: List<DrmInitData.SchemeData>? = null
        if (offlineLicenseKeySetId == null) {
            schemeDatas = getSchemeDatas(
                format.drmInitData,
                uuid,
                false
            )
            if (schemeDatas.isEmpty()) {
                val error = MissingSchemeDataException(uuid)
                e(TAG, "DRM error", error)
                eventDispatcher?.drmSessionManagerError(error)
                return ErrorStateDrmSession(
                    DrmSessionException(error, PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR)
                )
            }
        }

        var session: DefaultDrmSession?
        if (!multiSession) {
            session = noMultiSessionDrmSession
        } else {
            // Only use an existing session if it has matching init data.
            session = null
            for (existingSession in sessions) {
                if (existingSession.schemeDatas == schemeDatas) {
                    session = existingSession
                    break
                }
            }
        }

        if (session == null) {
            // Create a new session.
            session =
                createAndAcquireSessionWithRetry(
                    schemeDatas,
                    false,
                    eventDispatcher,
                    shouldReleasePreacquiredSessionsBeforeRetrying
                )
            if (!multiSession) {
                noMultiSessionDrmSession = session
            }
            sessions.add(session)
        } else {
            session.acquire(eventDispatcher)
        }
        return session
    }

    override fun getCryptoType(format: Format): @C.CryptoType Int {
        verifyPlaybackThread(false)
        val cryptoType: @C.CryptoType Int = exoMediaDrm!!.cryptoType
        if (format.drmInitData == null) {
            val trackType = getTrackType(format.sampleMimeType)
            return if (linearSearch(
                    useDrmSessionsForClearContentTrackTypes,
                    trackType
                ) != C.INDEX_UNSET
            )
                cryptoType
            else
                C.CRYPTO_TYPE_NONE
        } else {
            return if (canAcquireSession(format.drmInitData)) cryptoType else C.CRYPTO_TYPE_UNSUPPORTED
        }
    }

    // Internal methods.
    private fun maybeAcquirePlaceholderSession(
        trackType: Int, shouldReleasePreacquiredSessionsBeforeRetrying: Boolean
    ): DrmSession? {
        val exoMediaDrm = this.exoMediaDrm!!
        val avoidPlaceholderDrmSessions =
            exoMediaDrm.cryptoType == C.CRYPTO_TYPE_FRAMEWORK && enableWorkarounds()
                    && FrameworkCryptoConfig.WORKAROUND_DEVICE_NEEDS_KEYS_TO_CONFIGURE_CODEC
        // Avoid attaching a session to sparse formats.
        if (avoidPlaceholderDrmSessions
            || linearSearch(
                useDrmSessionsForClearContentTrackTypes,
                trackType
            ) == C.INDEX_UNSET || exoMediaDrm.cryptoType == C.CRYPTO_TYPE_UNSUPPORTED
        ) {
            return null
        }
        if (placeholderDrmSession == null) {
            val placeholderDrmSession =
                createAndAcquireSessionWithRetry(
                    listOf(),
                    true,
                    null,
                    shouldReleasePreacquiredSessionsBeforeRetrying
                )
            sessions.add(placeholderDrmSession)
            this.placeholderDrmSession = placeholderDrmSession
        } else {
            placeholderDrmSession!!.acquire(null)
        }
        return placeholderDrmSession
    }

    private fun canAcquireSession(drmInitData: DrmInitData): Boolean {
        if (offlineLicenseKeySetId != null) {
            // An offline license can be restored so a session can always be acquired.
            return true
        }
        val schemeDatas = getSchemeDatas(drmInitData, uuid, true)
        if (schemeDatas.isEmpty()) {
            if (drmInitData.schemeDataCount == 1 && drmInitData.get(0)!!
                    .matches(C.COMMON_PSSH_UUID)
            ) {
                // Assume scheme specific data will be added before the session is opened.
                w(
                    TAG,
                    "DrmInitData only contains common PSSH SchemeData. Assuming support for: $uuid"
                )
            } else {
                // No data for this manager's scheme.
                return false
            }
        }
        val schemeType = drmInitData.schemeType
        if (schemeType == null || C.CENC_TYPE_cenc == schemeType) {
            // If there is no scheme information, assume patternless AES-CTR.
            return true
        } else if (C.CENC_TYPE_cbcs == schemeType) {
            // Support for cbcs (AES-CBC with pattern encryption) was added in API 24. However, the
            // implementation was not stable until API 25.
            return Build.VERSION.SDK_INT >= 25
        } else if (C.CENC_TYPE_cbc1 == schemeType || C.CENC_TYPE_cens == schemeType) {
            // Support for cbc1 (AES-CTR with pattern encryption) and cens (AES-CBC without pattern
            // encryption) was also added in API 24 and made stable from API 25, however support was
            // removed from API 30. Since the range of API levels for which these modes are usable is too
            // small to be useful, we don't indicate support on any API level.
            return false
        }
        // Unknown schemes, assume one of them is supported.
        return true
    }

    @Synchronized
    private fun initPlaybackLooper(playbackLooper: Looper) {
        if (this.playbackLooper == null) {
            this.playbackLooper = playbackLooper
            this.playbackHandler = Handler(playbackLooper)
        } else {
            Preconditions.checkState(this.playbackLooper == playbackLooper)
            playbackHandler!!
        }
    }

    private fun maybeCreateMediaDrmHandler(playbackLooper: Looper) {
        if (mediaDrmHandler == null) {
            mediaDrmHandler = MediaDrmHandler(playbackLooper)
        }
    }

    private fun createAndAcquireSessionWithRetry(
        schemeDatas: List<DrmInitData.SchemeData>?,
        isPlaceholderSession: Boolean,
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        shouldReleasePreacquiredSessionsBeforeRetrying: Boolean
    ): DefaultDrmSession {
        var session =
            createAndAcquireSession(schemeDatas, isPlaceholderSession, eventDispatcher)
        // If we're short on DRM session resources, first try eagerly releasing all our keepalive
        // sessions and then retry the acquisition.
        if (acquisitionFailedIndicatingResourceShortage(session) && !keepaliveSessions.isEmpty()) {
            releaseAllKeepaliveSessions()
            undoAcquisition(session, eventDispatcher)
            session = createAndAcquireSession(schemeDatas, isPlaceholderSession, eventDispatcher)
        }

        // If the acquisition failed again due to continued resource shortage, and
        // shouldReleasePreacquiredSessionsBeforeRetrying is true, try releasing all pre-acquired
        // sessions and then retry the acquisition.
        if (acquisitionFailedIndicatingResourceShortage(session)
            && shouldReleasePreacquiredSessionsBeforeRetrying
            && !preacquiredSessionReferences.isEmpty()
        ) {
            releaseAllPreacquiredSessions()
            if (!keepaliveSessions.isEmpty()) {
                // Some preacquired sessions released above are now in their keepalive timeout phase. We
                // release the keepalive references immediately.
                releaseAllKeepaliveSessions()
            }
            undoAcquisition(session, eventDispatcher)
            session = createAndAcquireSession(schemeDatas, isPlaceholderSession, eventDispatcher)
        }
        return session
    }

    private fun undoAcquisition(
        session: DrmSession, eventDispatcher: DrmSessionEventListener.EventDispatcher?
    ) {
        session.release(eventDispatcher)
        if (sessionKeepaliveMs != C.TIME_UNSET) {
            session.release(null)
        }
    }

    private fun releaseAllKeepaliveSessions() {
        // Make a local copy, because sessions are removed from this.keepaliveSessions during
        // release (via callback).
        val keepaliveSessions = keepaliveSessions.toSet()
        for (keepaliveSession in keepaliveSessions) {
            keepaliveSession?.release(null)
        }
    }

    private fun releaseAllPreacquiredSessions() {
        // Make a local copy, because sessions are removed from this.preacquiredSessionReferences
        // during release (via callback).
        val preacquiredSessionReferences = this.preacquiredSessionReferences.toSet()
        for (preacquiredSessionReference in preacquiredSessionReferences) {
            preacquiredSessionReference?.release()
        }
    }

    private fun createAndAcquireSession(
        schemeDatas: List<DrmInitData.SchemeData>?,
        isPlaceholderSession: Boolean,
        eventDispatcher: DrmSessionEventListener.EventDispatcher?
    ): DefaultDrmSession {
        // Placeholder sessions should always play clear samples without keys.
        val playClearSamplesWithoutKeys = this.playClearSamplesWithoutKeys or isPlaceholderSession
        val session =
            DefaultDrmSession(
                uuid,
                exoMediaDrm!!,
                provisioningManagerImpl,
                referenceCountListener,
                schemeDatas,
                mode,
                playClearSamplesWithoutKeys,
                isPlaceholderSession,
                offlineLicenseKeySetId,
                keyRequestParameters,
                callback,
                playbackLooper!!,
                loadErrorHandlingPolicy,
                playerId!!
            )
        // Acquire the session once on behalf of the caller to DrmSessionManager - this is the
        // reference 'assigned' to the caller which they're responsible for releasing. Do this first,
        // to ensure that eventDispatcher receives all events related to the initial
        // acquisition/opening.
        session.acquire(eventDispatcher)
        if (sessionKeepaliveMs != C.TIME_UNSET) {
            // Acquire the session once more so the Manager can keep it alive.
            session.acquire(null)
        }
        return session
    }

    private fun maybeReleaseMediaDrm() {
        if (exoMediaDrm != null && prepareCallsCount == 0 && sessions.isEmpty()
            && preacquiredSessionReferences.isEmpty()
        ) {
            // This manager and all its sessions are fully released so we can release exoMediaDrm.
            exoMediaDrm!!.release()
            exoMediaDrm = null
        }
    }

    private fun verifyPlaybackThread(allowBeforeSetPlayer: Boolean) {
        if (allowBeforeSetPlayer && playbackLooper == null) {
            w(
                TAG,
                "DefaultDrmSessionManager accessed before setPlayer(), possibly on the wrong thread.",
                IllegalStateException()
            )
        } else if (Thread.currentThread() !== playbackLooper!!.thread) {
            w(
                TAG,
                ("DefaultDrmSessionManager accessed on the wrong thread.\nCurrent thread: "
                        + Thread.currentThread().name
                        + "\nExpected thread: "
                        + playbackLooper!!.thread.name),
                IllegalStateException()
            )
        }
    }

    @SuppressLint("HandlerLeak")
    inner class MediaDrmHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            // The event is not associated with any particular session.
            val sessionId = msg.obj as? ByteArray? ?: return
            for (session in sessions) {
                if (session.hasSessionId(sessionId)) {
                    session.onMediaDrmEvent(msg.what)
                    return
                }
            }
        }
    }

    private inner class ProvisioningManagerImpl : DefaultDrmSession.ProvisioningManager {
        private val sessionsAwaitingProvisioning = mutableSetOf<DefaultDrmSession>()
        private var provisioningSession: DefaultDrmSession? = null

        override fun provisionRequired(session: DefaultDrmSession) {
            sessionsAwaitingProvisioning.add(session)
            if (provisioningSession != null) {
                // Provisioning is already in-flight.
                return
            }
            provisioningSession = session
            session.provision()
        }

        override fun onProvisionCompleted() {
            provisioningSession = null
            val sessionsToNotify = sessionsAwaitingProvisioning.toList()
            // Clear the list before calling onProvisionComplete in case provisioning is re-requested.
            sessionsAwaitingProvisioning.clear()
            for (session in sessionsToNotify) {
                session.onProvisionCompleted()
            }
        }

        override fun onProvisionError(error: Exception, thrownByExoMediaDrm: Boolean) {
            provisioningSession = null
            val sessionsToNotify = sessionsAwaitingProvisioning.toList()
            // Clear the list before calling onProvisionError in case provisioning is re-requested.
            sessionsAwaitingProvisioning.clear()
            for (session in sessionsToNotify) {
                session.onProvisionError(error, thrownByExoMediaDrm)
            }
        }

        fun onSessionFullyReleased(session: DefaultDrmSession?) {
            sessionsAwaitingProvisioning.remove(session)
            if (provisioningSession === session) {
                provisioningSession = null
                if (!sessionsAwaitingProvisioning.isEmpty()) {
                    // Other sessions were waiting for the released session to complete a provision operation.
                    // We need to have one of those sessions perform the provision operation instead.
                    provisioningSession = sessionsAwaitingProvisioning.iterator().next()
                    provisioningSession!!.provision()
                }
            }
        }
    }

    private inner class ReferenceCountListenerImpl : ReferenceCountListener {
        override fun onReferenceCountIncremented(
            session: DefaultDrmSession,
            newReferenceCount: Int
        ) {
            if (sessionKeepaliveMs != C.TIME_UNSET) {
                // The session has been acquired elsewhere so we want to cancel our timeout.
                keepaliveSessions.remove(session)
                playbackHandler!!.removeCallbacksAndMessages(session)
            }
        }

        override fun onReferenceCountDecremented(
            session: DefaultDrmSession,
            newReferenceCount: Int
        ) {
            if (newReferenceCount == 1 && prepareCallsCount > 0 && sessionKeepaliveMs != C.TIME_UNSET) {
                // Only the internal keep-alive reference remains, so we can start the timeout. We only
                // do this if the manager isn't released, because a released manager has already released
                // all its internal session keep-alive references.
                keepaliveSessions.add(session)
                playbackHandler!!.postAtTime(
                    { session.release(null) },
                    session,
                    SystemClock.uptimeMillis() + sessionKeepaliveMs
                )
            } else if (newReferenceCount == 0) {
                // This session is fully released.
                sessions.remove(session)
                if (placeholderDrmSession === session) {
                    placeholderDrmSession = null
                }
                if (noMultiSessionDrmSession === session) {
                    noMultiSessionDrmSession = null
                }
                provisioningManagerImpl.onSessionFullyReleased(session)
                if (sessionKeepaliveMs != C.TIME_UNSET) {
                    playbackHandler!!.removeCallbacksAndMessages(session)
                    keepaliveSessions.remove(session)
                }
            }
            maybeReleaseMediaDrm()
        }
    }

    private inner class MediaDrmEventListener : ExoMediaDrm.OnEventListener {
        override fun onEvent(
            md: ExoMediaDrm, sessionId: ByteArray?, event: Int, extra: Int, data: ByteArray?
        ) {
            mediaDrmHandler!!.obtainMessage(event, sessionId).sendToTarget()
        }
    }

    private inner class PreacquiredSessionReference
        (private val eventDispatcher: DrmSessionEventListener.EventDispatcher?) :
        DrmSessionReference {
        private var session: DrmSession? = null
        private var isReleased = false

        fun acquire(format: Format) {
            playbackHandler!!.post(
                Runnable {
                    if (prepareCallsCount == 0 || isReleased) {
                        // The manager has been fully released or this reference has already been
                        // released. Abort the acquisition attempt.
                        return@Runnable
                    }
                    this.session =
                        acquireSession(
                            playbackLooper!!,
                            eventDispatcher,
                            format,
                            false
                        )
                    preacquiredSessionReferences.add(this)
                })
        }

        override fun release() {
            // Ensure the underlying session is released immediately if we're already on the playback
            // thread, to allow a failed session opening to be immediately retried.
            postOrRun(
                playbackHandler!!,
                Runnable {
                    if (isReleased) {
                        return@Runnable
                    }
                    if (session != null) {
                        session!!.release(eventDispatcher)
                    }
                    preacquiredSessionReferences.remove(this)
                    isReleased = true
                })
        }
    }

    companion object {
        const val PLAYREADY_CUSTOM_DATA_KEY: String = "PRCustomData"
        const val MODE_PLAYBACK: Int = 0
        const val MODE_QUERY: Int = 1
        const val MODE_DOWNLOAD: Int = 2
        const val MODE_RELEASE: Int = 3
        const val INITIAL_DRM_REQUEST_RETRY_COUNT: Int = 3
        const val DEFAULT_SESSION_KEEPALIVE_MS: Long = 5 * 60 * C.MILLIS_PER_SECOND
        private const val TAG = "DefaultDrmSessionMgr"

        private fun acquisitionFailedIndicatingResourceShortage(session: DrmSession): Boolean {
            if (session.state != DrmSession.STATE_ERROR) {
                return false
            }
            val cause = session.error!!.cause
            return cause is ResourceBusyException
                    || DrmUtil.isFailureToConstructResourceBusyException(cause)
        }

        private fun getSchemeDatas(
            drmInitData: DrmInitData, uuid: UUID, allowMissingData: Boolean
        ): List<DrmInitData.SchemeData> {
            // Look for matching scheme data (matching the Common PSSH box for ClearKey).
            val matchingSchemeDatas = mutableListOf<DrmInitData.SchemeData>()
            for (i in 0..<drmInitData.schemeDataCount) {
                val schemeData = drmInitData.get(i)
                val uuidMatches =
                    schemeData!!.matches(uuid)
                            || (C.CLEARKEY_UUID == uuid && schemeData.matches(C.COMMON_PSSH_UUID))
                if (uuidMatches && (schemeData.data != null || allowMissingData)) {
                    matchingSchemeDatas.add(schemeData)
                }
            }
            return matchingSchemeDatas
        }
    }
}
