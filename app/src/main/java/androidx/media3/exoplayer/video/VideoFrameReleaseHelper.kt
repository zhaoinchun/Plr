
package androidx.media3.exoplayer.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Build
import android.os.Handler
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.view.Choreographer.VsyncCallback
import android.view.Display
import android.view.Surface
import androidx.annotation.CallSuper
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w

import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import com.google.common.base.Preconditions
import kotlin.concurrent.Volatile
import kotlin.math.abs

class VideoFrameReleaseHelper(private val context: Context) {
    private var vsyncSamplerBuilt = false
    private var vsyncSampler: VSyncSampler? = null
    private var started = false
    private var surface: Surface? = null
    private var surfaceMediaFrameRate = 0f
    private var surfacePlaybackFrameRate = 0f
    private var playbackSpeed = 1f

    @C.VideoChangeFrameRateStrategy
    private var changeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int

    private var lastVsyncHysteresisOffsetNs: Long = 0
    private var pendingVsyncHysteresisOffsetNs: Long = 0

    private var pendingLastAdjustedFrameIndex: Long = 0
    private var pendingLastAdjustedReleaseTimeNs: Long = 0
    private var pendingLastPresentationTimeUs: Long = 0
    private var lastAdjustedFrameIndex: Long = 0
    private var lastAdjustedReleaseTimeNs: Long = 0
    private var lastAdjustedPresentationTimeUs: Long = 0
    private var vsyncSamplerListener: VSyncSamplerListener? = null

     interface VSyncSamplerListener {
        fun onVsyncSampled(sampler: Any?)
    }

    init {
        changeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
    }

    fun setChangeFrameRateStrategy(
        @C.VideoChangeFrameRateStrategy changeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int
    ) {
        if (this.changeFrameRateStrategy == changeFrameRateStrategy) {
            return
        }
        this.changeFrameRateStrategy = changeFrameRateStrategy
        updateSurfacePlaybackFrameRate( true)
    }

    fun onStarted() {
        started = true
        resetAdjustment()
        if (!vsyncSamplerBuilt) {
            vsyncSampler = VSyncSampler.maybeBuildInstance(context)
            if (vsyncSampler != null) {
                vsyncSampler!!.listener = vsyncSamplerListener
            }
            vsyncSamplerBuilt = true
        }
        if (vsyncSampler != null) {
            vsyncSampler!!.register()
        }
        updateSurfacePlaybackFrameRate( false)
    }

    fun onSurfaceChanged(surface: Surface?) {
        if (this.surface === surface) {
            return
        }
        clearSurfaceFrameRate()
        this.surface = surface
        updateSurfacePlaybackFrameRate( true)
    }

    fun onPositionReset() {
        resetAdjustment()
    }

    fun onPlaybackSpeed(playbackSpeed: Float) {
        this.playbackSpeed = playbackSpeed
        updateSurfacePlaybackFrameRate( false)
    }

    fun setSurfaceMediaFrameRate(surfaceMediaFrameRate: Float) {
        if (this.surfaceMediaFrameRate == surfaceMediaFrameRate) {
            return
        }
        this.surfaceMediaFrameRate = surfaceMediaFrameRate
        updateSurfacePlaybackFrameRate( false)
    }

    fun onStopped() {
        started = false
        if (vsyncSampler != null) {
            vsyncSampler!!.unregister()
        }
        clearSurfaceFrameRate()
    }

