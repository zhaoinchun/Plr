package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.MimeTypes.allSamplesAreSyncSamples
import androidx.media3.common.StreamKey
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ClippingMediaSource.IllegalClippingException
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class ClippingMediaPeriod(
    val mediaPeriod: MediaPeriod,
    enableInitialDiscontinuity: Boolean,
    startUs: Long,
    endUs: Long,
    private val enableClippingInMediaPeriod: Boolean
) : MediaPeriod, MediaPeriod.Callback {
    private var callback: MediaPeriod.Callback? = null
    private var sampleStreams: Array<ClippingSampleStream?>
    private var pendingInitialDiscontinuityPositionUs: Long
    private var hasProcessedInitialDiscontinuity = false
    private var usesStreamPrerollFlags = false
    private var lastReportedDiscontinuityUs: Long
    var startUs: Long = 0
    var endUs: Long = 0
    private var clippingError: IllegalClippingException? = null
    private var isPeriodClippingEndPosition = false

    constructor(
        mediaPeriod: MediaPeriod,
        enableInitialDiscontinuity: Boolean,
        startUs: Long,
        endUs: Long
    ) : this(mediaPeriod, enableInitialDiscontinuity, startUs, endUs, false)

    init {
        sampleStreams = arrayOfNulls(0)
        pendingInitialDiscontinuityPositionUs =
            if (enableInitialDiscontinuity) startUs else C.TIME_UNSET
        lastReportedDiscontinuityUs = C.TIME_UNSET
        updateClipping(startUs, endUs)
    }

    fun updateClipping(startUs: Long, endUs: Long) {
        this.startUs = startUs
        this.endUs = endUs
        if (enableClippingInMediaPeriod) {
            val periodEndPositionUs = mediaPeriod.setEndPositionUs(endUs)
            Preconditions.checkState(
                periodEndPositionUs == C.TIME_END_OF_SOURCE || periodEndPositionUs == endUs,
                "Period updating end positions not supported, %s!=%s",
                periodEndPositionUs,
                endUs
            )
            isPeriodClippingEndPosition = periodEndPositionUs == endUs
        }
    }

    fun setClippingError(clippingError: IllegalClippingException) {
        this.clippingError = clippingError
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        mediaPeriod.prepare(this, positionUs)
    }

    @Throws(IOException::class)
    override fun maybeThrowPrepareError() {
        clippingError?.also { throw it }
        mediaPeriod.maybeThrowPrepareError()
    }

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
        return mediaPeriod.getStreamKeys(trackSelections)
    }

    override val trackGroups: TrackGroupArray
        get() = mediaPeriod.trackGroups

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        sampleStreams = arrayOfNulls<ClippingSampleStream>(streams.size)
        val childStreams = arrayOfNulls<SampleStream>(streams.size)
        for (i in streams.indices) {
            val stream = streams[i] as ClippingSampleStream?
            sampleStreams[i] = streams[i] as ClippingSampleStream?
            childStreams[i] = stream?.childStream
        }
        val realEnablePositionUs =
            mediaPeriod.selectTracks(
                selections, mayRetainStreamFlags, childStreams, streamResetFlags, positionUs
            )
        val correctedEnablePositionUs: Long =
            enforceClippingRange(realEnablePositionUs, positionUs, endUs)

        var anyTrackHasPreroll = false
        for (i in streams.indices) {
            val childStream = childStreams[i]
            if (childStream == null) {
                sampleStreams[i] = null
            } else if (sampleStreams[i].let { it == null || it.childStream !== childStream }) {
                val shouldKeepInitialDiscontinuity = shouldKeepInitialDiscontinuity(
                    realEnablePositionUs,
                    positionUs,
                    selections,
                    i
                )
                val hasPreroll = isPendingInitialDiscontinuity && shouldKeepInitialDiscontinuity
                sampleStreams[i] = ClippingSampleStream(childStream, hasPreroll)
                anyTrackHasPreroll = anyTrackHasPreroll or hasPreroll
            }
            streams[i] = sampleStreams[i]
        }
        pendingInitialDiscontinuityPositionUs =
            if (anyTrackHasPreroll) correctedEnablePositionUs else C.TIME_UNSET
        if (usesStreamPrerollFlags) {
            hasProcessedInitialDiscontinuity = true
        }
        return correctedEnablePositionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        mediaPeriod.discardBuffer(positionUs, toKeyframe)
    }

    override fun reevaluateBuffer(positionUs: Long) {
        mediaPeriod.reevaluateBuffer(positionUs)
    }

    override fun setUsesStreamPrerollFlags() {
        usesStreamPrerollFlags = true
        mediaPeriod.setUsesStreamPrerollFlags()
    }

    override fun readDiscontinuity(): Long {
        if (isPendingInitialDiscontinuity) {
            hasProcessedInitialDiscontinuity = true
            val initialDiscontinuityUs = pendingInitialDiscontinuityPositionUs
            pendingInitialDiscontinuityPositionUs = C.TIME_UNSET
            lastReportedDiscontinuityUs = initialDiscontinuityUs
            // Always read an initial discontinuity from the child, and use it if set.
            val childDiscontinuityUs = readDiscontinuity()
            return if (childDiscontinuityUs != C.TIME_UNSET) childDiscontinuityUs else initialDiscontinuityUs
        }
        var discontinuityUs = mediaPeriod.readDiscontinuity()
        if (discontinuityUs == C.TIME_UNSET) {
            return C.TIME_UNSET
        }
        discontinuityUs = enforceClippingRange(discontinuityUs, startUs, endUs)
        if (discontinuityUs == lastReportedDiscontinuityUs) {
            // Already reported, don't force reset rendering pipeline again.
            return C.TIME_UNSET
        }
        lastReportedDiscontinuityUs = discontinuityUs
        return discontinuityUs
    }

    override val bufferedPositionUs: Long
        get() {
            val bufferedPositionUs = mediaPeriod.bufferedPositionUs
            if (isPeriodClippingEndPosition) {
                return if (endUs != C.TIME_END_OF_SOURCE && bufferedPositionUs != C.TIME_END_OF_SOURCE)
                    min(endUs, bufferedPositionUs)
                else
                    bufferedPositionUs
            }
            if (bufferedPositionUs == C.TIME_END_OF_SOURCE
                || (endUs != C.TIME_END_OF_SOURCE && bufferedPositionUs >= endUs)
            ) {
                return C.TIME_END_OF_SOURCE
            }
            return bufferedPositionUs
        }

    override fun seekToUs(positionUs: Long): Long {
        pendingInitialDiscontinuityPositionUs = C.TIME_UNSET
        for (sampleStream in sampleStreams) {
            sampleStream?.clearSentEos()
        }
        return enforceClippingRange(mediaPeriod.seekToUs(positionUs), startUs, endUs)
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        if (positionUs == startUs) {
            // Never adjust seeks to the start of the clipped view.
            return startUs
        }
        val clippedSeekParameters = clipSeekParameters(positionUs, seekParameters)
        return mediaPeriod.getAdjustedSeekPositionUs(positionUs, clippedSeekParameters)
    }

    override val nextLoadPositionUs: Long
        get() {
            val nextLoadPositionUs = mediaPeriod.nextLoadPositionUs
            if (isPeriodClippingEndPosition) {
                return if (endUs != C.TIME_END_OF_SOURCE && nextLoadPositionUs != C.TIME_END_OF_SOURCE)
                    min(endUs, nextLoadPositionUs)
                else
                    nextLoadPositionUs
            }
            if (nextLoadPositionUs == C.TIME_END_OF_SOURCE
                || (endUs != C.TIME_END_OF_SOURCE && nextLoadPositionUs >= endUs)
            ) {
                return C.TIME_END_OF_SOURCE
            }
            return nextLoadPositionUs
        }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        return mediaPeriod.continueLoading(loadingInfo)
    }

    override val isLoading: Boolean
        get() = mediaPeriod.isLoading

    // MediaPeriod.Callback implementation.
    override fun onPrepared(mediaPeriod: MediaPeriod) {
        if (clippingError != null) {
            return
        }
        callback!!.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback!!.onContinueLoadingRequested(this)
    }

    val isPendingInitialDiscontinuity: Boolean
        get() = pendingInitialDiscontinuityPositionUs != C.TIME_UNSET
                && !hasProcessedInitialDiscontinuity

    private fun clipSeekParameters(
        positionUs: Long,
        seekParameters: SeekParameters
    ): SeekParameters {
        val beforeMax = positionUs - startUs
        val toleranceBeforeUs = constrainValue(seekParameters.toleranceBeforeUs, 0, beforeMax)

        val afterMax = if (endUs == C.TIME_END_OF_SOURCE) Long.MAX_VALUE else endUs - positionUs
        val toleranceAfterUs = constrainValue(seekParameters.toleranceAfterUs, 0, afterMax)
        if (toleranceBeforeUs == seekParameters.toleranceBeforeUs
            && toleranceAfterUs == seekParameters.toleranceAfterUs
        ) {
            return seekParameters
        } else {
            return SeekParameters(toleranceBeforeUs, toleranceAfterUs)
        }
    }

    private inner class ClippingSampleStream(
        val childStream: SampleStream,
        private val hasPreroll: Boolean
    ) : SampleStream {
        private var sentEos = false

        fun clearSentEos() {
            sentEos = false
        }

        override val isReady: Boolean
            get() = !isPendingInitialDiscontinuity && childStream.isReady

        @Throws(IOException::class)
        override fun maybeThrowError() {
            childStream.maybeThrowError()
        }

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): Int {
            if (isPendingInitialDiscontinuity) {
                return C.RESULT_NOTHING_READ
            }
            if (isPeriodClippingEndPosition) {
                val result: @ReadDataResult Int =
                    childStream.readData(formatHolder, buffer, readFlags)
                if (result == C.RESULT_FORMAT_READ) {
                    updateDecoderDelayPaddingForClipping(formatHolder, startUs, endUs)
                    return C.RESULT_FORMAT_READ
                }
                return result
            }
            if (sentEos) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            val bufferedPositionUs = bufferedPositionUs
            val result: @ReadDataResult Int = childStream.readData(formatHolder, buffer, readFlags)
            if (lastReportedDiscontinuityUs != C.TIME_UNSET && result != C.RESULT_NOTHING_READ) {
                // We read something, so don't suppress further discontinuities even if they are to the same
                // position as the last one.
                lastReportedDiscontinuityUs = C.TIME_UNSET
            }
            if (result == C.RESULT_FORMAT_READ) {
                updateDecoderDelayPaddingForClipping(formatHolder, startUs, endUs)
                return C.RESULT_FORMAT_READ
            }
            if (endUs != C.TIME_END_OF_SOURCE
                && ((result == C.RESULT_BUFFER_READ && buffer.timeUs >= endUs)
                        || (result == C.RESULT_NOTHING_READ && bufferedPositionUs == C.TIME_END_OF_SOURCE && !buffer.waitingForKeys))
            ) {
                buffer.clear()
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                sentEos = true
                return C.RESULT_BUFFER_READ
            }
            return result
        }

        override fun skipData(positionUs: Long): Int {
            if (isPendingInitialDiscontinuity) {
                return C.RESULT_NOTHING_READ
            }
            return childStream.skipData(positionUs)
        }

        override val flags: @SampleStream.Flags Int
            get() {
                var flags = childStream.flags
                if (endUs != C.TIME_END_OF_SOURCE) {
                    flags = flags or SampleStream.FLAG_STRICT_DURATION
                }
                if (hasPreroll) {
                    flags = flags and SampleStream.FLAG_MAYBE_HAS_PREROLL.inv()
                    flags = flags or SampleStream.FLAG_HAS_PREROLL
                }
                return flags
            }

    }

    companion object {
        private fun shouldKeepInitialDiscontinuity(
            startUs: Long,
            requestedPositionUs: Long,
            selections: Array<ExoTrackSelection?>,
            streamIndex: Int
        ): Boolean {
            // If the source adjusted the start position to be before the requested position, we need to
            // report a discontinuity to ensure renderers decode-only the samples before the requested start
            // position.
            if (startUs < requestedPositionUs) {
                return true
            }
            // If the clipping start position is non-zero, the clipping sample streams will adjust
            // timestamps on buffers they read from the unclipped sample streams. These adjusted buffer
            // timestamps can be negative, because sample streams provide buffers starting at a key-frame,
            // which may be before the clipping start point. When the renderer reads a buffer with a
            // negative timestamp, its offset timestamp can jump backwards compared to the last timestamp
            // read in the previous period. Renderer implementations may not allow this, so we signal a
            // discontinuity which resets the renderers before they read the clipping sample stream.
            // However, for tracks where all samples are sync samples, we assume they have random access
            // seek behaviour and do not need an initial discontinuity to reset the renderer.
            if (startUs != 0L) {
                val selection = selections[streamIndex]
                if (selection != null) {
                    val selectedFormat = selection.selectedFormat
                    return !allSamplesAreSyncSamples(
                        selectedFormat.sampleMimeType, selectedFormat.codecs
                    )
                }
            }
            return false
        }

        private fun enforceClippingRange(
            positionUs: Long, minPositionUs: Long, maxPositionUs: Long
        ): Long {
            var positionUs = positionUs
            positionUs = max(positionUs, minPositionUs)
            if (maxPositionUs != C.TIME_END_OF_SOURCE) {
                positionUs = min(positionUs, maxPositionUs)
            }
            return positionUs
        }

        private fun updateDecoderDelayPaddingForClipping(
            formatHolder: FormatHolder, startUs: Long, endUs: Long
        ) {
            val format = formatHolder.format!!
            if (format.encoderDelay != 0 || format.encoderPadding != 0) {
                // Clear gapless playback metadata if the start/end points don't match the media.
                val encoderDelay = if (startUs != 0L) 0 else format.encoderDelay
                val encoderPadding = if (endUs != C.TIME_END_OF_SOURCE) 0 else format.encoderPadding
                formatHolder.format = format.buildUpon()
                    .setEncoderDelay(encoderDelay)
                    .setEncoderPadding(encoderPadding)
                    .build()
            }
        }
    }
}
