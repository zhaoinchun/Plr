package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import java.io.IOException

internal class TimeOffsetMediaPeriod(
    val wrappedMediaPeriod: MediaPeriod, private val timeOffsetUs: Long
) : MediaPeriod, MediaPeriod.Callback {
    private var callback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        wrappedMediaPeriod.prepare( this, positionUs - timeOffsetUs)
    }

    @Throws(IOException::class)
    override fun maybeThrowPrepareError() {
        wrappedMediaPeriod.maybeThrowPrepareError()
    }

    override val trackGroups: TrackGroupArray
        get() = wrappedMediaPeriod.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
        return wrappedMediaPeriod.getStreamKeys(trackSelections)
    }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        val childStreams = arrayOfNulls<SampleStream>(streams.size)
        for (i in streams.indices) {
            val sampleStream: TimeOffsetSampleStream? = streams[i] as TimeOffsetSampleStream?
            childStreams[i] = sampleStream?.childStream
        }
        val startPositionUs =
            wrappedMediaPeriod.selectTracks(
                selections,
                mayRetainStreamFlags,
                childStreams,
                streamResetFlags,
                positionUs - timeOffsetUs
            )
        for (i in streams.indices) {
            val childStream = childStreams[i]
            if (childStream == null) {
                streams[i] = null
            } else if (streams[i] == null
                || (streams[i] as TimeOffsetSampleStream).childStream !== childStream
            ) {
                streams[i] = TimeOffsetSampleStream(childStream, timeOffsetUs)
            }
        }
        return startPositionUs + timeOffsetUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        wrappedMediaPeriod.discardBuffer(positionUs - timeOffsetUs, toKeyframe)
    }

    override fun setUsesStreamPrerollFlags() {
        wrappedMediaPeriod.setUsesStreamPrerollFlags()
    }

    override fun readDiscontinuity(): Long {
        val discontinuityPositionUs = wrappedMediaPeriod.readDiscontinuity()
        return if (discontinuityPositionUs == C.TIME_UNSET)
            C.TIME_UNSET
        else
            discontinuityPositionUs + timeOffsetUs
    }

    override fun seekToUs(positionUs: Long): Long {
        return wrappedMediaPeriod.seekToUs(positionUs - timeOffsetUs) + timeOffsetUs
    }

    override fun getAdjustedSeekPositionUs(
        positionUs: Long,
        seekParameters: SeekParameters
    ): Long {
        return wrappedMediaPeriod.getAdjustedSeekPositionUs(
            positionUs - timeOffsetUs,
            seekParameters
        ) + timeOffsetUs
    }

    override val bufferedPositionUs: Long
        get() {
            val bufferedPositionUs = wrappedMediaPeriod.bufferedPositionUs
            return if (bufferedPositionUs == C.TIME_END_OF_SOURCE)
                C.TIME_END_OF_SOURCE
            else
                bufferedPositionUs + timeOffsetUs
        }

    override val nextLoadPositionUs: Long
        get() {
            val nextLoadPositionUs = wrappedMediaPeriod.nextLoadPositionUs
            return if (nextLoadPositionUs == C.TIME_END_OF_SOURCE)
                C.TIME_END_OF_SOURCE
            else
                nextLoadPositionUs + timeOffsetUs
        }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        return wrappedMediaPeriod.continueLoading(
            loadingInfo
                .buildUpon()
                .setPlaybackPositionUs(loadingInfo.playbackPositionUs - timeOffsetUs)
                .build()
        )
    }

    override val isLoading: Boolean
        get() = wrappedMediaPeriod.isLoading

    override fun reevaluateBuffer(positionUs: Long) {
        wrappedMediaPeriod.reevaluateBuffer(positionUs - timeOffsetUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback!!.onPrepared( this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback!!.onContinueLoadingRequested( this)
    }

    override fun setEndPositionUs(endPositionUs: Long): Long {
        val correctedEndPositionUs =
            if (endPositionUs == C.TIME_END_OF_SOURCE) C.TIME_END_OF_SOURCE else endPositionUs - timeOffsetUs
        val actualEndPositionUs = wrappedMediaPeriod.setEndPositionUs(correctedEndPositionUs)
        return if (actualEndPositionUs == C.TIME_END_OF_SOURCE)
            C.TIME_END_OF_SOURCE
        else
            actualEndPositionUs + timeOffsetUs
    }

    private class TimeOffsetSampleStream(
        val childStream: SampleStream,
        private val timeOffsetUs: Long
    ) : SampleStream {
        override val isReady: Boolean
            get() = childStream.isReady

        @Throws(IOException::class)
        override fun maybeThrowError() {
            childStream.maybeThrowError()
        }

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): Int {
            val readResult = childStream.readData(formatHolder, buffer, readFlags)
            if (readResult == C.RESULT_BUFFER_READ) {
                buffer.timeUs += timeOffsetUs
            }
            return readResult
        }

        override fun skipData(positionUs: Long): Int {
            return childStream.skipData(positionUs - timeOffsetUs)
        }

        override val flags: @SampleStream.Flags Int
            get() = childStream.flags
    }
}