    fun adjustReleaseTime(
        releaseTimeNs: Long, presentationTimeUs: Long, frameDurationNs: Long, frameIndex: Long
    ): Long {
        if (presentationTimeUs != pendingLastPresentationTimeUs) {
            lastAdjustedFrameIndex = pendingLastAdjustedFrameIndex
            lastAdjustedReleaseTimeNs = pendingLastAdjustedReleaseTimeNs
            lastAdjustedPresentationTimeUs = pendingLastPresentationTimeUs
            lastVsyncHysteresisOffsetNs = pendingVsyncHysteresisOffsetNs
        }

        // Until we know better, the adjustment will be a no-op.
        var adjustedReleaseTimeNs = releaseTimeNs

        if (lastAdjustedFrameIndex != C.INDEX_UNSET.toLong()) {
            val elapsedReleaseTimeSinceLastFrameNs: Long
            if (frameDurationNs != C.TIME_UNSET) {
                elapsedReleaseTimeSinceLastFrameNs =
                    ((frameDurationNs * (frameIndex - lastAdjustedFrameIndex)) / playbackSpeed).toLong()
            } else {
                elapsedReleaseTimeSinceLastFrameNs =
                    ((presentationTimeUs - lastAdjustedPresentationTimeUs) * 1000 / playbackSpeed).toLong()
            }
            val candidateAdjustedReleaseTimeNs =
                lastAdjustedReleaseTimeNs + elapsedReleaseTimeSinceLastFrameNs
            if (adjustmentAllowed(releaseTimeNs, candidateAdjustedReleaseTimeNs)) {
                adjustedReleaseTimeNs = candidateAdjustedReleaseTimeNs
            } else {
                resetAdjustment()
            }
        }
        pendingLastAdjustedFrameIndex = frameIndex
        pendingLastAdjustedReleaseTimeNs = adjustedReleaseTimeNs
        pendingLastPresentationTimeUs = presentationTimeUs

        if (vsyncSampler == null) {
            return adjustedReleaseTimeNs
        }
        val sampledVsyncTimeNs = vsyncSampler!!.sampledVsyncTimeNs
        val vsyncDurationNs = vsyncSampler!!.vsyncDurationNs
        if (sampledVsyncTimeNs == C.TIME_UNSET || vsyncDurationNs == C.TIME_UNSET) {
            return adjustedReleaseTimeNs
        }
        // Find the timestamp of the closest vsync. This is the vsync that we're targeting.
        val snappedTimeNs =
            findClosestVsyncAndUpdateHysteresis(
                adjustedReleaseTimeNs, sampledVsyncTimeNs, vsyncDurationNs
            )
        // Apply an offset so that we release before the target vsync, but after the previous one.
        return snappedTimeNs - (vsyncDurationNs * VSYNC_OFFSET_PERCENTAGE) / 100
    }

    @VisibleForTesting
    fun setVsyncData(vsyncSampleTimeNs: Long, vsyncDurationNs: Long) {
        vsyncSampler!!.sampledVsyncTimeNs =
            vsyncSampleTimeNs
        vsyncSampler!!.vsyncDurationNs = vsyncDurationNs
    }

    @VisibleForTesting
    fun setVSyncSamplerListener(listener: VSyncSamplerListener?) {
        this.vsyncSamplerListener = listener
        if (vsyncSampler != null) {
            vsyncSampler!!.listener = listener
        }
    }

    private fun resetAdjustment() {
        lastAdjustedFrameIndex = C.INDEX_UNSET.toLong()
        pendingLastAdjustedFrameIndex = C.INDEX_UNSET.toLong()
        pendingLastPresentationTimeUs = C.TIME_UNSET
        lastVsyncHysteresisOffsetNs = 0
        pendingVsyncHysteresisOffsetNs = 0
    }

    private fun updateSurfacePlaybackFrameRate(forceUpdate: Boolean) {
        if (Build.VERSION.SDK_INT < 30 || surface == null || changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF || !surface!!.isValid()) {
            return
        }

        var surfacePlaybackFrameRate = 0f
        if (started && surfaceMediaFrameRate != Format.NO_VALUE.toFloat()) {
            surfacePlaybackFrameRate = surfaceMediaFrameRate * playbackSpeed
        }
        // We always set the frame-rate if we have a new surface, since we have no way of knowing what
        // it might have been set to previously.
        if (!forceUpdate && this.surfacePlaybackFrameRate == surfacePlaybackFrameRate) {
            return
        }
        this.surfacePlaybackFrameRate = surfacePlaybackFrameRate
        Api30.setSurfaceFrameRate(surface!!, surfacePlaybackFrameRate)
    }

