package androidx.media3.exoplayer.text

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isText
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Util.createHandler
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import org.checkerframework.dataflow.qual.SideEffectFree
import java.io.IOException

class TextRenderer(
    output: TextOutput?,
    outputLooper: Looper?,
// Fields used when handling Subtitle objects from legacy samples.
    private val subtitleDecoderFactory: SubtitleDecoderFactory = SubtitleDecoderFactory.DEFAULT
) : BaseRenderer(
    C.TRACK_TYPE_TEXT
), Handler.Callback {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [REPLACEMENT_STATE_NONE, REPLACEMENT_STATE_SIGNAL_END_OF_STREAM, REPLACEMENT_STATE_WAIT_END_OF_STREAM]
    )
    private annotation class ReplacementState

    // Fields used when handling CuesWithTiming objects from application/x-media3-cues samples.
    private val cueDecoder = CueDecoder()
    private val cueDecoderInputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private var cuesResolver:CuesResolver? = null

    private var waitingForKeyFrame = false
    private var decoderReplacementState: @ReplacementState Int = 0
    private var subtitleDecoder: SubtitleDecoder? = null
    private var nextSubtitleInputBuffer: SubtitleInputBuffer? = null
    private var subtitle: SubtitleOutputBuffer? = null
    private var nextSubtitle: SubtitleOutputBuffer? = null
    private var nextSubtitleEventIndex = 0

    // Fields used with both CuesWithTiming and Subtitle objects
    private val outputHandler: Handler? =
        if (outputLooper == null) null else createHandler(outputLooper, this)
    private val output = output!!
    private val formatHolder = FormatHolder()
    private var inputStreamEnded = false
    private var outputStreamEnded = false
    private var streamFormat: Format? = null
    private var lastRendererPositionUs= C.TIME_UNSET
    private var finalStreamEndPositionUs= C.TIME_UNSET
    private var legacyDecodingEnabled= false
    override val name: String = TAG

    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        return if (isCuesWithTiming(format) || subtitleDecoderFactory.supportsFormat(format)) {
            RendererCapabilities.create(
                if (format.cryptoType == C.CRYPTO_TYPE_NONE) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_DRM
            )
        } else if (isText(format.sampleMimeType)) {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
    }

    // on the loading side of SampleQueue.
    fun setFinalStreamEndPositionUs(streamEndPositionUs: Long) {
        Preconditions.checkState(isCurrentStreamFinal)
        this.finalStreamEndPositionUs = streamEndPositionUs
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        streamFormat = formats[0]
        if (!Companion.isCuesWithTiming(streamFormat!!)) {
            assertLegacyDecodingEnabledIfRequired()
            if (subtitleDecoder != null) {
                decoderReplacementState = REPLACEMENT_STATE_SIGNAL_END_OF_STREAM
            } else {
                initSubtitleDecoder()
            }
        } else {
            this.cuesResolver =
                if (streamFormat!!.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
                    MergingCuesResolver()
                else
                    ReplacingCuesResolver()
        }
    }

    override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        lastRendererPositionUs = positionUs
        if (cuesResolver != null) {
            cuesResolver!!.clear()
        }
        clearOutput()
        inputStreamEnded = false
        outputStreamEnded = false
        finalStreamEndPositionUs = C.TIME_UNSET
        if (streamFormat != null && !Companion.isCuesWithTiming(streamFormat!!)) {
            if (decoderReplacementState != REPLACEMENT_STATE_NONE) {
                replaceSubtitleDecoder()
            } else {
                releaseSubtitleBuffers()
                val subtitleDecoder = this.subtitleDecoder!!
                subtitleDecoder.flush()
                subtitleDecoder.setOutputStartTimeUs(lastRendererPositionUs)
            }
        }
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (isCurrentStreamFinal
            && finalStreamEndPositionUs != C.TIME_UNSET && positionUs >= finalStreamEndPositionUs
        ) {
            releaseSubtitleBuffers()
            outputStreamEnded = true
        }
        if (outputStreamEnded) {
            return
        }
        if (isCuesWithTiming(streamFormat!!)) {
            cuesResolver!!
            renderFromCuesWithTiming(positionUs)
        } else {
            assertLegacyDecodingEnabledIfRequired()
            renderFromSubtitles(positionUs)
        }
    }

    @Deprecated(
        """This method (and all support for 'legacy' subtitle decoding during rendering) will
        be removed in a future release."""
    )
    fun experimentalSetLegacyDecodingEnabled(legacyDecodingEnabled: Boolean) {
        this.legacyDecodingEnabled = legacyDecodingEnabled
    }

    @RequiresNonNull("this.cuesResolver")
    private fun renderFromCuesWithTiming(positionUs: Long) {
        var outputNeedsUpdating = readAndDecodeCuesWithTiming(positionUs)

        val nextCueChangeTimeUs = cuesResolver!!.getNextCueChangeTimeUs(lastRendererPositionUs)
        if (nextCueChangeTimeUs == C.TIME_END_OF_SOURCE && inputStreamEnded && !outputNeedsUpdating) {
            outputStreamEnded = true
        }
        if (nextCueChangeTimeUs != C.TIME_END_OF_SOURCE && nextCueChangeTimeUs <= positionUs) {
            outputNeedsUpdating = true
        }

        if (outputNeedsUpdating) {
            val cuesAtTimeUs = cuesResolver!!.getCuesAtTimeUs(positionUs)
            val previousCueChangeTimeUs = cuesResolver!!.getPreviousCueChangeTimeUs(positionUs)
            updateOutput(CueGroup(cuesAtTimeUs, getPresentationTimeUs(previousCueChangeTimeUs)))
            cuesResolver!!.discardCuesBeforeTimeUs(previousCueChangeTimeUs)
        }
        lastRendererPositionUs = positionUs
    }

    @RequiresNonNull("this.cuesResolver")
    private fun readAndDecodeCuesWithTiming(positionUs: Long): Boolean {
        if (inputStreamEnded) {
            return false
        }
        var readResult: @ReadDataResult Int =
            readSource(
                formatHolder,
                cueDecoderInputBuffer,
                SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA
            )
        if (readResult == C.RESULT_NOTHING_READ && !cueDecoderInputBuffer.isEndOfStream) {
            // Nothing to consume yet.
            return false
        } else if (readResult == C.RESULT_BUFFER_READ && !cueDecoderInputBuffer.isEndOfStream) {
            // New buffer available, only consume if close enough to the current position.
            if (positionUs < cueDecoderInputBuffer.timeUs - MAX_READ_AHEAD_DURATION_US) {
                return false
            }
        } else if (readResult == C.RESULT_BUFFER_READ || readResult == C.RESULT_NOTHING_READ) {
            // EOS signal available, only consume if close enough to the stream duration.
            val streamEndPositionUs = streamEndPositionUs
            val positionInPeriodUs = positionUs - streamOffsetUs
            if (streamEndPositionUs == C.TIME_UNSET
                || positionInPeriodUs < streamEndPositionUs - MAX_READ_AHEAD_DURATION_US
            ) {
                return false
            }
        }
        readResult = readSource(formatHolder, cueDecoderInputBuffer, 0)
        when (readResult) {
            C.RESULT_BUFFER_READ -> {
                if (cueDecoderInputBuffer.isEndOfStream) {
                    inputStreamEnded = true
                    return false
                }
                cueDecoderInputBuffer.flip()
                val cueData = cueDecoderInputBuffer.data!!
                val cuesWithTiming =
                    cueDecoder.decode(
                        cueDecoderInputBuffer.timeUs,
                        cueData.array(),
                        cueData.arrayOffset(),
                        cueData.limit()
                    )
                cueDecoderInputBuffer.clear()

                return cuesResolver!!.addCues(cuesWithTiming, positionUs)
            }

            C.RESULT_FORMAT_READ, C.RESULT_NOTHING_READ -> return false
            else -> return false
        }
    }

    private fun renderFromSubtitles(positionUs: Long) {
        lastRendererPositionUs = positionUs
        if (nextSubtitle == null) {
            subtitleDecoder!!.setPositionUs(positionUs)
            try {
                nextSubtitle = subtitleDecoder!!.dequeueOutputBuffer()
            } catch (e: SubtitleDecoderException) {
                handleDecoderError(e)
                return
            }
        }

        if (state != Renderer.STATE_STARTED) {
            return
        }

        var textRendererNeedsUpdate = false
        if (subtitle != null) {
            // We're iterating through the events in a subtitle. Set textRendererNeedsUpdate if we
            // advance to the next event.
            var subtitleNextEventTimeUs = this.nextEventTime
            while (subtitleNextEventTimeUs <= positionUs) {
                nextSubtitleEventIndex++
                subtitleNextEventTimeUs = this.nextEventTime
                textRendererNeedsUpdate = true
            }
        }
        if (nextSubtitle != null) {
            val nextSubtitle = this.nextSubtitle
            if (nextSubtitle!!.isEndOfStream) {
                if (!textRendererNeedsUpdate && this.nextEventTime == Long.MAX_VALUE) {
                    if (decoderReplacementState == REPLACEMENT_STATE_WAIT_END_OF_STREAM) {
                        replaceSubtitleDecoder()
                    } else {
                        releaseSubtitleBuffers()
                        outputStreamEnded = true
                    }
                }
            } else if (nextSubtitle.timeUs <= positionUs) {
                // Advance to the next subtitle. Sync the next event index and trigger an update.
                if (subtitle != null) {
                    subtitle!!.release()
                }
                nextSubtitleEventIndex = nextSubtitle.getNextEventTimeIndex(positionUs)
                subtitle = nextSubtitle
                this.nextSubtitle = null
                textRendererNeedsUpdate = true
            }
        }

        if (textRendererNeedsUpdate) {
            // If textRendererNeedsUpdate then subtitle must be non-null.
            // textRendererNeedsUpdate is set and we're playing. Update the renderer.
            val presentationTimeUs = getPresentationTimeUs(getCurrentEventTimeUs(positionUs))
            val cueGroup = CueGroup(subtitle!!.getCues(positionUs)!!, presentationTimeUs)
            updateOutput(cueGroup)
        }

        if (decoderReplacementState == REPLACEMENT_STATE_WAIT_END_OF_STREAM) {
            return
        }

        try {
            while (!inputStreamEnded) {
                var nextInputBuffer = this.nextSubtitleInputBuffer
                if (nextInputBuffer == null) {
                    nextInputBuffer = subtitleDecoder!!.dequeueInputBuffer()
                    if (nextInputBuffer == null) {
                        return
                    }
                    this.nextSubtitleInputBuffer = nextInputBuffer
                }
                if (decoderReplacementState == REPLACEMENT_STATE_SIGNAL_END_OF_STREAM) {
                    nextInputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                    subtitleDecoder!!.queueInputBuffer(nextInputBuffer)
                    this.nextSubtitleInputBuffer = null
                    decoderReplacementState = REPLACEMENT_STATE_WAIT_END_OF_STREAM
                    return
                }
                // Try and read the next subtitle from the source.
                var result: @ReadDataResult Int =
                    readSource(
                        formatHolder,
                        nextInputBuffer,
                        SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA
                    )
                if (result == C.RESULT_NOTHING_READ && !nextInputBuffer.isEndOfStream) {
                    // Nothing to consume yet.
                    return
                } else if (result == C.RESULT_BUFFER_READ && !nextInputBuffer.isEndOfStream) {
                    // New buffer available, only consume if close enough to the current position.
                    if (positionUs < nextInputBuffer.timeUs - MAX_READ_AHEAD_DURATION_US) {
                        return
                    }
                } else if (result == C.RESULT_BUFFER_READ || result == C.RESULT_NOTHING_READ) {
                    // EOS signal available, only consume if close enough to the stream duration.
                    val streamEndPositionUs = streamEndPositionUs
                    val positionInPeriodUs = positionUs - streamOffsetUs
                    if (streamEndPositionUs == C.TIME_UNSET
                        || positionInPeriodUs < streamEndPositionUs - MAX_READ_AHEAD_DURATION_US
                    ) {
                        return
                    }
                }
                result = readSource(formatHolder, nextInputBuffer, 0)
                if (result == C.RESULT_BUFFER_READ) {
                    if (nextInputBuffer.isEndOfStream) {
                        inputStreamEnded = true
                        waitingForKeyFrame = false
                    } else {
                        val format = formatHolder.format
                        if (format == null) {
                            // We haven't received a format yet.
                            return
                        }
                        nextInputBuffer.subsampleOffsetUs = format.subsampleOffsetUs
                        nextInputBuffer.flip()
                        waitingForKeyFrame = waitingForKeyFrame and !nextInputBuffer.isKeyFrame
                    }
                    if (!waitingForKeyFrame) {
                        subtitleDecoder!!.queueInputBuffer(nextInputBuffer)
                        this.nextSubtitleInputBuffer = null
                    }
                } else if (result == C.RESULT_NOTHING_READ) {
                    return
                }
            }
        } catch (e: SubtitleDecoderException) {
            handleDecoderError(e)
        }
    }

    override fun onDisabled() {
        streamFormat = null
        finalStreamEndPositionUs = C.TIME_UNSET
        clearOutput()
        lastRendererPositionUs = C.TIME_UNSET
        if (subtitleDecoder != null) {
            releaseSubtitleDecoder()
        }
    }

    override val isEnded: Boolean
        get() = outputStreamEnded

    override val isReady: Boolean
        get() {
            if (streamFormat == null) {
                return true
            }

            // We don't block playback whilst subtitles are loading.
            // Note: To change this behavior, it will be necessary to consider [Internal: b/12949941].
            if (isCuesWithTiming(streamFormat!!)) {
                if (cuesResolver!!.getNextCueChangeTimeUs(lastRendererPositionUs)
                    != C.TIME_END_OF_SOURCE
                ) {
                    // We have a cue change loaded in the future.
                    return true
                } else {
                    // We don't have any future cues, so let's see if there's a loading error, and return
                    // ready=false if so.
                    try {
                        maybeThrowStreamError()
                        return true
                    } catch (e: IOException) {
                        return false
                    }
                }
            } else {
                return !outputStreamEnded
                        && (!inputStreamEnded || hasEventsAfter(subtitle, lastRendererPositionUs)
                        || hasEventsAfter(nextSubtitle, lastRendererPositionUs)
                        || nextSubtitleInputBuffer == null)
            }
        }

    private fun releaseSubtitleBuffers() {
        nextSubtitleInputBuffer = null
        nextSubtitleEventIndex = C.INDEX_UNSET
        if (subtitle != null) {
            subtitle!!.release()
            subtitle = null
        }
        if (nextSubtitle != null) {
            nextSubtitle!!.release()
            nextSubtitle = null
        }
    }

    private fun releaseSubtitleDecoder() {
        releaseSubtitleBuffers()
        subtitleDecoder!!.release()
        subtitleDecoder = null
        decoderReplacementState = REPLACEMENT_STATE_NONE
    }

    private fun initSubtitleDecoder() {
        waitingForKeyFrame = true
        subtitleDecoder =
            subtitleDecoderFactory.createDecoder(streamFormat!!)
        subtitleDecoder!!.setOutputStartTimeUs(lastRendererPositionUs)
    }

    private fun replaceSubtitleDecoder() {
        releaseSubtitleDecoder()
        initSubtitleDecoder()
    }

    private val nextEventTime: Long
        get() {
            if (nextSubtitleEventIndex == C.INDEX_UNSET) {
                return Long.MAX_VALUE
            }
            return if (nextSubtitleEventIndex >= subtitle!!.eventTimeCount) Long.MAX_VALUE else
                subtitle!!.getEventTime(nextSubtitleEventIndex)
        }

    private fun updateOutput(cueGroup: CueGroup) {
        if (outputHandler != null) {
            outputHandler.obtainMessage(MSG_UPDATE_OUTPUT, cueGroup).sendToTarget()
        } else {
            invokeUpdateOutputInternal(cueGroup)
        }
    }

    private fun clearOutput() {
        updateOutput(
            CueGroup(
                listOf(),
                getPresentationTimeUs(lastRendererPositionUs)
            )
        )
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_UPDATE_OUTPUT -> {
                invokeUpdateOutputInternal((msg.obj as androidx.media3.common.text.CueGroup?)!!)
                return true
            }

            else -> throw IllegalStateException()
        }
    }

    @Suppress("deprecation") // We need to call both onCues method for backward compatibility.
    private fun invokeUpdateOutputInternal(cueGroup: CueGroup) {
        output.onCues(cueGroup.cues)
        output.onCues(cueGroup)
    }

    private fun handleDecoderError(e: SubtitleDecoderException?) {
        e(TAG, "Subtitle decoding failed. streamFormat=" + streamFormat, e)
        clearOutput()
        replaceSubtitleDecoder()
    }

    @RequiresNonNull("subtitle")
    @SideEffectFree
    private fun getCurrentEventTimeUs(positionUs: Long): Long {
        val nextEventTimeIndex = subtitle!!.getNextEventTimeIndex(positionUs)
        if (nextEventTimeIndex == 0 || subtitle!!.eventTimeCount == 0) {
            return subtitle!!.timeUs
        }

        return if (nextEventTimeIndex == C.INDEX_UNSET)
            subtitle!!.getEventTime(subtitle!!.eventTimeCount - 1)
        else
            subtitle!!.getEventTime(nextEventTimeIndex - 1)
    }

    @SideEffectFree
    private fun getPresentationTimeUs(positionUs: Long): Long {
        Preconditions.checkState(positionUs != C.TIME_UNSET)
        return positionUs - streamOffsetUs
    }

    @RequiresNonNull("streamFormat")
    private fun assertLegacyDecodingEnabledIfRequired() {
        Preconditions.checkState(
            legacyDecodingEnabled
                    || streamFormat!!.sampleMimeType == MimeTypes.APPLICATION_CEA608
                    || streamFormat!!.sampleMimeType == MimeTypes.APPLICATION_MP4CEA608
                    || streamFormat!!.sampleMimeType == MimeTypes.APPLICATION_CEA708,
            "Legacy decoding is disabled, can't handle %s samples (expected %s).",
            streamFormat!!.sampleMimeType,
            MimeTypes.APPLICATION_MEDIA3_CUES
        )
    }

    companion object {
        private const val TAG = "TextRenderer"
        private const val REPLACEMENT_STATE_NONE = 0
        private const val REPLACEMENT_STATE_SIGNAL_END_OF_STREAM = 1
        private const val REPLACEMENT_STATE_WAIT_END_OF_STREAM = 2
        private const val MSG_UPDATE_OUTPUT = 1
        private const val MAX_READ_AHEAD_DURATION_US = C.MICROS_PER_SECOND

        private fun hasEventsAfter(subtitle: Subtitle?, timeUs: Long): Boolean {
            return subtitle != null && subtitle.eventTimeCount > 0 && subtitle.getEventTime(subtitle.eventTimeCount - 1) > timeUs
        }

        @SideEffectFree
        private fun isCuesWithTiming(format: Format): Boolean {
            return format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES
        }
    }
}
