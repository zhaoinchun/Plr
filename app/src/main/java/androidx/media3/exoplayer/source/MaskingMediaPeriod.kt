package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions

import java.io.IOException

class MaskingMediaPeriod(
    val id: MediaPeriodId, private val allocator: Allocator,
    val preparePositionUs: Long
) : MediaPeriod, MediaPeriod.Callback {
    interface PrepareListener {
        fun onPrepareComplete(mediaPeriodId: MediaPeriodId)
        fun onPrepareError(mediaPeriodId: MediaPeriodId, exception: IOException)
    }

    private var mediaSource:MediaSource? = null
    private var mediaPeriod:MediaPeriod? = null
    private var callback: MediaPeriod.Callback? = null
    private var listener: PrepareListener? = null
    private var notifiedPrepareError = false

    var preparePositionOverrideUs: Long = C.TIME_UNSET
        private set
    private var usesStreamPrerollFlags = false

    fun setPrepareListener(listener: PrepareListener) {
        this.listener = listener
    }

    fun overridePreparePositionUs(preparePositionUs: Long) {
        preparePositionOverrideUs = preparePositionUs
    }

    fun setMediaSource(mediaSource: MediaSource) {
        Preconditions.checkState(this.mediaSource == null)
        this.mediaSource = mediaSource
    }

    fun createPeriod(id: MediaPeriodId) {
        val preparePositionUs = getPreparePositionWithOverride(this.preparePositionUs)
        mediaPeriod = mediaSource!!.createPeriod(id, allocator, preparePositionUs)
        val mediaPeriod = mediaPeriod!!
        if (usesStreamPrerollFlags) {
            mediaPeriod.setUsesStreamPrerollFlags()
        }
        if (callback != null) {
            mediaPeriod.prepare( this, preparePositionUs)
        }
    }

    fun releasePeriod() {
        mediaPeriod?.also { mediaSource!!.releasePeriod(it) }
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        mediaPeriod?.prepare(
            this, getPreparePositionWithOverride(this.preparePositionUs)
        )
    }

    @Throws(IOException::class)
    override fun maybeThrowPrepareError() {
        val mediaPeriod = mediaPeriod
        val mediaSource = mediaSource
        try {
            if (mediaPeriod != null) {
                mediaPeriod.maybeThrowPrepareError()
            } else {
                mediaSource?.maybeThrowSourceInfoRefreshError()
            }
        } catch (e: IOException) {
            val listener = listener ?: throw e

            if (!notifiedPrepareError) {
                notifiedPrepareError = true
                listener.onPrepareError(id, e)
            }
        }
    }

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
        return mediaPeriod!!.getStreamKeys(trackSelections)
    }

    override val trackGroups: TrackGroupArray
        get() = mediaPeriod!!.trackGroups

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        var positionUs = positionUs
        if (preparePositionOverrideUs != C.TIME_UNSET && positionUs == preparePositionUs) {
            positionUs = preparePositionOverrideUs
        }
        preparePositionOverrideUs = C.TIME_UNSET
        return mediaPeriod!!.selectTracks(
            selections,
            mayRetainStreamFlags,
            streams,
            streamResetFlags,
            positionUs
        )
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        mediaPeriod!!.discardBuffer(positionUs, toKeyframe)
    }

    override fun setUsesStreamPrerollFlags() {
        this.usesStreamPrerollFlags = true
        if (mediaPeriod != null) {
            mediaPeriod!!.setUsesStreamPrerollFlags()
        }
    }

    override fun readDiscontinuity(): Long {
        return mediaPeriod!!.readDiscontinuity()
    }

    override val bufferedPositionUs: Long
        get() = mediaPeriod!!.bufferedPositionUs

    override fun seekToUs(positionUs: Long): Long {
        return mediaPeriod!!.seekToUs(positionUs)
    }

    override fun getAdjustedSeekPositionUs(
        positionUs: Long,
        seekParameters: SeekParameters
    ): Long {
        return mediaPeriod!!.getAdjustedSeekPositionUs(positionUs, seekParameters)
    }

    override val nextLoadPositionUs: Long
        get() = mediaPeriod!!.nextLoadPositionUs

    override fun reevaluateBuffer(positionUs: Long) {
        mediaPeriod!!.reevaluateBuffer(positionUs)
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        return mediaPeriod?.continueLoading(loadingInfo) == true
    }

    override val isLoading: Boolean
        get() = mediaPeriod?.isLoading == true

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback!!.onContinueLoadingRequested(this)
    }

    override fun setEndPositionUs(endPositionUs: Long): Long {
        return mediaPeriod?.setEndPositionUs(endPositionUs) ?: C.TIME_END_OF_SOURCE
    }

    // MediaPeriod.Callback implementation
    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback!!.onPrepared(this)
        listener?.onPrepareComplete(id)
    }

    private fun getPreparePositionWithOverride(preparePositionUs: Long): Long {
        return if (preparePositionOverrideUs != C.TIME_UNSET)
            preparePositionOverrideUs
        else
            preparePositionUs
    }
}
