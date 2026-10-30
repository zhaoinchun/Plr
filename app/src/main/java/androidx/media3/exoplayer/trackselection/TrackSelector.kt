package androidx.media3.exoplayer.trackselection

import android.content.Context
import androidx.annotation.CallSuper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.base.Preconditions

abstract class TrackSelector {
    fun interface Factory {
        fun createTrackSelector(context: Context): TrackSelector
    }

    fun interface InvalidationListener {
        fun onTrackSelectionsInvalidated(parameters: TrackSelectionParameters?)
        fun onRendererCapabilitiesChanged(renderer: Renderer) {}
    }

    private var listener: InvalidationListener? = null
    private var bandwidthMeter: BandwidthMeter? = null

    @CallSuper
    open fun init(listener: InvalidationListener?, bandwidthMeter: BandwidthMeter) {
        Preconditions.checkState(this.listener == null)
        this.listener = listener
        this.bandwidthMeter = bandwidthMeter
    }

    @CallSuper
    open fun release() {
        listener = null
        bandwidthMeter = null
    }

    @Throws(ExoPlaybackException::class)
    abstract fun selectTracks(
        rendererCapabilities: Array<RendererCapabilities>,
        trackGroups: TrackGroupArray,
        periodId: MediaPeriodId,
        timeline: Timeline
    ): TrackSelectorResult

    abstract fun onSelectionActivated(info: Any?)
    open fun onParametersActivated(parameters: TrackSelectionParameters?) {
        // Default implementation is no-op.
    }

    open var parameters: TrackSelectionParameters?
        get() = TrackSelectionParameters.DEFAULT
        set(parameters) {
            // Default implementation doesn't support this method.
        }

    open val isSetParametersSupported: Boolean = false

    open fun setAudioAttributes(audioAttributes: AudioAttributes) {
        // Default implementation is no-op.
    }

    open val rendererCapabilitiesListener: RendererCapabilities.Listener? = null

    protected fun invalidate(parameters: TrackSelectionParameters?) {
        listener?.onTrackSelectionsInvalidated(parameters)
    }

    protected fun invalidateForRendererCapabilitiesChange(renderer: Renderer) {
        listener?.onRendererCapabilitiesChanged(renderer)
    }

    protected fun getBandwidthMeter(): BandwidthMeter {
        return bandwidthMeter!!
    }
}
