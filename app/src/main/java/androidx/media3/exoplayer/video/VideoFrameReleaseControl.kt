package androidx.media3.exoplayer.video

import android.content.Context
import android.view.Surface
import androidx.annotation.FloatRange
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.util.Clock

import androidx.media3.common.util.Util.msToUs
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.video.VideoSink.FirstFrameReleaseInstruction
import com.google.common.base.Preconditions
import kotlin.math.min

class VideoFrameReleaseControl(
    applicationContext: Context,
    private val frameTimingEvaluator: FrameTimingEvaluator,
    private val allowedJoiningTimeMs: Long
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    
    @IntDef(
       value =  [FRAME_RELEASE_IMMEDIATELY, FRAME_RELEASE_SCHEDULED, FRAME_RELEASE_DROP, FRAME_RELEASE_SKIP, FRAME_RELEASE_IGNORE, FRAME_RELEASE_TRY_AGAIN_LATER
        ]
    )
    annotation class FrameReleaseAction

    class FrameReleaseInfo {
        var earlyUs=C.TIME_UNSET
        var releaseTimeNs= C.TIME_UNSET

         fun reset() {
            earlyUs = C.TIME_UNSET
            releaseTimeNs = C.TIME_UNSET
        }
    }

    interface FrameTimingEvaluator {
        fun shouldForceReleaseFrame(earlyUs: Long, elapsedSinceLastReleaseUs: Long): Boolean
        fun shouldDropFrame(earlyUs: Long, elapsedRealtimeUs: Long, isLastFrame: Boolean): Boolean
        @Throws(ExoPlaybackException::class)
        fun shouldIgnoreFrame(
            earlyUs: Long,
            positionUs: Long,
            elapsedRealtimeUs: Long,
            isLastFrame: Boolean,
            treatDroppedBuffersAsSkipped: Boolean
        ): Boolean
    }

    private var earlySchedulingThresholdUs: Long

    private val frameReleaseHelper: VideoFrameReleaseHelper

    private var started = false
    private var firstFrameState: @C.FirstFrameState Int
    private var initialPositionUs: Long
    private var lastReleaseRealtimeUs: Long = 0
    private var joiningDeadlineMs: Long
    private var joiningRenderNextFrameImmediately = false
    private var playbackSpeed: Float
    private var clock: Clock
    private var hasOutputSurface = false
    private var frameReadyWithoutSurface = false
    private var disableAdvancingTimestampChecks = false
    private var requiresOutputSurface: Boolean

    init {
        frameReleaseHelper = VideoFrameReleaseHelper(applicationContext)
        firstFrameState = C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED
        initialPositionUs = C.TIME_UNSET
        joiningDeadlineMs = C.TIME_UNSET
        playbackSpeed = 1f
        clock = Clock.DEFAULT
        requiresOutputSurface = true
        earlySchedulingThresholdUs =
            MediaCodecVideoRenderer.DEFAULT_EARLY_SCHEDULING_THRESHOLD_US
    }

    fun setEarlySchedulingThresholdUs(earlySchedulingThresholdUs: Long) {
        this.earlySchedulingThresholdUs = earlySchedulingThresholdUs
    }

    fun onStreamChanged(
        firstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int
    ) {
        when (firstFrameReleaseInstruction) {
            VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY -> firstFrameState =
                C.FIRST_FRAME_NOT_RENDERED

            VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED -> firstFrameState =
                C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED

            VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED -> lowerFirstFrameState(
                C.FIRST_FRAME_NOT_RENDERED_AFTER_STREAM_CHANGE
            )

            else -> throw IllegalStateException()
        }
        frameReleaseHelper.onPositionReset()
    }

    fun onStarted() {
        started = true
        lastReleaseRealtimeUs = msToUs(clock.elapsedRealtime())
        frameReleaseHelper.onStarted()
    }

    fun onStopped() {
        started = false
        joiningDeadlineMs = C.TIME_UNSET
        frameReleaseHelper.onStopped()
    }

    fun setOutputSurface(outputSurface: Surface?) {
        hasOutputSurface = outputSurface != null
        frameReadyWithoutSurface = false
        frameReleaseHelper.onSurfaceChanged(outputSurface)
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED)
    }

    fun setSurfaceMediaFrameRate(surfaceMediaFrameRate: Float) {
        frameReleaseHelper.setSurfaceMediaFrameRate(surfaceMediaFrameRate)
    }

    fun setRequiresOutputSurface(requiresOutputSurface: Boolean) {
        this.requiresOutputSurface = requiresOutputSurface
    }

    fun onFrameReleasedIsFirstFrame(): Boolean {
        val firstFrame = firstFrameState != C.FIRST_FRAME_RENDERED
        firstFrameState = C.FIRST_FRAME_RENDERED
        lastReleaseRealtimeUs = msToUs(clock.elapsedRealtime())
        return firstFrame
    }

    fun setClock(clock: Clock) {
        this.clock = clock
    }

    fun allowReleaseFirstFrameBeforeStarted() {
        if (firstFrameState == C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED) {
            firstFrameState = C.FIRST_FRAME_NOT_RENDERED
        }
    }

    fun isReady(otherwiseReady: Boolean): Boolean {
        if (otherwiseReady
            && (firstFrameState == C.FIRST_FRAME_RENDERED
                    || (frameReadyWithoutSurface && (!hasOutputSurface || !requiresOutputSurface)))
        ) {
            // Ready. If we were joining then we've now joined, so clear the joining deadline.
            joiningDeadlineMs = C.TIME_UNSET
            return true
        } else if (joiningDeadlineMs == C.TIME_UNSET) {
            // Not joining.
            return false
        } else if (clock.elapsedRealtime() < joiningDeadlineMs) {
            // Joining and still withing the deadline.
            return true
        } else {
            // The joining deadline has been exceeded. Give up and clear the deadline.
            joiningDeadlineMs = C.TIME_UNSET
            return false
        }
    }

    fun join(renderNextFrameImmediately: Boolean) {
        joiningRenderNextFrameImmediately = renderNextFrameImmediately
        joiningDeadlineMs =
            if (allowedJoiningTimeMs > 0) (clock.elapsedRealtime() + allowedJoiningTimeMs) else C.TIME_UNSET
    }

    val isJoining: Boolean
        get() = joiningDeadlineMs != C.TIME_UNSET

    @Throws(ExoPlaybackException::class)
    fun getFrameReleaseAction(
        presentationTimeUs: Long,
        positionUs: Long,
        elapsedRealtimeUs: Long,
        outputStreamStartPositionUs: Long,
        isDecodeOnlyFrame: Boolean,
        isLastFrame: Boolean,
        frameDurationNs: Long,
        frameIndex: Long,
        frameReleaseInfo: FrameReleaseInfo
    ): @FrameReleaseAction Int {
        frameReleaseInfo.reset()

        if (started && initialPositionUs == C.TIME_UNSET) {
            initialPositionUs = positionUs
        }

        frameReleaseInfo.earlyUs =
            calculateEarlyTimeUs(positionUs, elapsedRealtimeUs, presentationTimeUs)

        if (isDecodeOnlyFrame && !isLastFrame) {
            return FRAME_RELEASE_SKIP
        }
        if (!hasOutputSurface && requiresOutputSurface) {
            // Skip frames in sync with playback, so we'll be at the right frame if a surface is set.
            if (frameTimingEvaluator.shouldIgnoreFrame(
                    frameReleaseInfo.earlyUs,
                    positionUs,
                    elapsedRealtimeUs,
                    isLastFrame,  
                    true
                )
            ) {
                return FRAME_RELEASE_IGNORE
            }
            if (started && frameReleaseInfo.earlyUs < 30000) {
                return FRAME_RELEASE_SKIP
            }
            frameReadyWithoutSurface = true
            return FRAME_RELEASE_TRY_AGAIN_LATER
        }
        if (!requiresOutputSurface) {
            frameReadyWithoutSurface = true
        }
        if (shouldForceRelease(positionUs, frameReleaseInfo.earlyUs, outputStreamStartPositionUs)) {
            return FRAME_RELEASE_IMMEDIATELY
        }
        if (!started || positionUs == initialPositionUs) {
            return FRAME_RELEASE_TRY_AGAIN_LATER
        }

        // Calculate release time and adjust earlyUs to screen vsync.
        val systemTimeNs = clock.nanoTime()
        frameReleaseInfo.releaseTimeNs =
            frameReleaseHelper.adjustReleaseTime(
                systemTimeNs + (frameReleaseInfo.earlyUs * 1000),
                presentationTimeUs,
                frameDurationNs,
                frameIndex
            )
        frameReleaseInfo.earlyUs = (frameReleaseInfo.releaseTimeNs - systemTimeNs) / 1000
        // While joining, late frames are skipped while we catch up with the playback position.
        val treatDropAsSkip =
            joiningDeadlineMs != C.TIME_UNSET && !joiningRenderNextFrameImmediately
        if (frameTimingEvaluator.shouldIgnoreFrame(
                frameReleaseInfo.earlyUs,
                positionUs,
                elapsedRealtimeUs,
                isLastFrame,
                treatDropAsSkip
            )
        ) {
            return FRAME_RELEASE_IGNORE
        } else if (frameTimingEvaluator.shouldDropFrame(
                frameReleaseInfo.earlyUs, elapsedRealtimeUs, isLastFrame
            )
        ) {
            // While joining, dropped buffers are considered skipped.
            return if (treatDropAsSkip) FRAME_RELEASE_SKIP else FRAME_RELEASE_DROP
        } else if (frameReleaseInfo.earlyUs > earlySchedulingThresholdUs) {
            return FRAME_RELEASE_TRY_AGAIN_LATER
        }
        return FRAME_RELEASE_SCHEDULED
    }

    fun reset() {
        frameReleaseHelper.onPositionReset()
        initialPositionUs = C.TIME_UNSET
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED)
        joiningDeadlineMs = C.TIME_UNSET
        frameReadyWithoutSurface = false
    }

    fun setChangeFrameRateStrategy(
        @C.VideoChangeFrameRateStrategy changeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int
    ) {
        frameReleaseHelper.setChangeFrameRateStrategy(changeFrameRateStrategy)
    }

    fun setPlaybackSpeed(@FloatRange(from = 0.0, fromInclusive = false) speed: Float) {
        Preconditions.checkArgument(speed > 0)
        if (speed == playbackSpeed) {
            return
        }
        this.playbackSpeed = speed
        frameReleaseHelper.onPlaybackSpeed(speed)
    }

    fun experimentalDisableAdvancingTimestampChecks() {
        this.disableAdvancingTimestampChecks = true
    }

    private fun lowerFirstFrameState(firstFrameState: @C.FirstFrameState Int) {
        this.firstFrameState = min(this.firstFrameState, firstFrameState)
    }

    private fun calculateEarlyTimeUs(
        positionUs: Long, elapsedRealtimeUs: Long, framePresentationTimeUs: Long
    ): Long {
        // Calculate how early we are. In other words, calculate the realtime duration that needs to
        // elapse before the frame should be rendered. A negative value means that we're already late.
        // Note: Use of double rather than float is intentional for accuracy in the calculations below.
        var earlyUs = ((framePresentationTimeUs - positionUs) / playbackSpeed.toDouble()).toLong()
        if (started) {
            // Account for the elapsed time since the start of this iteration of the rendering loop.
            earlyUs -= msToUs(clock.elapsedRealtime()) - elapsedRealtimeUs
        }

        return earlyUs
    }

    private fun shouldForceRelease(
        positionUs: Long, earlyUs: Long, outputStreamStartPositionUs: Long
    ): Boolean {
        if (joiningDeadlineMs != C.TIME_UNSET && !joiningRenderNextFrameImmediately) {
            // No force releasing of the initial or late frames during joining unless requested.
            return false
        }
        when (firstFrameState) {
            C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED -> return started
            C.FIRST_FRAME_NOT_RENDERED -> return true
            C.FIRST_FRAME_NOT_RENDERED_AFTER_STREAM_CHANGE -> return positionUs >= outputStreamStartPositionUs
            C.FIRST_FRAME_RENDERED -> {
                val elapsedTimeSinceLastReleaseUs =
                    msToUs(clock.elapsedRealtime()) - lastReleaseRealtimeUs
                return started
                        && (disableAdvancingTimestampChecks
                        || (initialPositionUs != C.TIME_UNSET && initialPositionUs != positionUs))
                        && frameTimingEvaluator.shouldForceReleaseFrame(
                    earlyUs,
                    elapsedTimeSinceLastReleaseUs
                )
            }

            else -> throw IllegalStateException()
        }
    }

    companion object {
        const val FRAME_RELEASE_IMMEDIATELY: Int = 0
        const val FRAME_RELEASE_SCHEDULED: Int = 1
        const val FRAME_RELEASE_DROP: Int = 2
        const val FRAME_RELEASE_SKIP: Int = 3
        const val FRAME_RELEASE_IGNORE: Int = 4
        const val FRAME_RELEASE_TRY_AGAIN_LATER: Int = 5
    }
}
