package androidx.media3.exoplayer

import androidx.annotation.GuardedBy
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock

import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import com.google.common.base.Preconditions

import java.io.IOException
import kotlin.math.max

abstract class BaseRenderer(override val trackType: @C.TrackType Int) : Renderer,
    RendererCapabilities {
    private val lock = Any()
    private val formatHolder = FormatHolder()
    private var configuration: RendererConfiguration? = null
    protected var index: Int = 0
        private set
    private var playerId: PlayerId? = null
    private var clock: Clock? = null
    override var state = 0
    override var stream: SampleStream? = null
    private var streamFormats: Array<Format>? = null
    protected var streamOffsetUs: Long = 0
        private set
    protected var lastResetPositionUs: Long = 0
        private set
    override var readingPositionUs = C.TIME_END_OF_SOURCE
    private var streamIsFinal = false
    private var throwRendererExceptionIsExecuting = false
    private var timeline = Timeline.EMPTY
    protected var mediaPeriodId: MediaPeriodId? = null
        private set
    protected var streamEndPositionUs = C.TIME_UNSET
        private set

    @GuardedBy("lock")
    private var rendererCapabilitiesListener: RendererCapabilities.Listener? = null

    override val capabilities: RendererCapabilities
        get() = this

    override fun init(index: Int, playerId: PlayerId?, clock: Clock?) {
        this.index = index
        this.playerId = playerId
        this.clock = clock
        onInit()
    }

    override val mediaClock: MediaClock?
        get() = null

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
        this.mediaPeriodId = mediaPeriodId
        state = Renderer.STATE_ENABLED
        onEnabled(joining, mayRenderStartOfStream)
        replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
        resetPosition(startPositionUs, joining, true)
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
        this.mediaPeriodId = mediaPeriodId
        updatePeriodInfo()
        if (readingPositionUs == C.TIME_END_OF_SOURCE) {
            readingPositionUs = startPositionUs
        }
        streamFormats = formats
        streamOffsetUs = offsetUs
        onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun hasReadStreamToEnd(): Boolean {
        return readingPositionUs == C.TIME_END_OF_SOURCE
    }

    override fun setCurrentStreamFinal() {
        streamIsFinal = true
    }

    override val isCurrentStreamFinal: Boolean
        get() = streamIsFinal

    @Throws(IOException::class)
    override fun maybeThrowStreamError() {
        stream!!.maybeThrowError()
    }

    override fun setTimeline(timeline: Timeline) {
        if (this.timeline != timeline) {
            this.timeline = timeline
            updatePeriodInfo()
            onTimelineChanged(this.timeline)
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        resetPosition(positionUs, false, sampleStreamIsResetToKeyFrame)
    }

    @Throws(ExoPlaybackException::class)
    private fun resetPosition(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        var sampleStreamIsResetToKeyFrame = sampleStreamIsResetToKeyFrame
        streamIsFinal = false
        lastResetPositionUs = positionUs
        readingPositionUs = positionUs
        if (!sampleStreamIsResetToKeyFrame) {
            sampleStreamIsResetToKeyFrame = skipSource(positionUs) != 0
        }
        onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    override fun stop() {
        Preconditions.checkState(state == Renderer.STATE_STARTED)
        state = Renderer.STATE_ENABLED
        onStopped()
    }

    override fun disable() {
        Preconditions.checkState(state == Renderer.STATE_ENABLED)
        formatHolder.clear()
        state = Renderer.STATE_DISABLED
        stream = null
        streamFormats = null
        streamIsFinal = false
        onDisabled()
        mediaPeriodId = null
        streamEndPositionUs = C.TIME_UNSET
    }

    override fun reset() {
        Preconditions.checkState(state == Renderer.STATE_DISABLED)
        formatHolder.clear()
        onReset()
    }

    override fun release() {
        Preconditions.checkState(state == Renderer.STATE_DISABLED)
        onRelease()
    }

    // RendererCapabilities implementation.
    @Throws(ExoPlaybackException::class)
    override fun supportsMixedMimeTypeAdaptation(): @AdaptiveSupport Int {
        return RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    override fun setListener(listener: RendererCapabilities.Listener?) {
        synchronized(lock) {
            this.rendererCapabilitiesListener = listener
        }
    }

    override fun clearListener() {
        synchronized(lock) {
            this.rendererCapabilitiesListener = null
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        // Do nothing.
    }

    protected open fun onInit() {
        // Do nothing
    }

    @Throws(ExoPlaybackException::class)
    protected open fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected open fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected open fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected open fun onStarted() {
        // Do nothing.
    }

    protected open fun onStopped() {
        // Do nothing.
    }

    protected open fun onDisabled() {
        // Do nothing.
    }

    protected open fun onReset() {
        // Do nothing.
    }

    protected open fun onRelease() {
        // Do nothing.
    }

    protected open fun onTimelineChanged(timeline: Timeline) {
        // Do nothing
    }

    protected fun getFormatHolder(): FormatHolder {
        formatHolder.clear()
        return formatHolder
    }

    protected fun getStreamFormats(): Array<Format> {
        return streamFormats!!
    }

    protected fun getConfiguration(): RendererConfiguration {
        return configuration!!
    }

    protected fun getPlayerId(): PlayerId {
        return playerId!!
    }

    protected fun getClock(): Clock {
        return clock!!
    }

    protected fun getTimeline(): Timeline {
        return timeline
    }

    protected fun createRendererException(
        cause: Throwable?,
        format: Format?,
        errorCode: @PlaybackException.ErrorCode Int
    ): ExoPlaybackException {
        return createRendererException(cause, format, false, errorCode)
    }

    protected fun createRendererException(
        cause: Throwable?,
        format: Format?,
        isRecoverable: Boolean,
        errorCode: @PlaybackException.ErrorCode Int
    ): ExoPlaybackException {
        var formatSupport: @C.FormatSupport Int = C.FORMAT_HANDLED
        if (format != null && !throwRendererExceptionIsExecuting) {
            // Prevent recursive re-entry from subclass supportsFormat implementations.
            throwRendererExceptionIsExecuting = true
            try {
                formatSupport = RendererCapabilities.getFormatSupport(supportsFormat(format))
            } catch (e: ExoPlaybackException) {
                // Ignore, we are already failing.
            } finally {
                throwRendererExceptionIsExecuting = false
            }
        }
        return ExoPlaybackException.createForRenderer(
            cause,
            name,
            this.index,
            format,
            formatSupport,
            mediaPeriodId,
            isRecoverable,
            errorCode
        )
    }

    protected fun readSource(
        formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
    ): @ReadDataResult Int {
        val isPeeking = (readFlags and SampleStream.FLAG_PEEK) != 0
        val result: @ReadDataResult Int = stream!!.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ) {
            if (buffer.isEndOfStream) {
                if (!isPeeking) {
                    readingPositionUs = C.TIME_END_OF_SOURCE
                }
                return if (streamIsFinal) C.RESULT_BUFFER_READ else C.RESULT_NOTHING_READ
            }
            buffer.timeUs += streamOffsetUs
            if (!isPeeking) {
                readingPositionUs = max(readingPositionUs, buffer.timeUs)
            }
        } else if (result == C.RESULT_FORMAT_READ) {
            var format = formatHolder.format!!
            if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE) {
                format =
                    format
                        .buildUpon()
                        .setSubsampleOffsetUs(format.subsampleOffsetUs + streamOffsetUs)
                        .build()
                formatHolder.format = format
            }
        }
        return result
    }

    protected fun skipSource(positionUs: Long): Int {
        return stream!!.skipData(positionUs - streamOffsetUs)
    }

    protected val isSourceReady: Boolean
        get() = if (hasReadStreamToEnd()) streamIsFinal else stream!!.isReady

    protected fun onRendererCapabilitiesChanged() {
        val listener: RendererCapabilities.Listener?
        synchronized(lock) {
            listener = rendererCapabilitiesListener
        }
        listener?.onRendererCapabilitiesChanged(this)
    }

    private fun updatePeriodInfo() {
        if (timeline.isEmpty || mediaPeriodId == null) {
            streamEndPositionUs = C.TIME_UNSET
            return
        }
        val mediaPeriodId = mediaPeriodId!!
        val periodIndex = timeline.getIndexOfPeriod(mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) {
            streamEndPositionUs = C.TIME_UNSET
            return
        }

        val period = timeline.getPeriod(periodIndex, Timeline.Period())
        streamEndPositionUs = period.durationUs
        if (mediaPeriodId.adGroupIndex != C.INDEX_UNSET) {
            // Use the ad duration in case the current stream is an ad period.
            val adGroup = period.adPlaybackState.getAdGroup(mediaPeriodId.adGroupIndex)
            streamEndPositionUs = adGroup.durationsUs[mediaPeriodId.adIndexInAdGroup]
        } else if (mediaPeriodId.nextAdGroupIndex != C.INDEX_UNSET) {
            val adGroup = period.adPlaybackState.getAdGroup(mediaPeriodId.nextAdGroupIndex)
            if (adGroup.timeUs != C.TIME_END_OF_SOURCE) {
                // Return next ad group time if not a post-roll. For post-rolls we can use the duration.
                streamEndPositionUs = adGroup.timeUs
            }
        }
    }
}
