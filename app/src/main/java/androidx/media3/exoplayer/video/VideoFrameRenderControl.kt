package androidx.media3.exoplayer.video

import androidx.media3.common.C
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Clock
import androidx.media3.common.util.LongArrayQueue
import androidx.media3.common.util.TimedValueQueue
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameReleaseAction
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameReleaseInfo
import androidx.media3.exoplayer.video.VideoSink.FirstFrameReleaseInstruction
import com.google.common.base.Preconditions

internal class VideoFrameRenderControl(
    private val frameRenderer: FrameRenderer,
    private val videoFrameReleaseControl: VideoFrameReleaseControl,
    private val videoFrameReleaseEarlyTimeForecaster: VideoFrameReleaseEarlyTimeForecaster,
    private val frameRateEstimator: FixedFrameRateEstimator
) {
    internal interface FrameRenderer {
        fun onVideoSizeChanged(videoSize: VideoSize)
        fun renderFrame(renderTimeNs: Long, presentationTimeUs: Long, isFirstFrame: Boolean)
        fun dropFrame()
    }

    private val videoFrameReleaseInfo: FrameReleaseInfo
    private val videoSizes: TimedValueQueue<VideoSize?>
    private val streamStartPositionsUs: TimedValueQueue<Long?>
    private val presentationTimestampsUs: LongArrayQueue
    private var latestInputPresentationTimeUs: Long
    private var latestOutputPresentationTimeUs: Long
    private var lastPresentationTimeUs: Long
    private var outputVideoSize: VideoSize
    private var outputStreamStartPositionUs: Long = 0

    init {
        videoFrameReleaseInfo = FrameReleaseInfo()
        videoSizes = TimedValueQueue<VideoSize?>()
        streamStartPositionsUs = TimedValueQueue<Long?>()
        presentationTimestampsUs = LongArrayQueue()
        latestInputPresentationTimeUs = C.TIME_UNSET
        outputVideoSize = VideoSize.UNKNOWN
        latestOutputPresentationTimeUs = C.TIME_UNSET
        lastPresentationTimeUs = C.TIME_UNSET
    }

    fun flush() {
        presentationTimestampsUs.clear()
        latestInputPresentationTimeUs = C.TIME_UNSET
        latestOutputPresentationTimeUs = C.TIME_UNSET
        lastPresentationTimeUs = C.TIME_UNSET
        if (streamStartPositionsUs.size() > 0) {
            // There is a pending streaming start position change. If seeking within the same stream, keep
            // the pending start position with max timestamp to ensure the start position is applied on
            // the frames after flushing. Otherwise if seeking to another stream, a new start position
            // will be set before a new frame arrives so we'll be able to apply the new start position.
            outputStreamStartPositionUs = getLastAndClear<Long?>(streamStartPositionsUs)!!
        }
        if (videoSizes.size() > 0) {
            // Do not clear the last pending video size, we still want to report the size change after a
            // flush. If after the flush, a new video size is announced, it will be used instead.
            val lastVideoSize: VideoSize = getLastAndClear<VideoSize>(videoSizes)!!
            videoSizes.add( 0, lastVideoSize)
        }
    }

    @Throws(ExoPlaybackException::class)
    fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        while (!presentationTimestampsUs.isEmpty) {
            val presentationTimeUs = presentationTimestampsUs.element()
            // Check whether this buffer comes with a new stream.
            if (maybeUpdateOutputStream(presentationTimeUs)) {
                videoFrameReleaseControl.onStreamChanged(
                    VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED
                )
            }
            frameRateEstimator.onNextFrame(presentationTimeUs * 1000)
            val frameReleaseAction: @FrameReleaseAction Int =
                videoFrameReleaseControl.getFrameReleaseAction(
                    presentationTimeUs,
                    positionUs,
                    elapsedRealtimeUs,
                    outputStreamStartPositionUs,  
                    false,  
                    false,
                    frameRateEstimator.frameDurationNs,
                    frameRateEstimator.frameIndex,
                    videoFrameReleaseInfo
                )
            if (frameReleaseAction != VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER
                && frameReleaseAction != VideoFrameReleaseControl.FRAME_RELEASE_IGNORE
            ) {
                videoFrameReleaseEarlyTimeForecaster.onVideoFrameProcessed(
                    presentationTimeUs, videoFrameReleaseInfo.earlyUs
                )
            }
            when (frameReleaseAction) {
                VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER -> return
                VideoFrameReleaseControl.FRAME_RELEASE_SKIP, VideoFrameReleaseControl.FRAME_RELEASE_DROP -> {
                    latestOutputPresentationTimeUs = presentationTimeUs
                    dropFrame()
                }

                VideoFrameReleaseControl.FRAME_RELEASE_IGNORE -> latestOutputPresentationTimeUs =
                    presentationTimeUs

                VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY, VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED -> {
                    latestOutputPresentationTimeUs = presentationTimeUs
                    renderFrame( 
                        frameReleaseAction
                                == VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY
                    )
                }

                else -> throw IllegalStateException(frameReleaseAction.toString())
            }
        }
    }

    fun onVideoSizeChanged(width: Int, height: Int) {
        videoSizes.add(
            if (latestInputPresentationTimeUs == C.TIME_UNSET) 0 else latestInputPresentationTimeUs + 1,
            VideoSize(width, height)
        )
    }

    fun onStreamChanged(
        firstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int,
        streamStartPositionUs: Long
    ) {
        if (presentationTimestampsUs.isEmpty) {
            videoFrameReleaseControl.onStreamChanged(firstFrameReleaseInstruction)
            outputStreamStartPositionUs = streamStartPositionUs
        } else {
            // Add a start position to the queue with a large negative timestamp to always apply it as
            // long as it is the only one in the queue.
            streamStartPositionsUs.add(
                if (latestInputPresentationTimeUs == C.TIME_UNSET)
                    Long.MIN_VALUE / 2
                else
                    latestInputPresentationTimeUs + 1,
                streamStartPositionUs
            )
        }
    }

    fun onFrameAvailableForRendering(presentationTimeUs: Long) {
        presentationTimestampsUs.add(presentationTimeUs)
        latestInputPresentationTimeUs = presentationTimeUs
        lastPresentationTimeUs = C.TIME_UNSET
    }

    fun signalEndOfInput() {
        if (latestInputPresentationTimeUs == C.TIME_UNSET) {
            // If EOS is signalled right after a flush without receiving a frame (could happen with frame
            // replaying as available frame is not reported to the render control), set the latest input
            // and output timestamp to end of source to ensure isEnded() returns true.
            latestInputPresentationTimeUs = C.TIME_END_OF_SOURCE
            latestOutputPresentationTimeUs = C.TIME_END_OF_SOURCE
        }
        lastPresentationTimeUs = latestInputPresentationTimeUs
    }

    val isEnded: Boolean
        get() = lastPresentationTimeUs != C.TIME_UNSET
                && latestOutputPresentationTimeUs == lastPresentationTimeUs

    private fun dropFrame() {
        presentationTimestampsUs.remove()
        frameRenderer.dropFrame()
    }

    private fun renderFrame(shouldRenderImmediately: Boolean) {
        val presentationTimeUs = presentationTimestampsUs.remove()

        val videoSizeUpdated = maybeUpdateOutputVideoSize(presentationTimeUs)
        if (videoSizeUpdated) {
            frameRenderer.onVideoSizeChanged(outputVideoSize)
        }
        val renderTimeNs =
            if (shouldRenderImmediately)
                Clock.DEFAULT.nanoTime()
            else
                videoFrameReleaseInfo.releaseTimeNs
        frameRenderer.renderFrame(
            renderTimeNs, presentationTimeUs, videoFrameReleaseControl.onFrameReleasedIsFirstFrame()
        )
    }

    private fun maybeUpdateOutputStream(presentationTimeUs: Long): Boolean {
        val newOutputStreamStartPositionUs = streamStartPositionsUs.pollFloor(presentationTimeUs)
        if (newOutputStreamStartPositionUs != null
            && newOutputStreamStartPositionUs != outputStreamStartPositionUs
        ) {
            outputStreamStartPositionUs = newOutputStreamStartPositionUs
            return true
        }
        return false
    }

    private fun maybeUpdateOutputVideoSize(presentationTimeUs: Long): Boolean {
        val newOutputVideoSize = videoSizes.pollFloor(presentationTimeUs)
        if (newOutputVideoSize != null && (newOutputVideoSize != VideoSize.UNKNOWN) && (newOutputVideoSize != outputVideoSize)) {
            outputVideoSize = newOutputVideoSize
            return true
        }
        return false
    }

    companion object {
        private fun <T> getLastAndClear(queue: TimedValueQueue<T?>): T? {
            Preconditions.checkArgument(queue.size() > 0)
            while (queue.size() > 1) {
                queue.pollFirst()
            }
            return queue.pollFirst()!!
        }
    }
}