    private fun clearSurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < 30 || surface == null || changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF || surfacePlaybackFrameRate == 0f || !surface!!.isValid()) {
            return
        }
        surfacePlaybackFrameRate = 0f
        Api30.setSurfaceFrameRate(surface!!,  0f)
    }

    private fun findClosestVsyncAndUpdateHysteresis(
        releaseTimeNs: Long, sampledVsyncTimeNs: Long, vsyncDurationNs: Long
    ): Long {
        val vsyncCount = (releaseTimeNs - sampledVsyncTimeNs) / vsyncDurationNs
        val snappedTimeNs = sampledVsyncTimeNs + (vsyncDurationNs * vsyncCount)
        val snappedBeforeNs: Long
        val snappedAfterNs: Long
        if (releaseTimeNs <= snappedTimeNs) {
            snappedBeforeNs = snappedTimeNs - vsyncDurationNs
            snappedAfterNs = snappedTimeNs
        } else {
            snappedBeforeNs = snappedTimeNs
            snappedAfterNs = snappedTimeNs + vsyncDurationNs
        }
        val snappedAfterDiffNs = snappedAfterNs - releaseTimeNs
        val snappedBeforeDiffNs = releaseTimeNs - snappedBeforeNs

        // Many frame rates oscillate between a clear match and an ambiguous match (e.g. 60fps on 90Hz).
        // Only evaluate hysteresis if the diffs are sufficiently close for it to matter and ignore the
        // clearer matches in between.
        val snappedDiffsDiffNs = abs(snappedAfterDiffNs - snappedBeforeDiffNs)
        val shouldEvaluateHysteresis = snappedDiffsDiffNs < vsyncDurationNs / 2
        if (shouldEvaluateHysteresis) {
            // Apply hysteresis logic: Clear if outside of range, initialize if newly within range.
            val hysteresisRangeNs = vsyncDurationNs / 4
            val isInHysteresisRange = snappedDiffsDiffNs < hysteresisRangeNs
            if (isInHysteresisRange) {
                if (lastVsyncHysteresisOffsetNs != 0L) {
                    pendingVsyncHysteresisOffsetNs = lastVsyncHysteresisOffsetNs
                } else {
                    pendingVsyncHysteresisOffsetNs =
                        if (snappedAfterDiffNs < snappedBeforeDiffNs) -hysteresisRangeNs else hysteresisRangeNs
                }
            } else {
                pendingVsyncHysteresisOffsetNs = 0
            }
        } else {
            pendingVsyncHysteresisOffsetNs = lastVsyncHysteresisOffsetNs
        }
        return if (snappedAfterDiffNs + pendingVsyncHysteresisOffsetNs < snappedBeforeDiffNs)
            snappedAfterNs
        else
            snappedBeforeNs
    }

    @RequiresApi(30)
    private object Api30 {
        fun setSurfaceFrameRate(surface: Surface, frameRate: Float) {
            val compatibility =
                if (frameRate == 0f)
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                else
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            try {
                surface.setFrameRate(frameRate, compatibility)
            } catch (e: IllegalStateException) {
                e(TAG, "Failed to call Surface.setFrameRate", e)
            }
        }
    }

    private abstract class VSyncSampler(val choreographer: Choreographer, 
                                        val displayManager: DisplayManager
    ) : DisplayListener {
        
        @Volatile
        var sampledVsyncTimeNs: Long

        @Volatile
        var vsyncDurationNs: Long

        var listener:  VSyncSamplerListener? = null

        init {
            sampledVsyncTimeNs = C.TIME_UNSET
            vsyncDurationNs = C.TIME_UNSET
        }

        @CallSuper
        open fun register() {
            displayManager.registerDisplayListener(this, createHandlerForCurrentLooper())
        }

        @CallSuper
        open fun unregister() {
            displayManager.unregisterDisplayListener(this)
        }

        override fun onDisplayAdded(displayId: Int) {
            // Do nothing.
        }

        override fun onDisplayRemoved(displayId: Int) {
            // Do nothing.
        }

        companion object {
             fun maybeBuildInstance(context: Context): VSyncSampler? {
                val displayManager =
                    context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
                if (displayManager == null) {
                    return null
                }
                val choreographer: Choreographer
                try {
                    choreographer = Choreographer.getInstance()
                } catch (e: RuntimeException) {
                    // See [Internal: b/213926330].
                    w(TAG, "Vsync sampling disabled due to platform error", e)
                    return null
                }
                return if (Build.VERSION.SDK_INT >= 33)
                    VSyncSamplerV33(choreographer, displayManager)
                else
                    VSyncSamplerBase(choreographer, displayManager)
            }
        }
    }

    private class VSyncSamplerBase(choreographer: Choreographer, displayManager: DisplayManager) :
        VSyncSampler(choreographer, displayManager), FrameCallback {
        override fun register() {
            super.register()
            choreographer.removeFrameCallback(this)
            choreographer.postFrameCallback(this)
            vsyncDurationNs = getVsyncDurationNsFromDefaultDisplay(displayManager)
        }

        override fun unregister() {
            super.unregister()
            choreographer.removeFrameCallback(this)
            sampledVsyncTimeNs = C.TIME_UNSET
            vsyncDurationNs = C.TIME_UNSET
        }

        override fun doFrame(vsyncTimeNs: Long) {
            sampledVsyncTimeNs = vsyncTimeNs
            choreographer.removeFrameCallback(this)
            choreographer.postFrameCallbackDelayed(this, VSYNC_SAMPLE_UPDATE_PERIOD_MS)
            if (listener != null) {
                listener!!.onVsyncSampled(this)
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                choreographer.removeFrameCallback(this)
                choreographer.postFrameCallback(this)
                vsyncDurationNs = getVsyncDurationNsFromDefaultDisplay(displayManager)
            }
        }

        companion object {
            private fun getVsyncDurationNsFromDefaultDisplay(displayManager: DisplayManager): Long {
                val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                if (defaultDisplay != null) {
                    val defaultDisplayRefreshRate = defaultDisplay.getRefreshRate().toDouble()
                    return (C.NANOS_PER_SECOND / defaultDisplayRefreshRate).toLong()
                } else {
                    w(TAG, "Unable to query display refresh rate")
                    return C.TIME_UNSET
                }
            }
        }
    }

    @RequiresApi(33)
    private class VSyncSamplerV33(choreographer: Choreographer, displayManager: DisplayManager) :
        VSyncSampler(choreographer, displayManager), VsyncCallback {
        private val handler: Handler

        init {
            this.handler = createHandlerForCurrentLooper()
        }

        override fun register() {
            super.register()
            choreographer.removeVsyncCallback(this)
            choreographer.postVsyncCallback(this)
        }

        override fun unregister() {
            super.unregister()
            handler.removeCallbacksAndMessages( null)
            choreographer.removeVsyncCallback(this)
            sampledVsyncTimeNs = C.TIME_UNSET
            vsyncDurationNs = C.TIME_UNSET
        }

        override fun onVsync(data: Choreographer.FrameData) {
            sampledVsyncTimeNs = data.getFrameTimeNanos()
            val frameTimelines = data.getFrameTimelines()
            if (frameTimelines.size >= 2) {
                val vsyncDurationNs =
                    (frameTimelines[1]!!.getExpectedPresentationTimeNanos()
                            - frameTimelines[0]!!.getExpectedPresentationTimeNanos())
                this.vsyncDurationNs = if (vsyncDurationNs == 0L) C.TIME_UNSET else vsyncDurationNs
            } else {
                vsyncDurationNs = C.TIME_UNSET
            }
            handler.removeCallbacksAndMessages( null)
            handler.postDelayed(
                Runnable { choreographer.postVsyncCallback(this) }, VSYNC_SAMPLE_UPDATE_PERIOD_MS
            )
            if (listener != null) {
                listener!!.onVsyncSampled(this)
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handler.removeCallbacksAndMessages( null)
                choreographer.removeVsyncCallback(this)
                choreographer.postVsyncCallback(this)
            }
        }
    }

    companion object {
        private const val TAG = "VideoFrameReleaseHelper"

        @VisibleForTesting
        const val VSYNC_SAMPLE_UPDATE_PERIOD_MS: Long = 500
        private const val MAX_ALLOWED_ADJUSTMENT_NS: Long = 20000000
        private const val VSYNC_OFFSET_PERCENTAGE: Long = 80

        private fun adjustmentAllowed(
            unadjustedReleaseTimeNs: Long, adjustedReleaseTimeNs: Long
        ): Boolean {
            return abs(unadjustedReleaseTimeNs - adjustedReleaseTimeNs) <= MAX_ALLOWED_ADJUSTMENT_NS
        }
    }
}
