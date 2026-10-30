package androidx.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import com.google.common.base.Preconditions
import java.io.IOException

abstract class NoSampleRenderer : Renderer, RendererCapabilities {
    protected var configuration: RendererConfiguration? = null
        private set
    protected var index: Int = 0
        private set
    override var state = 0
    override var stream: SampleStream? = null
    private var streamIsFinal = false

    override val trackType: @C.TrackType Int = C.TRACK_TYPE_NONE
    override val capabilities: RendererCapabilities?
        get() = this

    override fun init(index: Int, playerId: PlayerId?, clock: Clock?) {
        this.index = index
    }

    override val mediaClock: MediaClock? = null

    @Throws(ExoPlaybackException::class)
    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        Preconditions.checkState(state == Renderer.STATE_DISABLED)
        this.configuration = configuration
        state = Renderer.STATE_ENABLED
        onEnabled(joining)
        replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
        onPositionReset(positionUs, joining, true)
    }

    @Throws(ExoPlaybackException::class)
    override fun start() {
        Preconditions.checkState(state == Renderer.STATE_ENABLED)
        state = Renderer.STATE_STARTED
        onStarted()
    }

    @Throws(ExoPlaybackException::class)
    override fun replaceStream(
        formats: Array<Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        Preconditions.checkState(!streamIsFinal)
        this.stream = stream
        onRendererOffsetChanged(offsetUs)
    }

    override fun hasReadStreamToEnd(): Boolean {
        return true
    }

    override val readingPositionUs: Long = C.TIME_END_OF_SOURCE

    override fun setCurrentStreamFinal() {
        streamIsFinal = true
    }

    override val isCurrentStreamFinal: Boolean
        get() = streamIsFinal

    @Throws(IOException::class)
    override fun maybeThrowStreamError() {
    }

    @Throws(ExoPlaybackException::class)
    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        streamIsFinal = false
        onPositionReset(positionUs, false, sampleStreamIsResetToKeyFrame)
    }

    override fun stop() {
        Preconditions.checkState(state == Renderer.STATE_STARTED)
        state = Renderer.STATE_ENABLED
        onStopped()
    }

    override fun disable() {
        Preconditions.checkState(state == Renderer.STATE_ENABLED)
        state = Renderer.STATE_DISABLED
        stream = null
        streamIsFinal = false
        onDisabled()
    }

    override fun reset() {
        Preconditions.checkState(state == Renderer.STATE_DISABLED)
        onReset()
    }

    override val isReady: Boolean = true
    override val isEnded: Boolean = true

    // RendererCapabilities implementation.
    @Throws(ExoPlaybackException::class)
    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
    }

    @Throws(ExoPlaybackException::class)
    override fun supportsMixedMimeTypeAdaptation(): @AdaptiveSupport Int {
        return RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    // PlayerMessage.Target implementation.
    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        // Do nothing.
    }

    override fun setTimeline(timeline: Timeline) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected fun onEnabled(joining: Boolean) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected fun onRendererOffsetChanged(offsetUs: Long) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected fun onStarted() {
        // Do nothing.
    }

    protected fun onStopped() {
        // Do nothing.
    }

    protected fun onDisabled() {
        // Do nothing.
    }

    protected fun onReset() {
        // Do nothing.
    }
}
