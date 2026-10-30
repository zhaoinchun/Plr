
package androidx.media3.exoplayer

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_IMAGE
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.Log.e
import androidx.media3.exoplayer.image.ImageMetadataListener
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.Objects
import kotlin.math.min

internal class RendererHolder(
    private val primaryRenderer: Renderer,
    private val secondaryRenderer: Renderer?, // Index of renderer in renderer list held by the {@link Player}.
    private val index: Int
) {
    private var prewarmingState: @RendererPrewarmingState Int
    private var primaryRequiresReset = false
    private var secondaryRequiresReset = false

    fun hasSecondary(): Boolean {
        return secondaryRenderer != null
    }

    fun startPrewarming() {
        Preconditions.checkState(!this.isPrewarming)
        prewarmingState =
            if (isRendererEnabled(primaryRenderer))
                RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY
            else
                if (secondaryRenderer != null && isRendererEnabled(secondaryRenderer))
                    RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
                else
                    RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY
    }

    val isPrewarming: Boolean
        get() = this.isPrimaryRendererPrewarming || this.isSecondaryRendererPrewarming

    private val isPrimaryRendererPrewarming: Boolean
        get() = prewarmingState == RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY
                || prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY

    private val isSecondaryRendererPrewarming: Boolean
        get() = prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY

    val enabledRendererCount: Int
        get() {
            var result = 0
            result += if (isRendererEnabled(primaryRenderer)) 1 else 0
            result += if (secondaryRenderer != null && isRendererEnabled(
                    secondaryRenderer
                )
            ) 1 else 0
            return result
        }

    val trackType: @C.TrackType Int
        get() = primaryRenderer.trackType

    fun getReadingPositionUs(period: MediaPeriodHolder?): Long {
        return Objects.requireNonNull<Renderer?>(getRendererReadingFromPeriod(period))
            .readingPositionUs
    }

    fun hasReadPeriodToEnd(mediaPeriodHolder: MediaPeriodHolder?): Boolean {
        val renderer =getRendererReadingFromPeriod(mediaPeriodHolder)!!
        return renderer.hasReadStreamToEnd()
    }
    fun setCurrentStreamFinal(mediaPeriodHolder: MediaPeriodHolder?, streamEndPositionUs: Long) {
        val renderer =getRendererReadingFromPeriod(mediaPeriodHolder)!!
        setCurrentStreamFinalInternal(renderer, streamEndPositionUs)
    }

    fun setCurrentStreamFinal(streamEndPositionUs: Long) {
        val isPrimaryRenderer =
            secondaryRenderer == null || prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY || prewarmingState == RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY
        val renderer =
            if (isPrimaryRenderer) primaryRenderer else secondaryRenderer
        setCurrentStreamFinalInternal(renderer, streamEndPositionUs)
    }

    fun maybeSetOldStreamToFinal(
        oldTrackSelectorResult: TrackSelectorResult,
        newTrackSelectorResult: TrackSelectorResult,
        streamEndPositionUs: Long
    ) {
        val oldRendererEnabled = oldTrackSelectorResult.isRendererEnabled(index)
        val newRendererEnabled = newTrackSelectorResult.isRendererEnabled(index)
        val isPrimaryOldRenderer =
            secondaryRenderer == null || prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY || (prewarmingState == RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY
                    && isRendererEnabled(primaryRenderer))
        val oldRenderer =
            if (isPrimaryOldRenderer) primaryRenderer else secondaryRenderer!!
        if (oldRendererEnabled && !oldRenderer.isCurrentStreamFinal) {
            val isNoSampleRenderer = this.trackType == C.TRACK_TYPE_NONE
            val oldConfig = oldTrackSelectorResult.rendererConfigurations[index]
            val newConfig = newTrackSelectorResult.rendererConfigurations[index]
            if (!newRendererEnabled || (newConfig != oldConfig) || isNoSampleRenderer
                || this.isPrewarming
            ) {
                // The renderer will be disabled when transitioning to playing the next period, because
                // there's no new selection, or because a configuration change is required, or because
                // it's a no-sample renderer for which rendererOffsetUs should be updated only when
                // starting to play the next period, or there is a backup renderer that has already been
                // enabled for the following media item. Mark the SampleStream as final to play out any
                // remaining data.
                setCurrentStreamFinalInternal(oldRenderer, streamEndPositionUs)
            }
        }
    }

    fun setAllNonPrewarmingRendererStreamsFinal(streamEndPositionUs: Long) {
        if (isRendererEnabled(primaryRenderer)
            && prewarmingState != RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY && prewarmingState != RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY
        ) {
            setCurrentStreamFinalInternal(primaryRenderer, streamEndPositionUs)
        }
        if (secondaryRenderer != null && isRendererEnabled(secondaryRenderer)
            && prewarmingState != RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY
        ) {
            setCurrentStreamFinalInternal(secondaryRenderer, streamEndPositionUs)
        }
    }

    private fun setCurrentStreamFinalInternal(renderer: Renderer, streamEndPositionUs: Long) {
        renderer.setCurrentStreamFinal()
        if (renderer is TextRenderer) {
            renderer.setFinalStreamEndPositionUs(streamEndPositionUs)
        }
    }

    fun getMinDurationToProgressUs(
        rendererPositionUs: Long, rendererPositionElapsedRealtimeUs: Long
    ): Long {
        var minDurationToProgress =
            if (isRendererEnabled(primaryRenderer))
                primaryRenderer.getDurationToProgressUs(
                    rendererPositionUs, rendererPositionElapsedRealtimeUs
                )
            else Long.MAX_VALUE
        if (secondaryRenderer != null && isRendererEnabled(secondaryRenderer)) {
            minDurationToProgress =
                min(
                    minDurationToProgress,
                    secondaryRenderer.getDurationToProgressUs(
                        rendererPositionUs, rendererPositionElapsedRealtimeUs
                    )
                )
        }
        return minDurationToProgress
    }

    fun enableMayRenderStartOfStream() {
        if (isRendererEnabled(primaryRenderer)) {
            primaryRenderer.enableMayRenderStartOfStream()
        } else if (secondaryRenderer != null && isRendererEnabled(secondaryRenderer)) {
            secondaryRenderer.enableMayRenderStartOfStream()
        }
    }

    @Throws(ExoPlaybackException::class)
    fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        primaryRenderer.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
        if (secondaryRenderer != null) {
            secondaryRenderer.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
        }
    }

    fun setTimeline(timeline: Timeline) {
        primaryRenderer.setTimeline(timeline)
        secondaryRenderer?.setTimeline(timeline)
    }

    val isEnded: Boolean
        get() {
            var renderersEnded = true
            if (isRendererEnabled(primaryRenderer)) {
                renderersEnded = renderersEnded and primaryRenderer.isEnded
            }
            if (secondaryRenderer != null && isRendererEnabled(
                    secondaryRenderer
                )
            ) {
                renderersEnded = renderersEnded and secondaryRenderer.isEnded
            }
            return renderersEnded
        }

    fun isReadingFromPeriod(period: MediaPeriodHolder?): Boolean {
        return getRendererReadingFromPeriod(period) != null
    }

    fun isPrewarmingPeriod(period: MediaPeriodHolder?): Boolean {
        val isPrimaryRendererPrewarming =
            this.isPrimaryRendererPrewarming && getRendererReadingFromPeriod(period) === primaryRenderer
        val isSecondaryRendererPrewarming =
            this.isSecondaryRendererPrewarming
                    && getRendererReadingFromPeriod(period) === secondaryRenderer
        return isPrimaryRendererPrewarming || isSecondaryRendererPrewarming
    }

    fun hasFinishedReadingFromPeriod(periodHolder: MediaPeriodHolder): Boolean {
        return hasFinishedReadingFromPeriodInternal(periodHolder, primaryRenderer)
                && hasFinishedReadingFromPeriodInternal(periodHolder, secondaryRenderer)
    }

    private fun hasFinishedReadingFromPeriodInternal(
        readingPeriodHolder: MediaPeriodHolder, renderer: Renderer?
    ): Boolean {
        if (renderer == null) {
            return true
        }
        val sampleStream = readingPeriodHolder.sampleStreams[index]
        if (renderer.stream != null
            && (renderer.stream !== sampleStream
                    || (sampleStream != null && !renderer.hasReadStreamToEnd() && !hasReachedServerSideInsertedAdsTransition(
                renderer,
                readingPeriodHolder
            )))
        ) {
            // The current reading period is still being read by at least one renderer.
            var followingPeriod = readingPeriodHolder.getNext()
            while (followingPeriod != null) {
                // If renderer is reading ahead as it was enabled early, then it is not 'reading' the
                // current reading period.
                if (followingPeriod.sampleStreams[index] == renderer.stream) {
                    return true
                }
                followingPeriod = followingPeriod.getNext()
            }
            return false
        }
        return true
    }

    private fun hasReachedServerSideInsertedAdsTransition(
        renderer: Renderer, reading: MediaPeriodHolder
    ): Boolean {
        val nextPeriod = reading.getNext()
        // We can advance the reading period early once we read beyond the transition point in a
        // server-side inserted ads stream because we know the samples are read from the same underlying
        // stream. This shortcut is helpful in case the transition point moved and renderers already
        // read beyond the new transition point. But wait until the next period is actually prepared to
        // allow a seamless transition.
        return reading.info.isFollowedByTransitionToSameStream
                && nextPeriod != null && nextPeriod.prepared
                && (renderer is TextRenderer // [internal: b/181312195]
                || renderer is MetadataRenderer
                || renderer.readingPositionUs >= nextPeriod.startPositionRendererTime)
    }

    @Throws(ExoPlaybackException::class)
    fun render(rendererPositionUs: Long, rendererPositionElapsedRealtimeUs: Long) {
        if (isRendererEnabled(primaryRenderer)) {
            primaryRenderer.render(rendererPositionUs, rendererPositionElapsedRealtimeUs)
        }
        if (secondaryRenderer != null && isRendererEnabled(secondaryRenderer)) {
            secondaryRenderer.render(rendererPositionUs, rendererPositionElapsedRealtimeUs)
        }
    }

    fun allowsPlayback(playingPeriodHolder: MediaPeriodHolder?): Boolean {
        val renderer = getRendererReadingFromPeriod(playingPeriodHolder)
        return renderer == null || renderer.hasReadStreamToEnd()
                || renderer.isReady
                || renderer.isEnded
    }

    @Throws(IOException::class)
    fun maybeThrowStreamError(mediaPeriodHolder: MediaPeriodHolder?) {
        getRendererReadingFromPeriod(mediaPeriodHolder)!!.maybeThrowStreamError()
    }

    @Throws(ExoPlaybackException::class)
    fun start() {
        if (primaryRenderer.state == Renderer.STATE_ENABLED
            && (prewarmingState != RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY)
        ) {
            primaryRenderer.start()
        } else if (secondaryRenderer != null && secondaryRenderer.state == Renderer.STATE_ENABLED && prewarmingState != RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY) {
            secondaryRenderer.start()
        }
    }

    fun stop() {
        if (isRendererEnabled(primaryRenderer)) {
            ensureStopped(primaryRenderer)
        }
        if (secondaryRenderer != null && isRendererEnabled(secondaryRenderer)) {
            ensureStopped(secondaryRenderer)
        }
    }

    private fun ensureStopped(renderer: Renderer) {
        if (renderer.state == Renderer.STATE_STARTED) {
            renderer.stop()
        }
    }

    @Throws(ExoPlaybackException::class)
    fun enable(
        configuration: RendererConfiguration,
        trackSelection: ExoTrackSelection,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId,
        mediaClock: DefaultMediaClock
    ) {
        val formats: Array<Format> = getFormats(trackSelection)
        val enablePrimary =
            prewarmingState == RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY || prewarmingState == RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY || prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
        if (enablePrimary) {
            primaryRequiresReset = true
            primaryRenderer.enable(
                configuration,
                formats,
                stream,
                positionUs,
                joining,
                mayRenderStartOfStream,
                startPositionUs,
                offsetUs,
                mediaPeriodId
            )
            mediaClock.onRendererEnabled(primaryRenderer)
        } else {
            secondaryRequiresReset = true
            secondaryRenderer!!
                .enable(
                    configuration,
                    formats,
                    stream,
                    positionUs,
                    joining,
                    mayRenderStartOfStream,
                    startPositionUs,
                    offsetUs,
                    mediaPeriodId
                )
            mediaClock.onRendererEnabled(secondaryRenderer!!)
        }
    }

    @Throws(ExoPlaybackException::class)
    fun handleMessage(
        messageType: @Renderer.MessageType Int,
        message: Any?,
        mediaPeriod: MediaPeriodHolder?
    ) {
        val renderer =getRendererReadingFromPeriod(mediaPeriod)!!
        renderer.handleMessage(messageType, message)
    }

    @Throws(ExoPlaybackException::class)
    fun setScrubbingMode(scrubbingModeParameters: ScrubbingModeParameters?) {
        primaryRenderer.handleMessage(
            Renderer.MSG_SET_SCRUBBING_MODE,
            scrubbingModeParameters
        )
        if (secondaryRenderer != null) {
            secondaryRenderer.handleMessage(
                Renderer.MSG_SET_SCRUBBING_MODE,
                scrubbingModeParameters
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    fun disable(mediaClock: DefaultMediaClock) {
        disableRenderer(primaryRenderer, mediaClock)
        if (secondaryRenderer != null) {
            val shouldTransferResources =
                isRendererEnabled(secondaryRenderer)
                        && prewarmingState != RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY
            disableRenderer(secondaryRenderer, mediaClock)
            maybeResetRenderer( false)
            if (shouldTransferResources) {
                transferResources( true)
            }
        }
        prewarmingState = RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY
    }

    @Throws(ExoPlaybackException::class)
    fun maybeHandlePrewarmingTransition() {
        if (prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY
            || prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
        ) {
            transferResources( 
                prewarmingState
                        == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
            )
            prewarmingState =
                if (prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY)
                    RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY
                else
                    RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_SECONDARY
        } else if (prewarmingState == RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY) {
            prewarmingState = RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun transferResources(transferToPrimary: Boolean) {
        if (transferToPrimary) {
            secondaryRenderer!!.handleMessage(Renderer.MSG_TRANSFER_RESOURCES, primaryRenderer)
        } else {
            primaryRenderer.handleMessage(
                Renderer.MSG_TRANSFER_RESOURCES,
                secondaryRenderer!!
            )
        }
    }

    fun disablePrewarming(mediaClock: DefaultMediaClock) {
        if (!this.isPrewarming) {
            return
        }
        val isPrewarmingPrimary =
            prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
                    || prewarmingState == RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY
        val isSecondaryActiveRenderer =
            prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
        try {
            disableRenderer(
                if (isPrewarmingPrimary) primaryRenderer else secondaryRenderer!!, mediaClock
            )
        } catch (e: RuntimeException) {
            // There's nothing we can do.
            e(TAG, "Disable prewarming failed.", e)
        }
        try {
            maybeResetRenderer( isPrewarmingPrimary)
        } catch (e: RuntimeException) {
            // There's nothing we can do.
            e(TAG, "Reset prewarming failed.", e)
        }
        prewarmingState =
            if (isSecondaryActiveRenderer)
                RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_SECONDARY
            else
                RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY
    }

    @Throws(ExoPlaybackException::class)
    fun maybeDisableOrResetPosition(
        sampleStream: SampleStream?,
        mediaClock: DefaultMediaClock,
        rendererPositionUs: Long,
        streamReset: Boolean
    ) {
        maybeDisableOrResetPositionInternal(
            primaryRenderer, sampleStream, mediaClock, rendererPositionUs, streamReset
        )
        if (secondaryRenderer != null) {
            maybeDisableOrResetPositionInternal(
                secondaryRenderer, sampleStream, mediaClock, rendererPositionUs, streamReset
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeDisableOrResetPositionInternal(
        renderer: Renderer,
        sampleStream: SampleStream?,
        mediaClock: DefaultMediaClock,
        rendererPositionUs: Long,
        streamReset: Boolean
    ) {
        if (isRendererEnabled(renderer)) {
            if (sampleStream !== renderer.stream) {
                // We need to disable the renderer.
                disableRenderer(renderer, mediaClock)
            } else if (streamReset) {
                // The renderer will continue to consume from its current stream, but needs to be reset.
                renderer.resetPosition(
                    rendererPositionUs,  
                    true
                )
            }
        }
    }

    private fun disableRenderer(renderer: Renderer, mediaClock: DefaultMediaClock) {
        Preconditions.checkState(primaryRenderer === renderer || secondaryRenderer === renderer)
        if (!isRendererEnabled(renderer)) {
            return
        }
        mediaClock.onRendererDisabled(renderer)
        ensureStopped(renderer)
        renderer.disable()
    }

    @Throws(ExoPlaybackException::class)
    fun resetPosition(
        playingPeriod: MediaPeriodHolder?, positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        val renderer = getRendererReadingFromPeriod(playingPeriod)
        if (renderer != null) {
            renderer.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
        }
    }

    fun supportsResetPositionWithoutKeyFrameReset(
        playingPeriod: MediaPeriodHolder?, positionUs: Long
    ): Boolean {
        val renderer = getRendererReadingFromPeriod(playingPeriod)
        return renderer != null && renderer.supportsResetPositionWithoutKeyFrameReset(positionUs)
    }

    fun reset() {
        if (!isRendererEnabled(primaryRenderer)) {
            maybeResetRenderer( true)
        }
        if (secondaryRenderer != null && !isRendererEnabled(secondaryRenderer)) {
            maybeResetRenderer( false)
        }
    }

    private fun maybeResetRenderer(resetPrimary: Boolean) {
        if (resetPrimary) {
            if (primaryRequiresReset) {
                primaryRenderer.reset()
                primaryRequiresReset = false
            }
        } else if (secondaryRequiresReset) {
            secondaryRenderer!!.reset()
            secondaryRequiresReset = false
        }
    }

    @Throws(ExoPlaybackException::class)
    fun replaceStreamsOrDisableRendererForTransition(
        readingPeriodHolder: MediaPeriodHolder,
        newTrackSelectorResult: TrackSelectorResult,
        mediaClock: DefaultMediaClock
    ): Int {
        val primaryRendererResult =
            replaceStreamsOrDisableRendererForTransitionInternal(
                primaryRenderer, readingPeriodHolder, newTrackSelectorResult, mediaClock
            )
        val secondaryRendererResult =
            replaceStreamsOrDisableRendererForTransitionInternal(
                secondaryRenderer, readingPeriodHolder, newTrackSelectorResult, mediaClock
            )
        return if (primaryRendererResult == REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED)
            secondaryRendererResult
        else
            primaryRendererResult
    }

    @Throws(ExoPlaybackException::class)
    private fun replaceStreamsOrDisableRendererForTransitionInternal(
        renderer: Renderer?,
        readingPeriodHolder: MediaPeriodHolder,
        newTrackSelectorResult: TrackSelectorResult,
        mediaClock: DefaultMediaClock
    ): Int {
        if (renderer == null || !isRendererEnabled(renderer) || (renderer === primaryRenderer && this.isPrimaryRendererPrewarming)
            || (renderer === secondaryRenderer && this.isSecondaryRendererPrewarming)
        ) {
            return REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED
        }
        val rendererIsReadingOldStream =
            renderer.stream !== readingPeriodHolder.sampleStreams[index]
        val rendererShouldBeEnabled = newTrackSelectorResult.isRendererEnabled(index)
        if (rendererShouldBeEnabled && !rendererIsReadingOldStream) {
            // All done.
            return REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED
        }
        if (!renderer.isCurrentStreamFinal) {
            // The renderer stream is not final, so we can replace the sample streams immediately.
            val formats: Array<Format> = getFormats(newTrackSelectorResult.selections[index])
            renderer.replaceStream(
                formats,
                readingPeriodHolder.sampleStreams[index]!!,
                readingPeriodHolder.startPositionRendererTime,
                readingPeriodHolder.rendererOffset,
                readingPeriodHolder.info.id
            )
            // Prevent sleeping across offload track transition else position won't get updated.
            return (REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED
                    or REPLACE_STREAMS_DISABLE_RENDERERS_DISABLE_OFFLOAD_SCHEDULING)
        } else if (renderer.isEnded) {
            // The renderer has finished playback, so we can disable it now.
            disableRenderer(renderer, mediaClock)
            if (!rendererShouldBeEnabled || this.isPrewarming) {
                maybeResetRenderer( renderer === primaryRenderer)
            }
            return REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED
        } else {
            // Need to wait until rendering of current item is finished.
            return 0
        }
    }

    fun release() {
        primaryRenderer.release()
        primaryRequiresReset = false
        if (secondaryRenderer != null) {
            secondaryRenderer.release()
            secondaryRequiresReset = false
        }
    }

    @Throws(ExoPlaybackException::class)
    fun setVideoOutput(videoOutput: Any?) {
        if (this.trackType != TRACK_TYPE_VIDEO) {
            return
        }
        if (prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
            || prewarmingState == RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_SECONDARY
        ) {
            secondaryRenderer!!.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, videoOutput)
        } else {
            primaryRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, videoOutput)
        }
    }

    @Throws(ExoPlaybackException::class)
    fun setVideoFrameMetadataListener(videoFrameMetadataListener: VideoFrameMetadataListener?) {
        // TODO: b/507835122 - Remove image track type once metadata listener handling is refined.
        if (this.trackType != TRACK_TYPE_VIDEO && this.trackType != TRACK_TYPE_IMAGE) {
            return
        }
        primaryRenderer.handleMessage(
            Renderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER, videoFrameMetadataListener
        )
        if (secondaryRenderer != null) {
            secondaryRenderer.handleMessage(
                Renderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER, videoFrameMetadataListener
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    fun setImageMetadataListener(imageMetadataListener: ImageMetadataListener?) {
        if (this.trackType != TRACK_TYPE_IMAGE) {
            return
        }
        primaryRenderer.handleMessage(
            Renderer.MSG_SET_IMAGE_METADATA_LISTENER,
            imageMetadataListener
        )
        if (secondaryRenderer != null) {
            secondaryRenderer.handleMessage(
                Renderer.MSG_SET_IMAGE_METADATA_LISTENER, imageMetadataListener
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    fun setVolume(volume: Float) {
        if (this.trackType != TRACK_TYPE_AUDIO) {
            return
        }
        primaryRenderer.handleMessage(Renderer.MSG_SET_VOLUME, volume)
        if (secondaryRenderer != null) {
            secondaryRenderer.handleMessage(Renderer.MSG_SET_VOLUME, volume)
        }
    }

    @Throws(ExoPlaybackException::class)
    fun setAudioSessionId(audioSessionId: Int) {
        if (this.trackType != TRACK_TYPE_AUDIO && this.trackType != TRACK_TYPE_VIDEO) {
            return
        }
        primaryRenderer.handleMessage(Renderer.MSG_SET_AUDIO_SESSION_ID, audioSessionId)
        if (secondaryRenderer != null) {
            secondaryRenderer.handleMessage(
                Renderer.MSG_SET_AUDIO_SESSION_ID,
                audioSessionId
            )
        }
    }

    val isRendererEnabled: Boolean
        get() {
            val checkPrimary =
                prewarmingState == RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY || prewarmingState == RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY || prewarmingState == RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
            return if (checkPrimary)
                isRendererEnabled(primaryRenderer)
            else
                isRendererEnabled(secondaryRenderer!!)
        }

    private fun getRendererReadingFromPeriod(period: MediaPeriodHolder?): Renderer? {
        if (period == null || period.sampleStreams[index] == null) {
            return null
        }
        if (primaryRenderer.stream === period.sampleStreams[index]) {
            return primaryRenderer
        } else if (secondaryRenderer != null
            && secondaryRenderer.stream === period.sampleStreams[index]
        ) {
            return secondaryRenderer
        }
        return null
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY, RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_SECONDARY, RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY, RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY, RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY
        ]
    )
    internal annotation class RendererPrewarmingState

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED, REPLACE_STREAMS_DISABLE_RENDERERS_DISABLE_OFFLOAD_SCHEDULING]
    )
    internal annotation class ReplaceStreamsOrDisableRendererResult

    init {
        prewarmingState = RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY
    }

    companion object {
        private const val TAG = "RendererHolder"

        private fun getFormats(newSelection: ExoTrackSelection?): Array<Format> {
            // Build an array of formats contained by the selection.
            val length = newSelection?.length() ?: 0
            return Array<Format>(length){
                newSelection!!.getFormat(it)
            }
        }

        private fun isRendererEnabled(renderer: Renderer): Boolean {
            return renderer.state != Renderer.STATE_DISABLED
        }

        const val RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_PRIMARY: Int = 0
        const val RENDERER_PREWARMING_STATE_NOT_PREWARMING_USING_SECONDARY: Int = 1
        const val RENDERER_PREWARMING_STATE_PREWARMING_PRIMARY: Int = 2
        const val RENDERER_PREWARMING_STATE_TRANSITIONING_TO_SECONDARY: Int = 3
        const val RENDERER_PREWARMING_STATE_TRANSITIONING_TO_PRIMARY: Int = 4
        const val REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED: Int = 1
        const val REPLACE_STREAMS_DISABLE_RENDERERS_DISABLE_OFFLOAD_SCHEDULING: Int = 1 shl 1
    }
}
