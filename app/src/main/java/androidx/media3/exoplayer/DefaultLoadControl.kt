package androidx.media3.exoplayer

import android.content.ContentResolver
import android.text.TextUtils
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.Log.w

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.getMediaDurationForPlayoutDuration
import androidx.media3.common.util.Util.getPlayoutDurationForMediaDuration
import androidx.media3.common.util.Util.msToUs
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.LoadControl.Parameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.PlayerIdAwareAllocator
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class DefaultLoadControl protected constructor(
    allocator: DefaultAllocator,
    minBufferMs: Int,
    minBufferForLocalPlaybackMs: Int,
    maxBufferMs: Int,
    maxBufferForLocalPlaybackMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackForLocalPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    bufferForPlaybackAfterRebufferForLocalPlaybackMs: Int,
    targetBufferBytes: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    prioritizeTimeOverSizeThresholdsForLocalPlayback: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean,
    playerTargetBufferBytes: Map<String?, Int?> = mapOf()
) : LoadControl {
    class Builder {
        private val playerTargetBufferBytes: HashMap<String?, Int?>

        private var allocator: DefaultAllocator? = null
        private var minBufferMs: Int
        private var minBufferForLocalPlaybackMs: Int
        private var maxBufferMs: Int
        private var maxBufferForLocalPlaybackMs: Int
        private var bufferForPlaybackMs: Int
        private var bufferForPlaybackForLocalPlaybackMs: Int
        private var bufferForPlaybackAfterRebufferMs: Int
        private var bufferForPlaybackAfterRebufferForLocalPlaybackMs: Int
        private var targetBufferBytes: Int
        private var prioritizeTimeOverSizeThresholds: Boolean
        private var prioritizeTimeOverSizeThresholdsForLocalPlayback: Boolean
        private var backBufferDurationMs: Int
        private var retainBackBufferFromKeyframe: Boolean
        private var buildCalled = false

        // For backwards-compatibility, calling only one of the generic setBufferDurationsMs or
        // setPrioritizeTimeOverSizeThresholds methods should not use local playback specific defaults
        // for the other setter to avoid unintended side effects of changing one default but keeping the
        // manual override for the other.
        private var onlyGenericConfigurationMethodsCalled = false

        init {
            playerTargetBufferBytes = HashMap<String?, Int?>()
            playerTargetBufferBytes.put(
                PlayerId.PRELOAD.name,
                DEFAULT_TARGET_BUFFER_BYTES_FOR_PRELOAD
            )
            minBufferMs = DEFAULT_MIN_BUFFER_MS
            minBufferForLocalPlaybackMs = DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS
            maxBufferMs = DEFAULT_MAX_BUFFER_MS
            maxBufferForLocalPlaybackMs = DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS
            bufferForPlaybackMs = DEFAULT_BUFFER_FOR_PLAYBACK_MS
            bufferForPlaybackForLocalPlaybackMs = DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS
            bufferForPlaybackAfterRebufferMs = DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            bufferForPlaybackAfterRebufferForLocalPlaybackMs =
                DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS
            targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES
            prioritizeTimeOverSizeThresholds = DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS
            prioritizeTimeOverSizeThresholdsForLocalPlayback =
                DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK
            backBufferDurationMs = DEFAULT_BACK_BUFFER_DURATION_MS
            retainBackBufferFromKeyframe = DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME
        }

        fun setAllocator(allocator: DefaultAllocator?): Builder {
            Preconditions.checkState(!buildCalled)
            this.allocator = allocator
            return this
        }

        fun setBufferDurationsMs(
            minBufferMs: Int,
            maxBufferMs: Int,
            bufferForPlaybackMs: Int,
            bufferForPlaybackAfterRebufferMs: Int
        ): Builder {
            Preconditions.checkState(!buildCalled)
            assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0")
            assertGreaterOrEqual(
                bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0"
            )
            assertGreaterOrEqual(
                minBufferMs,
                bufferForPlaybackMs,
                "minBufferMs",
                "bufferForPlaybackMs"
            )
            assertGreaterOrEqual(
                minBufferMs,
                bufferForPlaybackAfterRebufferMs,
                "minBufferMs",
                "bufferForPlaybackAfterRebufferMs"
            )
            assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs")
            this.minBufferMs = minBufferMs
            this.maxBufferMs = maxBufferMs
            this.bufferForPlaybackMs = bufferForPlaybackMs
            this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs
            this.minBufferForLocalPlaybackMs = minBufferMs
            this.maxBufferForLocalPlaybackMs = maxBufferMs
            this.bufferForPlaybackForLocalPlaybackMs = bufferForPlaybackMs
            this.bufferForPlaybackAfterRebufferForLocalPlaybackMs = bufferForPlaybackAfterRebufferMs
            if (onlyGenericConfigurationMethodsCalled == null) {
                onlyGenericConfigurationMethodsCalled = true
            }
            return this
        }

        fun setBufferDurationsMsForStreaming(
            minBufferMs: Int,
            maxBufferMs: Int,
            bufferForPlaybackMs: Int,
            bufferForPlaybackAfterRebufferMs: Int
        ): Builder {
            Preconditions.checkState(!buildCalled)
            assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0")
            assertGreaterOrEqual(
                bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0"
            )
            assertGreaterOrEqual(
                minBufferMs,
                bufferForPlaybackMs,
                "minBufferMs",
                "bufferForPlaybackMs"
            )
            assertGreaterOrEqual(
                minBufferMs,
                bufferForPlaybackAfterRebufferMs,
                "minBufferMs",
                "bufferForPlaybackAfterRebufferMs"
            )
            assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs")
            this.minBufferMs = minBufferMs
            this.maxBufferMs = maxBufferMs
            this.bufferForPlaybackMs = bufferForPlaybackMs
            this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs
            onlyGenericConfigurationMethodsCalled = false
            return this
        }

        fun setBufferDurationsMsForLocalPlayback(
            minBufferMs: Int,
            maxBufferMs: Int,
            bufferForPlaybackMs: Int,
            bufferForPlaybackAfterRebufferMs: Int
        ): Builder {
            Preconditions.checkState(!buildCalled)
            assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0")
            assertGreaterOrEqual(
                bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0"
            )
            assertGreaterOrEqual(
                minBufferMs,
                bufferForPlaybackMs,
                "minBufferMs",
                "bufferForPlaybackMs"
            )
            assertGreaterOrEqual(
                minBufferMs,
                bufferForPlaybackAfterRebufferMs,
                "minBufferMs",
                "bufferForPlaybackAfterRebufferMs"
            )
            assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs")
            this.minBufferForLocalPlaybackMs = minBufferMs
            this.maxBufferForLocalPlaybackMs = maxBufferMs
            this.bufferForPlaybackForLocalPlaybackMs = bufferForPlaybackMs
            this.bufferForPlaybackAfterRebufferForLocalPlaybackMs = bufferForPlaybackAfterRebufferMs
            onlyGenericConfigurationMethodsCalled = false
            return this
        }

        fun setTargetBufferBytes(targetBufferBytes: Int): Builder {
            Preconditions.checkState(!buildCalled)
            this.targetBufferBytes = targetBufferBytes
            return this
        }

        fun setPlayerTargetBufferBytes(playerName: String?, playerTargetBufferBytes: Int): Builder {
            Preconditions.checkState(!buildCalled)
            this.playerTargetBufferBytes.put(playerName, playerTargetBufferBytes)
            return this
        }

        fun setPrioritizeTimeOverSizeThresholds(prioritizeTimeOverSizeThresholds: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds
            this.prioritizeTimeOverSizeThresholdsForLocalPlayback = prioritizeTimeOverSizeThresholds
            if (onlyGenericConfigurationMethodsCalled == null) {
                onlyGenericConfigurationMethodsCalled = true
            }
            return this
        }

        fun setPrioritizeTimeOverSizeThresholdsForStreaming(
            prioritizeTimeOverSizeThresholds: Boolean
        ): Builder {
            Preconditions.checkState(!buildCalled)
            this.prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds
            onlyGenericConfigurationMethodsCalled = false
            return this
        }

        fun setPrioritizeTimeOverSizeThresholdsForLocalPlayback(
            prioritizeTimeOverSizeThresholds: Boolean
        ): Builder {
            Preconditions.checkState(!buildCalled)
            this.prioritizeTimeOverSizeThresholdsForLocalPlayback = prioritizeTimeOverSizeThresholds
            onlyGenericConfigurationMethodsCalled = false
            return this
        }

        fun setBackBuffer(
            backBufferDurationMs: Int,
            retainBackBufferFromKeyframe: Boolean
        ): Builder {
            Preconditions.checkState(!buildCalled)
            assertGreaterOrEqual(backBufferDurationMs, 0, "backBufferDurationMs", "0")
            this.backBufferDurationMs = backBufferDurationMs
            this.retainBackBufferFromKeyframe = retainBackBufferFromKeyframe
            return this
        }

        fun build(): DefaultLoadControl {
            Preconditions.checkState(!buildCalled)
            buildCalled = true
            if (allocator == null) {
                allocator = DefaultAllocator( true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
            }
            if (onlyGenericConfigurationMethodsCalled) {
                // For backwards-compatibility, if only generic setters were called, ensure the local
                // playback values are equivalent to the streaming ones even if not explicitly specified.
                minBufferForLocalPlaybackMs = minBufferMs
                maxBufferForLocalPlaybackMs = maxBufferMs
                bufferForPlaybackForLocalPlaybackMs = bufferForPlaybackMs
                bufferForPlaybackAfterRebufferForLocalPlaybackMs = bufferForPlaybackAfterRebufferMs
                prioritizeTimeOverSizeThresholdsForLocalPlayback = prioritizeTimeOverSizeThresholds
            }
            return DefaultLoadControl(
                allocator!!,
                minBufferMs,
                minBufferForLocalPlaybackMs,
                maxBufferMs,
                maxBufferForLocalPlaybackMs,
                bufferForPlaybackMs,
                bufferForPlaybackForLocalPlaybackMs,
                bufferForPlaybackAfterRebufferMs,
                bufferForPlaybackAfterRebufferForLocalPlaybackMs,
                targetBufferBytes,
                prioritizeTimeOverSizeThresholds,
                prioritizeTimeOverSizeThresholdsForLocalPlayback,
                backBufferDurationMs,
                retainBackBufferFromKeyframe,
                playerTargetBufferBytes
            )
        }
    }

    private val window: Timeline.Window
    private val period: Timeline.Period
    private val allocator: DefaultAllocator
    private val minBufferUs: Long
    private val minBufferForLocalPlaybackUs: Long
    private val maxBufferUs: Long
    private val maxBufferForLocalPlaybackUs: Long
    private val bufferForPlaybackUs: Long
    private val bufferForPlaybackForLocalPlaybackUs: Long
    private val bufferForPlaybackAfterRebufferUs: Long
    private val bufferForPlaybackAfterRebufferForLocalPlaybackUs: Long
    private val targetBufferBytesOverwrite: Int
    private val prioritizeTimeOverSizeThresholds: Boolean
    private val prioritizeTimeOverSizeThresholdsForLocalPlayback: Boolean
    override val backBufferDurationUs: Long
    private val retainBackBufferFromKeyframe: Boolean
    private val playerTargetBufferBytesOverwrites: Map<String?, Int?>
    private val loadingStates: ConcurrentHashMap<PlayerId?, PlayerLoadingState>

    private var threadId: Long = 0

    constructor() : this(
        DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        DEFAULT_MIN_BUFFER_MS,
        DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_MAX_BUFFER_MS,
        DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_TARGET_BUFFER_BYTES,
        DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
        DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK,
        DEFAULT_BACK_BUFFER_DURATION_MS,
        DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME
    )

    init {
        assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0")
        assertGreaterOrEqual(
            bufferForPlaybackForLocalPlaybackMs, 0, "bufferForPlaybackForLocalPlaybackMs", "0"
        )
        assertGreaterOrEqual(
            bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0"
        )
        assertGreaterOrEqual(
            bufferForPlaybackAfterRebufferForLocalPlaybackMs,
            0,
            "bufferForPlaybackAfterRebufferForLocalPlaybackMs",
            "0"
        )
        assertGreaterOrEqual(minBufferMs, bufferForPlaybackMs, "minBufferMs", "bufferForPlaybackMs")
        assertGreaterOrEqual(
            minBufferForLocalPlaybackMs,
            bufferForPlaybackForLocalPlaybackMs,
            "minBufferForLocalPlaybackMs",
            "bufferForPlaybackForLocalPlaybackMs"
        )
        assertGreaterOrEqual(
            minBufferMs,
            bufferForPlaybackAfterRebufferMs,
            "minBufferMs",
            "bufferForPlaybackAfterRebufferMs"
        )
        assertGreaterOrEqual(
            minBufferForLocalPlaybackMs,
            bufferForPlaybackAfterRebufferForLocalPlaybackMs,
            "minBufferForLocalPlaybackMs",
            "bufferForPlaybackAfterRebufferForLocalPlaybackMs"
        )
        assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs")
        assertGreaterOrEqual(
            maxBufferForLocalPlaybackMs,
            minBufferForLocalPlaybackMs,
            "maxBufferForLocalPlaybackMs",
            "minBufferForLocalPlaybackMs"
        )
        assertGreaterOrEqual(backBufferDurationMs, 0, "backBufferDurationMs", "0")

        this.window = Timeline.Window()
        this.period = Timeline.Period()
        this.allocator = allocator
        this.minBufferUs = msToUs(minBufferMs)
        this.minBufferForLocalPlaybackUs = msToUs(minBufferForLocalPlaybackMs)
        this.maxBufferUs = msToUs(maxBufferMs)
        this.maxBufferForLocalPlaybackUs = msToUs(maxBufferForLocalPlaybackMs)
        this.bufferForPlaybackUs = msToUs(bufferForPlaybackMs)
        this.bufferForPlaybackForLocalPlaybackUs = msToUs(bufferForPlaybackForLocalPlaybackMs)
        this.bufferForPlaybackAfterRebufferUs = msToUs(bufferForPlaybackAfterRebufferMs)
        this.bufferForPlaybackAfterRebufferForLocalPlaybackUs =
            msToUs(bufferForPlaybackAfterRebufferForLocalPlaybackMs)
        this.targetBufferBytesOverwrite = targetBufferBytes
        this.prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds
        this.prioritizeTimeOverSizeThresholdsForLocalPlayback =
            prioritizeTimeOverSizeThresholdsForLocalPlayback
        this.backBufferDurationUs = msToUs(backBufferDurationMs)
        this.retainBackBufferFromKeyframe = retainBackBufferFromKeyframe
        loadingStates = ConcurrentHashMap<PlayerId?, PlayerLoadingState>()
        playerTargetBufferBytesOverwrites = playerTargetBufferBytes.toMap()
        threadId = C.INDEX_UNSET.toLong()
    }

    override fun onPrepared(playerId: PlayerId) {
        val currentThreadId = Thread.currentThread().getId()
        Preconditions.checkState(
            threadId == C.INDEX_UNSET.toLong() || threadId == currentThreadId,
            "Players that share the same LoadControl must share the same playback thread. See"
                    + " ExoPlayer.Builder.setPlaybackLooper(Looper)."
        )
        threadId = currentThreadId
        val playerLoadingState = loadingStates.get(playerId)
        if (playerLoadingState == null) {
            loadingStates.put(playerId, PlayerLoadingState())
        } else {
            playerLoadingState.referenceCount++
        }
        resetPlayerLoadingState(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>
    ) {
        val targetBufferBytesOverwrite = getTargetBufferBytesOverwrite(parameters.playerId!!)
        loadingStates.get(parameters.playerId)!!.targetBufferBytes =
            if (targetBufferBytesOverwrite == C.LENGTH_UNSET)
                calculateTargetBufferBytes(parameters, trackSelections)
            else
                targetBufferBytesOverwrite
        updateAllocator()
    }

    override fun onStopped(playerId: PlayerId) {
        removePlayer(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        removePlayer(playerId)
        if (loadingStates.isEmpty()) {
            threadId = C.INDEX_UNSET.toLong()
        }
    }

    override fun getAllocator(playerId: PlayerId): Allocator {
        return PlayerIdFilteringAllocatorImpl(playerId)
    }

    override fun getBackBufferDurationUs(playerId: PlayerId?): Long {
        return backBufferDurationUs
    }

    override fun retainBackBufferFromKeyframe(playerId: PlayerId?): Boolean {
        return retainBackBufferFromKeyframe
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val playerId = parameters.playerId!!
        val playerLoadingState: PlayerLoadingState = loadingStates.get(playerId)!!
        val targetBufferSizeReached =
            getTotalBufferBytesAllocated(playerId) >= getTargetBufferBytes(playerId)
        if (playerId == PlayerId.PRELOAD) {
            return !targetBufferSizeReached
        }
        val isLocalPlayback = isLocalPlayback(parameters)
        var minBufferUs = getMinBufferUs(isLocalPlayback)
        val maxBufferUs = getMaxBufferUs(isLocalPlayback)
        if (parameters.playbackSpeed > 1) {
            // The playback speed is faster than real time, so scale up the minimum required media
            // duration to keep enough media buffered for a playout duration of minBufferUs.
            val mediaDurationMinBufferUs =
                getMediaDurationForPlayoutDuration(minBufferUs, parameters.playbackSpeed)
            minBufferUs = min(mediaDurationMinBufferUs, maxBufferUs)
        }
        // Prevent playback from getting stuck if minBufferUs is too small.
        minBufferUs = max(minBufferUs, 500000)
        if (parameters.bufferedDurationUs < minBufferUs) {
            val prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds(isLocalPlayback)
            val heapHasEnoughHeadroomForPrioritizeTimeOverSizeThreshold =
                heapHasEnoughHeadroomForPrioritizeTimeOverSizeThreshold()
            playerLoadingState.isLoading =
                (prioritizeTimeOverSizeThresholds
                        && heapHasEnoughHeadroomForPrioritizeTimeOverSizeThreshold)
                        || !targetBufferSizeReached
            if (!playerLoadingState.isLoading && prioritizeTimeOverSizeThresholds
                && !heapHasEnoughHeadroomForPrioritizeTimeOverSizeThreshold
            ) {
                i(
                    TAG,
                    "Stopped loading before minBufferUs reached due to memory pressure, despite "
                            + "prioritizeTimeOverSizeThresholds=true."
                )
            }
            if (!playerLoadingState.isLoading && parameters.bufferedDurationUs < 500000) {
                w(TAG, "Target buffer size reached with less than 500ms of buffered media data.")
            }
        } else if (parameters.bufferedDurationUs >= maxBufferUs || targetBufferSizeReached) {
            playerLoadingState.isLoading = false
        } // Else don't change the loading state.

        return playerLoadingState.isLoading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val isLocalPlayback = isLocalPlayback(parameters)
        val bufferedDurationUs =
            getPlayoutDurationForMediaDuration(
                parameters.bufferedDurationUs, parameters.playbackSpeed
            )
        var minBufferDurationUs =
            if (parameters.rebuffering)
                getBufferForPlaybackAfterRebufferUs(isLocalPlayback)
            else
                getBufferForPlaybackUs(isLocalPlayback)
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            minBufferDurationUs = min(parameters.targetLiveOffsetUs / 2, minBufferDurationUs)
        }
        return minBufferDurationUs <= 0 || bufferedDurationUs >= minBufferDurationUs || (!prioritizeTimeOverSizeThresholds(
            isLocalPlayback
        )
                && (getTotalBufferBytesAllocated(parameters.playerId!!)
                >= getTargetBufferBytes(parameters.playerId)))
    }

    override fun shouldContinuePreloading(
        playerId: PlayerId?,
        timeline: Timeline?,
        mediaPeriodId: MediaPeriodId?,
        bufferedDurationUs: Long
    ): Boolean {
        for (playerLoadingState in loadingStates.values) {
            if (playerLoadingState.isLoading) {
                return false
            }
        }
        return true
    }

    @Deprecated(
        """Overwrite {@link #calculateTargetBufferBytes(Parameters, ExoTrackSelection[])}
        instead."""
    )
    protected fun calculateTargetBufferBytes(trackSelectionArray: Array<ExoTrackSelection?>?): Int {
        return C.LENGTH_UNSET
    }

    @Suppress("deprecation") // Calling deprecated method for backwards compatibility
    protected fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters, trackSelectionArray: Array<ExoTrackSelection?>
    ): Int {
        val deprecatedResult = calculateTargetBufferBytes(trackSelectionArray)
        if (deprecatedResult != C.LENGTH_UNSET) {
            return deprecatedResult
        }
        var targetBufferSize = 0
        val isLocalPlayback = isLocalPlayback(parameters)
        for (exoTrackSelection in trackSelectionArray) {
            if (exoTrackSelection != null) {
                targetBufferSize +=
                    getDefaultBufferSize(exoTrackSelection.trackGroup!!.type, isLocalPlayback)
            }
        }
        return constrainValue(targetBufferSize, DEFAULT_MIN_BUFFER_SIZE, DEFAULT_MAX_BUFFER_SIZE)
    }

    @VisibleForTesting
    fun calculateTotalTargetBufferBytes(): Int {
        var totalTargetBufferBytes = 0
        for (state in loadingStates.values) {
            totalTargetBufferBytes += state.targetBufferBytes
        }
        return totalTargetBufferBytes
    }

    private fun resetPlayerLoadingState(playerId: PlayerId) {
        val playerLoadingState: PlayerLoadingState = loadingStates.get(playerId)!!
        val targetBufferBytesOverwrite = getTargetBufferBytesOverwrite(playerId)
        playerLoadingState.targetBufferBytes =
            if (targetBufferBytesOverwrite != C.LENGTH_UNSET)
                targetBufferBytesOverwrite
            else
                DEFAULT_MIN_BUFFER_SIZE
        playerLoadingState.isLoading = false
    }

    private fun getTargetBufferBytesOverwrite(playerId: PlayerId): Int {
        val playerTargetBufferBytesOverwrite = playerTargetBufferBytesOverwrites.get(playerId.name)
        if (playerTargetBufferBytesOverwrite != null
            && playerTargetBufferBytesOverwrite != C.LENGTH_UNSET
        ) {
            return playerTargetBufferBytesOverwrite
        }
        return targetBufferBytesOverwrite
    }

    private fun removePlayer(playerId: PlayerId) {
        val playerLoadingState = loadingStates.get(playerId)
        if (playerLoadingState != null) {
            playerLoadingState.referenceCount--
            if (playerLoadingState.referenceCount == 0) {
                loadingStates.remove(playerId)
                updateAllocator()
            }
        }
    }

    private fun updateAllocator() {
        if (loadingStates.isEmpty()) {
            allocator.reset()
        } else {
            allocator.setTargetBufferSize(calculateTotalTargetBufferBytes())
        }
    }

    private fun isLocalPlayback(parameters: LoadControl.Parameters): Boolean {
        val windowIndex =
            parameters.timeline!!.getPeriodByUid(
                parameters.mediaPeriodId!!.periodUid,
                period
            ).windowIndex
        val mediaItem = parameters.timeline.getWindow(windowIndex, window).mediaItem
        if (mediaItem.localConfiguration == null) {
            return false
        }
        val scheme = mediaItem.localConfiguration.uri.getScheme()
        return TextUtils.isEmpty(scheme) || LOCAL_PLAYBACK_SCHEMES.contains(scheme)
    }

    private fun getMinBufferUs(isLocalPlayback: Boolean): Long {
        return if (isLocalPlayback) minBufferForLocalPlaybackUs else minBufferUs
    }

    private fun getMaxBufferUs(isLocalPlayback: Boolean): Long {
        return if (isLocalPlayback) maxBufferForLocalPlaybackUs else maxBufferUs
    }

    private fun getBufferForPlaybackUs(isLocalPlayback: Boolean): Long {
        return if (isLocalPlayback) bufferForPlaybackForLocalPlaybackUs else bufferForPlaybackUs
    }

    private fun getBufferForPlaybackAfterRebufferUs(isLocalPlayback: Boolean): Long {
        return if (isLocalPlayback)
            bufferForPlaybackAfterRebufferForLocalPlaybackUs
        else
            bufferForPlaybackAfterRebufferUs
    }

    private fun prioritizeTimeOverSizeThresholds(isLocalPlayback: Boolean): Boolean {
        return if (isLocalPlayback)
            prioritizeTimeOverSizeThresholdsForLocalPlayback
        else
            prioritizeTimeOverSizeThresholds
    }

    private fun getTotalBufferBytesAllocated(playerId: PlayerId): Int {
        return loadingStates.get(playerId)!!.allocatedCounts * allocator.individualAllocationLength
    }

    private fun getTargetBufferBytes(playerId: PlayerId): Int {
        return loadingStates.get(playerId)!!.targetBufferBytes
    }

    private fun heapHasEnoughHeadroomForPrioritizeTimeOverSizeThreshold(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        // Either the heap still has space to grow (total < max), or it is at max size but there is
        // still at least 4% free, including unused allocations inside DefaultAllocator (which are
        // already taking up heap space).
        return runtime.totalMemory() < maxMemory
                || runtime.freeMemory() + allocator.unusedBytesAllocated >= (maxMemory / 25)
    }

    private class PlayerLoadingState {
        var referenceCount: Int = 1
        var isLoading: Boolean = false
        var targetBufferBytes: Int = 0

        @get:Synchronized
        @GuardedBy("this")
        var allocatedCounts: Int = 0
            private set

        @Synchronized
        fun increaseAllocatedCounts() {
            allocatedCounts++
        }

        @Synchronized
        fun decreaseAllocatedCounts() {
            allocatedCounts--
        }
    }

    private inner class PlayerIdFilteringAllocatorImpl(@field:GuardedBy("this") private var playerId: PlayerId) :
        PlayerIdAwareAllocator {
        @GuardedBy("this")
        private val allocationPlayerIdMap: HashMap<Allocation, PlayerId>

        init {
            this.allocationPlayerIdMap = HashMap()
        }

        @Synchronized
        override fun setPlayerId(playerId: PlayerId) {
            this.playerId = playerId
        }

        @Synchronized
        override fun allocate(): Allocation {
            val allocation = allocator.allocate()
            allocationPlayerIdMap[allocation] = playerId
            val playerLoadingState = loadingStates[playerId]
            playerLoadingState?.increaseAllocatedCounts()
            return allocation
        }

        @Synchronized
        override fun release(allocation: Allocation) {
            allocator.release(allocation)
            releaseInternal(allocation)
        }

        @Synchronized
        override fun release(allocationNode: Allocator.AllocationNode) {
            var allocationNode = allocationNode
            allocator.release(allocationNode)
            while (allocationNode != null) {
                releaseInternal(allocationNode.allocation)
                allocationNode = allocationNode.next()!!
            }
        }

        @Synchronized
        override fun trim() {
            allocator.trim()
        }

        override val totalBytesAllocated: Int
            @Synchronized
            get() = getTotalBufferBytesAllocated(playerId)

        @get:Synchronized
        override val individualAllocationLength: Int
            get() = allocator.individualAllocationLength

        @GuardedBy("this")
        fun releaseInternal(allocation: Allocation) {
            val playerId = allocationPlayerIdMap.remove(allocation)!!
            val playerLoadingState = loadingStates[playerId]
            playerLoadingState?.decreaseAllocatedCounts()
        }
    }

    companion object {
        const val DEFAULT_MIN_BUFFER_MS: Int = 50000

        // Load at least as much as needed for DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS
        const val DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS: Int = 1000
        const val DEFAULT_MAX_BUFFER_MS: Int = 50000

        // Upper limit for low-bitrate streams that is also high enough to cover most reasonable timestamp
        // gaps in the media as well.
        const val DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS: Int = 50000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_MS: Int = 1000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS: Int = 1000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 2000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS: Int = 1000
        val DEFAULT_TARGET_BUFFER_BYTES: Int = C.LENGTH_UNSET
        const val DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS: Boolean = false

        // Ensure DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS even if target buffer size in bytes is reached
        // for extremely high-bitrate content.
        const val DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK: Boolean = true
        const val DEFAULT_BACK_BUFFER_DURATION_MS: Int = 0
        const val DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME: Boolean = false
        val DEFAULT_VIDEO_BUFFER_SIZE: Int = 2000 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK: Int = 300 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_AUDIO_BUFFER_SIZE: Int = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_TEXT_BUFFER_SIZE: Int = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_METADATA_BUFFER_SIZE: Int = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_CAMERA_MOTION_BUFFER_SIZE: Int = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_IMAGE_BUFFER_SIZE: Int = 400 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_MUXED_BUFFER_SIZE: Int =
            DEFAULT_VIDEO_BUFFER_SIZE + DEFAULT_AUDIO_BUFFER_SIZE + DEFAULT_TEXT_BUFFER_SIZE
        val DEFAULT_MIN_BUFFER_SIZE: Int = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE

        // Assuming a muxed file with video, multiple audio and text languages and an image track.
        val DEFAULT_MAX_BUFFER_SIZE: Int = (DEFAULT_VIDEO_BUFFER_SIZE
                + 4 * DEFAULT_AUDIO_BUFFER_SIZE + 4 * DEFAULT_TEXT_BUFFER_SIZE + DEFAULT_IMAGE_BUFFER_SIZE)
        val DEFAULT_TARGET_BUFFER_BYTES_FOR_PRELOAD: Int =
            DEFAULT_VIDEO_BUFFER_SIZE + DEFAULT_AUDIO_BUFFER_SIZE

        @Suppress("deprecation") // Accepting deprecated RawResourceDataSource.RAW_RESOURCE_SCHEME
        val LOCAL_PLAYBACK_SCHEMES = listOf(
            ContentResolver.SCHEME_FILE,
            ContentResolver.SCHEME_CONTENT,
            DataSchemeDataSource.SCHEME_DATA,
            ContentResolver.SCHEME_ANDROID_RESOURCE,
            RawResourceDataSource.RAW_RESOURCE_SCHEME,
            "asset"
        )

        private const val TAG = "DefaultLoadControl"

        private fun getDefaultBufferSize(
            trackType: @C.TrackType Int,
            isLocalPlayback: Boolean
        ): Int {
            when (trackType) {
                C.TRACK_TYPE_DEFAULT -> return DEFAULT_MUXED_BUFFER_SIZE
                C.TRACK_TYPE_AUDIO -> return DEFAULT_AUDIO_BUFFER_SIZE
                C.TRACK_TYPE_VIDEO -> return if (isLocalPlayback)
                    DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK
                else
                    DEFAULT_VIDEO_BUFFER_SIZE

                C.TRACK_TYPE_TEXT -> return DEFAULT_TEXT_BUFFER_SIZE
                C.TRACK_TYPE_METADATA -> return DEFAULT_METADATA_BUFFER_SIZE
                C.TRACK_TYPE_CAMERA_MOTION -> return DEFAULT_CAMERA_MOTION_BUFFER_SIZE
                C.TRACK_TYPE_IMAGE -> return DEFAULT_IMAGE_BUFFER_SIZE
                C.TRACK_TYPE_NONE -> return 0
                C.TRACK_TYPE_UNKNOWN -> return DEFAULT_MIN_BUFFER_SIZE
                else -> throw IllegalArgumentException()
            }
        }

        private fun assertGreaterOrEqual(value1: Int, value2: Int, name1: String?, name2: String?) {
            Preconditions.checkArgument(value1 >= value2, "%s cannot be less than %s", name1, name2)
        }
    }
}
